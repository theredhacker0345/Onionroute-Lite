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

    // ── Executable Names (symlinks in app data dir) ───────────
    const val EXEC_TOR = "tor"
    const val EXEC_OBFS4PROXY = "obfs4proxy"
    const val EXEC_SNOWFLAKE = "snowflake-client"
    const val EXEC_SNOWFLAKE_PROXY = "snowflake-proxy"
    const val EXEC_TUN2SOCKS = "tun2socks"

    // ── File Paths ────────────────────────────────────────────
    const val TORRC_FILENAME = "torrc"
    const val TORRC_TEMPLATE = "torrc.template"
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
    const val PREF_LAST_TOTAL_UP = "last_total_up"
    const val PREF_LAST_TOTAL_DOWN = "last_total_down"
    const val PREF_APP_ROUTING_MODE = "app_routing_mode"
    const val PREF_SELECTED_APPS = "selected_apps"
    const val PREF_EXCLUDED_APPS = "excluded_apps"
    const val PREF_APP_ICON = "app_icon"
    const val PREF_WEBTUNNEL_CUSTOM_BRIDGES = "webtunnel_custom_bridges"
    const val PREF_CUSTOM_BRIDGES = "custom_bridges"

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
    const val VPN_DNS = "10.0.0.1"

    // ── Snowflake Default Configuration ───────────────────────
    const val SNOWFLAKE_BROKER_URL = "https://snowflake-broker.torproject.net/"
    const val SNOWFLAKE_FRONT_DOMAIN = "cdn.sstatic.net"

    // ── Tor Exit IP Check ─────────────────────────────────────
    // Queried via the Tor SOCKS5 proxy (127.0.0.1:9050) every
    // EXIT_IP_UPDATE_INTERVAL_MS to display the user's current Tor
    // exit node IP. Response JSON: {"IsTor":true,"IP":"1.2.3.4"}
    const val EXIT_IP_CHECK_URL = "https://check.torproject.org/api/ip"
    const val EXIT_IP_UPDATE_INTERVAL_MS = 15_000L
    // STUN servers for Snowflake WebRTC NAT traversal.
    // Using FOSS-friendly STUN servers where possible.
    // stun.l.google.com is excluded from the default list because
    // it is a non-free network service (Google STUN), which would
    // trigger F-Droid NonFreeNet AntiFeature. Users can add it
    // manually in bridge settings if needed.
    const val SNOWFLAKE_ICE_SERVERS = "stun:stun.antisip.com:3478,stun:stun.bluesip.net:3478,stun:stun.sipnet.cz:3478"

    // ── Default obfs4 Bridges ─────────────────────────────────
    val DEFAULT_OBFS4_BRIDGES = listOf(
        "obfs4 80.96.58.89:9130 09F8EE9BBF2224564255A69C75D2BDED0C11ED69 cert=eDeANY48aUHUkN93TkyqqOpWfu7CFUcyR8q6SwbRcg1pLPgWL8GPpt&#43;&#43;DakdOkgeqmQrUg iat-mode=0",
        "obfs4 129.151.27.14:3721 159E2EE0E954B669865AAF963EA534884850E6AD cert=Yngpf&#43;xcO4yraE/51Q3Kl6k2&#43;mQvaK2wPk&#43;HsP4cDs5zLJU83gyajohigzmTSoMEfyBUDg iat-mode=0",
        "obfs4 82.64.193.83:12867 294AF2AD929814DB41F9AB6A8B9D0E17C6EFA6B7 cert=ixnzOa7SUh45eHpgKJJwSCuRV17DxLgLYWb4qtLgqsJbD32yfNB9cfDSvgi7XANsUhO&#43;aw iat-mode=0"
    )

    // ── Default WebTunnel Bridges ─────────────────────────────
    // WebTunnel bridges mimic HTTPS traffic, making them effective
    // against deep packet inspection (DPI) systems. These default
    // bridges are operated by the Tor Project.
    // No separate transport plugin binary is needed — the bridge
    // lines contain HTTPS URL endpoints that Tor connects to directly.
    val DEFAULT_WEBTUNNEL_BRIDGES = listOf(
        "webtunnel 159.69.22.241:443 40D1E0A98733F1E3E0548BD2F2B3C7B6B08C93B7 url=https://wt-1.broker.torproject.org/webtunnel/line/5a5a6463-05a0-4c44-bef7-40e3e2e128f2 ver=0.4.0",
        "webtunnel 37.218.240.2:443 6689A4F6FA2FDA96D6EDAF6D4B85C7E6F0E382B6 url=https://wt-2.broker.torproject.org/webtunnel/line/60A7DE4A11A6BB86C7D9F6E6BEB5C3AC1E5BE6F1 ver=0.4.0"
    )

    // ── Kill Switch ───────────────────────────────────────────
    const val KILLSWITCH_CHECK_INTERVAL_MS = 3000L

    // ── Quick Settings Tile ───────────────────────────────────
    const val PREF_TOR_CONNECTED = "tor_connected"
    const val TILE_ICON_ONION = "ic_tile_onion"
    const val TILE_ICON_REFRESH = "ic_tile_refresh"
}
