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
# NOTE: upstream has never released a 3.x; the current 2.x line tops out at
# 2.18.0. This stays pinned to 2.15.0 for a reproducible build, but be aware
# that is NOT the newest release.
HEV_VERSION="2.15.0"

# ─────────────────────────────────────────────────────────────
# KNOWN BLOCKER — VPN mode cannot work as currently wired.
#
# This script produces libhev-socks5-tunnel.so via upstream's Android.mk,
# which ends in `include $(BUILD_SHARED_LIBRARY)`. That yields an ELF
# DYN with no PT_INTERP, so execve() fails with ENOEXEC. TorVpnService
# launches it with ProcessBuilder(libtun2socksPath, ...), which requires an
# interpreter — VPN mode therefore never establishes a forwarder.
#
# Fixing it is not a build-flag change: hev-socks5-tunnel has no CLI option
# for an existing TUN descriptor. Its only fd-aware entry points are C APIs
#
#     int  hev_socks5_tunnel_main_from_str(const unsigned char *cfg,
#                                          unsigned int len, int tun_fd);
#     void hev_socks5_tunnel_quit(void);
#
# (main() always passes tun_fd = -1, i.e. "create my own TUN", which needs
# root and is unavailable to an unprivileged app.) Reaching them requires a
# JNI bridge that runs this function on a native thread with the
# VpnService ParcelFileDescriptor — the approach Orbot uses. Until that
# exists, VPN mode should be treated as non-functional rather than merely
# untested, and the graceful-degradation path matters: TorService already
# keeps the TUN up when the kill switch is enabled so traffic fails closed.
# ─────────────────────────────────────────────────────────────

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

"${NDK_BUILD}" \
    NDK_PROJECT_PATH="${NDK_TMPDIR}" \
    APP_BUILD_SCRIPT="${NDK_TMPDIR}/jni/Android.mk" \
    APP_ABI="${ABI}" \
    APP_PLATFORM=android-29 \
    NDK_LIBS_OUT="${NDK_TMPDIR}/libs" \
    NDK_OUT="${NDK_TMPDIR}/obj" \
    APP_OPTIM=release \
    APP_CFLAGS="-O3" \
    -j$(nproc)

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

echo "Built: ${OUTPUT_DIR}/libtun2socks.so"
ls -lh "${OUTPUT_DIR}/libtun2socks.so"
echo ""
echo "hev-socks5-tunnel build complete for ${ABI}!"
