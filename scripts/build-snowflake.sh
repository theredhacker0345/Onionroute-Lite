#!/bin/bash
# ══════════════════════════════════════════════════════════════
# Build Snowflake Client & Proxy for Android (multi-ABI)
# ══════════════════════════════════════════════════════════════
set -euo pipefail

ABI="${1:-armeabi-v7a}"
NDK_PATH="${2:-${ANDROID_NDK_HOME:-/opt/android-ndk}}"
OUTPUT_DIR="${3:-./app/src/main/jniLibs/${ABI}}"
SNOWFLAKE_VERSION="2.10.1"

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
echo " Building Snowflake ${SNOWFLAKE_VERSION} for Android ${ABI} "
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
BUILD_DIR="${SCRIPT_DIR}/../build-native/snowflake/${ABI}"
mkdir -p "${BUILD_DIR}"
cd "${BUILD_DIR}"

# ── Build strategy ───────────────────────────────────────────
# Same approach as obfs4: go mod init → go get <package> (which
# resolves all transitive deps into go.sum) → go build <package>
# WITHOUT go mod tidy (which would prune unused deps).
#
# IMPORTANT: Module path does NOT have .git suffix:
#   gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/snowflake/v2

SF_MODULE="gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/snowflake/v2"

echo "Downloading Snowflake v${SNOWFLAKE_VERSION} for ${ABI}..."
go mod init onionroute-build/snowflake

# go get the specific packages so all transitive deps are resolved.
go get "${SF_MODULE}/client@v${SNOWFLAKE_VERSION}"
go get "${SF_MODULE}/proxy@v${SNOWFLAKE_VERSION}"

# ── Build 1: Snowflake Client ────────────────────────────────
echo "Building Snowflake client for ${ABI}..."
go build -trimpath -ldflags="-s -w -extldflags '-pie'" \
    -o snowflake-client \
    "${SF_MODULE}/client"

# ── Build 2: Snowflake Proxy (Kindness Mode) ────────────────
echo "Building Snowflake proxy for ${ABI}..."
go build -trimpath -ldflags="-s -w -extldflags '-pie'" \
    -o snowflake-proxy \
    "${SF_MODULE}/proxy"

# ── Copy ─────────────────────────────────────────────────────
mkdir -p "${OUTPUT_DIR}"
cp snowflake-client "${OUTPUT_DIR}/libsnowflake.so"
cp snowflake-proxy "${OUTPUT_DIR}/libsnowflake-proxy.so"

echo "Built: ${OUTPUT_DIR}/libsnowflake.so"
ls -lh "${OUTPUT_DIR}/libsnowflake.so"
echo "Built: ${OUTPUT_DIR}/libsnowflake-proxy.so"
ls -lh "${OUTPUT_DIR}/libsnowflake-proxy.so"
echo "Snowflake client + proxy build complete for ${ABI}!"
