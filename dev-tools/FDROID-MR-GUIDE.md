# F-Droid Merge Request — Step-by-Step Guide

This guide walks you through opening the Merge Request to get OnionRoute Lite
published on F-Droid.

## Prerequisites

1. **GitLab account** on `gitlab.com` (create one at https://gitlab.com/users/sign_up)
2. **F-Droid GitLab access**: Visit https://gitlab.com/fdroid and make sure you can see the fdroiddata project
3. **SSH key or Personal Access Token** for GitLab (to push to your fork)
4. **yq** installed locally (`sudo snap install yq` on Ubuntu, or `brew install yq` on macOS)

## Step 1: Fork fdroiddata on GitLab

1. Go to https://gitlab.com/fdroid/fdroiddata
2. Click the **Fork** button (top right)
3. Choose your namespace (your GitLab username)
4. Wait for the fork to complete — you'll be redirected to `https://gitlab.com/<your-username>/fdroiddata`

## Step 2: Prepare the metadata file

The metadata file is at `metadata/org.onionroute.lite.yml` in this repo.
It's already filled out — just verify the `commit` field points to the right hash:

```bash
cd /path/to/Onionroute-Lite
cat metadata/org.onionroute.lite.yml | grep -A2 "Builds:"
```

You should see:
```yaml
Builds:
  - versionName: '1.0.0'
    versionCode: 1000
    commit: 9b533dd5e96b9fbc9efb92204ee8ceb81c8961ca
```

If the commit hash is different (because you made more commits), update it:
```bash
yq -i ".Builds[0].commit = \"$(git rev-parse HEAD)\"" metadata/org.onionroute.lite.yml
git add metadata/org.onionroute.lite.yml
git commit -m "chore: update F-Droid metadata commit hash"
git push origin main
```

## Step 3: Clone your fork and create a branch

```bash
# Clone your GitLab fork
git clone https://gitlab.com/<your-username>/fdroiddata.git
cd fdroiddata

# Add upstream remote to stay in sync
git remote add upstream https://gitlab.com/fdroid/fdroiddata.git
git fetch upstream

# Create a branch for the new app
git checkout -b org.onionroute.lite upstream/master
```

## Step 4: Copy the metadata file

```bash
cp /path/to/Onionroute-Lite/metadata/org.onionroute.lite.yml metadata/

# Verify YAML syntax
yq eval '.' metadata/org.onionroute.lite.yml > /dev/null && echo "YAML OK"
```

## Step 5: Commit and push to your fork

```bash
git add metadata/org.onionroute.lite.yml
git commit -m "New app: OnionRoute Lite

Lightweight Orbot alternative for Android — Tor VPN, SOCKS5/HTTP proxy,
obfs4 + snowflake bridges, kill switch, kindness mode (snowflake proxy).

Source: https://github.com/darkmaster0345/Onionroute-Lite
License: GPL-3.0-or-later
AntiFeatures: NonFreeNet"

git push origin org.onionroute.lite
```

## Step 6: Open the Merge Request on GitLab

1. Go to https://gitlab.com/fdroid/fdroiddata/-/merge_requests/new
2. Select your fork as the source:
   - **Source project**: `<your-username>/fdroiddata`
   - **Source branch**: `org.onionroute.lite`
3. Select fdroid as the target:
   - **Target project**: `fdroid/fdroiddata`
   - **Target branch**: `master`
4. Use this MR title: `New app: OnionRoute Lite`
5. Use this MR description:

```markdown
## New App: OnionRoute Lite

**Summary:** Lightweight Tor client for Android with VPN mode, SOCKS5/HTTP proxy, snowflake + obfs4 bridges, kill switch, and kindness mode (snowflake proxy).

**Source code:** https://github.com/darkmaster0345/Onionroute-Lite
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
- CI builds + tests on every push: https://github.com/darkmaster0345/Onionroute-Lite/actions
- Emulator test (API 34, x86_64) verifies app launches and native binaries load
- Lint report attached to each CI run
- Static analysis (BUGS.md) — all findings addressed

### Notes
- `extractNativeLibs=true` is required because Tor/obfs4proxy/snowflake are PIE executables run via ProcessBuilder, not shared libraries loaded via System.loadLibrary()
- `foregroundServiceType=specialUse` on all 3 foreground services (Tor, VPN, Kindness) for Android 14 compliance
- NonFreeNet AntiFeature declared because Tor directory authorities and bridge infrastructure use the public internet
- No Google Play Services, no Firebase, no analytics, no tracking SDKs

### Fastlane metadata
Included in the source repo under `fastlane/metadata/android/en-US/` (title, short_description, full_description, changelogs, screenshots directory).
```

6. Click **Create merge request**

## Step 7: Wait for F-Droid review

F-Droid maintainers will review your MR. Expect:
- **1-2 weeks** for initial response
- **Back-and-forth comments** about build reproducibility, AntiFeatures, metadata format
- **Build verification** — F-Droid will build the app from your source and compare the APK hash to ensure reproducibility

Common feedback you might receive:
- "Please add `scandelete` for X" — if there are prebuilt binaries you missed
- "Please clarify the NonFreeNet AntiFeature" — explain why it's needed
- "Build is not reproducible" — check that your build flags match F-Droid's exactly
- "Please add a screenshot" — add one to `fastlane/metadata/android/en-US/images/phoneScreenshots/`

Be patient and responsive. F-Droid is run by volunteers.

## Automated Script

If you prefer automation, run:
```bash
cd /path/to/Onionroute-Lite
./dev-tools/prepare-fdroid-mr.sh
```

This script does steps 2-5 automatically. You still need to do step 1 (fork) and step 6 (open MR) manually.

## Current Status

- **Source repo:** https://github.com/darkmaster0345/Onionroute-Lite
- **Release tag:** `v1.0.0-fdroid` on commit `9b6c353`
- **Metadata commit:** `9b533dd5e96b9fbc9efb92204ee8ceb81c8961ca`
- **CI:** All checks passing (build, emulator test, metadata validation)
- **Release APK:** Built by CI on tag push, downloadable from GitHub Releases
