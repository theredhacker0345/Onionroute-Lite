#!/bin/bash
# ══════════════════════════════════════════════════════════════
# Build obfs4proxy for Android (multi-ABI)
# ══════════════════════════════════════════════════════════════
# obfs4proxy is a pluggable transport that disguises Tor
# traffic as random-looking data. It's written in Go.
#
# Supported ABIs:
#   - armeabi-v7a  (32-bit ARM, GOARCH=arm GOARM=7)
#   - arm64-v8a    (64-bit ARM, GOARCH=arm64)
#   - x86          (32-bit Intel, GOARCH=386)
#   - x86_64       (64-bit Intel, GOARCH=amd64)
#
# Prerequisites:
#   - Go 1.21+ (do NOT use Go 1.22+ — anet package is incompatible)
#   - Android NDK r26d
#   - Git
#
# Usage:
#   ./build-obfs4.sh [ABI] [NDK_PATH] [OUTPUT_DIR]
#   ./build-obfs4.sh arm64-v8a
#   ./build-obfs4.sh x86 /opt/android-ndk
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
OBFS4_VERSION="v0.0.0-20231012084234-c3e2d44b1033"

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
echo " Building obfs4proxy ${OBFS4_VERSION} for Android ${ABI} "
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
export CGO_CFLAGS="${ABI_CFLAGS}"
export CGO_LDFLAGS="${ABI_LDFLAGS}"

# ── Build in ABI-specific reproducible directory ─────────────
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BUILD_DIR="${SCRIPT_DIR}/../build-native/obfs4/${ABI}"
mkdir -p "${BUILD_DIR}"
cd "${BUILD_DIR}"

# ── Build strategy ───────────────────────────────────────────
# We use `go install pkg@version` which downloads the module and
# compiles the package to $GOBIN/$GOPATH/bin. Then we copy the
# binary to the output directory with the .so name.
#
# Note: `go build pkg@version` does NOT work — only `go get` and
# `go install` support the path@version syntax in Go 1.21.
# And `go install` produces a binary, while `go get` only downloads.
#
# Previous approaches (go mod init → go get → go mod tidy → go build)
# failed because go mod tidy pruned dependencies that weren't
# imported by any non-ignored source file in the current module.

# Set GOBIN to our build directory so `go install` puts the binary there
export GOBIN="${BUILD_DIR}"

echo "Building obfs4proxy ${OBFS4_VERSION} for ${ABI}..."

# The obfs4proxy binary lives at cmd/obfs4proxy in the obfs4 module.
# Try the standard cmd/ path first, then fall back to root path
# (older versions put it at the module root).
OBFS4_PKG="gitlab.com/yawning/obfs4.git/cmd/obfs4proxy@${OBFS4_VERSION}"
if ! go install -trimpath -ldflags="-s -w -extldflags '-pie'" \
    "${OBFS4_PKG}" 2>&1; then
    echo "cmd/obfs4proxy path failed, trying module root path..."
    OBFS4_PKG="gitlab.com/yawning/obfs4.git/obfs4proxy@${OBFS4_VERSION}"
    go install -trimpath -ldflags="-s -w -extldflags '-pie'" \
        "${OBFS4_PKG}"
fi

# ── Copy ─────────────────────────────────────────────────────
mkdir -p "${OUTPUT_DIR}"
cp "${GOBIN}/obfs4proxy" "${OUTPUT_DIR}/libobfs4proxy.so"

echo "Built: ${OUTPUT_DIR}/libobfs4proxy.so"
ls -lh "${OUTPUT_DIR}/libobfs4proxy.so"
echo ""
echo "obfs4proxy build complete for ${ABI}!"
