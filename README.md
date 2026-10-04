<p align="center">
  <img src="https://gitlab.com/theredhacker0345/Onionroute-Lite/-/raw/main/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" alt="OnionRoute Lite launcher icon" width="120" height="120">
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
| **IPv6 Leak Protection** | IPv6 is captured into the tunnel (`::/0`) or fails closed — apps can never bypass Tor on dual-stack networks |
| **Self-Healing Reconnect** | Unexpected tor crashes auto-restart with backoff before showing an error |
| **SOCKS5 Proxy** | `127.0.0.1:9050` for apps that support proxy settings |
| **HTTP Proxy** | `127.0.0.1:8118` (Tor's HTTPTunnelPort) |
| **Snowflake Bridge** | Censorship circumvention via WebRTC proxies |
| **obfs4 Bridges** | Built-in default bridges for additional circumvention |
| **WebTunnel** | Mimics HTTPS traffic to defeat deep packet inspection |
| **CI-Verified Bridges** | GitHub Actions health-checks bridges every hour; the app fetches alive bridges at runtime |
| **Per-App Routing** | Select which apps use Tor; rules apply live to a running VPN |
| **.onion Access** | Automatic via Tor routing |
| **Bandwidth Monitor** | Real-time speed + cumulative totals (upload & download) |
| **Kill Switch** | Block all internet if Tor disconnects unexpectedly |
| **Auto-start on Boot** | Optional, configurable in app |
| **Battery-Optimization Prompt** | One-time system dialog so OEM battery managers don't kill tor after screen-off |
| **Low-RAM Design** | Bounded icon memory, capped tor buffers — targets 1.5GB devices |
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
| `libwebtunnel.so` | ~8 MB | webtunnel client (required for the WebTunnel toggle) |

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

### IPv6 Leak Prevention

The TUN interface routes **both** `0.0.0.0/0` and `::/0`. On dual-stack (IPv4+IPv6) networks — most LTE and modern WiFi — an IPv4-only VPN would let apps silently bypass Tor over IPv6 while everything *looked* routed. OnionRoute Lite captures IPv6 into the tunnel too: v6 traffic goes through tun2socks → SOCKS5 → tor, or fails closed. It never leaks around the VPN.

### Self-Healing Reconnect

If the tor process dies unexpectedly (OOM killer, OEM battery manager, network switch), the service automatically restarts it with linear backoff (3s, 6s) while the UI shows "Connecting…". Only after repeated failures does the app surface an error. 

**This does not cover Chinese-OEM background policies, and the earlier version
of this document claimed it did. It did not, and that claim has been removed
rather than softened.** `isIgnoringBatteryOptimizations()` reflects only the
AOSP Doze / App-Standby exemption. Each vendor keeps its own separate switches,
and the AOSP grant does nothing for any of them:

| ROM | Setting to enable manually |
| --- | --- |
| MIUI / HyperOS | Battery saver OFF, "Ultra saver" OFF, Autostart ON |
| EMUI / HarmonyOS | App launch: manual, Secondary launch / auto-manage OFF, Protected apps ON |
| ColorOS / OxygenOS | App battery management: Allow background activity + Auto-launch + Run in background |
| vivo / iQOO Funtouch | Background power consumption: allow + Autostart ON |
| One UI (Samsung) | Sleeping apps: never put this app to sleep; Background usage limits: Never sleeping apps |

Without the relevant switch the vendor kills the service regardless of its
foreground status, and a Tor client needs an unbroken session to be useful. On
those ROMs, set the row above or VPN/Tor will drop silently. See BUGS.md.

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
| **RAM** | 1.5 GB — App uses ~30-50 MB; tor daemon ~20-40 MB (queue buffers capped at 32 MB) |
| **Storage** | 8 GB — APK is ~38 MB; leave room for tor data |
| **CPU** | Spreadtrum SC9830E — First connect may take 30-60s |
| **Battery** | Foreground service drains faster; the app asks once to be excluded from battery optimization |

### Low-RAM Engineering

Memory is bounded by design so the app stays smooth on 1.5GB devices:

| Technique | Effect |
|-----------|--------|
| App icons rasterized to fixed 96px bitmaps | Per-app routing list costs ~15 MB instead of 30-80 MB of full-res adaptive icons |
| `MaxMemInQueue 32 MBytes` in generated torrc | Tor's channel/cell buffers can't balloon under multi-app VPN load |
| No `largeHeap` flag | Normal heap ceiling keeps the app friendly to co-running apps |
| Speed-history capped at 60 samples; R8 full-mode shrinking | Small state footprint, minimal DEX |

> **Phone/tablet only.** The release APK ships only ARM ABIs
> (armeabi-v7a + arm64-v8a). x86/x86_64 (Intel-based tablets and
> emulators) are not supported in release builds — see
> [Supported ABIs](#supported-abis) for details.

### Recommended Settings

1. **Settings → Battery → Battery Optimization** → Set OnionRoute Lite to *Don't optimize*
   (the app shows this system dialog once on your first manual connect — accepting is recommended)
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
|       |   |   +-- TorVpnService.kt    # VPN routing service (IPv4+IPv6)
|       |   |   +-- KindnessService.kt  # Snowflake proxy service
|       |   |   +-- AppSelectionManager.kt # Per-app routing rules
|       |   |   +-- BootReceiver.kt     # Auto-start on boot
|       |   |   +-- ConnectTileService.kt
|       |   |   +-- RefreshTileService.kt
|       |   +-- tor/
|       |   |   +-- TorProcessManager.kt # Manages tor binary + torrc generation
|       |   |   +-- TorControlConnection.kt # Control port client
|       |   |   +-- BandwidthTracker.kt # Bandwidth stats
|       |   |   +-- BridgeUpdater.kt    # Runtime CI-verified bridge fetcher
|       |   |   +-- BridgeConfig.kt     # Bridge configuration
|       |   |   +-- WebTunnelConfig.kt  # WebTunnel bridge config
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
|   +-- build-webtunnel.sh              # Build webtunnel-client
+-- metadata/
|   +-- org.onionroute.lite.yml         # F-Droid metadata
+-- fastlane/
|   +-- metadata/android/en-US/         # F-Droid store listing
+-- .gitlab-ci.yml                      # Manual-only GitLab CI pipeline
+-- docs/GITLAB_CI.md                   # Manual job and runner requirements
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
5. Open a GitLab merge request

All contributions must be compatible with GPL-3.0-or-later.

---

## Acknowledgements

### Battery and energy profiling — [EcoTrace](https://github.com/theredhacker0345/Eco-Trace)

The energy-efficiency work in this release was done using **EcoTrace**'s rule
set — the Android Energy Intelligence Platform by
[theredhacker0345](https://github.com/theredhacker0345).

EcoTrace's distinguishing claim is that it finds not just *what* drains a
battery's worth of energy but *why* the code architecture causes it, and its
23-pattern detector catalogue is what this app was audited against. Three of
its rules found real problems here:

| Rule | Finding in OnionRoute Lite | Fix |
|---|---|---|
| **N01 / N06** — network call inside a loop, polling where an event would do | The exit-IP probe issued an HTTPS-through-Tor request every 15s for the whole session — 5,760 multi-hop Tor transactions per connected day | Made event-driven off `SIGNAL NEWNYM`, with a 5-minute backstop: **5,760 → 288/day** |
| **N06 / N01** — `LaunchedEffect` poller outliving the screen | The Circuit Viewer refreshed `GETINFO circuit-status` every 5s inside a composition-scoped loop, which survives screen-off and the Home button | `repeatOnLifecycle(STARTED)`, and 5s → 15s (circuits rotate over minutes, not seconds) |
| **N01 (adjacent)** — needless work at 1Hz | `broadcastBandwidth` rebuilt the entire notification — including two `PendingIntent` allocations — once per second, because that is Tor's `BW` event rate | Throttled to 5s. **86,400 → ~17,280 notification rebuilds/day.** The in-app graph still updates at 1Hz |

Two more worth recording because the tool's own detector missed them and they
were found by reading its rule *semantics* rather than its regexes:

- **`Service` with no `stopSelf` on a terminal path.** EcoTrace treats one
  `stopSelf()` anywhere in a class as proof of termination, so all three of this
  app's services pass its A01 check — while `TorVpnService.stopVpn()` genuinely
  could reach a foreground state with a live notification and nothing left to
  clear it. Fixed by making teardown idempotent rather than early-returning.
- **Coroutine timers and Compose animations are invisible to the pattern
  matcher.** EcoTrace's N01 requires an OkHttp-style call site within 16 lines
  of the loop header, and A04 requires `ValueAnimator.INFINITE`. Neither fires
  on `while (true) { … delay(n) }` or `rememberInfiniteTransition`, which is how
  these anti-patterns are now idiomatically written — in Kotlin and Compose.
  A Kotlin/Compose-aware detector would be a genuinely useful upstream
  contribution.

Also of note: EcoTrace's `getentropy` finding does **not** apply here. It
correctly flagged a weak undefined `getentropy` in `libtor.so`, and traced it to
OpenSSL 1.1.1w's `rand_unix.c`, which guards the call at runtime and falls back
to `/dev/urandom`. `getentropy` is absent from Bionic below API 28, so Android
6.0 never calls it — the weak undefined symbol does not fail the link. Worth
knowing before anyone relinks tor against API ≥ 28 stubs.

Full rule catalogue and methodology:
<https://github.com/theredhacker0345/Eco-Trace>

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

<!-- BRIDGE-HEALTH-START -->
## Bridge Health

![Bridge Status](https://img.shields.io/badge/bridges-8/8%20alive-00E5C4?style=for-the-badge&labelColor=1A1A1A)
![Checks](https://img.shields.io/badge/checks-100-0D0D0D?style=for-the-badge&labelColor=1A1A1A)

A maintainer can manually start the GitLab bridge-health diagnostic job to test bundled bridges and generate review artifacts. The job never swaps bridges, changes this repository, creates a release, or commits to `main`. The app fetches the reviewed [`alive_bridges.json`](bridge-health/alive_bridges.json) from the canonical GitLab project; publishing a newer file requires a normal reviewed commit.

**Last check:** `2026-07-13T16:09:14+00:00`

| Bridge | Type | Status | Bootstrap | Failures | History |
|--------|------|--------|-----------|----------|---------|
| `80.96.58.89:9130` | obfs4 | ✅ alive | 0.13s | 0 | 🟩🟩🟩🟩🟩🟩 |
| `129.151.27.14:3721` | obfs4 | ✅ alive | 0.06s | 0 | 🟩🟩🟩🟩🟩🟩 |
| `82.64.193.83:12867` | obfs4 | ✅ alive | 0.09s | 0 | 🟩🟩🟨🟩🟩🟩 |
| `89.40.206.72:37171` | obfs4 | ✅ alive | 0.02s | 0 | 🟩🟩🟩🟩🟩🟩 |
| `159.203.18.175:5846` | obfs4 | ✅ alive | 0.02s | 0 | 🟩🟩🟩🟩🟩🟩 |
| `159.69.22.241:443` | webtunnel | ✅ alive | 0.1s | 0 | 🟩🟩🟩🟩🟩🟩 |
| `67.205.142.190:9889` | webtunnel | ✅ alive | 0.02s | 0 | 🟩🟩🟩🟩🟩🟩 |
| `192.0.2.3:80` | snowflake | ✅ alive | 1.29s | 0 | 🟩🟩🟩🟩🟩🟩 |

*History: 🟩 alive · 🟨 timeout · 🟥 dead · 🟧 error · ⬜ no data (last 6 checks)*

<details>
<summary>How it works</summary>

1. When a maintainer manually starts `bridge_health_diagnostics` in GitLab, a runner tests all bridges in `Constants.kt`:
   - **obfs4 / webtunnel**: TCP connectivity check to the bridge's IP:port
   - **snowflake**: HTTPS HEAD to the broker URL + UDP STUN Binding Request to each STUN server (the bridge line's IP is a virtual placeholder)
2. If any bridge has 2+ consecutive failures, the diagnostic artifact may include candidate replacements:
   - Fetch replacement bridges from Tor Project's BridgeDB (multiple requests per transport type)
   - Filter out documentation/test addresses (RFC 3849 IPv6, RFC 5737 IPv4)
   - TCP-test each candidate (5s timeout)
   - Propose same-transport or cross-transport alternatives for maintainer review
   - Keep Snowflake unchanged (the bridge line is a fixed virtual placeholder pointing at the Tor Project broker)
3. Save results, candidate data, a decision, and a proposed `alive_bridges.json` as job artifacts
4. A maintainer reviews the artifacts and, if appropriate, makes a normal reviewed GitLab commit

The app uses a 3-layer bridge sourcing strategy:
1. **Runtime JSON** — fetches reviewed `alive_bridges.json` from GitLab
2. **Local cache** — last successful fetch (works offline, 24h validity)
3. **Hardcoded** — `Constants.kt` fallback (changed only through reviewed source commits)

Pipeline details: [`docs/GITLAB_CI.md`](docs/GITLAB_CI.md)

</details>
<!-- BRIDGE-HEALTH-END -->
