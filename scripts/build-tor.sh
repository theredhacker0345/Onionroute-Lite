#!/bin/bash
# ══════════════════════════════════════════════════════════════
# Build Tor for Android (multi-ABI)
# ══════════════════════════════════════════════════════════════
# Cross-compiles Tor daemon for Android with all dependencies
# statically linked. Supports 4 CPU architectures (ABIs).
#
# Key fixes for NDK r26d (Clang-only toolchain):
#   - OpenSSL 3.x Configure needs CC/AR/RANLIB exported
#   - -D__ANDROID_API__=N for proper API level detection
#   - android-arm -> android-arm for armeabi-v7a with -latomic
#   - No GCC lookup — force Clang via CC= variable
#
# Supported ABIs:
#   - armeabi-v7a  (32-bit ARM)
#   - arm64-v8a    (64-bit ARM)
#   - x86          (32-bit Intel)
#   - x86_64       (64-bit Intel)
#
# Usage:
#   ./build-tor.sh [ABI] [NDK_PATH] [OUTPUT_DIR]
# ══════════════════════════════════════════════════════════════

set -euo pipefail

# ── Parse arguments ───────────────────────────────────────────
ABI="${1:-armeabi-v7a}"
NDK_PATH="${2:-${ANDROID_NDK_HOME:-/opt/android-ndk}}"
OUTPUT_DIR="${3:-./app/src/main/jniLibs/${ABI}}"

# ── Version Configuration ─────────────────────────────────────
TOR_VERSION="0.4.9.9"
TOR_DIR="tor-${TOR_VERSION}"
TOR_SHA256="bd75ba7fd68f607c7806fcf70156a300aa926e9ad69a5e56a8e6414f5227e833"

# Dependency versions — OpenSSL 3.0 LTS (works with NDK r26d Clang-only toolchain)
LIBEVENT_VERSION="2.1.12-stable"
OPENSSL_VERSION="3.0.20"
OPENSSL_SHA256="c80a01dfc70ece4dc21168932c37739042d404d46ccc81a5986dd75314ecda6f"
ZLIB_VERSION="1.3.1"

# ── ABI-specific configuration ────────────────────────────────
API=23  # Android 6.0 (Marshmallow) minimum
TOOLCHAIN="${NDK_PATH}/toolchains/llvm/prebuilt/linux-x86_64"

case "${ABI}" in
    armeabi-v7a)
        NDK_COMPILER_PREFIX="armv7a-linux-androideabi${API}"
        ABI_CFLAGS="-march=armv7-a -mfloat-abi=softfp -mfpu=vfpv3-d16"
        OPENSSL_TARGET="android-arm"
        LIBEVENT_HOST="arm-linux-androideabi"
        ;;
    arm64-v8a)
        NDK_COMPILER_PREFIX="aarch64-linux-android${API}"
        ABI_CFLAGS="-march=armv8-a"
        OPENSSL_TARGET="android-arm64"
        LIBEVENT_HOST="aarch64-linux-android"
        ;;
    x86)
        NDK_COMPILER_PREFIX="i686-linux-android${API}"
        ABI_CFLAGS="-march=i686 -msse3"
        OPENSSL_TARGET="android-x86"
        LIBEVENT_HOST="i686-linux-android"
        ;;
    x86_64)
        NDK_COMPILER_PREFIX="x86_64-linux-android${API}"
        ABI_CFLAGS="-march=x86-64 -msse4.2"
        OPENSSL_TARGET="android-x86_64"
        LIBEVENT_HOST="x86_64-linux-android"
        ;;
    *)
        echo "ERROR: Unsupported ABI '${ABI}'"
        echo "Supported: armeabi-v7a, arm64-v8a, x86, x86_64"
        exit 1
        ;;
esac

echo "════════════════════════════════════════════"
echo " Building Tor ${TOR_VERSION} for Android ${ABI} "
echo "════════════════════════════════════════════"
echo "NDK: ${NDK_PATH}"
echo "ABI: ${ABI}"
echo "Compiler: ${NDK_COMPILER_PREFIX}-clang"
echo "OpenSSL target: ${OPENSSL_TARGET}"
echo "libevent host: ${LIBEVENT_HOST}"
echo "Output: ${OUTPUT_DIR}"
echo ""

# ── Setup NDK Toolchain ──────────────────────────────────────
export CC="${TOOLCHAIN}/bin/${NDK_COMPILER_PREFIX}-clang"
export CXX="${TOOLCHAIN}/bin/${NDK_COMPILER_PREFIX}-clang++"
export AR="${TOOLCHAIN}/bin/llvm-ar"
export RANLIB="${TOOLCHAIN}/bin/llvm-ranlib"
export STRIP="${TOOLCHAIN}/bin/llvm-strip"
export NM="${TOOLCHAIN}/bin/llvm-nm"
export SYSROOT="${TOOLCHAIN}/sysroot"

# Security hardening flags — ENABLED for F-Droid compliance
export CFLAGS="-Os -fPIE -fPIC ${ABI_CFLAGS} -fstack-protector-strong -D_FORTIFY_SOURCE=2 -D__ANDROID_API__=${API}"
export LDFLAGS="-Os -fPIE -fPIC ${ABI_CFLAGS} -Wl,-z,relro -Wl,-z,now"

# ── Verify NDK ───────────────────────────────────────────────
if [ ! -d "${TOOLCHAIN}" ]; then
    echo "ERROR: NDK toolchain not found at ${TOOLCHAIN}"
    exit 1
fi

if [ ! -f "${CC}" ]; then
    echo "ERROR: Compiler not found: ${CC}"
    echo "Ensure NDK r26d+ is installed with the LLVM toolchain"
    exit 1
fi

echo "CC: ${CC}"
echo "AR: ${AR}"
echo "RANLIB: ${RANLIB}"
echo "STRIP: ${STRIP}"
echo ""

# ── Download Tor Source ──────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BUILD_DIR="${SCRIPT_DIR}/../build-native/tor/${ABI}"
mkdir -p "${BUILD_DIR}"

# ── Create GCC symlinks for OpenSSL ─────────────────────────
# OpenSSL's android targets hardcode a lookup for
# 'arm-linux-androideabi-gcc' (or similar) on PATH.
# NDK r26d removed GCC entirely (Clang-only). We create
# symlinks so OpenSSL can find the expected compiler names.
# Using symlinks directly to the NDK's Clang wrapper scripts.
#
# IMPORTANT: The NDK's clang "binary" is actually a shell script
# that internally calls `clang` from a relative path. So we MUST
# also add the NDK's bin/ to PATH so the script can find its
# own `clang` binary.
WRAPPER_DIR="${BUILD_DIR}/ndk-wrappers"
mkdir -p "${WRAPPER_DIR}"

# Add NDK toolchain bin to PATH first (so clang scripts can find their own `clang`)
export PATH="${TOOLCHAIN}/bin:${PATH}"

# Determine the OpenSSL-expected GCC prefix per ABI
case "${ABI}" in
    armeabi-v7a) GCC_PREFIX="arm-linux-androideabi" ;;
    arm64-v8a)   GCC_PREFIX="aarch64-linux-android" ;;
    x86)         GCC_PREFIX="i686-linux-android" ;;
    x86_64)      GCC_PREFIX="x86_64-linux-android" ;;
esac

# Create symlinks: gcc and g++ point to NDK's Clang
ln -sf "${CC}" "${WRAPPER_DIR}/${GCC_PREFIX}-gcc"
ln -sf "${CXX}" "${WRAPPER_DIR}/${GCC_PREFIX}-g++"
# Create symlink: ar points to llvm-ar
ln -sf "${AR}" "${WRAPPER_DIR}/${GCC_PREFIX}-ar"
# Create symlink: ranlib points to llvm-ranlib
ln -sf "${RANLIB}" "${WRAPPER_DIR}/${GCC_PREFIX}-ranlib"

# Add wrapper dir to PATH so OpenSSL's Configure can find the compilers
export PATH="${WRAPPER_DIR}:${PATH}"
echo "Created GCC symlinks for OpenSSL: ${WRAPPER_DIR}"
echo "Added NDK toolchain bin to PATH: ${TOOLCHAIN}/bin"

if [ ! -d "${BUILD_DIR}/${TOR_DIR}" ]; then
    echo "Downloading Tor ${TOR_VERSION}..."
    curl -L --retry 3 --retry-delay 5 \
        "https://dist.torproject.org/tor-${TOR_VERSION}.tar.gz" \
        -o "${BUILD_DIR}/tor.tar.gz"

    # Verify checksum
    echo "Verifying Tor checksum..."
    echo "${TOR_SHA256}  ${BUILD_DIR}/tor.tar.gz" | sha256sum -c -

    tar xzf "${BUILD_DIR}/tor.tar.gz" -C "${BUILD_DIR}"
    rm "${BUILD_DIR}/tor.tar.gz"
fi

# ── Build Dependencies (per-ABI) ─────────────────────────────
DEPS_PREFIX="${BUILD_DIR}/deps-prefix"
mkdir -p "${DEPS_PREFIX}"

# Build zlib
if [ ! -f "${DEPS_PREFIX}/lib/libz.a" ]; then
    echo "Building zlib ${ZLIB_VERSION} for ${ABI}..."
    ZLIB_DIR="${BUILD_DIR}/zlib-${ZLIB_VERSION}"
    if [ ! -d "${ZLIB_DIR}" ]; then
        curl -L --retry 3 "https://github.com/madler/zlib/releases/download/v${ZLIB_VERSION}/zlib-${ZLIB_VERSION}.tar.gz" \
            -o "${BUILD_DIR}/zlib.tar.gz"
        tar xzf "${BUILD_DIR}/zlib.tar.gz" -C "${BUILD_DIR}"
        rm "${BUILD_DIR}/zlib.tar.gz"
    fi
    cd "${ZLIB_DIR}"
    make clean 2>/dev/null || true
    ./configure --prefix="${DEPS_PREFIX}" --static
    make -j$(nproc)
    make install
fi

# Build OpenSSL 3.x — KEY FIX: must export CC/AR/RANLIB for Clang-only NDK
if [ ! -f "${DEPS_PREFIX}/lib/libssl.a" ]; then
    echo "Building OpenSSL ${OPENSSL_VERSION} for ${ABI}..."
    OSSL_DIR="${BUILD_DIR}/openssl-${OPENSSL_VERSION}"
    # Always remove cached OpenSSL source to avoid stale config from failed builds
    rm -rf "${OSSL_DIR}"
    echo "Downloading OpenSSL ${OPENSSL_VERSION}..."
    curl -L --retry 3 \
        "https://www.openssl.org/source/openssl-${OPENSSL_VERSION}.tar.gz" \
        -o "${BUILD_DIR}/openssl.tar.gz"

    # Verify checksum
    echo "Verifying OpenSSL checksum..."
    echo "${OPENSSL_SHA256}  ${BUILD_DIR}/openssl.tar.gz" | sha256sum -c -

    tar xzf "${BUILD_DIR}/openssl.tar.gz" -C "${BUILD_DIR}"
    rm "${BUILD_DIR}/openssl.tar.gz"

    cd "${OSSL_DIR}"
    # Fully clean previous failed build artifacts
    make distclean 2>/dev/null || true

    # OpenSSL 3.x Configure for Android with Clang toolchain
    # MUST pass CC/AR/RANLIB explicitly — NDK r26d has no GCC
    # NOTE: OpenSSL 3.x forbids mixing env vars (CFLAGS/LDFLAGS) with
    # Configure command-line flags for the same variables. Since we
    # already export CFLAGS/LDFLAGS as env vars, we only pass CC/AR/RANLIB
    # and the API define via Configure arguments.
    echo "Verifying GCC compatibility before OpenSSL Configure..."
    echo "PATH: $PATH"
    echo "which arm-linux-androideabi-gcc: $(which arm-linux-androideabi-gcc 2>&1 || echo NOT_FOUND)"
    arm-linux-androideabi-gcc --version 2>&1 | head -3 || echo "GCC compat test failed"
    echo "Running OpenSSL Configure ${OPENSSL_TARGET}..."
    ./Configure "${OPENSSL_TARGET}" \
        --prefix="${DEPS_PREFIX}" \
        no-shared no-tests no-dso \
        -D__ANDROID_API__=${API} \
        CC="${CC}" \
        AR="${AR}" \
        RANLIB="${RANLIB}" 2>&1 || { echo "OpenSSL Configure FAILED"; exit 1; }

    make -j$(nproc)
    make install_sw
fi

# Build libevent
if [ ! -f "${DEPS_PREFIX}/lib/libevent.a" ]; then
    echo "Building libevent ${LIBEVENT_VERSION} for ${ABI}..."
    LE_DIR="${BUILD_DIR}/libevent-${LIBEVENT_VERSION}"
    if [ ! -d "${LE_DIR}" ]; then
        curl -L --retry 3 \
            "https://github.com/libevent/libevent/releases/download/release-${LIBEVENT_VERSION}/libevent-${LIBEVENT_VERSION}.tar.gz" \
            -o "${BUILD_DIR}/libevent.tar.gz"
        tar xzf "${BUILD_DIR}/libevent.tar.gz" -C "${BUILD_DIR}"
        rm "${BUILD_DIR}/libevent.tar.gz"
    fi
    cd "${LE_DIR}"
    make clean 2>/dev/null || true
    ./configure \
        --host="${LIBEVENT_HOST}" \
        --prefix="${DEPS_PREFIX}" \
        --disable-shared \
        --enable-static \
        --disable-tests \
        --disable-samples \
        --disable-libevent-regress \
        CC="${CC}" \
        AR="${AR}" \
        RANLIB="${RANLIB}" \
        CFLAGS="${CFLAGS} -I${DEPS_PREFIX}/include" \
        LDFLAGS="${LDFLAGS} -L${DEPS_PREFIX}/lib"
    make -j$(nproc)
    make install
fi

# ── Configure & Build Tor ────────────────────────────────────
cd "${BUILD_DIR}/${TOR_DIR}"

echo "Configuring Tor ${TOR_VERSION} for ${ABI}..."
./configure \
    --host="${LIBEVENT_HOST}" \
    --prefix="/tmp/tor-install" \
    --disable-asciidoc \
    --disable-manpage \
    --disable-html-manual \
    --disable-systemd \
    --disable-lzma \
    --disable-zstd \
    --disable-seccomp \
    --enable-static-libevent \
    --enable-static-openssl \
    --enable-static-zlib \
    --with-libevent-dir="${DEPS_PREFIX}" \
    --with-openssl-dir="${DEPS_PREFIX}" \
    --with-zlib-dir="${DEPS_PREFIX}" \
    CC="${CC}" \
    CXX="${CXX}" \
    AR="${AR}" \
    RANLIB="${RANLIB}" \
    CFLAGS="${CFLAGS} -I${DEPS_PREFIX}/include" \
    LDFLAGS="${LDFLAGS} -L${DEPS_PREFIX}/lib"

echo "Building Tor for ${ABI}..."
make -j$(nproc)

# ── Strip & Copy ─────────────────────────────────────────────
mkdir -p "${OUTPUT_DIR}"

echo "Stripping binary..."
${STRIP} --strip-all src/app/tor -o "${OUTPUT_DIR}/libtor.so"

SIZE=$(stat -c%s "${OUTPUT_DIR}/libtor.so" 2>/dev/null || stat -f%z "${OUTPUT_DIR}/libtor.so" 2>/dev/null)
if [ "$SIZE" -lt 1000 ]; then
    echo "ERROR: libtor.so is only ${SIZE} bytes — build likely failed!"
    exit 1
fi

echo "Built: ${OUTPUT_DIR}/libtor.so"
ls -lh "${OUTPUT_DIR}/libtor.so"

echo ""
echo "Tor ${TOR_VERSION} build complete for ${ABI}!"
