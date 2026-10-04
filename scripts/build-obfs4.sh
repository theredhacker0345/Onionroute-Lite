#!/bin/bash
# ══════════════════════════════════════════════════════════════
# Build obfs4proxy for Android (multi-ABI)
# ══════════════════════════════════════════════════════════════
set -euo pipefail

ABI="${1:-armeabi-v7a}"
NDK_PATH="${2:-${ANDROID_NDK_HOME:-/opt/android-ndk}}"
OUTPUT_DIR="${3:-./app/src/main/jniLibs/${ABI}}"
OBFS4_VERSION="v0.0.0-20231012084234-c3e2d44b1033"

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
echo " Building obfs4proxy ${OBFS4_VERSION} for Android ${ABI} "
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
BUILD_DIR="${SCRIPT_DIR}/../build-native/obfs4/${ABI}"
mkdir -p "${BUILD_DIR}"
cd "${BUILD_DIR}"

# ── Build strategy ───────────────────────────────────────────
# The correct approach for Go cross-compilation in CI:
#   1. go mod init — create a module
#   2. go get <package> — this resolves ALL transitive dependencies
#      and populates both go.mod and go.sum
#   3. go build <package> — build the package
#
# We do NOT run `go mod tidy` because it would prune deps not
# imported by source files in our module.
# We do NOT use `go mod download` alone because it doesn't resolve
# transitive deps into go.sum.
# We do NOT use `go install pkg@version` because it fails with
# GOBIN when cross-compiling.
# We do NOT use `go build pkg@version` because it doesn't support
# the @version syntax.
#
# The key insight: `go get <package>` (not just <module>) resolves
# all transitive dependencies of that package and adds them to
# go.sum. Then `go build <package>` works because go.sum has all
# the entries needed.

OBFS4_PKG="gitlab.com/yawning/obfs4.git/obfs4proxy"

echo "Downloading obfs4proxy ${OBFS4_VERSION} for ${ABI}..."
# `go mod init` FAILS if go.mod already exists, and the build aborts on
# a non-zero status. That makes the script non-idempotent: any re-run
# over a dirty tree (an interrupted build, a partial clone, or simply
# building a second ABI into the same per-ABI dir) fails here and the
# component is reported MISSING even though nothing is actually wrong.
# Guard it so re-running is always safe.
if [ ! -f go.mod ]; then
    go mod init onionroute-build/obfs4
fi

# go get the specific package (not just the module root) so all
# transitive deps are resolved into go.sum.
go get "${OBFS4_PKG}@${OBFS4_VERSION}"

echo "Building obfs4proxy for ${ABI}..."
if ! go build -trimpath -ldflags="-s -w -extldflags '-pie'" \
    -o obfs4proxy "${OBFS4_PKG}" 2>&1; then
    echo "Module root path failed, trying cmd/obfs4proxy..."
    OBFS4_PKG="gitlab.com/yawning/obfs4.git/cmd/obfs4proxy"
    go build -trimpath -ldflags="-s -w -extldflags '-pie'" \
        -o obfs4proxy "${OBFS4_PKG}"
fi

mkdir -p "${OUTPUT_DIR}"
cp obfs4proxy "${OUTPUT_DIR}/libobfs4proxy.so"

echo "Built: ${OUTPUT_DIR}/libobfs4proxy.so"
ls -lh "${OUTPUT_DIR}/libobfs4proxy.so"
echo "obfs4proxy build complete for ${ABI}!"
