package org.onionroute.lite.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import org.onionroute.lite.Constants
import org.onionroute.lite.MainActivity
import java.io.File

/**
 * Foreground service that runs a Snowflake proxy to help censored users.
 *
 * Kindness Mode allows OnionRoute Lite users to volunteer their connection
 * as a Snowflake proxy. Censored users can then route their Tor traffic
 * through the volunteer's proxy via WebRTC, bypassing network censorship.
 *
 * Responsible for:
 * - Installing the snowflake-proxy native binary from the APK's lib directory
 * - Running the snowflake-proxy process with the Tor Project broker
 * - Maintaining a foreground notification while the proxy is active
 * - Stopping the proxy cleanly when Kindness Mode is disabled
 *
 * The proxy binary is built from the same Snowflake source tree:
 *   gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/snowflake.git/v2/proxy
 *
 * F-Droid notes:
 * - No proprietary dependencies
 * - All network traffic goes to Tor Project infrastructure (broker, STUN)
 * - Default STUN servers are FOSS-friendly (no Google STUN)
 * - User must explicitly opt in via Kindness Mode toggle
 */
class KindnessService : Service() {

    private val tag = "KindnessService"

    private var proxyProcess: Process? = null
    private var monitorThread: Thread? = null

    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    // ── Service Lifecycle ─────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.d(tag, "KindnessService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForeground(
                Constants.KINDNESS_NOTIFICATION_ID,
                buildNotification("Starting Snowflake proxy…")
            )
        }

        when (intent?.action) {
            Constants.ACTION_START_KINDNESS -> startProxy()
            Constants.ACTION_STOP_KINDNESS -> stopProxy()
            else -> {
                // Default: start proxy if service is launched without action
                startProxy()
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopProxyInternal()
        super.onDestroy()
    }

    // ── Proxy Control ─────────────────────────────────────────────

    /**
     * Starts the Snowflake proxy binary as a background process.
     *
     * The proxy connects to the Tor Project broker and registers itself
     * as an available proxy. Censored users' Snowflake clients will
     * discover this proxy via the broker and establish WebRTC connections.
     */
    private fun startProxy() {
        if (proxyProcess != null) {
            Log.w(tag, "Snowflake proxy is already running")
            updateNotification("Snowflake proxy active")
            return
        }

        Log.i(tag, "Starting Snowflake proxy…")

        try {
            // Execute the proxy binary directly from nativeLibraryDir
            val proxyBinary = getProxyBinary()
            if (proxyBinary == null) {
                Log.e(tag, "Failed to locate snowflake-proxy binary")
                updateNotification("Snowflake proxy — binary not found")
                broadcastKindnessStatus(false)
                return
            }

            val processBuilder = ProcessBuilder(
                proxyBinary.absolutePath,
                "-broker", Constants.SNOWFLAKE_BROKER_URL,
                "-front", Constants.SNOWFLAKE_FRONT_DOMAIN,
                "-ice", Constants.SNOWFLAKE_ICE_SERVERS,
                "-log", "info"
            )

            val appDataDir = filesDir.absolutePath
            processBuilder.environment()["HOME"] = appDataDir
            processBuilder.redirectErrorStream(true)

            proxyProcess = processBuilder.start()
            startProcessMonitor()

            updateNotification("Helping censored users — Snowflake proxy active")
            broadcastKindnessStatus(true)

            Log.i(tag, "Snowflake proxy started (PID: ${getPid()})")

        } catch (e: Exception) {
            Log.e(tag, "Failed to start Snowflake proxy", e)
            updateNotification("Snowflake proxy — error")
            broadcastKindnessStatus(false)
        }
    }

    /**
     * Stops the Snowflake proxy and removes the foreground notification.
     */
    private fun stopProxy() {
        stopProxyInternal()
        updateNotification("Snowflake proxy stopped")
        broadcastKindnessStatus(false)

        // Persist disabled state
        val prefs = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)
        prefs.edit().putBoolean(Constants.PREF_KINDNESS_ENABLED, false).apply()

        // Stop the service entirely
        stopSelf()
    }

    private fun stopProxyInternal() {
        try {
            monitorThread?.interrupt()
            monitorThread = null

            proxyProcess?.destroy()
            proxyProcess = null

            Log.i(tag, "Snowflake proxy stopped")
        } catch (e: Exception) {
            Log.e(tag, "Error stopping Snowflake proxy", e)
        }
    }

    // ── Binary Installation ───────────────────────────────────────

    /**
     * Executes the snowflake-proxy binary directly from nativeLibraryDir.
     *
     * With android:extractNativeLibs="true", Android extracts the .so files
     * to nativeLibraryDir on install. We execute them directly from there —
     * no copying to filesDir (which is mounted noexec on Android 10+).
     *
     * @return The executable File in nativeLibraryDir, or null if not found
     */
    private fun getProxyBinary(): File? {
        val nativeLibDir = applicationInfo.nativeLibraryDir
        val binary = File(nativeLibDir, Constants.BINARY_SNOWFLAKE_PROXY)

        if (!binary.exists()) {
            Log.e(tag, "libsnowflake-proxy.so not found in $nativeLibDir")
            return null
        }

        // Ensure execute permission (should already be set, but be safe)
        if (!binary.canExecute()) {
            binary.setExecutable(true, false)
        }
        Log.d(tag, "Found snowflake-proxy binary: ${binary.absolutePath}")
        return binary
    }

    // ── Process Monitoring ────────────────────────────────────────

    private fun startProcessMonitor() {
        monitorThread = Thread({
            try {
                val process = proxyProcess ?: return@Thread
                val exitCode = process.waitFor()
                Log.w(tag, "Snowflake proxy exited with code $exitCode")
                proxyProcess = null

                // Auto-restart if kindness mode is still enabled
                val prefs = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)
                val kindnessEnabled = prefs.getBoolean(Constants.PREF_KINDNESS_ENABLED, false)
                if (kindnessEnabled && exitCode != 0) {
                    Log.i(tag, "Attempting Snowflake proxy restart…")
                    Thread.sleep(RESTART_DELAY_MS)
                    if (prefs.getBoolean(Constants.PREF_KINDNESS_ENABLED, false)) {
                        startProxy()
                    }
                } else {
                    updateNotification("Snowflake proxy stopped")
                    broadcastKindnessStatus(false)
                }
            } catch (e: InterruptedException) {
                Log.d(tag, "Process monitor interrupted")
            } catch (e: Exception) {
                Log.e(tag, "Process monitor error", e)
            }
        }, "SnowflakeProxyMonitor")
        monitorThread?.isDaemon = true
        monitorThread?.start()
    }

    // ── Notification ──────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                Constants.KINDNESS_NOTIFICATION_CHANNEL_ID,
                "Kindness Mode",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent notification for Snowflake proxy service"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, KindnessService::class.java).apply {
            action = Constants.ACTION_STOP_KINDNESS
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, Constants.KINDNESS_NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Kindness Mode")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            notificationManager.notify(
                Constants.KINDNESS_NOTIFICATION_ID,
                buildNotification(text)
            )
        } catch (e: Exception) {
            Log.e(tag, "Failed to update notification", e)
        }
    }

    // ── Broadcast Helpers ─────────────────────────────────────────

    private fun broadcastKindnessStatus(isActive: Boolean) {
        val intent = Intent(Constants.ACTION_KINDNESS_UPDATE).apply {
            putExtra(Constants.EXTRA_KINDNESS_ACTIVE, isActive)
            setPackage(packageName)
        }
        sendBroadcast(intent)
        Log.d(tag, "Kindness status broadcast: ${if (isActive) "active" else "inactive"}")
    }

    // ── Utilities ─────────────────────────────────────────────────

    private fun getPid(): Long {
        return try {
            val p = proxyProcess ?: return -1L
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val method = Process::class.java.getMethod("pid")
                (method.invoke(p) as? Long) ?: -1L
            } else {
                -1L
            }
        } catch (e: Exception) {
            -1L
        }
    }

    companion object {
        private const val RESTART_DELAY_MS = 5000L

        /**
         * Starts the Kindness Mode service (Snowflake proxy).
         */
        fun start(context: Context) {
            val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(Constants.PREF_KINDNESS_ENABLED, true).apply()

            val intent = Intent(context, KindnessService::class.java).apply {
                action = Constants.ACTION_START_KINDNESS
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Stops the Kindness Mode service.
         */
        fun stop(context: Context) {
            val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(Constants.PREF_KINDNESS_ENABLED, false).apply()

            val intent = Intent(context, KindnessService::class.java).apply {
                action = Constants.ACTION_STOP_KINDNESS
            }
            context.startService(intent)
        }

        /**
         * Checks if Kindness Mode is enabled in preferences.
         */
        fun isEnabledInPrefs(context: Context): Boolean {
            val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(Constants.PREF_KINDNESS_ENABLED, false)
        }
    }
}
