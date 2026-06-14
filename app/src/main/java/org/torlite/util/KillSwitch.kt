package org.torlite.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.util.Log
import org.torlite.Constants

/**
 * Kill Switch implementation for TorLite.
 *
 * When Tor disconnects unexpectedly, the kill switch blocks all
 * internet traffic to prevent data leaks through the clearnet.
 *
 * Implementation strategy:
 * - The VPN TUN interface captures all device traffic
 * - If tor dies, the TUN forwarder stops routing packets
 * - Since the VPN is still active (routes all traffic to TUN),
 *   and TUN is not forwarding, traffic is effectively blocked
 * - No iptables or root required
 *
 * This is the simplest and most reliable kill switch approach
 * for a VpnService-based app. The VPN itself acts as the
 * enforcement mechanism — traffic either goes through Tor or
 * goes nowhere.
 *
 * For non-VPN mode (SOCKS5/HTTP proxy only):
 * - The kill switch cannot enforce blocking at the OS level
 * - We notify the user that Tor has disconnected
 * - Apps using the proxy will simply fail to connect
 * - This is a limitation of not using VPN mode
 */
class KillSwitch(private val context: Context) {

    private val tag = "KillSwitch"
    private var isEnabled = false
    private var isVpnMode = false
    private var onKillSwitchTriggered: (() -> Unit)? = null

    /**
     * Enable or disable the kill switch.
     */
    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
        Log.d(tag, "Kill switch ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Set whether VPN mode is active.
     * Kill switch behavior differs based on VPN mode.
     */
    fun setVpnMode(vpnActive: Boolean) {
        isVpnMode = vpnActive
    }

    /**
     * Register a callback for when the kill switch is triggered.
     */
    fun setOnKillSwitchTriggered(callback: () -> Unit) {
        onKillSwitchTriggered = callback
    }

    /**
     * Called when Tor disconnects unexpectedly.
     *
     * In VPN mode: The TUN interface will stop forwarding, so
     * all traffic is automatically blocked. We just need to
     * update the UI and notification.
     *
     * In non-VPN mode: We can't block traffic at the OS level,
     * so we notify the user and hope they notice.
     */
    fun onTorDisconnected() {
        if (!isEnabled) return

        Log.w(tag, "Tor disconnected — kill switch triggered!")

        if (isVpnMode) {
            // VPN mode: traffic is already blocked since TUN
            // forwarder stopped. Just notify.
            Log.i(tag, "VPN mode active — traffic is blocked by dead TUN forwarder")
        } else {
            // Non-VPN mode: we can't enforce blocking
            Log.w(tag, "Non-VPN mode — cannot enforce traffic blocking. " +
                    "Apps using SOCKS5/HTTP proxy will simply fail to connect.")
        }

        onKillSwitchTriggered?.invoke()
    }

    /**
     * Check if network is available (for diagnostic purposes).
     */
    fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork: NetworkInfo? = cm.activeNetworkInfo
        return activeNetwork?.isConnectedOrConnecting == true
    }

    companion object {
        /**
         * Check if kill switch is enabled in preferences.
         */
        fun isEnabledInPrefs(context: Context): Boolean {
            val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(Constants.PREF_KILL_SWITCH_ENABLED, true) // Default ON
        }

        /**
         * Save kill switch preference.
         */
        fun setEnabledInPrefs(context: Context, enabled: Boolean) {
            val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(Constants.PREF_KILL_SWITCH_ENABLED, enabled).apply()
        }
    }
}
