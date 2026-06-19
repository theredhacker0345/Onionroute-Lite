<p align="center">
  <img src="ic_launcher.png" alt="OnionRoute Lite" width="120" height="120">
</p>

<h1 align="center">OnionRoute Lite</h1>

<p align="center">
  <strong>A minimal, lightweight Tor client for Android</strong><br>
  VPN mode &middot; Snowflake &middot; obfs4 &middot; Censorship circumvention
</p>

<p align="center">
  <img src="https://img.shields.io/badge/License-GPL--3.0--or--later-blue.svg" alt="License: GPL-3.0+">
  <img src="https://img.shields.io/badge/Platform-Android%206.0%2B-green.svg" alt="Platform: Android 6.0+">
  <img src="https://img.shields.io/badge/F--Droid-Compliant-orange.svg" alt="F-Droid Compliant">
  <img src="https://img.shields.io/badge/RAM-1.5GB%20target-lightgrey.svg" alt="1.5GB RAM target">
</p>

---

## Features

| Feature | Description |
|---------|-------------|
| **One-tap Tor** | Connect/disconnect with a single button |
| **VPN Mode** | Route ALL device traffic through Tor (no root required) |
| **SOCKS5 Proxy** | `127.0.0.1:9050` for apps that support proxy settings |
| **HTTP Proxy** | `127.0.0.1:8118` (Tor's HTTPTunnelPort) |
| **Snowflake Bridge** | Censorship circumvention via WebRTC proxies |
| **obfs4 Bridges** | Built-in default bridges for additional circumvention |
| **WebTunnel** | Mimics HTTPS traffic to defeat deep packet inspection |
| **.onion Access** | Automatic via Tor routing |
| **Bandwidth Monitor** | Real-time speed + cumulative totals (upload & download) |
| **Kill Switch** | Block all internet if Tor disconnects unexpectedly |
| **Auto-start on Boot** | Optional, configurable in app |
| **Camouflage Icons** | Disguise the app with alternate launcher icons |
| **Kindness Mode** | Run a Snowflake proxy to help censored users |

## What This Is NOT

- Not a browser (use Firefox with your Tor connection)
- Not a root app (uses Android's VpnService API)
- Not a hidden service host
- Not affiliated with the Tor Project or Orbot

---

## Screenshots & Architecture

### Traffic Flow

**VPN Mode (all apps):**
```
App → Android VPN (TUN) → hev-socks5-tunnel → Tor SOCKS5 (9050) → Tor Network → Internet
```

**Manual Proxy (per-app):**
```
App (configured) → Tor SOCKS5 (9050) / HTTP (8118) → Tor Network → Internet
```

### Architecture Diagram

```
+--------------------------------------------+
|            UI (MainActivity)                |
|  [Connect] [Snowflake] [VPN] [Bandwidth]   |
+---------------------+----------------------+
                      | Broadcasts
+---------------------v----------------------+
|          TorService (Foreground)            |
|  +---------------+  +------------------+   |
|  |ProcessManager |  |ControlConnection |   |
|  |  - tor        |  |  - BW events     |   |
|  |  - obfs4      |  |  - CIRC events   |   |
|  |  - snowflake  |  |  - STATUS events |   |
|  +---------------+  +--------+---------+   |
|  +---------------+           |             |
|  | KillSwitch    |  +--------v---------+   |
|  | Monitor       |  | BandwidthTracker |   |
|  +---------------+  +------------------+   |
+--------------------------------------------+
+--------------------------------------------+
|        TorVpnService (VpnService)           |
|  +--------------------------------------+  |
|  | hev-socks5-tunnel (native)           |  |
|  | TUN interface -> SOCKS5 :9050        |  |
|  | (Blocks UDP, handles kill switch)    |  |
|  +--------------------------------------+  |
+--------------------------------------------+
```

---

## Building from Source

### Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| Android SDK | API 34 | Compile target |
| Android NDK | r26d | Native binary cross-compilation |
| Go | 1.21+ | For obfs4proxy and snowflake |
| Gradle | 8.5 | Included via wrapper |
| JDK | 17 | Temurin distribution |
| Build host | Linux | Tested on Ubuntu 22.04 |

### Step 1: Build Native Binaries

```bash
# Set NDK path
export ANDROID_NDK_HOME=/path/to/android-ndk-r26d

# Build all native binaries for all ABIs
chmod +x scripts/*.sh
./scripts/build-all.sh all "$ANDROID_NDK_HOME"

# Or build for a specific ABI
./scripts/build-all.sh armeabi-v7a "$ANDROID_NDK_HOME"
```

This produces the following in `app/src/main/jniLibs/<abi>/`:

| Binary | Size (approx) | Source |
|--------|--------------|--------|
| `libtor.so` | ~6 MB | tor 0.4.8.13 |
| `libobfs4proxy.so` | ~6 MB | obfs4proxy 0.0.14 |
| `libsnowflake.so` | ~5 MB | snowflake 2.10.1 |
| `libsnowflake-proxy.so` | ~33 MB | snowflake proxy 2.10.1 |
| `libtun2socks.so` | ~100 KB | hev-socks5-tunnel 3.0.5 |

### Step 2: Build the APK

```bash
# Debug build
./gradlew assembleFdroidDebug

# Release build
./gradlew assembleFdroidRelease
```

### Step 3: Install

```bash
# Install on connected device
adb install app/build/outputs/apk/fdroid/debug/*.apk

# Or reinstall
adb install -r app/build/outputs/apk/fdroid/release/*.apk
```

---

## F-Droid Compliance

This app is designed for F-Droid submission from the ground up:

| Requirement | Status |
|-------------|--------|
| **100% FLOSS** | GPL-3.0-or-later, no proprietary dependencies |
| **No Google Play Services** | No Firebase, no analytics, no tracking |
| **Reproducible Builds** | Consistent build config, frozen timestamps |
| **Native Binaries Built from Source** | Build scripts in `scripts/` directory |
| **No Runtime Binary Downloads** | All binaries bundled at build time |
| **Minimal Dependencies** | Only AndroidX, Material, and Kotlin Coroutines |
| **Bundled Fonts** | No Google Fonts Provider dependency (OFL 1.1 licensed) |
| **No NonFreeNet AntiFeature** | Uses FOSS-friendly STUN servers only |

### Submitting to F-Droid

1. Fork [fdroiddata](https://gitlab.com/fdroid/fdroiddata)
2. Copy `metadata/org.onionroute.lite.yml` to `metadata/`
3. Submit a merge request
4. F-Droid maintainers will review and build

---

## Security

### Kill Switch

The kill switch works by keeping the VPN interface active but stopping the SOCKS5 forwarding. Since all traffic must go through the VPN (Android routes everything to TUN), dead forwarding = no traffic. This is more reliable than iptables-based approaches and doesn't require root.

### DNS Leak Prevention

All DNS queries are routed through Tor's DNSPort (`127.0.0.1:5400`) when VPN mode is active. This prevents your ISP from seeing which domains you resolve. Verify with [dnsleaktest.com](https://dnsleaktest.com).

### UDP Blocking

Tor only supports TCP. All UDP traffic is blocked at the VPN layer. This means:
- VoIP apps won't work through VPN mode
- Some streaming services may fail
- Online gaming won't work

This is a fundamental Tor limitation, not a bug.

---

## Device-Specific Notes

Built for **Samsung Galaxy Grand Prime Plus** (SM-G532F):

| Aspect | Detail |
|--------|--------|
| **RAM** | 1.5 GB — App uses ~30-50 MB; tor daemon ~20-40 MB |
| **Storage** | 8 GB — APK is ~38 MB; leave room for tor data |
| **CPU** | Spreadtrum SC9830E — First connect may take 30-60s |
| **Battery** | Foreground service drains faster; plug in when possible |

> **Phone/tablet only.** The release APK ships only ARM ABIs
> (armeabi-v7a + arm64-v8a). x86/x86_64 (Intel-based tablets and
> emulators) are not supported in release builds — see
> [Supported ABIs](#supported-abis) for details.

### Recommended Settings

1. **Settings → Battery → Battery Optimization** → Set OnionRoute Lite to *Don't optimize*
2. **Settings → Apps → OnionRoute Lite** → Enable *Allow background activity*

---

## Project Structure

```
onionroute-lite/
+-- app/
|   +-- build.gradle                    # App-level build config
|   +-- proguard-rules.pro              # ProGuard rules
|   +-- src/main/
|       +-- AndroidManifest.xml
|       +-- assets/tor/torrc.template   # Base tor configuration
|       +-- java/org/onionroute/lite/
|       |   +-- Constants.kt            # All app constants
|       |   +-- MainActivity.kt         # Main UI (Jetpack Compose)
|       |   +-- KillSwitch.kt           # Kill switch logic
|       |   +-- service/
|       |   |   +-- TorService.kt       # Foreground service (core)
|       |   |   +-- TorVpnService.kt    # VPN routing service
|       |   |   +-- KindnessService.kt  # Snowflake proxy service
|       |   |   +-- ConnectTileService.kt
|       |   |   +-- RefreshTileService.kt
|       |   |   +-- BootReceiver.kt     # Auto-start on boot
|       |   +-- tor/
|       |   |   +-- TorProcessManager.kt # Manages tor binary
|       |   |   +-- TorControlConnection.kt # Control port client
|       |   |   +-- BandwidthTracker.kt # Bandwidth stats
|       |   |   +-- BridgeConfig.kt     # Bridge configuration
|       |   +-- ui/
|       |       +-- screens/            # Compose screens
|       |       +-- theme/Theme.kt      # OnionRoute design system
|       +-- res/                         # UI resources
|           +-- font/                    # Bundled fonts (OFL 1.1)
|           +-- mipmap-*/                # Launcher icon (per-density)
|           +-- drawable-*/              # Adaptive icon foreground + camouflage icons
|           +-- values/                  # Strings, colors, styles
|           +-- xml/                     # Network security, VPN config
|           +-- jniLibs/                 # Native binaries (built from source)
+-- scripts/
|   +-- build-all.sh                    # Build all native binaries
|   +-- build-tor.sh                    # Build tor daemon
|   +-- build-obfs4.sh                  # Build obfs4proxy
|   +-- build-snowflake.sh              # Build snowflake client + proxy
|   +-- build-tun2socks.sh              # Build hev-socks5-tunnel
+-- metadata/
|   +-- org.onionroute.lite.yml         # F-Droid metadata
+-- fastlane/
|   +-- metadata/android/en-US/         # F-Droid store listing
+-- .github/workflows/
|   +-- android-build.yml               # CI/CD pipeline
+-- LICENSE                             # GPL-3.0-or-later
+-- THIRD-PARTY-LICENSES.md             # Font & dependency licenses
+-- PRIVACY_POLICY.md                   # Privacy policy
+-- CHANGELOG.md                        # Version changelog
+-- README.md                           # This file
```

---

## Supported ABIs

OnionRoute Lite ships **only ARM ABIs** in the release APK to keep the
file size small (~38 MB instead of ~77 MB). x86 and x86_64 are
**emulator-only** ABIs and would double the APK size with no benefit to
real Android users — ~99% of Android phones and tablets are ARM-based.

| ABI | In Release APK? | Target Devices |
|-----|-----------------|----------------|
| `armeabi-v7a` | ✅ Yes | Older 32-bit ARM phones (Samsung Grand Prime Plus, etc.) |
| `arm64-v8a`   | ✅ Yes | Modern 64-bit ARM phones and tablets |
| `x86`         | ❌ No | Android emulators, rare Intel tablets |
| `x86_64`      | ❌ No | Android emulators (x86_64 images) |

> **Need x86/x86_64 for emulator testing?** Build the debug variant
> locally: `./gradlew assembleFdroidDebug` — the debug CI job builds
> armeabi-v7a + x86_64 so the app can be tested on standard Android
> emulators. The release APK is for production phone/tablet installs only.

---

## Contributing

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/my-feature`)
3. Commit your changes (`git commit -am 'Add my feature'`)
4. Push to the branch (`git push origin feature/my-feature`)
5. Open a Pull Request

All contributions must be compatible with GPL-3.0-or-later.

---

## License

```
OnionRoute Lite — A minimal Tor client for Android
Copyright (C) 2024 darkmaster0345

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.
```

Full license: [LICENSE](LICENSE)

| Component | License |
|-----------|---------|
| OnionRoute Lite | GPL-3.0-or-later |
| Tor | BSD-3-Clause |
| obfs4proxy | BSD-3-Clause |
| Snowflake | BSD-3-Clause |
| hev-socks5-tunnel | GPL-3.0 |
| Bundled fonts | SIL Open Font License v1.1 |

<!-- SNOWFLAKE-VOLUNTEER-START -->
## Snowflake Volunteer

![Status](https://img.shields.io/badge/status-ARMED%20%26%20MONITORING-00E5C4?style=for-the-badge)
![Sessions](https://img.shields.io/badge/sessions-3-0D0D0D?style=for-the-badge&labelColor=1A1A1A)
![People Helped](https://img.shields.io/badge/people%20helped-0-00E5C4?style=for-the-badge&labelColor=1A1A1A)
![Time Donated](https://img.shields.io/badge/time%20donated-5h%2055m-0D0D0D?style=for-the-badge&labelColor=1A1A1A)
![Bandwidth](https://img.shields.io/badge/bandwidth-0%20B-0D0D0D?style=for-the-badge&labelColor=1A1A1A)

This repository runs a **Tor Snowflake proxy** on GitHub Actions runners every 6 hours, donating ~5h50m of compute per session to help people in censored countries bypass internet restrictions. Zero external services, zero cost — pure creative use of CI compute for human rights.

![Dashboard](snowflake-volunteer/dashboard.svg?v=20260619T075922+0000)

> *Last updated: 2026-06-19 09:10 UTC · Auto-regenerates after each session*

<details>
<summary>How it works</summary>

1. Every 6 hours, a GitHub Actions runner (ubuntu-latest) spins up
2. Builds the snowflake-proxy binary from source (Tor Project, Go)
3. Runs the proxy for ~5h50m (just under the 6h job timeout)
4. Real censored users connect via WebRTC through our proxy
5. Stats are parsed from the proxy log and committed back to `main`
6. This README section + the SVG dashboard auto-regenerate

Workflow: [`.github/workflows/snowflake-volunteer.yml`](.github/workflows/snowflake-volunteer.yml)

</details>

<details>
<summary>Start a session manually</summary>

1. Go to **Actions** -> **Snowflake Volunteer**
2. Click **Run workflow**
3. Set duration (default 350 min, max 350)
4. Watch the logs — real users may connect within minutes

> Public repos have unlimited Actions minutes, so this is genuinely free compute for a good cause.

</details>
<!-- SNOWFLAKE-VOLUNTEER-END -->

<!-- BRIDGE-HEALTH-START -->
## Bridge Health

![Bridge Status](https://img.shields.io/badge/bridges-0/6%20alive-FF4444?style=for-the-badge&labelColor=1A1A1A)
![Checks](https://img.shields.io/badge/checks-3-0D0D0D?style=for-the-badge&labelColor=1A1A1A)
![AI](https://img.shields.io/badge/AI-Qwen%202.5%203B-00E5C4?style=for-the-badge&labelColor=1A1A1A)

An AI agent (Qwen 2.5 3B, running locally on the GitHub Actions runner via llama.cpp — no external API) tests every bundled bridge every 30 minutes. If a bridge has been dead for 3+ consecutive checks, the AI opens a PR to swap it for a working replacement.

**Last check:** `2026-06-19T08:19:10+00:00`

| Bridge | Type | Status | Bootstrap | Failures |
|--------|------|--------|-----------|----------|
| `obfs4 193.11.166.194:10901 C7B...` | obfs4 | ⏳ timeout | — | 2 |
| `obfs4 85.31.186.98:443 011F259...` | obfs4 | ⏳ timeout | — | 2 |
| `obfs4 85.31.186.26:443 91D625E...` | obfs4 | ⏳ timeout | — | 2 |
| `webtunnel 159.69.22.241:443 40...` | webtunnel | ⏳ timeout | — | 2 |
| `webtunnel 37.218.240.2:443 668...` | webtunnel | ⏳ timeout | — | 2 |
| `snowflake 192.0.2.3:80 2B280B2...` | snowflake | ⏳ timeout | — | 3 |

<details>
<summary>How it works</summary>

1. Every 30 min, a GitHub Actions runner installs `tor` + `llama.cpp`
2. Tests each bridge in `Constants.kt` — real Tor bootstrap, 120s timeout
3. If any bridge has 3+ consecutive failures, the AI agent decides:
   - Fetch replacement bridges from Tor Project's BridgeDB
   - Test each replacement live (must bootstrap in < 30s)
   - Ask the local LLM: "should we swap? which replacement?"
4. If the AI says swap, the workflow edits `Constants.kt` and opens a PR
5. The PR description is written by the AI with evidence + reasoning
6. You review and merge — the AI never auto-merges

**AI details:** Qwen 2.5 3B Instruct (Q4_K_M, 2GB, Apache 2.0) via llama.cpp — ~25s per decision on 4-core CPU. Cost: $0. Model cached in `actions/cache`.

Workflow: [`.github/workflows/bridge-health.yml`](.github/workflows/bridge-health.yml)

</details>
<!-- BRIDGE-HEALTH-END -->
