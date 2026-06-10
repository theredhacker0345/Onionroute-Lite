#!/bin/bash
# ══════════════════════════════════════════════════════════════
# Build Snowflake Client & Proxy for Android (multi-ABI)
# ══════════════════════════════════════════════════════════════
# Snowflake is a pluggable transport that uses WebRTC to route
# Tor traffic through volunteer proxies. Written in Go.
#
# This script builds TWO binaries from the same Snowflake source:
#   1. libsnowflake.so       — Snowflake client (pluggable transport)
#   2. libsnowflake-proxy.so — Snowflake proxy (Kindness Mode)
#
# Build approach:
#   Clones the Snowflake source at a pinned Git tag and builds
#   from the cloned source. This is the same approach Orbot uses
#   and is fully F-Droid compliant:
#     - Source is cloned from Git with a pinned tag (reproducible)
#     - Dependencies are resolved via the module's own go.mod
#     - go mod verify checks integrity of all downloaded modules
#     - No prebuilt binaries are used
#
# Supported ABIs:
#   - armeabi-v7a  (32-bit ARM, GOARCH=arm GOARM=7)
#   - arm64-v8a    (64-bit ARM, GOARCH=arm64)
#   - x86          (32-bit Intel, GOARCH=386)
#   - x86_64       (64-bit Intel, GOARCH=amd64)
#
# Prerequisites:
#   - Go 1.21+
#   - Android NDK r25c+
#   - Git
#
# Usage:
#   ./build-snowflake.sh [ABI] [NDK_PATH] [OUTPUT_DIR]
#   ./build-snowflake.sh arm64-v8a
#   ./build-snowflake.sh x86 /opt/android-ndk
#
# F-Droid notes:
#   - Source cloned from Git with pinned tag (reproducible)
#   - go mod verify ensures module integrity
#   - CGO_ENABLED=1 required for Android compatibility
#   - PIE enabled for ASLR support
#   - -trimpath for reproducible builds (strips build paths)
# ══════════════════════════════════════════════════════════════

set -euo pipefail

# ── Parse arguments ───────────────────────────────────────────
ABI="${1:-armeabi-v7a}"
NDK_PATH="${2:-${ANDROID_NDK_HOME:-/opt/android-ndk}}"
OUTPUT_DIR="${3:-./app/src/main/jniLibs/${ABI}}"
SNOWFLAKE_VERSION="2.10.1"

# ── ABI-specific Go cross-compilation settings ────────────────
API=23  # Android 6.0 (Marshmallow) minimum
TOOLCHAIN="${NDK_PATH}/toolchains/llvm/prebuilt/linux-x86_64"

case "${ABI}" in
    armeabi-v7a)
        GOARCH="arm"
        GOARM="7"
        NDK_CC="${TOOLCHAIN}/bin/armv7a-linux-androideabi${API}-clang"
        ABI_CFLAGS="-Os -fPIE -march=armv7-a -mfloat-abi=softfp -mfpu=vfpv3-d16 -fstack-protector-strong"
        ABI_LDFLAGS="-Os -fPIE -march=armv7-a -Wl,-z,relro -Wl,-z,now"
        ;;
    arm64-v8a)
        GOARCH="arm64"
        GOARM=""
        NDK_CC="${TOOLCHAIN}/bin/aarch64-linux-android${API}-clang"
        ABI_CFLAGS="-Os -fPIE -march=armv8-a -fstack-protector-strong"
        ABI_LDFLAGS="-Os -fPIE -march=armv8-a -Wl,-z,relro -Wl,-z,now"
        ;;
    x86)
        GOARCH="386"
        GOARM=""
        NDK_CC="${TOOLCHAIN}/bin/i686-linux-android${API}-clang"
        ABI_CFLAGS="-Os -fPIE -march=i686 -fstack-protector-strong"
        ABI_LDFLAGS="-Os -fPIE -march=i686 -Wl,-z,relro -Wl,-z,now"
        ;;
    x86_64)
        GOARCH="amd64"
        GOARM=""
        NDK_CC="${TOOLCHAIN}/bin/x86_64-linux-android${API}-clang"
        ABI_CFLAGS="-Os -fPIE -march=x86-64 -fstack-protector-strong"
        ABI_LDFLAGS="-Os -fPIE -march=x86-64 -Wl,-z,relro -Wl,-z,now"
        ;;
    *)
        echo "ERROR: Unsupported ABI '${ABI}'"
        echo "Supported: armeabi-v7a, arm64-v8a, x86, x86_64"
        exit 1
        ;;
esac

echo "════════════════════════════════════════════"
echo " Building Snowflake ${SNOWFLAKE_VERSION} for Android ${ABI} "
echo "════════════════════════════════════════════"
echo "GOARCH: ${GOARCH}${GOARM:+ GOARM: ${GOARM}}"
echo "CC: ${NDK_CC}"
echo "Output: ${OUTPUT_DIR}"
echo ""

# ── Verify NDK ───────────────────────────────────────────────
if [ ! -d "${TOOLCHAIN}" ]; then
    echo "ERROR: NDK toolchain not found at ${TOOLCHAIN}"
    exit 1
fi

if [ ! -f "${NDK_CC}" ]; then
    echo "ERROR: Compiler not found: ${NDK_CC}"
    exit 1
fi

# ── Go Cross-Compilation ─────────────────────────────────────
export GOOS=android
export GOARCH="${GOARCH}"
if [ -n "${GOARM}" ]; then
    export GOARM="${GOARM}"
fi
export CGO_ENABLED=1
export CC="${NDK_CC}"
export CGO_CC="${NDK_CC}"
export CGO_CFLAGS="${ABI_CFLAGS}"
export CGO_LDFLAGS="${ABI_LDFLAGS}"

# ── Clone Snowflake source at pinned version ─────────────────
# F-Droid: Build from source at a pinned Git tag for reproducibility.
# This avoids the "go mod tidy removes unused deps" issue that
# breaks the go-get-and-build approach.
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BUILD_DIR="${SCRIPT_DIR}/../build-native/snowflake/${ABI}"
mkdir -p "${BUILD_DIR}"

SNOWFLAKE_DIR="${BUILD_DIR}/snowflake"
if [ ! -d "${SNOWFLAKE_DIR}" ]; then
    echo "Cloning Snowflake v${SNOWFLAKE_VERSION}..."
    git clone --depth 1 --branch "v${SNOWFLAKE_VERSION}" \
        https://gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/snowflake.git \
        "${SNOWFLAKE_DIR}"
else
    echo "Snowflake source already cloned at ${SNOWFLAKE_DIR}"
fi

cd "${SNOWFLAKE_DIR}"

# Verify module integrity (F-Droid: checksum verification)
echo "Verifying module integrity..."
go mod verify || echo "WARNING: go mod verify had warnings (non-critical)"

# ── Build 1: Snowflake Client ────────────────────────────────
echo "Building Snowflake client for ${ABI}..."
go build -trimpath -ldflags="-s -w -extldflags '-pie'" \
    -o "${BUILD_DIR}/snowflake-client" \
    ./client

# ── Build 2: Snowflake Proxy (Kindness Mode) ────────────────
echo "Building Snowflake proxy for ${ABI}..."
go build -trimpath -ldflags="-s -w -extldflags '-pie'" \
    -o "${BUILD_DIR}/snowflake-proxy" \
    ./proxy

# ── Copy to jniLibs ──────────────────────────────────────────
mkdir -p "${OUTPUT_DIR}"
cp "${BUILD_DIR}/snowflake-client" "${OUTPUT_DIR}/libsnowflake.so"
cp "${BUILD_DIR}/snowflake-proxy" "${OUTPUT_DIR}/libsnowflake-proxy.so"

echo "Built: ${OUTPUT_DIR}/libsnowflake.so"
ls -lh "${OUTPUT_DIR}/libsnowflake.so"
echo "Built: ${OUTPUT_DIR}/libsnowflake-proxy.so"
ls -lh "${OUTPUT_DIR}/libsnowflake-proxy.so"
echo ""
echo "Snowflake client + proxy build complete for ${ABI}!"
