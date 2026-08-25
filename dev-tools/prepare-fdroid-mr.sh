#!/usr/bin/env bash
# ══════════════════════════════════════════════════════════════
# prepare-fdroid-mr.sh
# ══════════════════════════════════════════════════════════════
# Prepares the F-Droid Merge Request branch for OnionRoute Lite.
#
# PREREQUISITES:
#   1. You have a GitLab account on https://gitlab.com/fdroid
#   2. You have forked https://gitlab.com/fdroid/fdroiddata to your GitLab account
#   3. You have SSH or HTTPS access to your GitLab fork
#   4. You have yq installed (sudo snap install yq)
#
# USAGE:
#   cd /path/to/Onionroute-Lite
#   ./dev-tools/prepare-fdroid-mr.sh
#
# This script will:
#   1. Get the current commit hash from the local git repo
#   2. Clone your fdroiddata fork
#   3. Create a branch named 'org.onionroute.lite'
#   4. Copy the metadata file from this repo to the fdroiddata fork
#   5. Update the metadata's commit field to the current commit hash
#   6. Commit and push to your fork
#   7. Print the GitLab MR URL to open
# ══════════════════════════════════════════════════════════════

set -euo pipefail

# ── Configuration ─────────────────────────────────────────────
APP_ID="org.onionroute.lite"
METADATA_FILE="metadata/${APP_ID}.yml"
BRANCH_NAME="${APP_ID}"

# Your GitLab username (CHANGE THIS!)
GITLAB_USER="${GITLAB_USER:-darkmaster0345}"

# Fork URL (CHANGE THIS if your GitLab username is different!)
FORK_URL="https://gitlab.com/${GITLAB_USER}/fdroiddata.git"

# ── Step 1: Verify we're in the right directory ───────────────
echo "=== Step 1: Verify environment ==="
if [ ! -f "$METADATA_FILE" ]; then
    echo "ERROR: $METADATA_FILE not found. Run this script from the OnionRoute-Lite repo root."
    exit 1
fi
if [ ! -d ".git" ]; then
    echo "ERROR: Not a git repository. Run this from the OnionRoute-Lite repo root."
    exit 1
fi

# ── Step 2: Get the current commit hash ───────────────────────
echo ""
echo "=== Step 2: Get current commit hash ==="
COMMIT_HASH=$(git rev-parse HEAD)
echo "Current commit: $COMMIT_HASH"
echo "Current branch: $(git branch --show-current)"
echo "Latest tag: $(git describe --tags --abbrev=0 2>/dev/null || echo '(none)')"

# ── Step 3: Update metadata commit field ──────────────────────
echo ""
echo "=== Step 3: Update metadata commit field ==="
# Use yq to update the commit field in the metadata file
yq -i ".Builds[0].commit = \"${COMMIT_HASH}\"" "$METADATA_FILE"
echo "Updated metadata Builds[0].commit to ${COMMIT_HASH}"

# Verify
ACTUAL_COMMIT=$(yq ".Builds[0].commit" "$METADATA_FILE")
if [ "$ACTUAL_COMMIT" != "$COMMIT_HASH" ]; then
    echo "ERROR: metadata commit field is ${ACTUAL_COMMIT}, expected ${COMMIT_HASH}"
    exit 1
fi
echo "Verified: metadata commit field = ${ACTUAL_COMMIT}"

# ── Step 4: Commit the metadata update ────────────────────────
echo ""
echo "=== Step 4: Commit metadata update ==="
git add "$METADATA_FILE"
if git diff --cached --quiet; then
    echo "Metadata already up to date — no commit needed."
else
    git commit -m "chore: update F-Droid metadata commit hash to ${COMMIT_HASH:0:7}"
    echo "Committed metadata update."
fi

echo ""
echo "=== Step 5: Push to origin ==="
echo "Push the metadata update to your GitHub repo?"
read -p "Press Enter to continue, or Ctrl+C to abort..." _
git push origin main

# ── Step 6: Clone fdroiddata fork ─────────────────────────────
echo ""
echo "=== Step 6: Clone fdroiddata fork ==="
WORK_DIR=$(mktemp -d)
echo "Cloning ${FORK_URL} to ${WORK_DIR}/fdroiddata ..."
git clone "$FORK_URL" "${WORK_DIR}/fdroiddata"
cd "${WORK_DIR}/fdroiddata"

# Add upstream remote to sync
git remote add upstream https://gitlab.com/fdroid/fdroiddata.git
git fetch upstream
git checkout -b "$BRANCH_NAME" upstream/master

# ── Step 7: Copy metadata file ────────────────────────────────
echo ""
echo "=== Step 7: Copy metadata file ==="
cp "${OLDPWD}/${METADATA_FILE}" "metadata/${APP_ID}.yml"
echo "Copied metadata to metadata/${APP_ID}.yml"

# Verify YAML syntax
yq eval '.' "metadata/${APP_ID}.yml" > /dev/null
echo "YAML syntax: OK"

# ── Step 8: Commit and push ───────────────────────────────────
echo ""
echo "=== Step 8: Commit and push to fork ==="
git add "metadata/${APP_ID}.yml"
git commit -m "New app: OnionRoute Lite

Lightweight Orbot alternative for Android — Tor VPN, SOCKS5/HTTP proxy,
obfs4 + snowflake bridges, kill switch, kindness mode (snowflake proxy).

Source: https://gitlab.com/theredhacker0345/Onionroute-Lite
Commit: ${COMMIT_HASH}
License: GPL-3.0-or-later
AntiFeatures: NonFreeNet"

echo ""
echo "Pushing to fork..."
git push fork "$BRANCH_NAME"

# ── Step 9: Print MR instructions ─────────────────────────────
echo ""
echo "══════════════════════════════════════════════════════════════"
echo "  F-Droid Merge Request — Ready to Open"
echo "══════════════════════════════════════════════════════════════"
echo ""
echo "  1. Open this URL in your browser:"
echo "     https://gitlab.com/fdroid/fdroiddata/-/merge_requests/new"
echo ""
echo "  2. Select your fork as the source:"
echo "     Source branch: ${GITLAB_USER}/fdroiddata : ${BRANCH_NAME}"
echo "     Target branch: fdroid/fdroiddata : master"
echo ""
echo "  3. Use this MR title:"
echo "     New app: OnionRoute Lite"
echo ""
echo "  4. Use this MR description:"
cat << 'MRDESC'
## New App: OnionRoute Lite

**Summary:** Lightweight Tor client for Android with VPN mode, SOCKS5/HTTP proxy, snowflake + obfs4 bridges, kill switch, and kindness mode (snowflake proxy).

**Source code:** https://gitlab.com/theredhacker0345/Onionroute-Lite
**License:** GPL-3.0-or-later
**AntiFeatures:** NonFreeNet (Tor directory authorities use the public internet)
**Categories:** Internet, Security

### Build
- **Native binaries** (Tor 0.4.9.9, obfs4proxy, snowflake, snowflake-proxy, tun2socks) built from source via NDK r26d + Go 1.21
- **Reproducible build** flags enabled (`-trimpath`, `-ffile-prefix-map`, `-Wl,--build-id=none`, `-buildid=`, `-buildvcs=false`)
- **Compiler hardening** on Tor build (`-fstack-protector-strong`, `-D_FORTIFY_SOURCE=2`, `-Wl,-z,relro,-z,now`)
- **All 4 ABIs** supported (armeabi-v7a, arm64-v8a, x86, x86_64)
- **Single universal APK** (fdroid flavor disables ABI splits)

### Verification
- Manually run GitLab CI jobs: https://gitlab.com/theredhacker0345/Onionroute-Lite/-/pipelines
- The `emulator_runtime` job (API 34, x86_64) verifies launch and native binaries when an `android-kvm` runner is available
- Lint report is attached to the manually started debug build job
- Static analysis (BUGS.md) — all findings addressed

### Notes
- `extractNativeLibs=true` is required because Tor/obfs4proxy/snowflake are PIE executables run via ProcessBuilder, not shared libraries loaded via System.loadLibrary()
- `foregroundServiceType=specialUse` on all 3 foreground services (Tor, VPN, Kindness) for Android 14 compliance
- NonFreeNet AntiFeature declared because Tor directory authorities and bridge infrastructure use the public internet
- No Google Play Services, no Firebase, no analytics, no tracking SDKs

### Fastlane metadata
Included in the source repo under `fastlane/metadata/android/en-US/` (title, short_description, full_description, changelogs, screenshots directory).
MRDESC
echo ""
echo "  5. Submit the MR. F-Droid maintainers will review — expect"
echo "     back-and-forth comments. Be patient."
echo ""
echo "══════════════════════════════════════════════════════════════"

# Cleanup
cd "$OLDPWD"
echo ""
echo "(Temporary clone at ${WORK_DIR}/fdroiddata — safe to delete after MR is opened)"
