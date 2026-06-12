# OnionRoute Lite

A minimal, lightweight Tor client for Android with VPN mode, Snowflake, and censorship circumvention.

**Designed for Samsung Galaxy Grand Prime Plus** (Android 6.0.1, 1.5GB RAM, 8GB storage, armeabi-v7a)

---

## Features

- **One-tap Tor** — Connect/disconnect with a single button
- **VPN Mode** — Route ALL device traffic through Tor (no root required)
- **SOCKS5 Proxy** — `127.0.0.1:9050` for apps that support proxy settings
- **HTTP Proxy** — `127.0.0.1:8118` (Tor's HTTPTunnelPort)
- **Snowflake Bridge** — Censorship circumvention via WebRTC proxies
- **obfs4 Bridges** — Built-in default bridges for additional circumvention
- **.onion Access** — Automatic via Tor routing
- **Bandwidth Monitor** — Real-time speed + cumulative totals (upload & download)
- **Kill Switch** — Block all internet if Tor disconnects unexpectedly
- **Auto-start on Boot** — Optional, configurable in app

## What This Is NOT

- Not a browser (use Firefox with your Tor connection)
- Not a root app (uses Android's VpnService API)
- Not a hidden service host
- Not affiliated with the Tor Project or Orbot

---

## Architecture

```
┌──────────────────────────────────────────┐
│            UI (MainActivity)              │
│  [Connect] [Snowflake] [VPN] [Bandwidth] │
└────────────────┬─────────────────────────┘
                 │ Broadcasts
┌────────────────▼─────────────────────────┐
│           TorService (Foreground)         │
│  ┌──────────────┐  ┌──────────────────┐  │
│  │ ProcessManager│  │ ControlConnection│  │
│  │  ├─ tor       │  │  ├─ BW events    │  │
│  │  ├─ obfs4     │  │  ├─ CIRC events  │  │
│  │  └─ snowflake │  │  └─ STATUS events│  │
│  └──────────────┘  └──────┬───────────┘  │
│  ┌──────────────┐         │               │
│  │ KillSwitch   │  ┌──────▼───────────┐  │
│  │ Monitor      │  │ BandwidthTracker │  │
│  └──────────────┘  └──────────────────┘  │
└──────────────────────────────────────────┘
┌──────────────────────────────────────────┐
│         TorVpnService (VpnService)        │
│  ┌──────────────────────────────────┐    │
│  │ hev-socks5-tunnel (native)       │    │
│  │ TUN interface → SOCKS5 :9050     │    │
│  │ (Blocks UDP, handles kill switch)│    │
│  └──────────────────────────────────┘    │
└──────────────────────────────────────────┘
```

### Traffic Flow

**VPN Mode (all apps):**
```
App → Android VPN (TUN) → hev-socks5-tunnel → Tor SOCKS5 (9050) → Tor Network → Internet
```

**Manual Proxy (per-app):**
```
App (configured) → Tor SOCKS5 (9050) / HTTP (8118) → Tor Network → Internet
```

---

## Building from Source

### Prerequisites

- **Android SDK** with API 34
- **Android NDK r25c**
- **Go 1.21+** (for obfs4proxy and snowflake)
- **Gradle 8.5** (included via wrapper)
- **Linux build host** (tested on Ubuntu 22.04)

### Step 1: Build Native Binaries

```bash
# Set NDK path
export ANDROID_NDK_HOME=/path/to/android-ndk-r25c

# Build all native binaries (tor, obfs4, snowflake, tun2socks)
chmod +x scripts/*.sh
./scripts/build-all.sh
```

This produces the following in `app/src/main/jniLibs/armeabi-v7a/`:

| Binary | Size (approx) | Source |
|--------|--------------|--------|
| libtor.so | ~6MB | tor 0.4.8.13 |
| libobfs4proxy.so | ~6MB | obfs4proxy 0.0.14 |
| libsnowflake.so | ~5MB | snowflake 2.10.1 |
| libtun2socks.so | ~100KB | hev-socks5-tunnel 3.0.5 |

### Step 2: Build the APK

```bash
# Debug build
./gradlew assembleDebug

# Release build
./gradlew assembleRelease
```

### Step 3: Install

```bash
# Install on connected device
adb install app/build/outputs/apk/release/app-release.apk

# Or via adb install -r for reinstall
adb install -r app/build/outputs/apk/release/app-release.apk
```

---

## F-Droid Submission

This app is designed for F-Droid compliance:

1. **100% FLOSS** — GPL-3.0-or-later, no proprietary dependencies
2. **No Google Play Services** — No Firebase, no analytics, no tracking
3. **Reproducible Builds** — Consistent build config, frozen timestamps
4. **Native Binaries Built from Source** — Build scripts in `scripts/` directory
5. **No Runtime Binary Downloads** — All binaries bundled at build time
6. **Minimal Dependencies** — Only AndroidX, Material, and Kotlin Coroutines
7. **Bundled Fonts** — No Google Fonts Provider dependency (OFL 1.1 licensed)

### F-Droid Metadata

The F-Droid metadata file is at `metadata/org.onionroute.lite.yml`. To submit:

1. Fork [fdroiddata](https://gitlab.com/fdroid/fdroiddata)
2. Copy `metadata/org.onionroute.lite.yml` to `metadata/`
3. Submit a merge request
4. F-Droid maintainers will review and build

### Anti-Features

This app declares `NonFreeNet` because:
- Tor network relies on volunteer-run relays (free software infrastructure)
- Snowflake connects to STUN servers (Google, etc.) for WebRTC signaling
- obfs4 bridges connect to Tor Project-operated relay IPs

The Tor network itself is free software, but F-Droid's scanner may flag
network connections to non-user-controlled servers.

---

## Security Considerations

### Kill Switch
The kill switch works by keeping the VPN interface active but stopping the SOCKS5 forwarding. Since all traffic must go through the VPN (Android routes everything to TUN), dead forwarding = no traffic. This is more reliable than iptables-based approaches and doesn't require root.

### DNS Leak Prevention
All DNS queries are routed through Tor's DNSPort (127.0.0.1:5400) when VPN mode is active. This prevents your ISP from seeing which domains you resolve. Verify with dnsleaktest.com.

### UDP Blocking
Tor only supports TCP. All UDP traffic is blocked at the VPN layer. This means:
- VoIP apps won't work through VPN mode
- Some streaming services may fail
- Online gaming won't work

This is a fundamental Tor limitation, not a bug.

---

## Device-Specific Notes (Samsung Grand Prime Plus)

| Aspect | Consideration |
|--------|--------------|
| **RAM (1.5GB)** | App uses ~30-50MB; tor daemon uses ~20-40MB; total ~70-90MB |
| **Storage (8GB)** | APK is ~18MB installed; leave room for tor data |
| **CPU (Spreadtrum SC9830E)** | Tor may be slow on first connect (bootstrap ~30-60s) |
| **Battery** | Foreground service drains battery faster; plug in when possible |
| **Doze Mode** | Whitelist OnionRoute Lite in battery optimization settings |

### Recommended Settings
1. Go to **Settings → Battery → Battery Optimization**
2. Find **OnionRoute Lite** and set to **"Don't optimize"**
3. Go to **Settings → Apps → OnionRoute Lite**
4. Ensure **"Allow background activity"** is enabled

---

## Project Structure

```
onionroute-lite/
├── app/
│   ├── build.gradle                    # App-level build config
│   ├── proguard-rules.pro              # ProGuard rules
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/tor/torrc.template   # Base tor configuration
│       ├── java/org/onionroute/lite/
│       │   ├── Constants.kt            # All app constants
│       │   ├── MainActivity.kt         # Main UI
│       │   ├── service/
│       │   │   ├── TorService.kt       # Foreground service (core)
│       │   │   ├── TorVpnService.kt    # VPN routing service
│       │   │   └── BootReceiver.kt     # Auto-start on boot
│       │   ├── tor/
│       │   │   ├── TorProcessManager.kt # Manages tor binary
│       │   │   ├── TorControlConnection.kt # Control port client
│       │   │   ├── BandwidthTracker.kt # Bandwidth stats
│       │   │   └── BridgeConfig.kt     # Bridge configuration
│       │   └── ui/
│       │       ├── Navigation.kt       # Screen navigation
│       │       ├── screens/            # Compose screens
│       │       └── theme/Theme.kt      # OnionRoute design system
│       ├── res/                         # UI resources
│       │   ├── font/                    # Bundled fonts (OFL 1.1)
│       │   ├── values/{strings,colors,styles}.xml
│       │   ├── drawable/                # Vector icons
│       │   └── xml/tor_vpn_config.xml
│       └── jniLibs/armeabi-v7a/        # Native binaries (built from source)
│           ├── libtor.so
│           ├── libobfs4proxy.so
│           ├── libsnowflake.so
│           └── libtun2socks.so
├── scripts/
│   ├── build-all.sh                    # Build all native binaries
│   ├── build-tor.sh                    # Build tor daemon
│   ├── build-obfs4.sh                  # Build obfs4proxy
│   ├── build-snowflake.sh              # Build snowflake client
│   └── build-tun2socks.sh              # Build hev-socks5-tunnel
├── metadata/
│   └── org.onionroute.lite.yml                 # F-Droid metadata
├── fastlane/
│   └── metadata/android/en-US/         # F-Droid store listing
├── build.gradle                        # Root build config
├── settings.gradle                     # Project settings
├── gradle.properties                   # Gradle properties
├── LICENSE                             # GPL-3.0-or-later
├── THIRD-PARTY-LICENSES.md             # Font & dependency licenses
├── PRIVACY_POLICY.md                   # Privacy policy
├── CHANGELOG.md                        # Version changelog
└── README.md                           # This file
```

---

## License

GPL-3.0-or-later — see [LICENSE](LICENSE)

Tor, obfs4proxy, and Snowflake are under BSD-3-Clause.
hev-socks5-tunnel is under GPL-3.0.
Fonts are under SIL Open Font License v1.1.
