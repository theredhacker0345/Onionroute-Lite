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
# License: Apache-2.0 (F-Droid compatible)
# Source: https://github.com/heiher/hev-socks5-tunnel
#
# Prerequisites:
#   - Android NDK r25c+
#   - Git
#
# Usage:
#   ./build-tun2socks.sh [ABI] [NDK_PATH] [OUTPUT_DIR]
#   ./build-tun2socks.sh arm64-v8a
#   ./build-tun2socks.sh x86_64 /opt/android-ndk
#
# F-Droid notes:
#   - Source is cloned from git with pinned tag
#   - Submodules are initialized recursively for third-party deps
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
    echo "Cloning hev-socks5-tunnel v${HEV_VERSION} (with submodules)..."
    git clone --recurse-submodules --depth 1 --branch "${HEV_VERSION}" \
        https://github.com/heiher/hev-socks5-tunnel.git \
        "${HEV_DIR}"
else
    echo "hev-socks5-tunnel already cloned, using cached source"
fi

cd "${HEV_DIR}"

# ── Build with ndk-build ────────────────────────────────────
echo "Building for ${ABI}..."
"${NDK_BUILD}" \
    -j$(nproc) \
    NDK_PROJECT_PATH=. \
    APP_BUILD_SCRIPT=Android.mk \
    APP_ABI="${ABI}" \
    APP_PLATFORM=android-23 \
    APP_OPTIM=release

# ── Copy from correct ABI-specific output ────────────────────
# ndk-build places outputs in libs/{abi}/ directory
# The output is a shared library: libhev-socks5-tunnel.so
HEV_OUTPUT="${HEV_DIR}/libs/${ABI}/libhev-socks5-tunnel.so"

if [ ! -f "${HEV_OUTPUT}" ]; then
    echo "ERROR: Built binary not found at ${HEV_OUTPUT}"
    echo "Checking alternative paths..."
    # Try to find the output
    find "${HEV_DIR}/libs" -type f -name "hev-socks5-tunnel" 2>/dev/null || true
    find "${HEV_DIR}" -maxdepth 3 -type f -name "hev-socks5-tunnel" 2>/dev/null || true
    echo "Check ndk-build output for errors"
    exit 1
fi

mkdir -p "${OUTPUT_DIR}"
cp "${HEV_OUTPUT}" "${OUTPUT_DIR}/libtun2socks.so"

echo "Built: ${OUTPUT_DIR}/libtun2socks.so"
ls -lh "${OUTPUT_DIR}/libtun2socks.so"
echo ""
echo "hev-socks5-tunnel build complete for ${ABI}!"
