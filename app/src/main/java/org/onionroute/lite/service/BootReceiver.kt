package org.onionroute.lite.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import org.onionroute.lite.Constants

/**
 * Broadcast receiver that starts Tor on device boot if auto-start is enabled.
 *
 * Checks the [Constants.PREF_AUTO_START] preference. If enabled, launches
 * [TorService] as a foreground service to establish the Tor connection
 * automatically after the device finishes booting.
 *
 * If VPN mode was enabled in the previous session ([Constants.PREF_VPN_ENABLED]),
 * also starts [TorVpnService] so the user's full-device anonymization is
 * restored without manual intervention.
 *
 * NOTE: Android revokes VPN consent on reboot unless the user set "Always-on
 * VPN" in Android Settings → Network → VPN. If consent was revoked,
 * [TorVpnService] will fail to establish the TUN interface (returns null)
 * and only Tor will start. The user can re-enable VPN from the app.
 */
class BootReceiver : BroadcastReceiver() {

    private val tag = "BootReceiver"

    override fun onReceive(context: Context, intent: Intent?) {
        val receivedAction = intent?.action
        if (receivedAction != Intent.ACTION_BOOT_COMPLETED && receivedAction != "android.intent.action.QUICKBOOT_POWERON") {
            Log.w(tag, "Ignoring action: $receivedAction")
            return
        }

        Log.i(tag, "Boot completed — checking auto-start preference")

        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val autoStart = prefs.getBoolean(Constants.PREF_AUTO_START, false)

        if (!autoStart) {
            Log.i(tag, "Auto-start disabled — skipping")
            return
        }

        Log.i(tag, "Auto-start enabled — starting TorService")

        // Start TorService first
        val torIntent = Intent(context, TorService::class.java).apply {
            action = Constants.ACTION_START_TOR
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(torIntent)
            } else {
                context.startService(torIntent)
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to start TorService on boot", e)
        }

        // If VPN mode was enabled before reboot, try to restore it.
        // TorVpnService.ensureTorStarted() will check if Tor is already
        // running (it is — we just started it) and skip re-starting.
        //
        // IMPORTANT: VpnService.Builder.establish() will return null if
        // Android revoked VPN consent on reboot. This is normal — the
        // user needs to re-grant consent from the app (tap the VPN toggle).
        // We can't show a consent dialog from a BroadcastReceiver.
        // The user will see Tor running but no VPN — they can re-enable
        // VPN manually by opening the app and tapping the VPN chip.
        val vpnEnabled = prefs.getBoolean(Constants.PREF_VPN_ENABLED, false)
        if (vpnEnabled) {
            Log.i(tag, "VPN was enabled before reboot — starting TorVpnService")
            val vpnIntent = Intent(context, TorVpnService::class.java).apply {
                action = Constants.ACTION_VPN_ALWAYS_ON
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(vpnIntent)
                } else {
                    context.startService(vpnIntent)
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to start TorVpnService on boot", e)
            }
        }
    }
}
