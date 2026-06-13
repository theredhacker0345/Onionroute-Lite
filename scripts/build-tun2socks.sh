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
#   - Android NDK r25c
#   - Git, Make
#
# Usage:
#   ./build-tun2socks.sh [ABI] [NDK_PATH] [OUTPUT_DIR]
#   ./build-tun2socks.sh arm64-v8a
#   ./build-tun2socks.sh x86_64 /opt/android-ndk
#
# F-Droid notes:
#   - Source is cloned from git with pinned tag
#   - Uses NDK's ndk-build system
#   - No downloads of prebuilt binaries
# ══════════════════════════════════════════════════════════════

set -euo pipefail

# ── Parse arguments ───────────────────────────────────────────
ABI="${1:-armeabi-v7a}"
NDK_PATH="${2:-${ANDROID_NDK_HOME:-/opt/android-ndk}}"
OUTPUT_DIR="${3:-./app/src/main/jniLibs/${ABI}}"
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
echo "Output: ${OUTPUT_DIR}"
echo ""

# ── Verify NDK ───────────────────────────────────────────────
if [ ! -d "${NDK_PATH}" ]; then
    echo "ERROR: NDK not found at ${NDK_PATH}"
    exit 1
fi

# ── Clone source with pinned version ─────────────────────────
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BUILD_DIR="${SCRIPT_DIR}/../build-native/tun2socks"
mkdir -p "${BUILD_DIR}"

HEV_DIR="${BUILD_DIR}/hev-socks5-tunnel"
if [ ! -d "${HEV_DIR}" ]; then
    echo "Cloning hev-socks5-tunnel v${HEV_VERSION}..."
    git clone --recurse-submodules --depth 1 --branch "${HEV_VERSION}" \
        https://github.com/heiher/hev-socks5-tunnel.git \
        "${HEV_DIR}"
else
    echo "hev-socks5-tunnel already cloned, using cached source"
fi

cd "${HEV_DIR}"

# ── Patch Application.mk for target ABI ──────────────────────
# hev-socks5-tunnel uses ndk-build which reads APP_ABI from
# Application.mk. We set it to build only the requested ABI.
# This avoids building all ABIs when only one is needed.

APP_MK="${HEV_DIR}/app/src/main/jni/Application.mk"
if [ -f "${APP_MK}" ]; then
    echo "Patching Application.mk for APP_ABI=${ABI}..."
    # Backup original if not already backed up
    if [ ! -f "${APP_MK}.orig" ]; then
        cp "${APP_MK}" "${APP_MK}.orig"
    fi
    # Replace APP_ABI line with our target
    sed -i "s/^APP_ABI := .*/APP_ABI := ${ABI}/" "${APP_MK}"
fi

# ── Build with ndk-build ──────────────────────────────────────
echo "Building for ${ABI}..."
export NDK_HOME="${NDK_PATH}"

# Use ndk-build directly with the root-level Android.mk
# hev-socks5-tunnel uses submodules (yaml, lwip, hev-task-system) which are
# included by the root Android.mk. The --recurse-submodules clone ensures
# these are available.
${NDK_PATH}/ndk-build \
    NDK_PROJECT_PATH="${HEV_DIR}" \
    APP_BUILD_SCRIPT="${HEV_DIR}/Android.mk" \
    APP_ABI="${ABI}" \
    APP_PLATFORM=android-23 \
    APP_SUPPORT_FLEXIBLE_PAGE_SIZES=false \
    NDK_OUT="${HEV_DIR}/obj" \
    NDK_LIBS_OUT="${HEV_DIR}/libs" \
    2>&1 || { echo "ERROR: ndk-build failed"; exit 1; }

# ── Copy from correct ABI-specific output ────────────────────
# ndk-build produces a shared library: libhev-socks5-tunnel.so
HEV_OUTPUT="${HEV_DIR}/libs/${ABI}/libhev-socks5-tunnel.so"

if [ ! -f "${HEV_OUTPUT}" ]; then
    echo "ERROR: Built binary not found at ${HEV_OUTPUT}"
    echo "Check ndk-build output for errors"
    exit 1
fi

mkdir -p "${OUTPUT_DIR}"
cp "${HEV_OUTPUT}" "${OUTPUT_DIR}/libtun2socks.so"

# Restore original Application.mk if we patched it
if [ -f "${APP_MK}.orig" ]; then
    cp "${APP_MK}.orig" "${APP_MK}"
fi

echo "Built: ${OUTPUT_DIR}/libtun2socks.so"
ls -lh "${OUTPUT_DIR}/libtun2socks.so"
echo ""
echo "hev-socks5-tunnel build complete for ${ABI}!"
