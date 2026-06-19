package org.onionroute.lite.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import org.onionroute.lite.Constants
import org.onionroute.lite.MainActivity
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Foreground service that runs a Snowflake proxy to help censored users.
 *
 * Kindness Mode allows OnionRoute Lite users to volunteer their connection
 * as a Snowflake proxy. Censored users can then route their Tor traffic
 * through the volunteer's proxy via WebRTC, bypassing network censorship.
 *
 * Responsible for:
 * - Running the snowflake-proxy native binary from the APK's lib directory
 * - Parsing proxy stdout for real-time stats (data donated, clients helped)
 * - Broadcasting stats to the UI via Intent broadcasts
 * - Maintaining a foreground notification while the proxy is active
 * - Stopping the proxy cleanly when Kindness Mode is disabled
 * - Retry with exponential backoff on transient failures (exit code 2)
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
    private var stdoutThread: Thread? = null

    private val dataDonated = AtomicLong(0L)
    private val peopleHelpedNow = AtomicLong(0L)
    private val peopleHelpedTotal = AtomicLong(0L)
    private val consecutiveFailures = AtomicInteger(0)
    /** Last error/fatal line from snowflake-proxy stdout. Included in
     *  the error notification when the process exits non-zero so the
     *  user can see WHY it failed (e.g. 'broker unreachable', 'STUN
     *  timeout', 'invalid flag') without needing logcat. */
    private val lastProxyError = java.util.concurrent.atomic.AtomicReference("")

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                Constants.KINDNESS_NOTIFICATION_ID,
                buildNotification("Starting Snowflake proxy…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForeground(
                Constants.KINDNESS_NOTIFICATION_ID,
                buildNotification("Starting Snowflake proxy\u2026")
            )
        }

        when (intent?.action) {
            Constants.ACTION_START_KINDNESS -> {
                consecutiveFailures.set(0)
                startProxy()
            }
            Constants.ACTION_STOP_KINDNESS -> stopProxy()
            else -> {
                consecutiveFailures.set(0)
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

        Log.i(tag, "Starting Snowflake proxy\u2026")

        try {
            val proxyBinary = getProxyBinary()
            if (proxyBinary == null) {
                Log.e(tag, "Failed to locate snowflake-proxy binary")
                updateNotification("Snowflake proxy \u2014 binary not found")
                broadcastKindnessStatus(false)
                return
            }

            // Build command with correct snowflake-proxy v2.10.1 flags.
            // CORRECT FLAGS (from --help):
            //   -broker URL     — broker URL (required)
            //   -stun URL       — comma-separated STUN servers (NOT -ice)
            //   -relay URL      — WebSocket relay URL (defaults to wss://snowflake.torproject.net/)
            //   -capacity N     — max concurrent clients
            //   -unsafe-logging — show IP addresses in logs for stats parsing
            //
            // DO NOT USE: -front (removed in v2), -ice (renamed to -stun),
            //   -log info (takes filename not level)
            val processBuilder = ProcessBuilder(
                proxyBinary.absolutePath,
                "-broker", Constants.SNOWFLAKE_BROKER_URL,
                "-stun", Constants.SNOWFLAKE_ICE_SERVERS,
                "-relay", "wss://snowflake.torproject.net/",
                "-capacity", "5",
                "-unsafe-logging"
            )

            val appDataDir = filesDir.absolutePath
            processBuilder.environment()["HOME"] = appDataDir
            processBuilder.redirectErrorStream(true)

            proxyProcess = processBuilder.start()

            // Capture last error line for better error notifications.
            // The stdout reader (startStdoutReader) parses stats; we also
            // stash the last 'error'/'fatal' line so the process monitor
            // can include it in the notification when the proxy exits.
            lastProxyError.set("")

            // Record session start time for uptime calculation
            val prefs = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)
            if (prefs.getLong(Constants.PREF_KINDNESS_SESSION_START, 0L) == 0L) {
                prefs.edit().putLong(Constants.PREF_KINDNESS_SESSION_START, System.currentTimeMillis()).apply()
            }

            // Restore accumulated stats from preferences
            dataDonated.set(prefs.getLong(Constants.PREF_KINDNESS_DATA_DONATED, 0L))
            peopleHelpedTotal.set(prefs.getLong(Constants.PREF_KINDNESS_PEOPLE_HELPED_TOTAL, 0L))

            startStdoutReader()
            startProcessMonitor()

            updateNotification("Helping censored users \u2014 Snowflake proxy active")
            broadcastKindnessStatus(true)
            broadcastKindnessStats()

            Log.i(tag, "Snowflake proxy started (PID: ${getPid()})")

        } catch (e: Exception) {
            Log.e(tag, "Failed to start Snowflake proxy", e)
            val errMsg = e.message?.take(150) ?: "Unknown error"
            updateNotification("Snowflake proxy \u2014 error: $errMsg")
            broadcastKindnessStatus(false)
        }
    }

    /**
     * Stops the Snowflake proxy and removes the foreground notification.
     */
    private fun stopProxy() {
        // Persist final stats before stopping
        persistStats()
        stopProxyInternal()
        updateNotification("Snowflake proxy stopped")
        broadcastKindnessStatus(false)

        // Persist disabled state and clear session
        val prefs = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)
        prefs.edit()
            .putBoolean(Constants.PREF_KINDNESS_ENABLED, false)
            .putLong(Constants.PREF_KINDNESS_SESSION_START, 0L)
            .apply()

        // Stop the service entirely
        stopSelf()
    }

    private fun stopProxyInternal() {
        try {
            stdoutThread?.interrupt()
            stdoutThread = null

            monitorThread?.interrupt()
            monitorThread = null

            proxyProcess?.destroy()
            proxyProcess = null

            // Reset active connection count since proxy is stopped
            peopleHelpedNow.set(0)

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
     * to nativeLibraryDir on install. We execute them directly from there.
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

        if (!binary.canExecute()) {
            binary.setExecutable(true, false)
        }
        Log.d(tag, "Found snowflake-proxy binary: ${binary.absolutePath}")
        return binary
    }

    // ── Stdout Reader ────────────────────────────────────────────

    /**
     * Reads the proxy's stdout line-by-line and extracts stats.
     *
     * Snowflake proxy outputs lines like:
     *   "proxy: client 1 connected"
     *   "proxy: client 1 disconnected, data relayed: 1234567 bytes"
     *   "proxy: snowflake proxy started"
     *   "proxy: error: ..."
     *
     * We parse these to track:
     * - Number of currently connected clients (people being helped right now)
     * - Total number of clients helped
     * - Total data donated (bytes relayed)
     */
    private fun startStdoutReader() {
        stdoutThread = Thread({
            try {
                val process = proxyProcess ?: return@Thread
                val reader = BufferedReader(InputStreamReader(process.inputStream), 4096)
                var line: String?

                while (reader.readLine().also { line = it } != null) {
                    line?.let { parseProxyLine(it) }
                }
            } catch (e: InterruptedException) {
                Log.d(tag, "Stdout reader interrupted")
            } catch (e: Exception) {
                Log.d(tag, "Stdout reader ended: ${e.message}")
            }
        }, "SnowflakeProxyStdout")
        stdoutThread?.isDaemon = true
        stdoutThread?.start()
    }

    /**
     * Parses a single line of proxy stdout for stats.
     *
     * Known patterns from snowflake-proxy v2:
     *   "proxy: client <n> connected"                              -> increment current count
     *   "proxy: client <n> disconnected, data relayed: <bytes> B"  -> decrement current, add data
     *   "proxy: In the snowflake proxy session, ... data relayed: <bytes>"  -> session total
     */
    private fun parseProxyLine(line: String) {
        Log.d(tag, "Proxy: $line")

        val trimmed = line.trim().lowercase()

        // Capture error/fatal lines so the process monitor can include
        // them in the error notification when the proxy exits.
        if (trimmed.contains("error") || trimmed.contains("fatal") ||
            trimmed.contains("panic") || trimmed.contains("failed to")) {
            // Truncate to avoid Notification text overflow
            lastProxyError.set(line.take(200))
        }

        // Client connected
        if (trimmed.contains("connected") && !trimmed.contains("disconnected")) {
            peopleHelpedNow.incrementAndGet()
            peopleHelpedTotal.incrementAndGet()
            broadcastKindnessStats()
        }

        // Client disconnected — look for data relayed
        if (trimmed.contains("disconnected")) {
            peopleHelpedNow.getAndUpdate { (it - 1).coerceAtLeast(0) }
            // Extract bytes: "data relayed: 1234567 B" or "data relayed: 1234567 bytes"
            val dataMatch = Regex("""data\s+relayed:\s+(\d+)""").find(trimmed)
            dataMatch?.groupValues?.get(1)?.toLongOrNull()?.let { bytes ->
                dataDonated.addAndGet(bytes)
            }
            broadcastKindnessStats()
        }

        // Session summary data
        if (trimmed.contains("data relayed:") && trimmed.contains("session")) {
            val dataMatch = Regex("""data\s+relayed:\s+(\d+)""").find(trimmed)
            dataMatch?.groupValues?.get(1)?.toLongOrNull()?.let { bytes ->
                dataDonated.addAndGet(bytes)
            }
            broadcastKindnessStats()
        }

        // Periodic stats update (every ~30s the proxy may report status)
        if (trimmed.contains("bytes") && (trimmed.contains("sent") || trimmed.contains("received"))) {
            val byteMatch = Regex("""(\d+)\s+bytes""").find(trimmed)
            byteMatch?.groupValues?.get(1)?.toLongOrNull()?.let { bytes ->
                // Only add if it looks like a delta, not a cumulative total
                if (bytes < 10_000_000) {
                    dataDonated.addAndGet(bytes)
                }
            }
            broadcastKindnessStats()
        }
    }

    // ── Stats Persistence & Broadcasting ──────────────────────────

    private fun persistStats() {
        try {
            val prefs = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)
            prefs.edit()
                .putLong(Constants.PREF_KINDNESS_DATA_DONATED, dataDonated.get())
                .putLong(Constants.PREF_KINDNESS_PEOPLE_HELPED_NOW, peopleHelpedNow.get().coerceAtLeast(0))
                .putLong(Constants.PREF_KINDNESS_PEOPLE_HELPED_TOTAL, peopleHelpedTotal.get())
                .apply()
        } catch (e: Exception) {
            Log.e(tag, "Failed to persist stats", e)
        }
    }

    private fun broadcastKindnessStats() {
        persistStats()

        val intent = Intent(Constants.ACTION_KINDNESS_STATS).apply {
            putExtra(Constants.EXTRA_KINDNESS_DATA_DONATED, dataDonated.get())
            putExtra(Constants.EXTRA_KINDNESS_PEOPLE_NOW, peopleHelpedNow.get().coerceAtLeast(0))
            putExtra(Constants.EXTRA_KINDNESS_PEOPLE_TOTAL, peopleHelpedTotal.get())
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    // ── Process Monitoring ────────────────────────────────────────

    private fun startProcessMonitor() {
        monitorThread = Thread({
            try {
                val process = proxyProcess ?: return@Thread
                val exitCode = process.waitFor()
                Log.w(tag, "Snowflake proxy exited with code $exitCode")
                proxyProcess = null

                val prefs = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)
                val kindnessEnabled = prefs.getBoolean(Constants.PREF_KINDNESS_ENABLED, false)

                if (kindnessEnabled && exitCode != 0) {
                    val failures = consecutiveFailures.incrementAndGet()

                    // Exponential backoff: 5s, 10s, 20s, 40s... capped at 5 min
                    val delay = (RESTART_DELAY_MS * (1L shl (failures - 1).coerceAtMost(6)))
                        .coerceAtMost(MAX_RESTART_DELAY_MS)

                    Log.i(tag, "Attempting Snowflake proxy restart (attempt $failures, wait ${delay}ms)\u2026")

                    if (failures > MAX_CONSECUTIVE_FAILURES) {
                        Log.w(tag, "Too many consecutive failures ($failures), stopping restart attempts")
                        val errLine = lastProxyError.get()
                        val notifMsg = if (errLine.isNotEmpty()) {
                            "Snowflake proxy \u2014 failed (exit $exitCode): $errLine".take(180)
                        } else {
                            "Snowflake proxy \u2014 failed (exit $exitCode). Tap to retry."
                        }
                        updateNotification(notifMsg)
                        broadcastKindnessStatus(false)
                        return@Thread
                    }

                    Thread.sleep(delay)
                    if (prefs.getBoolean(Constants.PREF_KINDNESS_ENABLED, false)) {
                        startProxy()
                    }
                } else if (exitCode == 0) {
                    // Clean exit — kindness was likely disabled
                    updateNotification("Snowflake proxy stopped")
                    broadcastKindnessStatus(false)
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

        // Show stats in notification if we have them
        val dataStr = formatBytes(dataDonated.get())
        val peopleNow = peopleHelpedNow.get().coerceAtLeast(0)
        val contentText = if (dataDonated.get() > 0 || peopleHelpedTotal.get() > 0) {
            "$text | $dataStr donated, $peopleNow being helped"
        } else {
            text
        }

        return NotificationCompat.Builder(this, Constants.KINDNESS_NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Kindness Mode")
            .setContentText(contentText)
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

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes > 0 -> "$bytes B"
        else -> "0 B"
    }

    companion object {
        private const val RESTART_DELAY_MS = 5000L
        private const val MAX_RESTART_DELAY_MS = 300_000L  // 5 minutes cap
        private const val MAX_CONSECUTIVE_FAILURES = 10

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

        /**
         * Reads the current kindness stats from preferences.
         * Used by UI to restore stats without waiting for a broadcast.
         */
        fun getStats(context: Context): KindnessStats {
            val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            return KindnessStats(
                dataDonated = prefs.getLong(Constants.PREF_KINDNESS_DATA_DONATED, 0L),
                peopleNow = prefs.getLong(Constants.PREF_KINDNESS_PEOPLE_HELPED_NOW, 0L),
                peopleTotal = prefs.getLong(Constants.PREF_KINDNESS_PEOPLE_HELPED_TOTAL, 0L),
                sessionStart = prefs.getLong(Constants.PREF_KINDNESS_SESSION_START, 0L)
            )
        }
    }
}

/**
 * Data class holding kindness mode statistics.
 */
data class KindnessStats(
    val dataDonated: Long = 0L,
    val peopleNow: Long = 0L,
    val peopleTotal: Long = 0L,
    val sessionStart: Long = 0L
)
