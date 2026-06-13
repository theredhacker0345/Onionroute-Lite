#!/bin/bash
# ══════════════════════════════════════════════════════════════
# Build all native binaries for OnionRoute Lite
# ══════════════════════════════════════════════════════════════
# Convenience script that builds all native components:
#   1. Tor daemon
#   2. obfs4proxy (obfs4 pluggable transport)
#   3. Snowflake client + proxy (snowflake pluggable transport + Kindness Mode)
#   4. hev-socks5-tunnel (TUN-to-SOCKS5 forwarder for VPN mode)
#
# Supports multiple Android ABIs:
#   - armeabi-v7a  (32-bit ARM, legacy devices)
#   - arm64-v8a    (64-bit ARM, modern devices)
#   - x86          (32-bit Intel, emulators)
#   - x86_64       (64-bit Intel, emulators)
#
# Prerequisites:
#   - Android NDK r26d installed
#   - Go 1.21+ installed
#   - Standard build tools (make, gcc, git, curl)
#
# Usage:
#   ./build-all.sh [ABI] [NDK_PATH]
#   ./build-all.sh                  # Build all ABIs
#   ./build-all.sh arm64-v8a        # Build only arm64-v8a
#   ./build-all.sh armeabi-v7a /opt/android-ndk
#
# Environment:
#   ANDROID_NDK_HOME - Path to Android NDK (overridden by arg)
#
# F-Droid notes:
#   - Build artifacts are kept in build-native/ for reproducibility
#   - All sources are downloaded with checksum verification
#   - Compiler hardening flags are enabled
# ══════════════════════════════════════════════════════════════

set -euo pipefail

# ── Supported ABIs ────────────────────────────────────────────
ALL_ABIS=("armeabi-v7a" "arm64-v8a" "x86" "x86_64")

# ── Parse arguments ───────────────────────────────────────────
ABI_ARG="${1:-all}"
NDK_PATH="${2:-${ANDROID_NDK_HOME:-/opt/android-ndk}}"

# Determine which ABIs to build
if [ "${ABI_ARG}" = "all" ]; then
    SELECTED_ABIS=("${ALL_ABIS[@]}")
else
    # Validate the requested ABI
    VALID=0
    for abi in "${ALL_ABIS[@]}"; do
        if [ "${abi}" = "${ABI_ARG}" ]; then
            VALID=1
            break
        fi
    done
    if [ "${VALID}" -eq 0 ]; then
        echo "ERROR: Unknown ABI '${ABI_ARG}'"
        echo "Supported ABIs: ${ALL_ABIS[*]}"
        exit 1
    fi
    SELECTED_ABIS=("${ABI_ARG}")
fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="${SCRIPT_DIR}/.."

echo "╔═══════════════════════════════════════════════╗"
echo "║   OnionRoute Lite — Build All Binaries         ║"
echo "║   ABIs: ${SELECTED_ABIS[*]}"
echo "║   NDK:  ${NDK_PATH}"
echo "╚═══════════════════════════════════════════════╝"
echo ""

# Verify NDK exists
if [ ! -d "${NDK_PATH}" ]; then
    echo "ERROR: Android NDK not found at ${NDK_PATH}"
    echo "Set ANDROID_NDK_HOME or pass NDK path as argument"
    exit 1
fi

# ── Build each ABI ────────────────────────────────────────────
TOTAL_START=$(date +%s)

for ABI in "${SELECTED_ABIS[@]}"; do
    OUTPUT_DIR="${PROJECT_DIR}/app/src/main/jniLibs/${ABI}"

    echo ""
    echo "╔═══════════════════════════════════════════╗"
    echo "║  Building for ABI: ${ABI}"
    echo "║  Output: ${OUTPUT_DIR}"
    echo "╚═══════════════════════════════════════════╝"
    echo ""

    # Create output directory
    mkdir -p "${OUTPUT_DIR}"

    # ── Build Each Component ──────────────────────────────────

    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo " [1/4] Building Tor daemon [${ABI}]..."
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    bash "${SCRIPT_DIR}/build-tor.sh" "${ABI}" "${NDK_PATH}" "${OUTPUT_DIR}"

    echo ""
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo " [2/4] Building obfs4proxy [${ABI}]..."
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    bash "${SCRIPT_DIR}/build-obfs4.sh" "${ABI}" "${NDK_PATH}" "${OUTPUT_DIR}"

    echo ""
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo " [3/4] Building Snowflake client + proxy [${ABI}]..."
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    bash "${SCRIPT_DIR}/build-snowflake.sh" "${ABI}" "${NDK_PATH}" "${OUTPUT_DIR}"

    echo ""
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo " [4/4] Building hev-socks5-tunnel [${ABI}]..."
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    bash "${SCRIPT_DIR}/build-tun2socks.sh" "${ABI}" "${NDK_PATH}" "${OUTPUT_DIR}"

    # ── Verify this ABI's outputs ─────────────────────────────
    echo ""
    echo "Verifying ${ABI} binaries..."
    MISSING=0
    for expected in libtor.so libobfs4proxy.so libsnowflake.so libsnowflake-proxy.so libtun2socks.so; do
        if [ ! -f "${OUTPUT_DIR}/${expected}" ]; then
            echo "  ERROR: Missing ${expected}"
            MISSING=1
        else
            echo "  OK: ${expected}"
        fi
    done

    if [ "${MISSING}" -eq 1 ]; then
        echo ""
        echo "ERROR: Some ${ABI} binaries are missing! Check build output above."
        exit 1
    fi

    echo ""
    echo "ABI ${ABI} build complete!"
    echo "  Size: $(du -sh "${OUTPUT_DIR}/" | cut -f1)"
done

# ── Summary ──────────────────────────────────────────────────
TOTAL_END=$(date +%s)
TOTAL_ELAPSED=$(( TOTAL_END - TOTAL_START ))

echo ""
echo "╔═══════════════════════════════════════════════╗"
echo "║          Build Complete!                       ║"
echo "║          Time: ${TOTAL_ELAPSED}s"
echo "╚═══════════════════════════════════════════════╝"
echo ""
echo "Native binaries per ABI:"

for ABI in "${SELECTED_ABIS[@]}"; do
    ABI_DIR="${PROJECT_DIR}/app/src/main/jniLibs/${ABI}"
    echo ""
    echo "  [${ABI}]"
    ls -lh "${ABI_DIR}/" | tail -n +2 | while read -r line; do
        echo "    ${line}"
    done
    echo "    Total: $(du -sh "${ABI_DIR}/" | cut -f1)"
done

echo ""
echo "All native binaries built successfully!"
echo ""
echo "Next steps:"
echo "  1. Build the APK: ./gradlew assembleRelease"
echo "  2. Per-ABI APKs will be generated automatically"
echo "  3. Install on device: adb install app/build/outputs/apk/fdroidRelease/app-fdroid-release-${SELECTED_ABIS[0]}.apk"
