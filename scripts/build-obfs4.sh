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
# We create a Go module, add the obfs4 dependency, and build
# WITHOUT running `go mod tidy`. This is critical because:
#
#   1. go mod tidy prunes unused dependencies from go.mod
#   2. Since our module has no source files importing obfs4,
#      tidy removes it and go build then fails
#   3. Build tags like //go:build ignore don't help because
#      tidy also respects them
#   4. go install pkg@version doesn't work with GOBIN when
#      cross-compiling ("cannot install cross-compiled binaries
#      when GOBIN is set")
#   5. go build pkg@version doesn't support the @version syntax
#
# The solution: go mod init → go get → go mod download → go build
# WITHOUT go mod tidy. This keeps all deps in go.mod/go.sum.

echo "Downloading obfs4proxy ${OBFS4_VERSION} for ${ABI}..."
go mod init onionroute-build/obfs4
go get gitlab.com/yawning/obfs4.git@${OBFS4_VERSION}
go mod download

echo "Building obfs4proxy for ${ABI}..."
# The obfs4proxy binary may be at different paths depending on version.
# Try the module root path first (where the main package is), then cmd/.
OBFS4_PKG="gitlab.com/yawning/obfs4.git/obfs4proxy"
if ! go build -trimpath -ldflags="-s -w -extldflags '-pie'" \
    -o obfs4proxy "${OBFS4_PKG}" 2>&1; then
    echo "Module root path failed, trying cmd/obfs4proxy..."
    OBFS4_PKG="gitlab.com/yawning/obfs4.git/cmd/obfs4proxy"
    go build -trimpath -ldflags="-s -w -extldflags '-pie'" \
        -o obfs4proxy "${OBFS4_PKG}"
fi

# ── Copy ─────────────────────────────────────────────────────
mkdir -p "${OUTPUT_DIR}"
cp obfs4proxy "${OUTPUT_DIR}/libobfs4proxy.so"

echo "Built: ${OUTPUT_DIR}/libobfs4proxy.so"
ls -lh "${OUTPUT_DIR}/libobfs4proxy.so"
echo ""
echo "obfs4proxy build complete for ${ABI}!"
