package org.onionroute.lite

/**
 * Application-wide constants for OnionRoute Lite.
 *
 * All configurable values are centralized here for easy maintenance.
 * No hardcoded values should appear elsewhere in the codebase.
 */
object Constants {

    // ── App Info ──────────────────────────────────────────────
    const val APP_VERSION = "1.0.0"

    // ── Tor Network Ports ──────────────────────────────────────
    const val TOR_SOCKS_PORT = 9050
    const val TOR_HTTP_PORT = 8118
    const val TOR_CONTROL_PORT = 9051
    const val TOR_DNS_PORT = 5400

    // ── Native Binary Names ────────────────────────────────────
    const val BINARY_TOR = "libtor.so"
    const val BINARY_OBFS4PROXY = "libobfs4proxy.so"
    const val BINARY_SNOWFLAKE = "libsnowflake.so"
    const val BINARY_SNOWFLAKE_PROXY = "libsnowflake-proxy.so"
    const val BINARY_TUN2SOCKS = "libtun2socks.so"

    // WebTunnel pluggable transport client. Tor REQUIRES a
    // ClientTransportPlugin binary for webtunnel bridge lines — without it,
    // bootstrap stalls at PT_MISSING (5%) and never completes.
    const val BINARY_WEBTUNNEL = "libwebtunnel.so"

    // ── File Paths ────────────────────────────────────────────
    const val TORRC_FILENAME = "torrc"
    const val TOR_DATA_DIR = "tor"
    const val TOR_CONTROL_COOKIE = "control_auth_cookie"

    // ── SharedPreferences Keys ─────────────────────────────────
    const val PREFS_NAME = "onionroute_lite_prefs"
    const val PREF_SNOWFLAKE_ENABLED = "snowflake_enabled"
    const val PREF_OBFS4_ENABLED = "obfs4_enabled"
    const val PREF_WEBTUNNEL_ENABLED = "webtunnel_enabled"
    const val PREF_KINDNESS_ENABLED = "kindness_enabled"
    const val PREF_VPN_ENABLED = "vpn_enabled"
    const val PREF_KILL_SWITCH_ENABLED = "kill_switch_enabled"
    const val PREF_AUTO_START = "pref_auto_start"
    const val PREF_APP_ROUTING_MODE = "app_routing_mode"
    const val PREF_SELECTED_APPS = "selected_apps"
    const val PREF_EXCLUDED_APPS = "excluded_apps"
    const val PREF_APP_ICON = "app_icon"
    const val PREF_CUSTOM_BRIDGES = "custom_bridges"

    // One-shot prompt: ask the user to exempt the app from battery
    // optimization (REQUEST_IGNORE_BATTERY_OPTIMIZATIONS is declared in the
    // manifest). Without the exemption, aggressive OEM battery managers
    // (MIUI, EMUI, ColorOS…) kill the tor process minutes after screen-off.
    const val PREF_BATTERY_PROMPT_SHOWN = "battery_prompt_shown"

    // ── Exit Country Selector ──────────────────────────────────
    const val PREF_EXIT_COUNTRY = "exit_country"
    const val ACTION_SET_EXIT_COUNTRY = "org.onionroute.lite.SET_EXIT_COUNTRY"
    const val EXTRA_EXIT_COUNTRY = "extra_exit_country"

    /** Curated exit-country choices ("" = no restriction). Kept small and
     *  censorship-relevant; torrc gets `ExitNodes {cc}` + StrictNodes 1. */
    val EXIT_COUNTRY_OPTIONS: List<Pair<String, String>> = listOf(
        "" to "Any",
        "de" to "Germany",
        "nl" to "Netherlands",
        "se" to "Sweden",
        "ch" to "Switzerland",
        "ro" to "Romania",
        "is" to "Iceland",
        "fi" to "Finland",
        "fr" to "France",
        "us" to "USA",
        "es" to "Spain",
        "jp" to "Japan"
    )

    // ── Circuit Viewer ─────────────────────────────────────────
    const val ACTION_GET_CIRCUITS = "org.onionroute.lite.GET_CIRCUITS"
    const val ACTION_CIRCUITS_UPDATE = "org.onionroute.lite.CIRCUITS_UPDATE"
    const val EXTRA_CIRCUITS_JSON = "extra_circuits_json"

    // ── Connection Health Dashboard ────────────────────────────
    // Latency samples ride along the existing exit-IP poller (same HTTP
    // request through SOCKS, timed) — zero extra network traffic.
    // latencyMs == -1 marks a failed probe (circuit churn / no network).
    const val ACTION_HEALTH_UPDATE = "org.onionroute.lite.HEALTH_UPDATE"
    const val EXTRA_HEALTH_LATENCY_MS = "extra_health_latency_ms"
    const val EXTRA_HEALTH_TIMESTAMP = "extra_health_timestamp"

    // ── Kindness Donation Level ────────────────────────────────
    // Max concurrent snowflake clients served by the proxy (-capacity).
    // Effectively caps donated bandwidth. 0 = unlimited.
    const val PREF_KINDNESS_CAPACITY = "kindness_capacity"
    const val KINDNESS_CAPACITY_DEFAULT = 5
    const val ACTION_RESTART_KINDNESS_PROXY = "org.onionroute.lite.KINDNESS_RESTART_PROXY"

    // ── Kindness Stats Preferences ────────────────────────────
    const val PREF_KINDNESS_DATA_DONATED = "kindness_data_donated"
    const val PREF_KINDNESS_PEOPLE_HELPED_NOW = "kindness_people_helped_now"
    const val PREF_KINDNESS_PEOPLE_HELPED_TOTAL = "kindness_people_helped_total"
    const val PREF_KINDNESS_SESSION_START = "kindness_session_start"

    // ── Notification ──────────────────────────────────────────
    const val NOTIFICATION_CHANNEL_ID = "onionroute_service"
    const val NOTIFICATION_ID = 1
    const val VPN_NOTIFICATION_ID = 2
    const val KINDNESS_NOTIFICATION_ID = 3
    const val KINDNESS_NOTIFICATION_CHANNEL_ID = "onionroute_kindness"

    // ── Intent Actions (for IPC between Activity & Service) ───
    const val ACTION_START_TOR = "org.onionroute.lite.START_TOR"
    const val ACTION_STOP_TOR = "org.onionroute.lite.STOP_TOR"
    const val ACTION_START_VPN = "org.onionroute.lite.START_VPN"
    const val ACTION_STOP_VPN = "org.onionroute.lite.STOP_VPN"
    const val ACTION_RESTART_VPN = "org.onionroute.lite.RESTART_VPN"
    const val ACTION_TOGGLE_SNOWFLAKE = "org.onionroute.lite.TOGGLE_SNOWFLAKE"
    const val ACTION_TOGGLE_OBFS4 = "org.onionroute.lite.TOGGLE_OBFS4"
    const val ACTION_TOGGLE_WEBTUNNEL = "org.onionroute.lite.TOGGLE_WEBTUNNEL"
    const val ACTION_START_KINDNESS = "org.onionroute.lite.START_KINDNESS"
    const val ACTION_STOP_KINDNESS = "org.onionroute.lite.STOP_KINDNESS"
    const val ACTION_STATUS_UPDATE = "org.onionroute.lite.STATUS_UPDATE"
    const val ACTION_BANDWIDTH_UPDATE = "org.onionroute.lite.BANDWIDTH_UPDATE"
    const val ACTION_KINDNESS_UPDATE = "org.onionroute.lite.KINDNESS_UPDATE"
    const val ACTION_VPN_ALWAYS_ON = "org.onionroute.lite.VPN_ALWAYS_ON"
    const val ACTION_NEW_CIRCUIT = "org.onionroute.lite.NEW_CIRCUIT"
    const val ACTION_IMPORT_BRIDGES = "org.onionroute.lite.IMPORT_BRIDGES"
    const val ACTION_KINDNESS_STATS = "org.onionroute.lite.KINDNESS_STATS"
    /** Broadcast when the Tor exit IP changes (or is first detected). */
    const val ACTION_EXIT_IP_UPDATE = "org.onionroute.lite.EXIT_IP_UPDATE"

    // ── Broadcast Extra Keys ──────────────────────────────────
    const val EXTRA_TOR_STATUS = "tor_status"
    const val EXTRA_SNOWFLAKE_STATUS = "snowflake_status"
    const val EXTRA_VPN_STATUS = "vpn_status"
    const val EXTRA_KINDNESS_ACTIVE = "kindness_active"
    const val EXTRA_BYTES_UP = "bytes_up"
    const val EXTRA_BYTES_DOWN = "bytes_down"
    const val EXTRA_SPEED_UP = "speed_up"
    const val EXTRA_KINDNESS_DATA_DONATED = "kindness_data_donated"
    const val EXTRA_KINDNESS_PEOPLE_NOW = "kindness_people_now"
    const val EXTRA_KINDNESS_PEOPLE_TOTAL = "kindness_people_total"
    const val EXTRA_SPEED_DOWN = "speed_down"
    /** Carries Tor bootstrap progress (0..100) when EXTRA_TOR_STATUS == "BOOTSTRAPPING". */
    const val EXTRA_BOOTSTRAP_PROGRESS = "bootstrap_progress"
    /** Carries the Tor exit node IP address (string, may be empty if lookup failed). */
    const val EXTRA_EXIT_IP = "exit_ip"

    // ── VPN Configuration ─────────────────────────────────────
    const val VPN_MTU = 1500
    const val VPN_ADDRESS = "10.0.0.2"
    const val VPN_ROUTE_PREFIX = 32
    // IPv6 address for the TUN interface. Paired with the ::/0 route so
    // IPv6 traffic is captured into the tunnel instead of bypassing Tor.
    const val VPN_ADDRESS_V6 = "fdfe:dcba:9876::1"

    // DNS server advertised to apps on the TUN.
    //
    // This address is deliberately NOT a real host. It exists only so that
    // every DNS query is forced into the tunnel and lands on
    // [TUN2SOCKS_MAPDNS_POOL_NETWORK] handling inside hev-socks5-tunnel's
    // mapped-DNS resolver, which answers it over SOCKS5 -> Tor. Verified in
    // hev-socks5-tunnel: udp_recv() hijacks a datagram when
    // `dst_port == mapdns.port && dst_ip == mapdns.address`
    // (hev-socks5-tunnel.c:243-248), so this value must match
    // [VPN_MAPDNS_ADDRESS] below exactly or interception silently fails and
    // queries are forwarded to a black hole.
    //
    // The previous value (10.0.0.1) was advertised with nothing listening on
    // it, so every DNS lookup failed outright.
    const val VPN_DNS = "10.111.222.3"

    // ── hev-socks5-tunnel mapped DNS ───────────────────────────
    // hev answers queries to VPN_DNS using synthetic A/AAAA records drawn from
    // the CGNAT range below: it replies with `network | index` where
    // `index < cache-size`, and reverse-maps them with `ip & ~netmask`
    // (hev-mapped-dns.c:224, :259). So netmask must cover the low bits that
    // vary, and cache-size both bounds the pool and caps RAM.
    const val VPN_MAPDNS_ADDRESS = "10.111.222.3"
    const val VPN_MAPDNS_PORT = 53
    const val TUN2SOCKS_MAPDNS_POOL_NETWORK = "100.64.0.0"
    const val TUN2SOCKS_MAPDNS_POOL_NETMASK = "255.192.0.0"
    // 4096 synthetic names rather than upstream's suggested 10000: each entry
    // holds a hostname string, and this device has 1.5GB of RAM. 4096 is far
    // more than any browsing session needs.
    const val TUN2SOCKS_MAPDNS_CACHE_SIZE = 4096

    // ── Snowflake Default Configuration ───────────────────────
    const val SNOWFLAKE_BROKER_URL = "https://snowflake-broker.torproject.net/"
    const val SNOWFLAKE_FRONT_DOMAIN = "cdn.sstatic.net"

    // ── Tor Exit IP Check ─────────────────────────────────────
    // Queried via the Tor SOCKS5 proxy (127.0.0.1:9050) every
    // EXIT_IP_UPDATE_INTERVAL_MS to display the user's current Tor
    // exit node IP. Response JSON: {"IsTor":true,"IP":"1.2.3.4"}
    const val EXIT_IP_CHECK_URL = "https://check.torproject.org/api/ip"
    const val EXIT_IP_UPDATE_INTERVAL_MS = 15_000L

    // ── Auto-Reconnect (self-healing) ─────────────────────────
    // When the tor process exits unexpectedly while connected, the service
    // transparently restarts it this many times (with linear backoff) before
    // surfacing an ERROR. Better-than-official smoothness: transient kills
    // by the OS/OOM recover without user interaction.
    const val TOR_AUTO_RESTART_ATTEMPTS = 2
    const val TOR_AUTO_RESTART_DELAY_BASE_MS = 3000L

    // ── Low-RAM Tuning ────────────────────────────────────────
    // Cap on tor's total in-memory cell/channel queue buffers. Tor's default
    // scales with system RAM and can balloon under multi-app VPN load; an
    // explicit bound keeps the daemon predictable on the 1.5GB devices this
    // app targets (tor typically idles at ~20-40 MB regardless).
    const val TOR_MAX_MEM_IN_QUEUE = "32 MBytes"
    // STUN servers for Snowflake WebRTC NAT traversal.
    //
    // These were tested via UDP STUN Binding Request on 2026-06-21 and
    // confirmed responding. The previous list (stun.antisip.com,
    // stun.bluesip.net, stun.sipnet.cz) is dead — all three either
    // refuse connections or don't resolve anymore, which caused the
    // bridge health monitor to flag snowflake as permanently dead and
    // also caused real snowflake connections to fail.
    //
    // F-Droid NonFreeNet policy: stun.l.google.com is operated by Google
    // and would trigger the NonFreeNet AntiFeature. We exclude it from
    // the default list. Users on heavily-censored networks where the
    // non-Google STUN servers are blocked can add it manually in bridge
    // settings if needed.
    //
    // Current list (FOSS-friendly, tested working 2026-06-21):
    //   - stun.voip.blackberry.com:3478  (BlackBerry, Canada)
    //   - stun.miwifi.com:3478           (Xiaomi, China — reliable globally)
    //   - stun.voipbuster.com:3478       (VoipBuster, Germany)
    //   - stun.sip.us:3478               (SIP.US, USA)
    //   - stun.freeswitch.org:3478       (FreeSWITCH FOSS project)
    const val SNOWFLAKE_ICE_SERVERS = "stun:stun.voip.blackberry.com:3478,stun:stun.miwifi.com:3478,stun:stun.voipbuster.com:3478,stun:stun.sip.us:3478,stun:stun.freeswitch.org:3478"

    // HTML-decoded obfs4 bridges.
    // BridgeDB returns HTML-encoded bridge lines on its web page (the `+`
    // character in cert strings is encoded as `&#43;`). Tor cannot parse
    // cert strings with `&#43;` — it needs the literal `+`. We decode the
    // entities here so the bridge lines are valid when written to torrc.
    // Without this fix, obfs4 bridges silently fail to bootstrap.
    val DEFAULT_OBFS4_BRIDGES = listOf(
        "obfs4 80.96.58.89:9130 09F8EE9BBF2224564255A69C75D2BDED0C11ED69 cert=eDeANY48aUHUkN93TkyqqOpWfu7CFUcyR8q6SwbRcg1pLPgWL8GPpt++DakdOkgeqmQrUg iat-mode=0",
        "obfs4 129.151.27.14:3721 159E2EE0E954B669865AAF963EA534884850E6AD cert=Yngpf+xcO4yraE/51Q3Kl6k2+mQvaK2wPk+HsP4cDs5zLJU83gyajohigzmTSoMEfyBUDg iat-mode=0",
        "obfs4 82.64.193.83:12867 294AF2AD929814DB41F9AB6A8B9D0E17C6EFA6B7 cert=ixnzOa7SUh45eHpgKJJwSCuRV17DxLgLYWb4qtLgqsJbD32yfNB9cfDSvgi7XANsUhO+aw iat-mode=0",
        "obfs4 89.40.206.72:37171 2CC3655B4CE0DC10FD8E67457D8F7A0F2FEFDAC0 cert=/IK083k+A2mGBVAE622gy81kn409GY+84DP8+L8ovLdMPY1UcPano+mA3FAvnz0WrNmVVg iat-mode=0",
        "obfs4 159.203.18.175:5846 76860AA01FAB2927E8805B4165C579BAB4315A5D cert=80m7x5D0p1neEUzCTWzdpHbCXom3QIecgIAvGTlnpgKyVPT/0TPoXJQxWmnLCbNQFj9SbA iat-mode=0"
    )

    // ── Default WebTunnel Bridges ─────────────────────────────
    // WebTunnel bridges mimic HTTPS traffic, making them effective
    // against deep packet inspection (DPI) systems. These default
    // bridges are operated by the Tor Project.
    //
    // IMPORTANT: WebTunnel is a pluggable transport — these lines only
    // work together with the libwebtunnel.so client binary registered
    // via ClientTransportPlugin (see scripts/build-webtunnel.sh and
    // TorProcessManager.generateTorrc). The second entry is an obfs4
    // fallback bridge; TorProcessManager registers whichever plugin each
    // line's transport needs and skips lines whose plugin binary is
    // missing.
    val DEFAULT_WEBTUNNEL_BRIDGES = listOf(
        "webtunnel 159.69.22.241:443 40D1E0A98733F1E3E0548BD2F2B3C7B6B08C93B7 url=https://wt-1.broker.torproject.org/webtunnel/line/5a5a6463-05a0-4c44-bef7-40e3e2e128f2 ver=0.4.0",
        "obfs4 67.205.142.190:9889 656C06D42A01D25138F2F52E459C6DC27B209F73 cert=Y8MM/9CEGC7PshNot9XYQQncWugSGeu/p6hbvNOsU1zl2U4TL50l4OBzyOhD6dLw4LVMSw iat-mode=0"
    )

    // ── Quick Settings Tile ───────────────────────────────────
    const val PREF_TOR_CONNECTED = "tor_connected"
}
