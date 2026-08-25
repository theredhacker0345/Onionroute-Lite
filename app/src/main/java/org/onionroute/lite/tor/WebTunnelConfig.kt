package org.onionroute.lite.tor

import android.content.Context
import android.util.Log
import org.onionroute.lite.Constants

/**
 * Manages WebTunnel bridge configuration for Tor circumvention.
 *
 * WebTunnel is a pluggable transport that mimics HTTPS traffic,
 * making Tor connections appear as regular web browsing. This is
 * particularly effective against deep packet inspection (DPI) systems
 * that block Tor traffic based on protocol fingerprinting.
 *
 * Key characteristics:
 * - Bridges connect via HTTPS, blending in with normal web traffic
 * - WebTunnel IS a pluggable transport: it REQUIRES its own client binary
 *   (libwebtunnel.so) registered via a ClientTransportPlugin line in torrc.
 *   Bridge lines alone do NOT work — tor stalls at PT_MISSING without the
 *   plugin binary.
 * - Default bridges are provided by the Tor Project
 * - Users can add custom WebTunnel bridge lines
 *
 * WebTunnel bridge line format:
 *   webtunnel <IP>:<PORT> <FINGERPRINT> url=<HTTPS_URL> ver=<VERSION>
 *
 * F-Droid notes:
 * - No proprietary dependencies
 * - Default bridges are operated by the Tor Project and trusted community members
 * - No non-free network services beyond the Tor network itself
 */
class WebTunnelConfig(private val context: Context) {

    private val tag = "WebTunnelConfig"

    private val prefs by lazy {
        context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
    }

    // ── Preferences ───────────────────────────────────────────────

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

    // ── Bridge Lines ──────────────────────────────────────────────

    /**
     * Returns the default WebTunnel bridge lines provided by the Tor Project.
     *
     * These bridges are maintained by the Tor Project and offer reliable
     * WebTunnel connectivity. They use HTTPS endpoints that appear as
     * regular web servers to censors.
     */
    fun getDefaultBridges(): List<String> {
        return Constants.DEFAULT_WEBTUNNEL_BRIDGES
    }

    /**
     * Returns custom WebTunnel bridge lines stored in preferences.
     */
    fun getCustomBridges(): List<String> {
        val bridgesStr = prefs.getString(Constants.PREF_WEBTUNNEL_CUSTOM_BRIDGES, "") ?: ""
        return bridgesStr.split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.startsWith("webtunnel") }
    }

    /**
     * Saves custom WebTunnel bridge lines to preferences.
     */
    fun setCustomBridges(bridges: List<String>) {
        prefs.edit()
            .putString(Constants.PREF_WEBTUNNEL_CUSTOM_BRIDGES, bridges.joinToString("\n"))
            .apply()
        Log.i(tag, "Saved ${bridges.size} custom WebTunnel bridge(s)")
    }

    // ── Torrc Configuration ───────────────────────────────────────

    /**
     * Returns the WebTunnel bridge configuration lines for torrc.
     *
     * WebTunnel bridges mimic HTTPS traffic. NOTE: these Bridge lines are
     * only usable together with a ClientTransportPlugin webtunnel exec
     * <libwebtunnel.so> line — TorProcessManager emits that line and skips
     * webtunnel bridges entirely when libwebtunnel.so is missing.
     */
    fun getWebTunnelConfig(): List<String> {
        val lines = mutableListOf<String>()

        // Use default bridges
        getDefaultBridges().forEach { bridge ->
            lines.add("Bridge $bridge")
        }

        // Append custom bridges
        getCustomBridges().forEach { bridge ->
            lines.add("Bridge $bridge")
        }

        lines.add("UseBridges 1")

        return lines
    }

    /**
     * Returns all active WebTunnel configuration lines for torrc generation.
     * Only returns config if WebTunnel is enabled.
     */
    fun getActiveConfig(): List<String> {
        if (!isWebTunnelEnabled()) return emptyList()
        return getWebTunnelConfig()
    }
}
