# OnionRoute Lite — Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0] - 2026-06-17

First public release. Initial F-Droid submission.

### Added
- One-tap Tor connect/disconnect
- VPN mode: route all device traffic through Tor (VpnService API)
- Per-app routing: choose which apps go through Tor
- SOCKS5 proxy on 127.0.0.1:9050
- HTTP CONNECT proxy on 127.0.0.1:8118
- Snowflake bridge for WebRTC-based censorship circumvention
- obfs4 bridge support with built-in default bridges
- WebTunnel bridge support (HTTPS-mimicking transport)
- Custom bridge import via QR code scan or paste
- .onion site access (automatic via Tor routing)
- Real-time bandwidth monitoring with speed graph
- Kill switch: block internet if Tor disconnects unexpectedly
- Always-on VPN support
- Quick Settings tiles for connection toggle and new circuit
- Kindness Mode: run Snowflake proxy to help censored users
- Kindness stats dashboard: real-time count of people helped, bandwidth donated
- Camouflage: 4 alternate launcher icons (Note, Weather, Music, Calc) to disguise the app
- Auto-start on boot (optional)
- Foreground service with persistent notification
- Disconnect button in notification
- OnionRoute design system: OLED-black UI with Jade accent
- Jetpack Compose UI with Material 3
- Bundled fonts (Syne, JetBrains Mono, DM Sans) under OFL 1.1
- Battery optimization: minimal RAM and CPU usage
- Optimized for low-end Android devices (Samsung Galaxy Grand Prime Plus, Android 6.0.1, armeabi-v7a)
- All 4 ABIs supported: armeabi-v7a, arm64-v8a, x86, x86_64

### Security
- Tor 0.4.9.9 with OpenSSL 1.1.1w
- Cookie-based Tor control port authentication
- DNS leak prevention via Tor DNSPort
- Kill switch enforced through VPN TUN interface
- Network security config: cleartext traffic only to 127.0.0.1/localhost, blocked everywhere else
- Wrapper scripts with owner-only permissions
- No proprietary dependencies, no Google Play Services, no Firebase, no analytics, no tracking SDKs
- Compiler hardening flags in native binary build scripts: `-fstack-protector-strong`, `-D_FORTIFY_SOURCE=2`, `-Wl,-z,relro,-z,now`
- PIE enabled for ASLR support
- NDK r26d aligned across all native build scripts
- `foregroundServiceType="specialUse"` on all 3 foreground services for Android 14 (API 34) compliance
- `useLegacyPackaging=true` for jniLibs (required because Tor/obfs4proxy/snowflake are PIE executables run via ProcessBuilder, not shared libraries)
- `extractNativeLibs=true` in manifest (matches useLegacyPackaging)
- `dependenciesInfo.includeInApk=false` to exclude DependencyInfoBlock (F-Droid requirement)
- `allowBackup=false` + `fullBackupContent=false` (privacy: no Tor state backed up to Google)
- Reproducible build flags in all native build scripts: `-trimpath`, `-ffile-prefix-map`, `-Wl,--build-id=none`, `-buildid=`, `-buildvcs=false`
- Go module verification (`go mod verify`) for obfs4 + snowflake builds

### F-Droid
- GPL-3.0-or-later license
- 100% FLOSS with no proprietary dependencies
- Reproducible build configuration
- Native binaries built from source during build process (Tor, obfs4proxy, snowflake, snowflake-proxy, tun2socks)
- Fastlane metadata for F-Droid listing (title, short/full description, changelogs, screenshots directory)
- Third-party license documentation (OFL 1.1 for fonts, BSD-3-Clause for libevent, GPL-3.0 for Tor)
- `metadata/org.onionroute.lite.yml` with all 4 ABIs, 20 scanignore entries, reproducibility notes
- NonFreeNet AntiFeature declared (Tor directory authorities use the public internet)
- FOSS-only STUN servers (stun.antisip.com, stun.bluesip.net, stun.sipnet.cz) — no Google STUN by default
