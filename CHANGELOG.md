# OnionRoute Lite — Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.1.0] - 2026-06-14

### Added
- **All 4 ABIs supported**: armeabi-v7a, arm64-v8a, x86, x86_64 (was armeabi-v7a only)
- **Kindness Mode**: run a Snowflake proxy to help censored users reach Tor
- **Kindness stats dashboard**: real-time count of people helped, bandwidth donated
- **Quick Settings tiles**: Connect toggle + New Circuit tile in the QS panel
- **Camouflage launcher icons**: 4 alternate icons (Note, Weather, Music, Calc) to disguise the app
- **WebTunnel bridge support**: HTTPS-mimicking transport for censorship circumvention
- **Custom bridge import**: paste bridge lines or scan a QR code
- **Always-on VPN support**: Android setting to keep VPN up persistently
- **Per-app routing**: choose which apps go through Tor (VPN mode)
- **Bandwidth speed graph**: visual real-time throughput display

### Changed
- **Tor 0.4.9.9** (was 0.4.7.x) with latest security patches
- **NDK r26d** aligned across all native build scripts
- **`foregroundServiceType="specialUse"`** on all 3 foreground services for Android 14 (API 34) compliance
- **`useLegacyPackaging=true`** for jniLibs (required because Tor/obfs4proxy/snowflake are PIE executables run via ProcessBuilder, not shared libraries)
- **`extractNativeLibs=true`** in manifest (matches useLegacyPackaging)
- **`dependenciesInfo.includeInApk=false`** to exclude DependencyInfoBlock (F-Droid requirement)
- **`allowBackup=false`** + `fullBackupContent=false` (privacy: no Tor state backed up to Google)
- **Network security config**: cleartext traffic only to 127.0.0.1/localhost, blocked everywhere else
- **Reproducible build flags** in all native build scripts: `-trimpath`, `-ffile-prefix-map`, `-Wl,--build-id=none`, `-buildid=`, `-buildvcs=false`
- **Compiler hardening** in Tor build: `-fstack-protector-strong`, `-D_FORTIFY_SOURCE=2`, `-Wl,-z,relro,-z,now`
- **Go module verification** (`go mod verify`) for obfs4 + snowflake builds

### Fixed
- **TorControlConnection race condition**: single listener thread now owns all reads from the control socket; `GETINFO` responses routed via `CountDownLatch` + `ConcurrentHashMap` (was: two threads reading from same InputStream simultaneously)
- **BandwidthTracker conflicting read**: removed redundant `pollBandwidth()` `GETINFO` polling that conflicted with the event listener
- **Kindness + WebTunnel toggle state** now restored on app restart (was: missing pref reads in `MainActivity`)
- **`peopleHelpedNow` AtomicLong going negative**: fixed with `getAndUpdate { (it-1).coerceAtLeast(0) }`
- **`peopleHelpedNow` not reset on proxy stop**: now reset to `0` in `stopProxyInternal()`
- **`consecutiveFailures` race condition**: changed to `AtomicInteger`
- **Version string hardcoded** as 1.0.0 in `SettingsScreen`: now reads actual version from `PackageInfo`
- **`BootReceiver` val reassignment** compilation error
- **PreviewActivity** removed from release builds via `release/AndroidManifest.xml` overlay (`tools:node="remove"`)
- **ProfileInstallReceiver** removed from release builds via same overlay
- **Malformed `tor_vpn_config.xml`** (`<x></x>`) removed
- **`debuggable=true`** in release: release build type no longer sets debuggable
- **Duplicate package dirs** (`org.torlite/`, `org.phantomvpn/`) removed
- **Duplicate PNG drawables** that conflicted with XML vector drawables removed

### F-Droid
- **GPL-3.0-or-later license** confirmed
- **THIRD-PARTY-LICENSES.md** created (OFL 1.1 for fonts, BSD-3-Clause for libevent, GPL-3.0 for Tor)
- **Fastlane metadata**: short description, full description, changelogs, screenshots directory
- **`metadata/org.onionroute.lite.yml`** with all 4 ABIs, 20 scanignore entries, reproducibility notes
- **NonFreeNet AntiFeature** declared (Tor directory authorities use the public internet)
- **Repo cleaned** of all prebuilt APKs, build artifacts, and agent tooling files

## [1.0.0] - 2026-06-08

### Added
- One-tap Tor connect/disconnect
- VPN mode: route all device traffic through Tor (VpnService API)
- SOCKS5 proxy on 127.0.0.1:9050
- HTTP CONNECT proxy on 127.0.0.1:8118
- Snowflake bridge for censorship circumvention
- obfs4 bridge support with default bridges
- .onion site access (automatic via Tor routing)
- Real-time bandwidth monitoring (speed + cumulative totals)
- Kill switch: block internet if Tor disconnects unexpectedly
- Auto-start on boot (optional)
- Foreground service with persistent notification
- Disconnect button in notification
- OnionRoute design system: OLED-black UI with Jade accent
- Jetpack Compose UI with Material 3
- Bundled fonts (Syne, JetBrains Mono, DM Sans) under OFL 1.1
- Battery optimization: minimal RAM and CPU usage
- Optimized for Samsung Galaxy Grand Prime Plus (Android 6.0.1, armeabi-v7a)

### Security
- Cookie-based Tor control port authentication
- DNS leak prevention via Tor DNSPort
- Kill switch enforced through VPN TUN interface
- Network security config: cleartext only to localhost
- Wrapper scripts with owner-only permissions
- No proprietary dependencies or tracking SDKs
- Compiler hardening flags enabled in native binary build scripts
- PIE enabled for ASLR support

### F-Droid
- GPL-3.0-or-later license
- 100% FLOSS with no proprietary dependencies
- Reproducible build configuration
- Native binaries built from source during build process
- Fastlane metadata for F-Droid listing
- Third-party license documentation (fonts, Tor components)
