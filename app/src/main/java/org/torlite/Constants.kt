package org.torlite

/**
 * Application-wide constants for TorLite.
 *
 * All configurable values are centralized here for easy maintenance.
 * No hardcoded values should appear elsewhere in the codebase.
 */
object Constants {

    // ── Tor Network Ports ──────────────────────────────────────
    const val TOR_SOCKS_PORT = 9050
    const val TOR_HTTP_PORT = 8118
    const val TOR_CONTROL_PORT = 9051
    const val TOR_DNS_PORT = 5400

    // ── Native Binary Names ────────────────────────────────────
    // Android loads .so files via System.loadLibrary, but we run
    // tor/obfs4/snowflake as separate processes via the executable
    // symlink pattern. These names must match the jniLibs entries.
    const val BINARY_TOR = "libtor.so"
    const val BINARY_OBFS4PROXY = "libobfs4proxy.so"
    const val BINARY_SNOWFLAKE = "libsnowflake.so"
    const val BINARY_TUN2SOCKS = "libtun2socks.so"

    // ── Executable Names (symlinks in app data dir) ───────────
    const val EXEC_TOR = "tor"
    const val EXEC_OBFS4PROXY = "obfs4proxy"
    const val EXEC_SNOWFLAKE = "snowflake-client"
    const val EXEC_TUN2SOCKS = "tun2socks"

    // ── File Paths ────────────────────────────────────────────
    const val TORRC_FILENAME = "torrc"
    const val TORRC_TEMPLATE = "torrc.template"
    const val TOR_DATA_DIR = "tor"
    const val TOR_CONTROL_COOKIE = "control_auth_cookie"

    // ── SharedPreferences Keys ─────────────────────────────────
    const val PREFS_NAME = "torlite_prefs"
    const val PREF_SNOWFLAKE_ENABLED = "snowflake_enabled"
    const val PREF_OBFS4_ENABLED = "obfs4_enabled"
    const val PREF_VPN_ENABLED = "vpn_enabled"
    const val PREF_KILL_SWITCH_ENABLED = "kill_switch_enabled"
    const val PREF_AUTO_START = "pref_auto_start"
    const val PREF_LAST_TOTAL_UP = "last_total_up"
    const val PREF_LAST_TOTAL_DOWN = "last_total_down"

    // ── Notification ──────────────────────────────────────────
    const val NOTIFICATION_CHANNEL_ID = "torlite_service"
    const val NOTIFICATION_ID = 1
    const val VPN_NOTIFICATION_ID = 2

    // ── Intent Actions (for IPC between Activity & Service) ───
    const val ACTION_START_TOR = "org.torlite.START_TOR"
    const val ACTION_STOP_TOR = "org.torlite.STOP_TOR"
    const val ACTION_START_VPN = "org.torlite.START_VPN"
    const val ACTION_STOP_VPN = "org.torlite.STOP_VPN"
    const val ACTION_TOGGLE_SNOWFLAKE = "org.torlite.TOGGLE_SNOWFLAKE"
    const val ACTION_STATUS_UPDATE = "org.torlite.STATUS_UPDATE"
    const val ACTION_BANDWIDTH_UPDATE = "org.torlite.BANDWIDTH_UPDATE"

    // ── Broadcast Extra Keys ──────────────────────────────────
    const val EXTRA_TOR_STATUS = "tor_status"
    const val EXTRA_SNOWFLAKE_STATUS = "snowflake_status"
    const val EXTRA_VPN_STATUS = "vpn_status"
    const val EXTRA_BYTES_UP = "bytes_up"
    const val EXTRA_BYTES_DOWN = "bytes_down"
    const val EXTRA_SPEED_UP = "speed_up"
    const val EXTRA_SPEED_DOWN = "speed_down"

    // ── VPN Configuration ─────────────────────────────────────
    const val VPN_MTU = 1500
    const val VPN_ADDRESS = "10.0.0.2"
    const val VPN_ROUTE_PREFIX = 32
    const val VPN_DNS = "10.0.0.1"

    // ── Snowflake Default Configuration ───────────────────────
    const val SNOWFLAKE_BROKER_URL = "https://snowflake-broker.torproject.net/"
    const val SNOWFLAKE_FRONT_DOMAIN = "cdn.sstatic.net"
    const val SNOWFLAKE_ICE_SERVERS = "stun:stun.l.google.com:19302,stun:stun.antisip.com:3478,stun:stun.bluesip.net:3478"

    // ── Default obfs4 Bridges ─────────────────────────────────
    val DEFAULT_OBFS4_BRIDGES = listOf(
        "obfs4 193.11.166.194:10901 C7B1CD38353F5B6358FFC2A7FEC0E7F19C743F6D cert=c84H9UW6bY1cFdT8OEk+u3jO2O4Y3bcGrQ2NiQemCy7E4vYsVF3VKw iat-mode=0",
        "obfs4 85.31.186.98:443 011F2592C5B56EE5423E624B4A7035B5B3D0B946 cert=Ov4fCC6MxR7GQx8w5Y0CJnDGp5QktT1x5JslU6HHUI8f9Ys0nCGRtQ iat-mode=0",
        "obfs4 85.31.186.26:443 91D625E7104B036D6E45C93E9B7D6FBA0929F510 cert=V0Jj8fCVAaW0Y7gFQThH3x2gUOCWsGs6MSX8F3q+0nY3GQPQ/V5Kwo iat-mode=0"
    )

    // ── Kill Switch ───────────────────────────────────────────
    const val KILLSWITCH_CHECK_INTERVAL_MS = 3000L
}
