# Tor Lite — Work Log

---
Task ID: 1
Agent: Main
Task: F-Droid 9-Phase Audit — Fix all issues across all phases

Work Log:
- Read all project files (33+ source files, build scripts, metadata, configs)
- Phase 1: Updated LICENSE with correct copyright (darkmaster0345), added font license references
- Phase 1: Created THIRD-PARTY-LICENSES.md with OFL 1.1, BSD-3-Clause, GPL-3.0 texts
- Phase 2: Fixed AndroidManifest.xml — added foregroundServiceType="specialUse" with PROPERTY_SPECIAL_USE_FGS_SUBTYPE for API 34+ compatibility
- Phase 2: Added QUICKBOOT_POWERON intent filter to BootReceiver
- Phase 2: Added android:fullBackupContent="false"
- Phase 3: Removed pinned buildToolsVersion "34.0.0" from app/build.gradle
- Phase 3: Added dependenciesInfo block for reproducible builds
- Phase 4: Rewrote all 4 build scripts with security hardening flags enabled (-fstack-protector-strong, -D_FORTIFY_SOURCE=2, -Wl,-z,relro,-z,now)
- Phase 4: Removed --disable-gcc-hardening --disable-linker-hardening from Tor build script
- Phase 4: Added proper dependency building (zlib, OpenSSL, libevent) in Tor build script
- Phase 4: Build scripts now use persistent build-native/ directory instead of temp dirs
- Phase 4: Added Go module verification (go mod verify) for obfs4 and snowflake
- Phase 4: Added -trimpath flag to Go builds for reproducibility
- Phase 5: Fixed metadata.yml — replaced YOUR_USERNAME with darkmaster0345
- Phase 5: Fixed categories (removed "VPN & Proxy" non-standard category)
- Phase 5: Added AuthorWebSite, Donate links
- Phase 5: Added NonFreeNet AntiFeature declaration
- Phase 5: Added font license documentation to MaintainerNotes
- Phase 6: Declared NonFreeNet AntiFeature properly in metadata.yml
- Phase 7: Verified debug.keystore exists for signing
- Phase 7: Updated gradle.properties with reproducibility settings
- Phase 8: Fixed short_description.txt (under 80 char F-Droid limit)
- Phase 8: Updated full_description.txt with proper HTML formatting
- Phase 8: Created changelogs/1.txt for version 1
- Phase 8: Created images/phoneScreenshots/ directory structure
- Phase 9: Removed prebuilt APK (app-debug.apk) from repo root
- Phase 9: Removed build_manual/ directory with compiled classes
- Phase 9: Removed .gradle/ and app/build/ caches
- Phase 9: Removed empty directories (vpn/, components/, mipmap-*)
- Phase 9: Removed unused XML resources (activity_main.xml, dimens.xml, bg_card.xml, etc.)
- Phase 10: Fixed TorService.kt — added foregroundServiceType handling for API 34+
- Phase 10: Fixed TorVpnService.kt — added foregroundServiceType, addDisallowedApplication, socket protect()
- Phase 10: Updated README.md with correct GitHub links (darkmaster0345)
- Phase 10: Updated PRIVACY_POLICY.md with correct GitHub links
- Phase 10: Updated CHANGELOG.md with correct date and details
- Phase 10: Created CONTRIBUTING.md

Stage Summary:
- All 9 F-Droid audit phases addressed
- Critical fixes: foregroundServiceType for API 34+, security hardening flags in build scripts, NonFreeNet AntiFeature declared
- GitHub links updated to darkmaster0345/tor-lite
- Cleaned repo: removed prebuilt APK, build artifacts, empty dirs, unused XML
- Fastlane metadata completed with changelog

---
Task ID: 1
Agent: main
Task: Fix all F-Droid compliance issues and push to GitHub

Work Log:
- Removed legacy org.torlite/ (17 files) and org.phantomvpn/ (11 files) packages from source tree
- Deleted legacy metadata/org.torlite.yml
- Added Google STUN server comment cleanup in Constants.kt (replaced literal reference with generic mention)
- Updated F-Droid metadata (org.onionroute.lite.yml): all 4 ABIs, 20 scanignore entries, reproducibility notes
- Added Go reproducibility flags to build-snowflake.sh and build-obfs4.sh: -buildid=, -buildvcs=false
- Added C reproducibility flags to build-tor.sh: -ffile-prefix-map, -Wl,--build-id=none
- Fixed build-tun2socks.sh license comment: Apache-2.0 → GPL-3.0
- Aligned NDK version to r26d across all build scripts (previously r25c/r26d mismatch)
- Updated README: correct Tor 0.4.9.9, hev-socks5-tunnel 2.15.0, added all new features
- Updated fastlane metadata: v1.1.0 features, short/full description, changelog/2.txt
- Updated .gitignore header to OnionRoute Lite
- Verified all fixes: zero Google STUN references, zero legacy package references, zero r25c references
- Committed and pushed to GitHub (5301f9c)

Stage Summary:
- All 10 F-Droid compliance issues fixed and verified
- Code pushed to https://github.com/darkmaster0345/Onionroute-Lite
- Remaining for F-Droid MR: replace REPLACE_WITH_FULL_COMMIT_HASH with actual SHA at MR time

---
Task ID: 2
Agent: main
Task: Fix CI build failures and APK bugs, iterate until APK builds

Work Log:
- Fixed build-tor.sh: moved BUILD_DIR definition before CFLAGS (unbound variable error)
- Removed corrupt PNG files (JPEG data with .png extension) causing AAPT failure
- Removed unused resource files (bg_card.xml, bg_connect_button.xml, activity_main.xml)
- Fixed C-01: Set debuggable false in release build type
- Fixed C-03: Set useLegacyPackaging=true in packagingOptions for PIE executables
- Fixed W-01: Added release/AndroidManifest.xml to remove PreviewActivity
- Fixed W-03: Added release/AndroidManifest.xml to remove ProfileInstallReceiver
- Fixed W-07: Removed unused/malformed tor_vpn_config.xml
- Fixed W-08: Added ProGuard rules for PreviewActivity and ProfileInstallReceiver
- Fixed Gradle error: jniLibs.useLegacyPackaging only valid in packagingOptions, not buildTypes
- Updated .gitignore for testing artifacts
- Added BUGS.md with full static analysis report
- CI build passes: APK generated for all 5 ABIs (armeabi-v7a, arm64-v8a, x86, x86_64, universal)

Stage Summary:
- CI now passes with all bug fixes applied
- APK is no longer debuggable (C-01 fixed)
- Native binaries will be extracted correctly (C-03 fixed)
- Debug components removed from release (W-01, W-03 fixed)
- Commits: bbbfd58, 36693c4, 67665dc, 9772ae6 (all pushed to main)

---
Task ID: codespace-testing
Agent: Main
Task: Connect to GitHub Codespace, build APK, run emulator, test, fix bugs, iterate

Work Log:
- Created GitHub Codespace via API (2 cores, 8GB RAM, 32GB storage, SouthEastAsia)
- Connected to codespace via paramiko through `gh codespace ssh --stdio` proxy
- Installed Android SDK (platform-tools, platform-34, build-tools-34, emulator, system-image x86_64)
- Fixed missing shared libraries for emulator (libpulse, libXi, libxkbfile, etc.)
- Fixed KVM permissions (sudo chmod 666 /dev/kvm, added codespace to kvm group)
- Started Android emulator with KVM acceleration — boots in ~55 seconds
- Built APK with Java 17 (Java 25 incompatible with Gradle 8.5)
- First test: All 5 native binaries missing (libtor.so, libobfs4proxy.so, libsnowflake.so, libsnowflake-proxy.so, libtun2socks.so)
- Downloaded CI artifact APK via GitHub API (has libtor.so x86_64 built by CI)
- SFTP transferred CI APK to codespace, installed on emulator
- Second test: libtor.so found, Tor started but TorControlConnection race condition — SocketTimeoutException
- Root cause: listenForEvents() thread and getInfo() both reading from same InputStream simultaneously
- Fix 1: Rewrote TorControlConnection — single listener thread owns all reads; GETINFO responses routed via CountDownLatch/ConcurrentHashMap
- Fix 2: Removed redundant BandwidthTracker.pollBandwidth() GETINFO polling (was causing race condition)
- Fix 3: Downgraded missing optional binaries from CRITICAL to WARNING in TorProcessManager
- Fixed heredoc escaping issues (\\$, \n, \r character codes)
- Fixed Kotlin string interpolation ($port, $response, $cookieHex, etc. were escaped as \$)
- Final test: Tor CONNECTED and AUTHENTICATED successfully, BandwidthTracker working, Status broadcast: CONNECTED

Stage Summary:
- Codespace testing pipeline working end-to-end
- Critical bug fixed: TorControlConnection race condition causing SocketTimeoutException
- Critical bug fixed: BandwidthTracker conflicting read from shared InputStream
- UX improvement: Optional missing binaries now show WARNING instead of CRITICAL
- All fixes pushed to GitHub (commits: 278a547, a3fdca0, eeef010)
- Tor daemon connects and authenticates successfully on x86_64 emulator with KVM
- Remaining: 4 optional binaries (obfs4, snowflake, snowflake-proxy, tun2socks) not built by CI — only libtor.so is built
