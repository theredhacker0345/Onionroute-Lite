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
# Supported ABIs:
#   - armeabi-v7a  (32-bit ARM, GOARCH=arm GOARM=7)
#   - arm64-v8a    (64-bit ARM, GOARCH=arm64)
#   - x86          (32-bit Intel, GOARCH=386)
#   - x86_64       (64-bit Intel, GOARCH=amd64)
#
# Prerequisites:
#   - Go 1.21+
#   - Android NDK r25c
#   - Git
#
# Usage:
#   ./build-snowflake.sh [ABI] [NDK_PATH] [OUTPUT_DIR]
#   ./build-snowflake.sh arm64-v8a
#   ./build-snowflake.sh x86 /opt/android-ndk
#
# F-Droid notes:
#   - Uses Go module checksum database for verification
#   - CGO_ENABLED=1 required for Android compatibility
#   - PIE enabled for ASLR support
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

# ── Build in ABI-specific reproducible directory ─────────────
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BUILD_DIR="${SCRIPT_DIR}/../build-native/snowflake/${ABI}"
mkdir -p "${BUILD_DIR}"
cd "${BUILD_DIR}"

# Initialize Go module with pinned version
echo "Downloading Snowflake v${SNOWFLAKE_VERSION} for ${ABI}..."
go mod init onionroute-build/snowflake
# IMPORTANT: The canonical module path does NOT have .git in it.
# The module's go.mod declares: module gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/snowflake/v2
# Using the .git variant causes go.mod resolution errors.
#
# Use dummy imports to force go mod tidy to track the client and proxy packages.
# Without this, `go get` adds the module as indirect and the packages
# aren't resolvable at build time.
echo 'package main; import _ "gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/snowflake/v2/client"' > dummy_client.go
go get gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/snowflake/v2@v${SNOWFLAKE_VERSION}
go mod tidy
rm -f dummy_client.go

# Verify all module checksums
go mod verify

# ── Build 1: Snowflake Client ────────────────────────────────
echo "Building Snowflake client for ${ABI}..."
go build -trimpath -ldflags="-s -w -extldflags '-pie'" \
    -o snowflake-client \
    gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/snowflake/v2/client

# ── Build 2: Snowflake Proxy (Kindness Mode) ────────────────
echo "Building Snowflake proxy for ${ABI}..."
go build -trimpath -ldflags="-s -w -extldflags '-pie'" \
    -o snowflake-proxy \
    gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/snowflake/v2/proxy

# ── Copy ─────────────────────────────────────────────────────
mkdir -p "${OUTPUT_DIR}"
cp snowflake-client "${OUTPUT_DIR}/libsnowflake.so"
cp snowflake-proxy "${OUTPUT_DIR}/libsnowflake-proxy.so"

echo "Built: ${OUTPUT_DIR}/libsnowflake.so"
ls -lh "${OUTPUT_DIR}/libsnowflake.so"
echo "Built: ${OUTPUT_DIR}/libsnowflake-proxy.so"
ls -lh "${OUTPUT_DIR}/libsnowflake-proxy.so"
echo ""
echo "Snowflake client + proxy build complete for ${ABI}!"
