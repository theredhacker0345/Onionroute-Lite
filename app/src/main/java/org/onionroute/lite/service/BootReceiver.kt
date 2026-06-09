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
 */
class BootReceiver : BroadcastReceiver() {

    private val tag = "BootReceiver"

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) {
            Log.w(tag, "Ignoring action: ${intent?.action}")
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

        val serviceIntent = Intent(context, TorService::class.java).apply {
            action = Constants.ACTION_START_TOR
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to start TorService on boot", e)
        }
    }
}
