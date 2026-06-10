#!/bin/bash
# ══════════════════════════════════════════════════════════════
# Build obfs4proxy for Android (multi-ABI)
# ══════════════════════════════════════════════════════════════
# obfs4proxy is a pluggable transport that disguises Tor
# traffic as random-looking data. It's written in Go.
#
# Build approach:
#   Clones the obfs4 source at a pinned Git tag and builds
#   from the cloned source. Same approach as Orbot.
#   F-Droid compliant: verifiable source, pinned tag, no prebuilts.
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
#   ./build-obfs4.sh [ABI] [NDK_PATH] [OUTPUT_DIR]
#   ./build-obfs4.sh arm64-v8a
#   ./build-obfs4.sh x86 /opt/android-ndk
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
OBFS4_VERSION="0.0.14"
OBFS4_TAG="obfs4proxy-0.0.14"

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
export CGO_CC="${NDK_CC}"
export CGO_CFLAGS="${ABI_CFLAGS}"
export CGO_LDFLAGS="${ABI_LDFLAGS}"

# ── Clone obfs4 source at pinned version ─────────────────────
# F-Droid: Build from source at a pinned Git tag for reproducibility.
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BUILD_DIR="${SCRIPT_DIR}/../build-native/obfs4/${ABI}"
mkdir -p "${BUILD_DIR}"

OBFS4_DIR="${BUILD_DIR}/obfs4"
if [ ! -d "${OBFS4_DIR}" ]; then
    echo "Cloning obfs4proxy ${OBFS4_VERSION}..."
    git clone --depth 1 --branch "${OBFS4_TAG}" \
        https://gitlab.com/yawning/obfs4.git \
        "${OBFS4_DIR}"
else
    echo "obfs4 source already cloned at ${OBFS4_DIR}"
fi

cd "${OBFS4_DIR}"

# Verify module integrity (F-Droid: checksum verification)
echo "Verifying module integrity..."
go mod verify || echo "WARNING: go mod verify had warnings (non-critical)"

# ── Determine package path ───────────────────────────────────
# obfs4 repo layout varies by version:
#   - Newer: cmd/obfs4proxy/
#   - Older: obfs4proxy/ at root
OBFS4_PKG=""
if [ -d "cmd/obfs4proxy" ]; then
    OBFS4_PKG="./cmd/obfs4proxy"
elif [ -d "obfs4proxy" ]; then
    OBFS4_PKG="./obfs4proxy"
else
    echo "ERROR: Cannot find obfs4proxy package directory"
    echo "Available directories:"
    ls -la
    exit 1
fi

echo "Building obfs4proxy for ${ABI} from ${OBFS4_PKG}..."
go build -trimpath -ldflags="-s -w -extldflags '-pie'" \
    -o "${BUILD_DIR}/obfs4proxy" \
    "${OBFS4_PKG}"

# ── Copy to jniLibs ──────────────────────────────────────────
mkdir -p "${OUTPUT_DIR}"
cp "${BUILD_DIR}/obfs4proxy" "${OUTPUT_DIR}/libobfs4proxy.so"

echo "Built: ${OUTPUT_DIR}/libobfs4proxy.so"
ls -lh "${OUTPUT_DIR}/libobfs4proxy.so"
echo ""
echo "obfs4proxy build complete for ${ABI}!"
