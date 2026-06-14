package org.phantomvpn.tor

import android.content.Context
import android.content.SharedPreferences
import org.phantomvpn.Constants

/**
 * Manages bridge configuration for Tor.
 *
 * Handles obfs4 and snowflake bridge lines, persisting user choices
 * to SharedPreferences and generating the appropriate torrc lines.
 *
 * Bridge lines are the primary mechanism for censorship circumvention.
 * obfs4 disguises Tor traffic as random data, while Snowflake uses
 * WebRTC to route through volunteer proxies, making it very hard to
 * block without collateral damage.
 */
class BridgeConfig(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)

    // ── Snowflake ─────────────────────────────────────────────

    var snowflakeEnabled: Boolean
        get() = prefs.getBoolean(Constants.PREF_SNOWFLAKE_ENABLED, false)
        set(value) = prefs.edit().putBoolean(Constants.PREF_SNOWFLAKE_ENABLED, value).apply()

    // ── obfs4 ─────────────────────────────────────────────────

    var obfs4Enabled: Boolean
        get() = prefs.getBoolean(Constants.PREF_OBFS4_ENABLED, false)
        set(value) = prefs.edit().putBoolean(Constants.PREF_OBFS4_ENABLED, value).apply()

    // ── Custom bridge lines (user-pasted) ─────────────────────

    var customBridges: String
        get() = prefs.getString("custom_bridges", "") ?: ""
        set(value) = prefs.edit().putString("custom_bridges", value).apply()

    // ── Torrc Generation ──────────────────────────────────────

    /**
     * Generates bridge-related lines for the torrc configuration file.
     *
     * This method builds the ClientTransportPlugin directives and
     * Bridge lines based on the current configuration. The tor
     * binary reads these at startup and connects through the
     * appropriate pluggable transport.
     *
     * @param torBinDir Directory where the executable symlinks are located
     * @return List of torrc lines to append, or empty list if no bridges
     */
    fun generateTorrcLines(torBinDir: String): List<String> {
        val lines = mutableListOf<String>()

        if (!snowflakeEnabled && !obfs4Enabled) {
            return emptyList()
        }

        // Pluggable transport exec lines
        val transportPlugins = mutableListOf<String>()

        if (snowflakeEnabled) {
            val snowflakePath = "$torBinDir/${Constants.EXEC_SNOWFLAKE}"
            transportPlugins.add(
                "ClientTransportPlugin snowflake exec $snowflakePath " +
                    "-url ${Constants.SNOWFLAKE_BROKER_URL} " +
                    "-front ${Constants.SNOWFLAKE_FRONT_DOMAIN} " +
                    "-ice ${Constants.SNOWFLAKE_ICE_SERVERS}"
            )
        }

        if (obfs4Enabled) {
            val obfs4Path = "$torBinDir/${Constants.EXEC_OBFS4PROXY}"
            transportPlugins.add("ClientTransportPlugin obfs4 exec $obfs4Path")
        }

        lines.addAll(transportPlugins)

        // Bridge lines
        if (snowflakeEnabled) {
            lines.add("Bridge snowflake 0.0.3.0:80 0000000000000000000000000000000000000000")
        }

        if (obfs4Enabled) {
            val bridges = getObfs4BridgeLines()
            bridges.forEach { lines.add("Bridge $it") }
        }

        return lines
    }

    /**
     * Returns the obfs4 bridge lines to use.
     * If the user has pasted custom bridges, use those.
     * Otherwise, fall back to the built-in defaults.
     */
    private fun getObfs4BridgeLines(): List<String> {
        val custom = customBridges.trim()
        if (custom.isNotEmpty()) {
            return custom.lines().filter { it.isNotBlank() }
        }
        return Constants.DEFAULT_OBFS4_BRIDGES
    }
}
