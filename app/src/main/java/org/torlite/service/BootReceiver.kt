package org.torlite.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.torlite.Constants
import android.util.Log

/**
 * BroadcastReceiver that auto-starts Tor on device boot.
 *
 * This receiver listens for the BOOT_COMPLETED broadcast and
 * starts TorService if the user has enabled auto-start in
 * preferences. This ensures Tor protection is active immediately
 * after the device restarts, without requiring manual intervention.
 *
 * F-Droid note: This receiver is declared in the manifest with
 * the BOOT_COMPLETED permission, which is a standard Android
 * permission and does not trigger any Anti-Feature flags.
 *
 * The receiver starts TorService as a foreground service, which
 * is required on Android 6.0+ to prevent the service from being
 * killed by the system. The service will show a persistent
 * notification as required by Android's foreground service rules.
 */
class BootReceiver : BroadcastReceiver() {

    private val tag = "BootReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        // Check if auto-start is enabled
        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val autoStart = prefs.getBoolean(Constants.PREF_AUTO_START, false)

        if (!autoStart) {
            Log.d(tag, "Auto-start disabled, skipping")
            return
        }

        Log.i(tag, "Auto-starting Tor service on boot")

        try {
            val serviceIntent = Intent(context, TorService::class.java).apply {
                action = Constants.ACTION_START_TOR
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to auto-start Tor on boot", e)
        }
    }
}
