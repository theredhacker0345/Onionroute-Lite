#!/bin/bash
# ══════════════════════════════════════════════════════════════
# Build hev-socks5-tunnel for Android (multi-ABI)
# ══════════════════════════════════════════════════════════════
# hev-socks5-tunnel is a lightweight C program that reads
# packets from a TUN file descriptor and forwards TCP
# connections through a SOCKS5 proxy. It's used for VPN mode.
#
# Supported ABIs:
#   - armeabi-v7a  (32-bit ARM)
#   - arm64-v8a    (64-bit ARM)
#   - x86          (32-bit Intel)
#   - x86_64       (64-bit Intel)
#
# hev-socks5-tunnel uses ndk-build which natively supports
# multiple ABIs via APP_ABI in Application.mk.
#
# License: GPLv3 (F-Droid compatible)
# Source: https://github.com/heiher/hev-socks5-tunnel
#
# Prerequisites:
#   - Android NDK r26d
#   - Git
#
# Usage:
#   ./build-tun2socks.sh [ABI] [NDK_PATH] [OUTPUT_DIR]
#   ./build-tun2socks.sh arm64-v8a
#   ./build-tun2socks.sh x86_64 /opt/android-ndk
#
# F-Droid notes:
#   - Source is cloned from git with pinned tag
#   - Uses NDK's ndk-build system directly
#   - No downloads of prebuilt binaries
#   - Must clone --recursive for third-part/ submodules
# ══════════════════════════════════════════════════════════════

set -euo pipefail

# ── Parse arguments ───────────────────────────────────────────
ABI="${1:-armeabi-v7a}"
NDK_PATH="${2:-${ANDROID_NDK_HOME:-/opt/android-ndk}}"
OUTPUT_DIR="${3:-./app/src/main/jniLibs/${ABI}}"

# Resolve OUTPUT_DIR (and NDK_PATH) to absolute paths NOW, before the build
# steps `cd` into the temporary ndk-build project directory below.
#
# Without this, a relative OUTPUT_DIR silently resolves against NDK_TMPDIR
# instead of the project root: `cp` writes to
# <tmpdir>/app/src/main/jniLibs/<abi>/libtun2socks.so and the real
# jniLibs/ keeps a STALE library from a previous run. The build prints
# "Built: ./app/src/main/jniLibs/..." either way, so nothing looks wrong.
# build-all.sh passes an absolute path so CI never hit this, which is why it
# went unnoticed.
mkdir -p "${OUTPUT_DIR}"
OUTPUT_DIR="$(cd "${OUTPUT_DIR}" && pwd)"
NDK_PATH="$(cd "${NDK_PATH}" && pwd)"
# ─────────────────────────────────────────────────────────────
# JNI BRIDGE — how this library is actually consumed.
#
# This library is NOT exec'd; it is loaded by System.loadLibrary("tun2socks").
# Upstream ships a complete JNI bridge in src/hev-jni.c (guarded by
# #ifdef ANDROID, and already picked up by the src/**/*.c wildcard in its
# Android.mk). JNI_OnLoad binds three methods onto a class it locates by name:
#
#     FindClass(PKGNAME "/" CLSNAME) then RegisterNatives(...)
#       TProxyStartService(Ljava/lang/String;I)V
#       TProxyStopService()V
#       TProxyGetStats()[J
#
# Upstream's defaults are PKGNAME=hev/htproxy, CLSNAME=TProxyService, which
# only exist in its own demo app. We override both below so the bindings land
# on org.onionroute.lite.tor.Tun2SocksNative instead. Both are #ifndef-guarded
# in hev-jni.c, so -D wins.
#
# Note the first argument is a CONFIG FILE PATH, not the YAML itself:
# native_start_service does strdup(GetStringUTFChars(...)) and passes it to
# hev_socks5_tunnel_main(path, fd), which reads it from disk via
# hev_config_init_from_file.
#
# The TUN fd is injected through that second argument. hev's tunnel_init()
# branches on it:
#     if (extern_tun_fd >= 0) { ioctl(fd, FIONBIO, ...); tun_fd = fd; return 0; }
# That early return is the entire point — it skips opening and configuring a
# TUN device, leaving VpnService in sole control of the interface. With
# tun_fd = -1 (what upstream's own main() passes) hev would try to create its
# own TUN, which an unprivileged app cannot do.
#
# Why an in-process library rather than a child process: hev has never exposed
# a CLI flag for an existing TUN descriptor in any release (2.15.0, 2.16.0 and
# 2.18.0 all take argv[1] as a config path and hardcode tun_fd = -1). The C API
# is the only fd-aware entry point, so reaching it requires JNI.
# ─────────────────────────────────────────────────────────────

HEV_VERSION="2.15.0"

# ── Validate ABI ─────────────────────────────────────────────
case "${ABI}" in
    armeabi-v7a|arm64-v8a|x86|x86_64)
        # Valid ABI
        ;;
    *)
        echo "ERROR: Unsupported ABI '${ABI}'"
        echo "Supported: armeabi-v7a, arm64-v8a, x86, x86_64"
        exit 1
        ;;
esac

echo "════════════════════════════════════════════"
echo " Building hev-socks5-tunnel ${HEV_VERSION} for Android ${ABI} "
echo "════════════════════════════════════════════"
echo "ABI: ${ABI}"
echo "NDK: ${NDK_PATH}"
echo "Output: ${OUTPUT_DIR}"
echo ""

# ── Verify NDK ───────────────────────────────────────────────
if [ ! -d "${NDK_PATH}" ]; then
    echo "ERROR: NDK not found at ${NDK_PATH}"
    exit 1
fi

NDK_BUILD="${NDK_PATH}/ndk-build"
if [ ! -f "${NDK_BUILD}" ]; then
    echo "ERROR: ndk-build not found at ${NDK_BUILD}"
    exit 1
fi

# ── Clone source with pinned version ─────────────────────────
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BUILD_DIR="${SCRIPT_DIR}/../build-native/tun2socks"
mkdir -p "${BUILD_DIR}"

HEV_DIR="${BUILD_DIR}/hev-socks5-tunnel"
if [ ! -d "${HEV_DIR}" ]; then
    echo "Cloning hev-socks5-tunnel v${HEV_VERSION}..."
    # --recursive is CRITICAL: third-part/ contains yaml, lwip, hev-task-system submodules
    git clone --depth 1 --branch "${HEV_VERSION}" --recursive \
        https://github.com/heiher/hev-socks5-tunnel.git \
        "${HEV_DIR}"
else
    echo "hev-socks5-tunnel already cloned, ensuring submodules are present..."
    cd "${HEV_DIR}" && git submodule update --init --recursive 2>/dev/null || true
fi

# ── Build with ndk-build ─────────────────────────────────────
# hev-socks5-tunnel uses the Android ndk-build system (Android.mk + Application.mk).
# There is NO `make ndk-build` target — you must run ndk-build directly.
#
# The official build method (from README):
#   mkdir hev-socks5-tunnel && cd hev-socks5-tunnel
#   git clone --recursive https://github.com/heiher/hev-socks5-tunnel jni
#   ndk-build
#
# ndk-build expects source in a directory called "jni/". We create a
# temporary project directory with a wrapper Android.mk that includes
# the cloned source, then run ndk-build with explicit paths.

NDK_TMPDIR="${BUILD_DIR}/ndk-project-${ABI}"
rm -rf "${NDK_TMPDIR}"
mkdir -p "${NDK_TMPDIR}/jni"

# Create wrapper Android.mk that includes the hev-socks5-tunnel source
cat > "${NDK_TMPDIR}/jni/Android.mk" <<'MKEOF'
include $(call all-subdir-makefiles)
MKEOF

# Symlink the hev-socks5-tunnel source into the jni/ directory
ln -s "${HEV_DIR}" "${NDK_TMPDIR}/jni/hev-socks5-tunnel"

echo "Building for ${ABI} with ndk-build..."
cd "${NDK_TMPDIR}"

# -DPKGNAME/-DCLSNAME retarget the JNI bridge in src/hev-jni.c from upstream's
# demo class (hev/htproxy.TProxyService) onto our Kotlin class
# (org.onionroute.lite.tor.Tun2SocksNative). Without this, JNI_OnLoad's
# FindClass fails at System.loadLibrary time and RegisterNatives is handed a
# null jclass.
# See the JNI BRIDGE note above for the full contract.
JNI_PKGNAME="org/onionroute/lite/tor"
JNI_CLSNAME="Tun2SocksNative"

# APP_PLATFORM must not exceed the app's minSdk (23, Android 6.0 — the primary
# target). This was android-29, which means any symbol newer than API 23 would
# make System.loadLibrary throw UnsatisfiedLinkError on the oldest devices the
# app supports — surfacing as "VPN unavailable" at runtime rather than as a
# build error. No other native script sets APP_PLATFORM at all (they take the
# NDK floor), so this was an outlier as well as a portability risk.
#
# NOTE: keep every comment ABOVE this invocation. A comment line inside a
# backslash-continued argument list terminates the continuation, which silently
# ran the following argument as a separate command (`-j4: command not found`)
# while ndk-build appeared to succeed on a stale output directory.
"${NDK_BUILD}" \
    NDK_PROJECT_PATH="${NDK_TMPDIR}" \
    APP_BUILD_SCRIPT="${NDK_TMPDIR}/jni/Android.mk" \
    APP_ABI="${ABI}" \
    APP_PLATFORM=android-23 \
    NDK_LIBS_OUT="${NDK_TMPDIR}/libs" \
    NDK_OUT="${NDK_TMPDIR}/obj" \
    APP_OPTIM=release \
    APP_CFLAGS="-O3 -DPKGNAME=${JNI_PKGNAME} -DCLSNAME=${JNI_CLSNAME}" \
    -j$(nproc)

# ── Verify the JNI bridge is actually wired for this ABI ──────
# A silent regression here (e.g. someone drops -D, or upstream renames the
# macro guards) would produce a library that loads but immediately fails to
# bind its methods — a native crash with no useful stack. Check the string
# that JNI_OnLoad will look up.
#
# `grep -a` reads the ELF directly rather than piping `strings` into `grep`.
# Under `set -o pipefail`, `strings | grep -q` is a footgun: grep -q exits on
# the first match, `strings` dies with SIGPIPE (141), and the pipeline reports
# failure even though the string IS present. -a makes grep treat the binary as
# text, and the target lives in .rodata so it is found verbatim.
SO_PATH="${NDK_TMPDIR}/libs/${ABI}/libhev-socks5-tunnel.so"

# A MISSING intermediate is a hard failure, not a skip.
#
# This block used to be wrapped in `if [ -f "${SO_PATH}" ]`, so when ndk-build's
# output was not where the check expected, the entire verification was skipped
# and the build reported success. That is not hypothetical: a full build-all.sh
# pass shipped an armeabi-v7a libtun2socks.so containing upstream's DEFAULT
# `hev/htproxy/TProxyService` instead of our class, with no warning. On that ABI
# JNI_OnLoad's FindClass would return null and RegisterNatives would crash the
# app natively on System.loadLibrary — i.e. VPN mode dead on the 32-bit devices
# that are this app's primary target, and the APK still built clean.
if [ ! -f "${SO_PATH}" ]; then
    echo "ERROR: expected ndk-build output missing: ${SO_PATH}"
    exit 1
fi

if grep -qaF "${JNI_PKGNAME}/${JNI_CLSNAME}" "${SO_PATH}"; then
    echo "JNI binding target OK: ${JNI_PKGNAME}/${JNI_CLSNAME}"
else
    echo "ERROR: libhev-socks5-tunnel.so does not reference ${JNI_PKGNAME}/${JNI_CLSNAME}."
    if grep -qaF "hev/htproxy/TProxyService" "${SO_PATH}"; then
        echo "       It still carries upstream's DEFAULT class, so -DPKGNAME/-DCLSNAME"
        echo "       did not reach the compiler for this ABI."
    fi
    echo "       JNI_OnLoad will not be able to bind its methods."
    exit 1
fi

# ── Copy from ndk-build output ───────────────────────────────
# ndk-build produces: libs/{abi}/libhev-socks5-tunnel.so
HEV_OUTPUT="${NDK_TMPDIR}/libs/${ABI}/libhev-socks5-tunnel.so"

if [ ! -f "${HEV_OUTPUT}" ]; then
    echo "ERROR: Built binary not found at ${HEV_OUTPUT}"
    echo "Check ndk-build output for errors"
    ls -la "${NDK_TMPDIR}/libs/" 2>/dev/null || true
    exit 1
fi

mkdir -p "${OUTPUT_DIR}"
cp "${HEV_OUTPUT}" "${OUTPUT_DIR}/libtun2socks.so"

# Verify the artifact that ACTUALLY SHIPS, not just the ndk-build intermediate.
# They are different files, and a copy that silently no-ops would leave a correct
# intermediate and a wrong APK — which is exactly the failure that let a
# default-class armeabi-v7a library reach the previous build unnoticed.
FINAL_SO="${OUTPUT_DIR}/libtun2socks.so"
if [ ! -f "${FINAL_SO}" ]; then
    echo "ERROR: copy produced nothing at ${FINAL_SO}" >&2
    exit 1
fi
if ! grep -qaF "${JNI_PKGNAME}/${JNI_CLSNAME}" "${FINAL_SO}"; then
    echo "ERROR: ${FINAL_SO} does not carry the JNI binding string." >&2
    echo "       The shipped library is not the one that was just verified." >&2
    exit 1
fi

echo "Built: ${OUTPUT_DIR}/libtun2socks.so"
ls -lh "${OUTPUT_DIR}/libtun2socks.so"
echo ""
echo "hev-socks5-tunnel build complete for ${ABI}!"
