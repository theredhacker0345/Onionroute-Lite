# OnionRoute Lite — Privacy Policy

**Last updated: 2026**

OnionRoute Lite is a free, open-source Tor client for Android. This privacy policy explains what data the app collects and how it handles your privacy.

## Data Collection

**OnionRoute Lite does NOT collect, store, transmit, or share any personal data.**

Specifically, OnionRoute Lite:

- Does NOT collect personal information (name, email, phone number, etc.)
- Does NOT collect device identifiers (IMEI, advertising ID, etc.)
- Does NOT collect location data
- Does NOT collect usage analytics or crash reports
- Does NOT use Google Play Services or Firebase
- Does NOT use any third-party analytics SDKs
- Does NOT serve advertisements
- Does NOT communicate with any servers other than the Tor network

## Network Connections

OnionRoute Lite connects exclusively to:

1. **The Tor Network** — A decentralized, volunteer-run network. Your traffic is encrypted and routed through multiple relays. No single relay knows both your identity and your destination. See [https://www.torproject.org](https://www.torproject.org) for details.

2. **Snowflake Brokers** (when Snowflake bridge is enabled) — The Snowflake broker (snowflake-broker.torproject.net) helps you find volunteer Snowflake proxies. The broker only sees your IP address briefly; it does not log or track you. See [https://snowflake.torproject.org](https://snowflake.torproject.org) for details.

3. **obfs4 Bridges** (when obfs4 bridge is enabled) — Default obfs4 bridge relays operated by the Tor Project. These relays see your IP address but cannot see your traffic content or destination.

## Local Data Storage

OnionRoute Lite stores the following data **locally on your device only**:

- Tor connection state (connected/disconnected)
- Snowflake and obfs4 bridge toggle preferences
- Cumulative bandwidth totals (bytes uploaded/downloaded)
- Kill switch preference

This data is stored in Android's SharedPreferences and is **never transmitted** anywhere. You can clear this data by clearing the app's data in Android Settings.

## VPN Permission

OnionRoute Lite uses Android's VpnService API to route device traffic through Tor. When you enable VPN mode:

- All device traffic is routed through a local virtual network interface
- Traffic is forwarded through Tor's SOCKS5 proxy on 127.0.0.1:9050
- No traffic is sent to any server other than the Tor network
- The VPN is only active while OnionRoute Lite is connected

## Kill Switch

When the kill switch is enabled and Tor disconnects unexpectedly:

- All network traffic is blocked (the VPN stays active but stops forwarding)
- No traffic leaks through the clearnet
- This is a privacy-protective measure, not a data collection mechanism

## Open Source

OnionRoute Lite is open-source software licensed under GPL-3.0-or-later. You can inspect the source code to verify all claims in this privacy policy:

Source code: https://github.com/darkmaster0345/onionroute-lite

## Changes to This Policy

If this privacy policy changes, the updated version will be included in the app's source repository and F-Droid listing. Since OnionRoute Lite does not have internet access for its own purposes, it cannot notify you of changes automatically.

## Contact

For privacy questions, open an issue on the project's issue tracker:
https://github.com/darkmaster0345/onionroute-lite/issues
