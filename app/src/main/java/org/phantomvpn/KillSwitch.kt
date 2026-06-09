package org.phantomvpn

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log

/**
 * Kill Switch implementation for PhantomVPN.
 * VPN TUN stays active but SOCKS5 forwarder stops → all traffic blocked.
 */
class KillSwitch(private val context: Context) {

    private val tag = "KillSwitch"
    private var isEnabled = false
    private var isVpnMode = false
    private var onKillSwitchTriggered: (() -> Unit)? = null

    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
        Log.d(tag, "Kill switch ${if (enabled) "enabled" else "disabled"}")
    }

    fun setVpnMode(vpnActive: Boolean) { isVpnMode = vpnActive }

    fun setOnKillSwitchTriggered(callback: () -> Unit) { onKillSwitchTriggered = callback }

    fun onTorDisconnected() {
        if (!isEnabled) return
        Log.w(tag, "Tor disconnected — kill switch triggered!")
        if (isVpnMode) {
            Log.i(tag, "VPN mode active — traffic is blocked by dead TUN forwarder")
        } else {
            Log.w(tag, "Non-VPN mode — cannot enforce traffic blocking")
        }
        onKillSwitchTriggered?.invoke()
    }

    @Suppress("DEPRECATION")
    fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = cm.activeNetworkInfo
        return activeNetwork?.isConnectedOrConnecting == true
    }

    companion object {
        fun isEnabledInPrefs(context: Context): Boolean {
            val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(Constants.PREF_KILL_SWITCH_ENABLED, true)
        }

        fun setEnabledInPrefs(context: Context, enabled: Boolean) {
            val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(Constants.PREF_KILL_SWITCH_ENABLED, enabled).apply()
        }
    }
}
