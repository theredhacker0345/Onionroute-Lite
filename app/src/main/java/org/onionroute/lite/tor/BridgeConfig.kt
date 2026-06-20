package org.onionroute.lite.tor

import android.content.Context
import android.util.Log
import org.onionroute.lite.Constants

/**
 * Manages bridge configuration for Tor circumvention.
 *
 * Supports:
 * - Snowflake (WebRTC-based transport)
 * - obfs4 (scramblesuit-based transport)
 * - WebTunnel (HTTPS-mimicking transport)
 * - Custom bridge lines
 *
 * Bridge preferences are stored in SharedPreferences and applied
 * when generating the torrc configuration file.
 */
class BridgeConfig(private val context: Context) {

    private val tag = "BridgeConfig"

    private val prefs by lazy {
        context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
    }

    // ── Snowflake ─────────────────────────────────────────────────

    /**
     * Checks if Snowflake is enabled in preferences.
     */
    fun isSnowflakeEnabled(): Boolean {
        return prefs.getBoolean(Constants.PREF_SNOWFLAKE_ENABLED, false)
    }

    /**
     * Enables or disables Snowflake bridge.
     */
    fun setSnowflakeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(Constants.PREF_SNOWFLAKE_ENABLED, enabled).apply()
        Log.i(tag, "Snowflake ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Returns the Snowflake bridge configuration lines for torrc.
     */
    fun getSnowflakeConfig(): List<String> {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        return listOf(
            "ClientTransportPlugin snowflake exec $nativeLibDir/${Constants.BINARY_SNOWFLAKE}",
            "Bridge snowflake 192.0.2.3:80 2B280B23E1107BB62ABFC40DDCC8824814F80A72 fingerprint=2B280B23E1107BB62ABFC40DDCC8824814F80A72 url=${Constants.SNOWFLAKE_BROKER_URL} fronts=${Constants.SNOWFLAKE_FRONT_DOMAIN} ice=${Constants.SNOWFLAKE_ICE_SERVERS}",
            "UseBridges 1"
        )
    }

    // ── obfs4 ─────────────────────────────────────────────────────

    /**
     * Checks if obfs4 is enabled in preferences.
     */
    fun isObfs4Enabled(): Boolean {
        return prefs.getBoolean(Constants.PREF_OBFS4_ENABLED, false)
    }

    /**
     * Enables or disables obfs4 bridge.
     */
    fun setObfs4Enabled(enabled: Boolean) {
        prefs.edit().putBoolean(Constants.PREF_OBFS4_ENABLED, enabled).apply()
        Log.i(tag, "obfs4 ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Returns the obfs4 bridge configuration lines for torrc.
     */
    fun getObfs4Config(): List<String> {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val lines = mutableListOf(
            "ClientTransportPlugin obfs4 exec $nativeLibDir/${Constants.BINARY_OBFS4PROXY}"
        )
        Constants.DEFAULT_OBFS4_BRIDGES.forEach { bridge ->
            lines.add("Bridge $bridge")
        }
        lines.add("UseBridges 1")
        return lines
    }

    // ── WebTunnel ────────────────────────────────────────────────

    /**
     * Checks if WebTunnel is enabled in preferences.
     */
    fun isWebTunnelEnabled(): Boolean {
        return prefs.getBoolean(Constants.PREF_WEBTUNNEL_ENABLED, false)
    }

    /**
     * Enables or disables WebTunnel bridge.
     */
    fun setWebTunnelEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(Constants.PREF_WEBTUNNEL_ENABLED, enabled).apply()
        Log.i(tag, "WebTunnel ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Returns the WebTunnel bridge configuration lines for torrc.
     *
     * WebTunnel bridges mimic HTTPS traffic. No separate transport
     * plugin binary is needed — the bridge lines contain HTTPS URL
     * endpoints that Tor connects to directly.
     */
    fun getWebTunnelConfig(): List<String> {
        val lines = mutableListOf<String>()
        Constants.DEFAULT_WEBTUNNEL_BRIDGES.forEach { bridge ->
            lines.add("Bridge $bridge")
        }
        lines.add("UseBridges 1")
        return lines
    }

    // ── Custom Bridges ────────────────────────────────────────────

    /**
     * Returns all custom bridge lines stored in preferences.
     */
    fun getCustomBridges(): List<String> {
        val bridgesStr = prefs.getString(Constants.PREF_CUSTOM_BRIDGES, "") ?: ""
        return bridgesStr.split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() && isValidBridgeLine(it) }
    }

    /**
     * Saves custom bridge lines to preferences.
     */
    fun setCustomBridges(bridges: List<String>) {
        prefs.edit().putString(Constants.PREF_CUSTOM_BRIDGES, bridges.joinToString("\n")).apply()
        Log.i(tag, "Saved ${bridges.size} custom bridge(s)")
    }

    /**
     * Removes a single custom bridge line by index.
     */
    fun removeCustomBridge(index: Int) {
        val current = getCustomBridges().toMutableList()
        if (index in current.indices) {
            current.removeAt(index)
            setCustomBridges(current)
            Log.i(tag, "Removed custom bridge at index $index")
        }
    }

    /**
     * Parses bridge text from QR scan or paste, handling multiple formats:
     *
     * Supported formats:
     *   - Plain bridge lines: "obfs4 IP:PORT FINGERPRINT cert=... iat-mode=0"
     *   - URI format: "obfs4://IP:PORT/?cert=...&iat-mode=0"
     *   - snowflake:// URI
     *   - webtunnel:// URI
     *   - Multiple lines separated by newlines
     *
     * Also decodes HTML entities (e.g. `&#43;` → `+`) because BridgeDB's
     * web page returns HTML-encoded bridge lines — if a user copy-pastes
     * from BridgeDB, the cert string would contain `&#43;` instead of `+`
     * and Tor would silently fail to parse the cert.
     *
     * Returns the number of valid bridges added.
     */
    fun parseAndAddBridges(text: String): Int {
        val existing = getCustomBridges().toMutableList()
        val newBridges = mutableListOf<String>()

        text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { line ->
                // Defensive: decode HTML entities that may have come from a
                // copy-paste of BridgeDB's HTML-encoded bridge page.
                val entityDecoded = decodeHtmlEntities(line)
                val bridgeLine = when {
                    // Handle bridge:// URI prefix (some QR codes use this)
                    entityDecoded.startsWith("bridge://", ignoreCase = true) -> {
                        entityDecoded.removePrefix("bridge://").removePrefix("Bridge://")
                    }
                    // Handle obfs4:// URI format → convert to plain obfs4 line
                    entityDecoded.startsWith("obfs4://", ignoreCase = true) -> {
                        convertUriToBridgeLine("obfs4", entityDecoded)
                    }
                    // Handle snowflake:// URI format → convert to plain snowflake line
                    entityDecoded.startsWith("snowflake://", ignoreCase = true) -> {
                        convertUriToBridgeLine("snowflake", entityDecoded)
                    }
                    // Handle webtunnel:// URI format → convert to plain webtunnel line
                    entityDecoded.startsWith("webtunnel://", ignoreCase = true) -> {
                        convertUriToBridgeLine("webtunnel", entityDecoded)
                    }
                    // Already a plain bridge line
                    else -> entityDecoded
                }

                val trimmed = bridgeLine.trim()
                if (trimmed.isNotEmpty() && isValidBridgeLine(trimmed) &&
                    !existing.contains(trimmed) && !newBridges.contains(trimmed)
                ) {
                    newBridges.add(trimmed)
                }
            }

        if (newBridges.isNotEmpty()) {
            existing.addAll(newBridges)
            setCustomBridges(existing)
            Log.i(tag, "Parsed and added ${newBridges.size} new bridge(s)")
        }

        return newBridges.size
    }

    /**
     * Decodes common HTML entities in bridge lines (defensive).
     * Same set as BridgeUpdater.decodeHtmlEntities.
     */
    private fun decodeHtmlEntities(s: String): String {
        return s
            .replace("&#43;", "+")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&#x27;", "'")
    }

    /**
     * Converts a URI-style bridge line (e.g. "obfs4://1.2.3.4:443/?cert=abc&iat-mode=0")
     * to a plain bridge line (e.g. "obfs4 1.2.3.4:443 FINGERPRINT cert=abc iat-mode=0").
     *
     * The fingerprint may be absent in URI format; we preserve what we can.
     */
    private fun convertUriToBridgeLine(transport: String, uri: String): String {
        val withoutScheme = uri.removePrefix("$transport://").removePrefix("${transport.uppercase()}://")

        // Split host:port from query params
        val pathAndQuery = withoutScheme.split("?", limit = 2)
        val hostPort = pathAndQuery[0].trimEnd('/')

        val params = if (pathAndQuery.size > 1) {
            pathAndQuery[1]
                .split("&")
                .mapNotNull { param ->
                    val kv = param.split("=", limit = 2)
                    if (kv.size == 2) {
                        // Convert URL-encoded values
                        "${kv[0]}=${java.net.URLDecoder.decode(kv[1], "UTF-8")}"
                    } else null
                }
                .joinToString(" ")
        } else ""

        return "$transport $hostPort $params".trim()
    }

    /**
     * Checks if a line is a valid bridge line (starts with a known transport name).
     */
    private fun isValidBridgeLine(line: String): Boolean {
        val lower = line.lowercase()
        return lower.startsWith("obfs4") ||
                lower.startsWith("snowflake") ||
                lower.startsWith("meek") ||
                lower.startsWith("webtunnel")
    }

    // ── Full Configuration ────────────────────────────────────────

    /**
     * Returns all active bridge configuration lines for torrc generation.
     * This combines Snowflake, obfs4, WebTunnel, and custom bridges based on current preferences.
     */
    fun getActiveBridgeConfig(): List<String> {
        val config = mutableListOf<String>()

        if (isSnowflakeEnabled()) {
            config.addAll(getSnowflakeConfig())
        }

        if (isObfs4Enabled()) {
            config.addAll(getObfs4Config())
        }

        if (isWebTunnelEnabled()) {
            config.addAll(getWebTunnelConfig())
        }

        val customBridges = getCustomBridges()
        if (customBridges.isNotEmpty()) {
            config.addAll(customBridges)
            if (!config.contains("UseBridges 1")) {
                config.add("UseBridges 1")
            }
        }

        return config
    }

    /**
     * Disables all bridges (Snowflake, obfs4, WebTunnel, and custom).
     */
    fun disableAllBridges() {
        setSnowflakeEnabled(false)
        setObfs4Enabled(false)
        setWebTunnelEnabled(false)
        setCustomBridges(emptyList())
        Log.i(tag, "All bridges disabled")
    }
}
