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
import org.onionroute.lite.KillSwitch
import org.onionroute.lite.MainActivity
import org.onionroute.lite.tor.BandwidthTracker
import org.onionroute.lite.tor.BridgeConfig
import org.onionroute.lite.tor.TorControlConnection
import org.onionroute.lite.tor.TorProcessManager

/**
 * Foreground service that manages the Tor daemon lifecycle.
 *
 * Responsible for:
 * - Starting/stopping the native Tor process via [TorProcessManager]
 * - Establishing control port connection via [TorControlConnection]
 * - Monitoring bandwidth via [BandwidthTracker]
 * - Managing bridge configuration via [BridgeConfig]
 * - Enforcing kill switch via [KillSwitch]
 * - Broadcasting status & bandwidth updates to the UI
 * - Running as a foreground service with persistent notification
 */
class TorService : Service() {

    private val tag = "TorService"

    private var torProcessManager: TorProcessManager? = null
    private var torControlConnection: TorControlConnection? = null
    private var bandwidthTracker: BandwidthTracker? = null
    private var bridgeConfig: BridgeConfig? = null
    private var killSwitch: KillSwitch? = null

    private var isTorRunning = false
    private var isSnowflakeEnabled = false
    private var isObfs4Enabled = false
    private var isWebTunnelEnabled = false

    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    // ── Service Lifecycle ─────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        killSwitch = KillSwitch(this)
        killSwitch?.setOnKillSwitchTriggered {
            broadcastStatus("ERROR", "Kill switch triggered — Tor disconnected unexpectedly")
        }
        bridgeConfig = BridgeConfig(this)
        bandwidthTracker = BandwidthTracker { down, up, totalDown, totalUp ->
            broadcastBandwidth(down, up, totalDown, totalUp)
        }
        Log.d(tag, "TorService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForeground(Constants.NOTIFICATION_ID, buildNotification("Starting Tor…"))
        }

        when (intent?.action) {
            Constants.ACTION_START_TOR -> startTor()
            Constants.ACTION_STOP_TOR -> stopTor()
            Constants.ACTION_STOP_VPN -> handleVpnStop()
            Constants.ACTION_TOGGLE_SNOWFLAKE -> toggleSnowflake()
            Constants.ACTION_TOGGLE_OBFS4 -> toggleObfs4()
            Constants.ACTION_TOGGLE_WEBTUNNEL -> toggleWebTunnel()
            else -> Log.w(tag, "Unknown action: ${intent?.action}")
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopTorInternal()
        super.onDestroy()
    }

    // ── Tor Control ───────────────────────────────────────────────

    private fun startTor() {
        if (isTorRunning) {
            Log.w(tag, "Tor is already running")
            broadcastStatus("CONNECTED")
            return
        }

        Log.i(tag, "Starting Tor daemon…")
        broadcastStatus("CONNECTING")

        try {
            val prefs = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)
            isSnowflakeEnabled = prefs.getBoolean(Constants.PREF_SNOWFLAKE_ENABLED, false)
            isObfs4Enabled = prefs.getBoolean(Constants.PREF_OBFS4_ENABLED, false)
            isWebTunnelEnabled = prefs.getBoolean(Constants.PREF_WEBTUNNEL_ENABLED, false)

            torProcessManager = TorProcessManager(this)
            val configResult = torProcessManager!!.startTor(
                useSnowflake = isSnowflakeEnabled,
                useObfs4 = isObfs4Enabled,
                useWebTunnel = isWebTunnelEnabled
            )

            if (!configResult) {
                Log.e(tag, "Failed to start Tor process")
                broadcastStatus("ERROR", "Failed to start Tor process")
                return
            }

            // Connect to Tor control port
            torControlConnection = TorControlConnection(this)
            val connected = torControlConnection!!.connect(
                Constants.TOR_CONTROL_PORT,
                torProcessManager!!.controlCookieFile
            )

            if (!connected) {
                Log.e(tag, "Failed to connect to Tor control port")
                broadcastStatus("ERROR", "Failed to connect to Tor control port")
                return
            }

            // Start bandwidth monitoring
            bandwidthTracker?.start(torControlConnection!!)

            isTorRunning = true
            killSwitch?.setVpnMode(isVpnActive())
            broadcastStatus("CONNECTED")
            updateNotification("OnionRoute Lite — Connected")

            Log.i(tag, "Tor started successfully")

        } catch (e: Exception) {
            Log.e(tag, "Error starting Tor", e)
            broadcastStatus("ERROR", e.message ?: "Unknown error starting Tor")
        }
    }

    private fun stopTor() {
        stopTorInternal()
        broadcastStatus("DISCONNECTED")
        updateNotification("OnionRoute Lite — Disconnected")
    }

    private fun stopTorInternal() {
        if (!isTorRunning) return

        Log.i(tag, "Stopping Tor daemon…")

        bandwidthTracker?.stop()
        torControlConnection?.disconnect()
        torProcessManager?.stopTor()

        torProcessManager = null
        torControlConnection = null
        isTorRunning = false

        killSwitch?.onTorDisconnected()
        Log.i(tag, "Tor stopped")
    }

    private fun toggleSnowflake() {
        isSnowflakeEnabled = !isSnowflakeEnabled
        val prefs = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)
        prefs.edit().putBoolean(Constants.PREF_SNOWFLAKE_ENABLED, isSnowflakeEnabled).apply()
        Log.i(tag, "Snowflake ${if (isSnowflakeEnabled) "enabled" else "disabled"}")

        // Restart Tor with new bridge config if running
        if (isTorRunning) {
            stopTorInternal()
            startTor()
        }
    }

    private fun toggleObfs4() {
        isObfs4Enabled = !isObfs4Enabled
        val prefs = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)
        prefs.edit().putBoolean(Constants.PREF_OBFS4_ENABLED, isObfs4Enabled).apply()
        Log.i(tag, "obfs4 ${if (isObfs4Enabled) "enabled" else "disabled"}")

        // Restart Tor with new bridge config if running
        if (isTorRunning) {
            stopTorInternal()
            startTor()
        }
    }

    private fun toggleWebTunnel() {
        isWebTunnelEnabled = !isWebTunnelEnabled
        val prefs = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)
        prefs.edit().putBoolean(Constants.PREF_WEBTUNNEL_ENABLED, isWebTunnelEnabled).apply()
        Log.i(tag, "WebTunnel ${if (isWebTunnelEnabled) "enabled" else "disabled"}")

        // Restart Tor with new bridge config if running
        if (isTorRunning) {
            stopTorInternal()
            startTor()
        }
    }

    private fun handleVpnStop() {
        killSwitch?.setVpnMode(false)
        Log.d(tag, "VPN stop signal received")
    }

    // ── Notification ──────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                Constants.NOTIFICATION_CHANNEL_ID,
                "OnionRoute Lite Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent notification for OnionRoute Lite Tor service"
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

        val stopIntent = Intent(this, TorService::class.java).apply {
            action = Constants.ACTION_STOP_TOR
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("OnionRoute Lite")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disconnect", stopPendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            notificationManager.notify(Constants.NOTIFICATION_ID, buildNotification(text))
        } catch (e: Exception) {
            Log.e(tag, "Failed to update notification", e)
        }
    }

    // ── Broadcast Helpers ─────────────────────────────────────────

    private fun broadcastStatus(status: String, errorMessage: String? = null) {
        val intent = Intent(Constants.ACTION_STATUS_UPDATE).apply {
            putExtra(Constants.EXTRA_TOR_STATUS, status)
            errorMessage?.let { putExtra("error_message", it) }
            setPackage(packageName)
        }
        sendBroadcast(intent)
        Log.d(tag, "Status broadcast: $status")
    }

    private fun broadcastBandwidth(speedDown: Long, speedUp: Long, totalDown: Long, totalUp: Long) {
        val intent = Intent(Constants.ACTION_BANDWIDTH_UPDATE).apply {
            putExtra(Constants.EXTRA_SPEED_DOWN, speedDown)
            putExtra(Constants.EXTRA_SPEED_UP, speedUp)
            putExtra(Constants.EXTRA_BYTES_DOWN, totalDown)
            putExtra(Constants.EXTRA_BYTES_UP, totalUp)
            setPackage(packageName)
        }
        sendBroadcast(intent)

        // Update notification with bandwidth info
        if (isTorRunning) {
            val downStr = formatSpeed(speedDown)
            val upStr = formatSpeed(speedUp)
            updateNotification("↓ $downStr  ↑ $upStr")
        }
    }

    // ── Utilities ─────────────────────────────────────────────────

    private fun isVpnActive(): Boolean {
        val prefs = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)
        return prefs.getBoolean(Constants.PREF_VPN_ENABLED, false)
    }

    private fun formatSpeed(bytesPerSec: Long): String = when {
        bytesPerSec >= 1_048_576 -> "%.1f MB/s".format(bytesPerSec / 1_048_576.0)
        bytesPerSec >= 1024 -> "%.1f KB/s".format(bytesPerSec / 1024.0)
        bytesPerSec > 0 -> "$bytesPerSec B/s"
        else -> "0 KB/s"
    }
}
