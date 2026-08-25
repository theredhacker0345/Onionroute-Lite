#!/bin/bash
# ══════════════════════════════════════════════════════════════
# Build webtunnel-client for Android (multi-ABI)
# ══════════════════════════════════════════════════════════════
# Builds the WebTunnel pluggable transport CLIENT from the Tor
# Project's webtunnel repository:
#   https://gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/webtunnel
#
# WebTunnel is a pluggable transport that disguises Tor traffic as
# regular HTTPS traffic to evade deep packet inspection. Tor REQUIRES
# a ClientTransportPlugin binary for it — bridge lines alone do NOT
# work (bootstrap stalls at PT_MISSING).
set -euo pipefail

ABI="${1:-armeabi-v7a}"
NDK_PATH="${2:-${ANDROID_NDK_HOME:-/opt/android-ndk}}"
OUTPUT_DIR="${3:-./app/src/main/jniLibs/${ABI}}"
WEBTUNNEL_VERSION="v0.0.3"

API=23
TOOLCHAIN="${NDK_PATH}/toolchains/llvm/prebuilt/linux-x86_64"

case "${ABI}" in
    armeabi-v7a) GOARCH="arm"; GOARM="7"; NDK_CC="${TOOLCHAIN}/bin/armv7a-linux-androideabi${API}-clang"; ABI_CFLAGS="-Os -fPIE -march=armv7-a -mfloat-abi=softfp -mfpu=vfpv3-d16 -fstack-protector-strong"; ABI_LDFLAGS="-Os -fPIE -march=armv7-a -Wl,-z,relro -Wl,-z,now" ;;
    arm64-v8a)   GOARCH="arm64"; GOARM=""; NDK_CC="${TOOLCHAIN}/bin/aarch64-linux-android${API}-clang"; ABI_CFLAGS="-Os -fPIE -march=armv8-a -fstack-protector-strong"; ABI_LDFLAGS="-Os -fPIE -march=armv8-a -Wl,-z,relro -Wl,-z,now" ;;
    x86)         GOARCH="386";  GOARM=""; NDK_CC="${TOOLCHAIN}/bin/i686-linux-android${API}-clang"; ABI_CFLAGS="-Os -fPIE -march=i686 -fstack-protector-strong"; ABI_LDFLAGS="-Os -fPIE -march=i686 -Wl,-z,relro -Wl,-z,now" ;;
    x86_64)      GOARCH="amd64"; GOARM=""; NDK_CC="${TOOLCHAIN}/bin/x86_64-linux-android${API}-clang"; ABI_CFLAGS="-Os -fPIE -march=x86-64 -fstack-protector-strong"; ABI_LDFLAGS="-Os -fPIE -march=x86-64 -Wl,-z,relro -Wl,-z,now" ;;
    *) echo "ERROR: Unsupported ABI '${ABI}'"; exit 1 ;;
esac

echo "════════════════════════════════════════════"
echo " Building webtunnel-client ${WEBTUNNEL_VERSION} for Android ${ABI} "
echo "════════════════════════════════════════════"

[ ! -d "${TOOLCHAIN}" ] && echo "ERROR: NDK toolchain not found" && exit 1
[ ! -f "${NDK_CC}" ] && echo "ERROR: Compiler not found: ${NDK_CC}" && exit 1

export GOOS=android
export GOARCH="${GOARCH}"
[ -n "${GOARM}" ] && export GOARM="${GOARM}"
export CGO_ENABLED=1
export CC="${NDK_CC}"
export CGO_CFLAGS="${ABI_CFLAGS}"
export CGO_LDFLAGS="${ABI_LDFLAGS}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BUILD_DIR="${SCRIPT_DIR}/../build-native/webtunnel/${ABI}"
mkdir -p "${BUILD_DIR}"
cd "${BUILD_DIR}"

# Same strategy as build-obfs4.sh:
#   go mod init → go get <package>@<version> (resolves transitive deps
#   into go.sum) → go build <package>.
WEBTUNNEL_PKG="gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/webtunnel/client"

echo "Downloading webtunnel ${WEBTUNNEL_VERSION} for ${ABI}..."
go mod init onionroute-build/webtunnel

if ! go get "${WEBTUNNEL_PKG}@${WEBTUNNEL_VERSION}"; then
    echo "Tag ${WEBTUNNEL_VERSION} not found, falling back to @latest..."
    go get "${WEBTUNNEL_PKG}@latest"
fi

echo "Building webtunnel-client for ${ABI}..."
go build -trimpath -ldflags="-s -w -extldflags '-pie'" \
    -o webtunnel-client "${WEBTUNNEL_PKG}"

mkdir -p "${OUTPUT_DIR}"
cp webtunnel-client "${OUTPUT_DIR}/libwebtunnel.so"

echo "Built: ${OUTPUT_DIR}/libwebtunnel.so"
ls -lh "${OUTPUT_DIR}/libwebtunnel.so"
echo "webtunnel-client build complete for ${ABI}!"
