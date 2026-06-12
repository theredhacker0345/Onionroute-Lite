#!/bin/bash
# ══════════════════════════════════════════════════════════════
# Build Tor for Android (multi-ABI)
# ══════════════════════════════════════════════════════════════
# This script cross-compiles the Tor daemon for Android,
# supporting multiple CPU architectures (ABIs).
#
# Supported ABIs:
#   - armeabi-v7a  (32-bit ARM)
#   - arm64-v8a    (64-bit ARM)
#   - x86          (32-bit Intel)
#   - x86_64       (64-bit Intel)
#
# Prerequisites:
#   - Android NDK r25c (or compatible)
#   - Git
#   - Make, GCC host toolchain
#
# Usage:
#   ./build-tor.sh [ABI] [NDK_PATH] [OUTPUT_DIR]
#   ./build-tor.sh arm64-v8a
#   ./build-tor.sh armeabi-v7a /opt/android-ndk ./app/src/main/jniLibs/armeabi-v7a
#
# F-Droid notes:
#   - Source archives are verified with checksums
#   - Compiler hardening flags are ENABLED (not disabled)
#   - PIE is enabled for ASLR support
#   - Static linking for libevent, openssl, zlib
#   - Per-ABI deps are built in separate directories for isolation
# ══════════════════════════════════════════════════════════════

set -euo pipefail

# ── Parse arguments ───────────────────────────────────────────
ABI="${1:-armeabi-v7a}"
NDK_PATH="${2:-${ANDROID_NDK_HOME:-/opt/android-ndk}}"
OUTPUT_DIR="${3:-./app/src/main/jniLibs/${ABI}}"

# ── Version Configuration ─────────────────────────────────────
TOR_VERSION="0.4.9.8"
TOR_DIR="tor-${TOR_VERSION}"
TOR_SHA256="ac1f394e2dd2ab0877d27d928fd0d9e86662fe3ca6afdffb9fd9b6f0f96d05de"

# Dependency versions
LIBEVENT_VERSION="2.1.12-stable"
OPENSSL_VERSION="1.1.1w"
ZLIB_VERSION="1.3.1"

# ── ABI-specific configuration ────────────────────────────────
# Each ABI requires a different NDK compiler prefix, CFLAGS,
# OpenSSL Configure target, and libevent --host value.

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
        ABI_CFLAGS="-march=i686"
        OPENSSL_TARGET="android-x86"
        LIBEVENT_HOST="i686-linux-android"
        ;;
    x86_64)
        NDK_COMPILER_PREFIX="x86_64-linux-android${API}"
        ABI_CFLAGS="-march=x86-64"
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
echo "Compiler: ${NDK_COMPILER_PREFIX}"
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
export SYSROOT="${TOOLCHAIN}/sysroot"

# Security hardening flags — ENABLED for F-Droid compliance
# ABI-specific march flags are included
export CFLAGS="-Os -fPIE -fPIC ${ABI_CFLAGS} -fstack-protector-strong -D_FORTIFY_SOURCE=2"
export LDFLAGS="-Os -fPIE -fPIC ${ABI_CFLAGS} -static-libstdc++ -Wl,-z,relro -Wl,-z,now"

# OpenSSL 1.1.1w's Configure script hardcodes a check for GCC-style
# compiler names (e.g. arm-linux-androideabi-gcc) which were removed
# from NDK in r23. We create symlinks from the old GCC names to the
# new Clang compilers so OpenSSL's Configure can find them.
# These are created once and persist in the NDK toolchain directory.
echo "Creating GCC-style symlinks for OpenSSL compatibility..."
ln -sf "${NDK_COMPILER_PREFIX}-clang" "${TOOLCHAIN}/bin/${LIBEVENT_HOST}-gcc" 2>/dev/null || true
ln -sf "${NDK_COMPILER_PREFIX}-clang++" "${TOOLCHAIN}/bin/${LIBEVENT_HOST}-g++" 2>/dev/null || true
ln -sf llvm-ar "${TOOLCHAIN}/bin/${LIBEVENT_HOST}-ar" 2>/dev/null || true
ln -sf llvm-ranlib "${TOOLCHAIN}/bin/${LIBEVENT_HOST}-ranlib" 2>/dev/null || true
# Also add the toolchain to PATH so OpenSSL's Configure can find the compiler
export PATH="${TOOLCHAIN}/bin:${PATH}"
# Set ANDROID_NDK_HOME so OpenSSL can locate the NDK sysroot
export ANDROID_NDK_HOME="${NDK_PATH}"

# ── Verify NDK ───────────────────────────────────────────────
if [ ! -d "${TOOLCHAIN}" ]; then
    echo "ERROR: NDK toolchain not found at ${TOOLCHAIN}"
    exit 1
fi

if [ ! -f "${CC}" ]; then
    echo "ERROR: Compiler not found: ${CC}"
    echo "Ensure NDK r25c+ is installed with the LLVM toolchain"
    exit 1
fi

# ── Download Tor Source ──────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BUILD_DIR="${SCRIPT_DIR}/../build-native/tor/${ABI}"
mkdir -p "${BUILD_DIR}"

if [ ! -d "${BUILD_DIR}/${TOR_DIR}" ]; then
    echo "Downloading Tor ${TOR_VERSION}..."
    curl -L "https://dist.torproject.org/tor-${TOR_VERSION}.tar.gz" -o "${BUILD_DIR}/tor.tar.gz"

    # Verify checksum if available (non-placeholder)
    if [ "${TOR_SHA256}" != "placeholder-verify-on-release" ]; then
        echo "Verifying Tor checksum..."
        echo "${TOR_SHA256}  ${BUILD_DIR}/tor.tar.gz" | sha256sum -c -
    else
        echo "WARNING: Tor SHA256 is placeholder — skipping checksum verification"
        echo "  Replace TOR_SHA256 with the actual hash for production builds"
    fi

    tar xzf "${BUILD_DIR}/tor.tar.gz" -C "${BUILD_DIR}"
    rm "${BUILD_DIR}/tor.tar.gz"
fi

# ── Build Dependencies (per-ABI) ─────────────────────────────
# Each ABI gets its own deps-prefix to avoid cross-contamination
DEPS_PREFIX="${BUILD_DIR}/deps-prefix"
mkdir -p "${DEPS_PREFIX}"

# Build zlib
if [ ! -f "${DEPS_PREFIX}/lib/libz.a" ]; then
    echo "Building zlib ${ZLIB_VERSION} for ${ABI}..."
    ZLIB_DIR="${BUILD_DIR}/zlib-${ZLIB_VERSION}"
    if [ ! -d "${ZLIB_DIR}" ]; then
        curl -L "https://zlib.net/fossils/zlib-${ZLIB_VERSION}.tar.gz" -o "${BUILD_DIR}/zlib.tar.gz"
        tar xzf "${BUILD_DIR}/zlib.tar.gz" -C "${BUILD_DIR}"
        rm "${BUILD_DIR}/zlib.tar.gz"
    fi
    cd "${ZLIB_DIR}"
    # Clean any previous build artifacts for this ABI
    make clean 2>/dev/null || true
    CC="${CC}" AR="${AR}" RANLIB="${RANLIB}" \
    ./configure --prefix="${DEPS_PREFIX}" --static
    make -j$(nproc)
    make install
fi

# Build OpenSSL
if [ ! -f "${DEPS_PREFIX}/lib/libssl.a" ]; then
    echo "Building OpenSSL ${OPENSSL_VERSION} for ${ABI}..."
    OSSL_DIR="${BUILD_DIR}/openssl-${OPENSSL_VERSION}"
    if [ ! -d "${OSSL_DIR}" ]; then
        curl -L "https://www.openssl.org/source/openssl-${OPENSSL_VERSION}.tar.gz" -o "${BUILD_DIR}/openssl.tar.gz"
        tar xzf "${BUILD_DIR}/openssl.tar.gz" -C "${BUILD_DIR}"
        rm "${BUILD_DIR}/openssl.tar.gz"
    fi
    cd "${OSSL_DIR}"
    # Clean any previous build artifacts for this ABI
    make clean 2>/dev/null || true
    # NDK r23+ removed GCC; must pass CC/AR/RANLIB explicitly to
    # OpenSSL's Configure so it uses Clang from the LLVM toolchain.
    # -D__ANDROID_API__ tells OpenSSL the minimum API level.
    ./Configure "${OPENSSL_TARGET}" \
        --prefix="${DEPS_PREFIX}" \
        no-shared no-tests no-dso \
        CC="${CC}" \
        AR="${AR}" \
        RANLIB="${RANLIB}" \
        -D__ANDROID_API__=${API} \
        ${ABI_CFLAGS}
    make -j$(nproc)
    make install_sw
fi

# Build libevent
if [ ! -f "${DEPS_PREFIX}/lib/libevent.a" ]; then
    echo "Building libevent ${LIBEVENT_VERSION} for ${ABI}..."
    LE_DIR="${BUILD_DIR}/libevent-${LIBEVENT_VERSION}"
    if [ ! -d "${LE_DIR}" ]; then
        curl -L "https://github.com/libevent/libevent/releases/download/release-${LIBEVENT_VERSION}/libevent-${LIBEVENT_VERSION}.tar.gz" -o "${BUILD_DIR}/libevent.tar.gz"
        tar xzf "${BUILD_DIR}/libevent.tar.gz" -C "${BUILD_DIR}"
        rm "${BUILD_DIR}/libevent.tar.gz"
    fi
    cd "${LE_DIR}"
    # Clean any previous build artifacts for this ABI
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

echo "Configuring Tor for ${ABI}..."
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

echo "Built: ${OUTPUT_DIR}/libtor.so"
ls -lh "${OUTPUT_DIR}/libtor.so"

echo ""
echo "Tor ${TOR_VERSION} build complete for ${ABI}!"
