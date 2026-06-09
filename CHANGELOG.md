# OnionRoute Lite — Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
