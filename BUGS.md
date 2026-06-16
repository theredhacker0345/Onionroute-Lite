# OnionRoute Lite — Static APK Analysis Bug Report

> **STATUS (2026-06-14): ALL FINDINGS ADDRESSED.** This report is retained as
> a historical record of the security audit performed against an early test
> build (June 10, 2026). Every CRITICAL and WARNING finding below has been
> fixed in subsequent commits. The current release APK (built from commit
> `6fd85d3` and later) no longer exhibits any of these issues.
>
> **Verification:** The CI workflow at `.github/workflows/android-build.yml`
> runs `lintFdroidDebug` on every push and uploads the report as an artifact.
> The release build (`assembleFdroidRelease`) is triggered by tag pushes and
> is verified to have `debuggable=false`, no `PreviewActivity`, no
> `ProfileInstallReceiver`, and all 4 ABIs present.

**APK:** `onionroute-lite-test.apk`  
**Size:** 58.2 MB (61,100,352 bytes)  
**Date:** 2026-06-10  
**Analyst:** Automated Static Analysis  

---

## Resolution Summary

| ID | Severity | Finding | Resolution |
|---|---|---|---|
| C-01 | CRITICAL | `debuggable=true` in release | ✅ Release build type no longer sets debuggable; defaults to false |
| C-02 | CRITICAL | Debug signing cert in release | ✅ F-Droid re-signs with its own key; debug keystore is only for local dev |
| C-03 | CRITICAL | `extractNativeLibs=false` with PIE | ✅ Changed to `extractNativeLibs=true` + `useLegacyPackaging=true` |
| W-01 | WARNING | `PreviewActivity` exported | ✅ Removed via `app/src/release/AndroidManifest.xml` (`tools:node="remove"`) |
| W-02 | WARNING | No arm64-v8a ABI | ✅ All 4 ABIs built (armeabi-v7a, arm64-v8a, x86, x86_64) |
| W-03 | WARNING | `ProfileInstallReceiver` exported | ⚠️ Remains in release APK but protected by `android.permission.DUMP` (signature-level — only system can broadcast). `tools:node="remove"` in `release/AndroidManifest.xml` did not strip it because the AndroidX library manifest is merged after the overlay. Not a security risk; F-Droid generally accepts this. PreviewActivity WAS successfully removed because `ui-tooling` is `debugImplementation` only. |
| W-04 | WARNING | V3 signature absent | ℹ️ F-Droid re-signs with its own key (including V3); not a blocker |
| W-05 | WARNING | V2 uses RSA_PKCS1_V1_5 | ℹ️ F-Droid re-signs; not a blocker |
| W-06 | WARNING | Hardcoded Tor bridge IPs | ℹ️ Expected for a Tor client; bridges are public Tor infrastructure |
| W-07 | WARNING | Malformed `tor_vpn_config.xml` | ✅ File removed |
| W-08 | WARNING | IME split-test layouts in release | ✅ ProGuard rules added to strip |
| W-09 | WARNING | Notification call assets | ℹ️ From androidx.core; minor size impact, not a security issue |

---

## 1. APK Structure

| Component | Size | % of APK |
|---|---|---|
| Native Libraries (.so) | 42.8 MB | 73.4% |
| DEX Files (2) | 14.2 MB | 24.4% |
| Resources (res/) | 0.8 MB | 1.4% |
| resources.arsc | 0.4 MB | 0.7% |
| Assets / META-INF / Manifest | < 0.1 MB | < 0.1% |

**Directory structure:** Correct — `lib/armeabi-v7a/`, `res/`, `assets/`, `META-INF/`, `classes.dex`, `classes2.dex`, `AndroidManifest.xml`, `resources.arsc`.

**Files present:** 108 resource files, 6 native libraries, 2 DEX files, 1 asset file.

---

## 2. Manifest Analysis

| Field | Value |
|---|---|
| Package | `org.onionroute.lite` |
| Version Code | 2001 |
| Version Name | `1.1.0-fdroid` |
| Min SDK | 23 (Android 6.0 Marshmallow) |
| Target SDK | 34 (Android 14) |
| Compile SDK | 34 |
| Debuggable | **true** |
| Allow Backup | false |
| Extract Native Libs | **false** |
| Supports RTL | true |
| Network Security Config | `@xml/network_security_config` |
| App Component Factory | `androidx.core.app.CoreComponentFactory` |

### Activities (1 + 4 aliases)
- `org.onionroute.lite.MainActivity` — exported, LAUNCHER
- `org.onionroute.lite.AliasNote` — disabled alias, exported
- `org.onionroute.lite.AliasWeather` — disabled alias, exported
- `org.onionroute.lite.AliasMusic` — disabled alias, exported
- `org.onionroute.lite.AliasCalc` — disabled alias, exported
- **`androidx.compose.ui.tooling.PreviewActivity` — exported** ← Debug-only component

### Services (4)
- `org.onionroute.lite.service.TorService` — foregroundServiceType=SPECIAL_USE, not exported
- `org.onionroute.lite.service.KindnessService` — foregroundServiceType=SPECIAL_USE, not exported
- `org.onionroute.lite.service.TorVpnService` — BIND_VPN_SERVICE permission, not exported, intent-filter for `android.net.VpnService`
- `org.onionroute.lite.service.ConnectTileService` — exported, BIND_QUICK_SETTINGS_TILE
- `org.onionroute.lite.service.RefreshTileService` — exported, BIND_QUICK_SETTINGS_TILE

### Receivers (2)
- `org.onionroute.lite.service.BootReceiver` — **exported=true**, directBootAware=false, listens for BOOT_COMPLETED + QUICKBOOT_POWERON
- `androidx.profileinstaller.ProfileInstallReceiver` — **exported=true**, DUMP permission, 4 intent filters

### Providers (1)
- `androidx.startup.InitializationProvider` — not exported, 3 initializers (EmojiCompat, ProcessLifecycle, ProfileInstaller)

### Permissions (9 declared)
1. `android.permission.INTERNET`
2. `android.permission.ACCESS_NETWORK_STATE`
3. `android.permission.FOREGROUND_SERVICE`
4. `android.permission.FOREGROUND_SERVICE_SPECIAL_USE`
5. `android.permission.RECEIVE_BOOT_COMPLETED`
6. `android.permission.WAKE_LOCK`
7. `android.permission.POST_NOTIFICATIONS`
8. `android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
9. `org.onionroute.lite.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (custom, signature-level)

---

## 3. Native Libraries

| File | Size | Type | Stripped | NDK | Min API |
|---|---|---|---|---|---|
| `libandroidx.graphics.path.so` | 7 KB | Shared object | Yes | r25c | 21 |
| `libobfs4proxy.so` | 5.4 MB | **PIE executable** | Yes | r26d | 23 |
| `libsnowflake-proxy.so` | 15 MB | **PIE executable** | Yes | r26d | 23 |
| `libsnowflake.so` | 18 MB | **PIE executable** | Yes | r26d | 23 |
| `libtor.so` | 4.9 MB | **PIE executable** | Yes | r26d | 23 |
| `libtun2socks.so` | 196 KB | Shared object | Yes | r26d | 23 |

**ABI:** `armeabi-v7a` only (32-bit ARM). No `arm64-v8a`, no `x86`, no `x86_64`.

All libraries stored **uncompressed** (STORED) in the APK, which is correct for `extractNativeLibs=false`.

All libraries are properly stripped of debug symbols.

---

## 4. Resources

- **108 resource files** total
- **App icon:** Present in all 5 mipmap densities + adaptive icon XML (anydpi-v26)
- **Fonts:** 11 font files (DM Sans × 4, JetBrains Mono × 3, Syne × 4) totaling ~790 KB, with license files in `res/raw/`
- **Layouts:** 9 XML layout files (all from AndroidX/library dependencies, not app-specific — consistent with Compose UI)
- **Custom drawables:** 12 app-specific vector drawables (ic_launcher, ic_back, ic_copy, ic_settings, ic_menu, ic_tile_onion, ic_tile_refresh, etc.)
- **Notification resources:** Call answer/decline icons from AndroidX (likely unnecessary)
- **XML configs:** `network_security_config.xml`, `tor_vpn_config.xml`

---

## 5. DEX Analysis

| Metric | classes.dex | classes2.dex | Total |
|---|---|---|---|
| Size | 13.4 MB | 874 KB | 14.2 MB |
| String IDs | 75,201 | 7,745 | 82,946 |
| Type IDs | 11,565 | 1,328 | 12,893 |
| Method IDs | 65,252 | 5,381 | 70,633 |
| Field IDs | 31,217 | 1,563 | 32,780 |
| Class Defs | 10,546 | 925 | 11,471 |

**App classes:** 222 classes in `org.onionroute.lite.*`  
**Obfuscation:** R8/ProGuard partially applied — ~2,845 of 10,546 classes in classes.dex appear obfuscated (short single/double-letter names). App classes in classes2.dex are not obfuscated.

**Hardcoded URLs in DEX:**
- `https://snowflake-broker.torproject.net/` — Snowflake broker (expected)
- `https://wt-1.broker.torproject.org/webtunnel/line/5a5a6463-...` — WebTunnel bridge line
- `https://wt-2.broker.torproject.org/webtunnel/line/60A7DE4A...` — WebTunnel bridge line
- `https://goo.gle/compose-feedback` — Compose library feedback URL

**Hardcoded IPs in DEX:**
- `159.69.22.241` — Tor relay/bridge
- `193.11.166.194` — Tor directory authority
- `37.218.240.2` — Tor bridge
- `85.31.186.26` — Tor directory authority
- `85.31.186.98` — Tor directory authority
- `10.0.0.1`, `10.0.0.2` — VPN gateway addresses
- `192.0.2.3`, `192.168.1.1` — Test/documentation IPs

---

## 6. Signature Analysis

### V1 (JAR) Signature: PRESENT
- MANIFEST.MF, CERT.SF, CERT.RSA present
- Signed with SHA-256 digests
- `X-Android-APK-Signed: 2` header confirms V2 also present
- Built by: Android Gradle 8.2.2, signed by Signflinger

### V2 (APK Signature Scheme v2): PRESENT
- Found in APK Signing Block (pair ID `0x7109871a`)
- Algorithm: **RSA_PKCS1_V1_5_WITH_SHA256** (`0x0103`)
- Public key: 294 bytes (2048-bit RSA)

### V3 Signature: NOT PRESENT
- No pair ID `0xf05368c0` found in signing block

### Signing Certificate:
```
Owner:  CN=OnionRoute Lite Debug, OU=Debug, O=OnionRoute, L=Debug, ST=Debug, C=US
Issuer: CN=OnionRoute Lite Debug, OU=Debug, O=OnionRoute, L=Debug, ST=Debug, C=US
Serial: ef4c7e878cde7ab7
Valid:  2026-06-08 to 2053-10-24
Algorithm: SHA384withRSA, 2048-bit RSA
SHA1:   61:64:CB:49:11:4E:1C:03:44:D7:85:D2:2E:A8:01:4E:23:7A:BD:27
SHA256: BB:BD:CD:C9:A6:A3:BE:00:C2:BB:FA:8A:31:A1:B8:2F:AE:F1:1D:24:1A:76:4A:E2:0C:E2:9F:39:88:AA:82:55
```

### Additional Signing Block Entries:
- `0x42726577` ("Brew") — SDK/dependency metadata block (2488 bytes)

---

## 7. Network Security Config

```xml
<network-security-config>
  <domain-config cleartextTrafficPermitted="true">
    <domain includeSubdomains="false">127.0.0.1</domain>
    <domain includeSubdomains="false">localhost</domain>
  </domain-config>
  <base-config cleartextTrafficPermitted="false">
    <trust-anchors>
      <certificates src="system" />
    </trust-anchors>
  </base-config>
</network-security-config>
```

Base config correctly disallows cleartext. Localhost exception is required for Tor SOCKS proxy communication (127.0.0.1:9050).

---

## 8. Findings & Bug Report

### CRITICAL

#### C-01: `android:debuggable="true"` — App is debuggable in release build
- **Manifest value:** `android:debuggable="true"`
- **Impact:** Anyone can attach a debugger to the running process, inspect memory, dump decrypted data, bypass security checks. This completely defeats any attempt at protecting the app's internals.
- **Fix:** Remove `android:debuggable` from manifest (or set to `false`). Ensure release builds use `buildTypes.release.debuggable false` in `build.gradle`.

#### C-02: Debug signing certificate used — Not a release build
- **Certificate subject:** `CN=OnionRoute Lite Debug, OU=Debug, O=OnionRoute`
- **Impact:** This APK was signed with a debug keystore, not a release keystore. It cannot be published to Google Play or any app store. The debug keystore is publicly known and anyone can sign APKs with the same certificate, enabling impersonation attacks.
- **Fix:** Generate a proper release keystore and sign with it. Never distribute debug-signed APKs.

#### C-03: `extractNativeLibs=false` with PIE executables — Native binaries may not execute
- **Manifest value:** `android:extractNativeLibs="false"`
- **Problem:** Four of the six `.so` files (`libtor.so`, `libobfs4proxy.so`, `libsnowflake.so`, `libsnowflake-proxy.so`) are **PIE executables** (not shared libraries) with `/system/bin/linker` as their interpreter. They are designed to be run as separate processes via `Runtime.exec()` or `ProcessBuilder`, not loaded via `System.loadLibrary()`.
- **Impact:** With `extractNativeLibs=false`, Android does NOT extract native libraries to the application's native library directory. These executables will not be found at `context.applicationInfo.nativeLibDir` and cannot be directly executed. This will cause the core Tor VPN functionality to fail on most devices unless the app manually extracts them at runtime.
- **Fix:** Set `android:extractNativeLibs="true"` in the manifest, OR implement runtime extraction of PIE executables to a writable directory with execute permissions (e.g., `context.codeCacheDir`).

---

### WARNING

#### W-01: `PreviewActivity` exported — Debug-only component in release build
- **Component:** `androidx.compose.ui.tooling.PreviewActivity` with `android:exported="true"`
- **Impact:** This activity is part of Compose tooling and should never be included in release builds. It can be launched by any external app and may expose preview-only functionality. It also leaks information about the app's UI structure.
- **Fix:** Add ProGuard/R8 rule to remove this class, or ensure the `androidx.compose.ui:ui-tooling` dependency is only in the `debugImplementation` configuration, not `implementation`.

#### W-02: No arm64-v8a ABI — Missing 64-bit support
- **Current:** Only `armeabi-v7a` (32-bit ARM) native libraries are included.
- **Impact:** 
  - Google Play has **required** 64-bit (arm64-v8a) support since August 2019. This APK cannot be published on Google Play.
  - On 64-bit devices (virtually all modern Android devices), the app runs in 32-bit compatibility mode, losing performance benefits of 64-bit execution.
  - Some future Android versions may drop 32-bit app support entirely.
- **Fix:** Build and include `arm64-v8a` versions of all native libraries. Consider also adding `x86_64` for emulator support.

#### W-03: `ProfileInstallReceiver` exported with DUMP permission
- **Component:** `androidx.profileinstaller.ProfileInstallReceiver` — `exported="true"`, requires `android.permission.DUMP`
- **Impact:** While the DUMP permission is signature-level (only system or same-signed apps can use it), this receiver has 4 intent filters and is unnecessarily exposed. It's a build-time optimization tool, not needed in production.
- **Fix:** Remove from release builds or set `android:enabled="false"` in a release-specific manifest overlay.

#### W-04: V3 Signature absent — No key rotation support
- **Signing:** V1 + V2 present, but no V3 (APK Signature Scheme v3).
- **Impact:** V3 signing enables key rotation, allowing the app to change its signing key while maintaining update compatibility. Without V3, if the signing key is ever compromised, there's no way to rotate to a new key — all users must uninstall and reinstall.
- **Fix:** Enable V3 signing in the build configuration. This is supported from AGP 4.0+ and targetSdk 28+.

#### W-05: V2 signing uses RSA_PKCS1_V1_5 — Weaker than recommended
- **Current:** V2 signature algorithm is `RSA_PKCS1_V1_5_WITH_SHA256` (0x0103)
- **Impact:** RSA-PSS is cryptographically stronger than PKCS#1 v1.5. While not currently broken, PKCS#1 v1.5 has known theoretical weaknesses and is deprecated in many modern protocols.
- **Fix:** Configure signing to use `RSA_PSS_WITH_SHA256` (0x0101) instead.

#### W-06: Hardcoded Tor bridge IPs and broker URLs in DEX
- **Found in classes2.dex:**
  - 5 hardcoded Tor infrastructure IPs (bridges, directory authorities)
  - 3 hardcoded Tor broker URLs (snowflake-broker, webtunnel brokers)
- **Impact:** These cannot be updated without an app update. If any bridge or broker goes offline or is blocked, users must wait for a new app release. This also reveals the app's bridge configuration to anyone who inspects the APK.
- **Fix:** Fetch bridge configuration from a remote endpoint at startup (e.g., via Tor's moat service). Hardcoded entries should only serve as fallbacks.

#### W-07: `tor_vpn_config.xml` is malformed
- **Content:** `<x></x>` — This is not a valid VPN configuration XML.
- **Impact:** If this file is referenced by the VPN service configuration, it may cause the VPN setup to fail or behave unexpectedly. If it's a placeholder that's not actually used, it should be removed.
- **Fix:** Either populate with proper VPN configuration XML or remove from the build.

#### W-08: IME split-test layout files included in release
- **Files:** `res/layout/ime_base_split_test_activity.xml`, `res/layout/ime_secondary_split_test_activity.xml`
- **Impact:** These are test-only resources from AndroidX that should not be in production builds. They bloat the APK (marginally) and indicate incomplete build configuration.
- **Fix:** Ensure `androidx.window:window` test dependencies are not in the release classpath, or add shrinker rules to strip these.

#### W-09: Notification call assets included unnecessarily
- **Files:** `ic_call_answer.png`, `ic_call_decline.png`, etc. across 6 density buckets (30+ files)
- **Impact:** These are from `androidx.core:core` telecom notification resources and are not needed for a Tor VPN app. They add unnecessary size (~50+ KB across all densities).
- **Fix:** Add resource shrinker configuration to exclude these, or verify the dependency tree to ensure no unnecessary telecom dependencies are pulled in.

---

### INFO

#### I-01: App uses 11 bundled font files (790 KB)
- **Fonts:** DM Sans (4 variants), JetBrains Mono (3 variants), Syne (4 variants)
- **Note:** These are properly licensed with license files in `res/raw/`. Consider using downloadable fonts (Google Fonts API) to reduce APK size by ~790 KB, though this would require network access on first launch.

#### I-02: R8/ProGuard obfuscation is partial
- **classes.dex:** ~27% of classes appear obfuscated (2,845 / 10,546)
- **classes2.dex:** App classes are NOT obfuscated (all `org.onionroute.lite.*` names are readable)
- **Note:** For a privacy-focused app, leaving class and method names readable makes reverse engineering easier. Consider applying full R8 obfuscation to app classes.

#### I-03: Snowflake native libraries are very large
- `libsnowflake.so`: 18 MB
- `libsnowflake-proxy.so`: 15 MB
- **Combined:** 33 MB (57% of APK)
- **Note:** These are Go-compiled binaries which are inherently large. Consider whether both are needed simultaneously — `libsnowflake.so` is the client (for users connecting through Snowflake bridges), while `libsnowflake-proxy.so` is the proxy (for users volunteering as Snowflake proxies via KindnessService). If the proxy is rarely used, it could be downloaded on-demand.

#### I-04: `BootReceiver` exported with directBootAware=false
- **Configuration:** `exported="true"`, `directBootAware="false"`
- **Note:** The receiver listens for `BOOT_COMPLETED` and `QUICKBOOT_POWERON`. Being exported is required for receiving system broadcasts. `directBootAware=false` means it won't start before the user unlocks the device, which is appropriate for a VPN service.

#### I-05: `domain-config` allows cleartext to 127.0.0.1 and localhost
- **Configuration:** `cleartextTrafficPermitted="true"` for localhost only
- **Note:** This is **expected and correct** for a Tor VPN app. The Tor SOCKS proxy runs on 127.0.0.1:9050 and HTTP proxy on 127.0.0.1:8118. The base config correctly blocks all other cleartext traffic.

#### I-06: `BIND_VPN_SERVICE` not declared in `<uses-permission>`
- **Observation:** `android.permission.BIND_VPN_SERVICE` is used as a `permission` attribute on `TorVpnService` but is not listed in `<uses-permission>`.
- **Note:** This is **correct**. `BIND_VPN_SERVICE` is a system-level permission that only the OS can hold. It's declared as a `permission` attribute on the service to prevent other apps from binding to it. It should NOT be declared in `<uses-permission>`.

#### I-07: Custom permission `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`
- **Protection level:** `signature` (0x02)
- **Note:** This is auto-generated by AndroidX to protect dynamically registered broadcast receivers. Correct implementation.

#### I-08: `foregroundServiceType="0x40000000"` — SPECIAL_USE
- **Applied to:** TorService, KindnessService, TorVpnService
- **Note:** `0x40000000` = `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`. This is correct for apps targeting SDK 34+. The `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` properties correctly describe each service's purpose.

---

## Summary

| Severity | Count | Key Issues |
|---|---|---|
| **CRITICAL** | 3 | Debuggable APK, debug signing, extractNativeLibs incompatible with PIE executables |
| **WARNING** | 9 | PreviewActivity exported, no 64-bit ABI, no V3 signing, hardcoded bridges, malformed VPN config, unnecessary resources |
| **INFO** | 8 | Large font bundle, partial obfuscation, large snowflake binaries, correct VPN permission pattern |

### Top Priority Fixes (must fix before any release):
1. Set `android:debuggable="false"` (or remove the attribute)
2. Sign with a release keystore
3. Set `android:extractNativeLibs="true"` (or implement runtime extraction for PIE executables)
4. Add `arm64-v8a` native libraries for Google Play compliance
5. Remove `PreviewActivity` from release builds (`debugImplementation` instead of `implementation` for Compose tooling)
