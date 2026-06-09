package org.phantomvpn.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.phantomvpn.Constants
import org.phantomvpn.KillSwitch
import org.phantomvpn.MainActivity
import org.phantomvpn.tor.BandwidthTracker
import org.phantomvpn.tor.BridgeConfig
import org.phantomvpn.tor.TorControlConnection
import org.phantomvpn.tor.TorProcessManager

/**
 * Foreground service that manages the Tor daemon lifecycle.
 * Uses Jetpack LifecycleService patterns but extends android.app.Service
 * directly to minimize dependency footprint.
 */
class TorService : Service() {

    private val tag = "TorService"

    private lateinit var processManager: TorProcessManager
    private lateinit var bridgeConfig: BridgeConfig
    private lateinit var bandwidthTracker: BandwidthTracker
    private lateinit var killSwitch: KillSwitch
    private var controlConnection: TorControlConnection? = null

    enum class TorState {
        IDLE, STARTING, BOOTSTRAPPING, CONNECTED, DISCONNECTING, ERROR
    }

    private var torState = TorState.IDLE
    private var isSnowflakeEnabled = false
    private var isVpnActive = false

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var bandwidthUpdateJob: Job? = null
    private var killSwitchJob: Job? = null
    private var periodicSaveJob: Job? = null

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Constants.ACTION_START_TOR -> startTor()
                Constants.ACTION_STOP_TOR -> stopTor()
                Constants.ACTION_TOGGLE_SNOWFLAKE -> toggleSnowflake()
                Constants.ACTION_START_VPN -> onVpnStarted()
                Constants.ACTION_STOP_VPN -> onVpnStopped()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        processManager = TorProcessManager(this)
        bridgeConfig = BridgeConfig(this)
        bandwidthTracker = BandwidthTracker()
        killSwitch = KillSwitch(this)

        isSnowflakeEnabled = bridgeConfig.snowflakeEnabled
        killSwitch.setEnabled(KillSwitch.isEnabledInPrefs(this))

        val prefs = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)
        bandwidthTracker.restoreTotals(
            prefs.getLong(Constants.PREF_LAST_TOTAL_DOWN, 0),
            prefs.getLong(Constants.PREF_LAST_TOTAL_UP, 0)
        )

        val filter = IntentFilter().apply {
            addAction(Constants.ACTION_START_TOR)
            addAction(Constants.ACTION_STOP_TOR)
            addAction(Constants.ACTION_TOGGLE_SNOWFLAKE)
            addAction(Constants.ACTION_START_VPN)
            addAction(Constants.ACTION_STOP_VPN)
        }
        // API 33+ requires RECEIVER_EXPORTED/NOT_EXPORTED flag
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(commandReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(commandReceiver, filter)
        }

        createNotificationChannel()

        killSwitch.setOnKillSwitchTriggered {
            torState = TorState.ERROR
            updateNotification("Tor disconnected — kill switch active")
            broadcastStatus()
            stopVpnService()
        }

        Log.i(tag, "TorService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification("Starting Tor...")

        // Handle foreground service type for API 34+
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(Constants.NOTIFICATION_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(Constants.NOTIFICATION_ID, notification)
        }

        if (intent?.action == Constants.ACTION_START_TOR) startTor()
        return START_STICKY
    }

    override fun onDestroy() {
        saveBandwidthTotals()
        killSwitchJob?.cancel()
        bandwidthUpdateJob?.cancel()
        periodicSaveJob?.cancel()
        controlConnection?.disconnect()
        processManager.stopAll()
        serviceScope.cancel()
        try { unregisterReceiver(commandReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = null

    // ══════════════════════════════════════════════════════════
    // Tor Lifecycle
    // ══════════════════════════════════════════════════════════

    private fun startTor() {
        if (torState == TorState.STARTING || torState == TorState.BOOTSTRAPPING) return

        serviceScope.launch {
            try {
                torState = TorState.STARTING
                updateNotification("Installing binaries...")
                broadcastStatus()

                if (!processManager.installBinaries()) {
                    torState = TorState.ERROR
                    updateNotification("Failed to install binaries")
                    broadcastStatus()
                    return@launch
                }

                updateNotification("Writing configuration...")
                if (!processManager.writeTorrc(bridgeConfig)) {
                    torState = TorState.ERROR
                    updateNotification("Failed to write config")
                    broadcastStatus()
                    return@launch
                }

                updateNotification("Starting Tor daemon...")
                if (!processManager.startTor()) {
                    torState = TorState.ERROR
                    updateNotification("Failed to start Tor")
                    broadcastStatus()
                    return@launch
                }

                torState = TorState.BOOTSTRAPPING
                updateNotification("Connecting to Tor network...")
                broadcastStatus()

                val bootstrapped = waitForBootstrap()
                if (!bootstrapped) {
                    torState = TorState.ERROR
                    updateNotification("Tor bootstrap failed — check network")
                    broadcastStatus()
                    return@launch
                }

                connectControlPort()
                startBandwidthUpdates()
                startKillSwitchMonitor()
                startPeriodicSave()

                torState = TorState.CONNECTED
                updateNotification("Tor connected — browsing is anonymous")
                broadcastStatus()
                Log.i(tag, "Tor started successfully")

            } catch (e: Exception) {
                Log.e(tag, "Failed to start Tor", e)
                torState = TorState.ERROR
                updateNotification("Tor failed: ${e.message}")
                broadcastStatus()
            }
        }
    }

    private fun stopTor() {
        if (torState == TorState.IDLE || torState == TorState.DISCONNECTING) return

        serviceScope.launch {
            try {
                torState = TorState.DISCONNECTING
                updateNotification("Disconnecting Tor...")
                broadcastStatus()

                if (isVpnActive) stopVpnService()

                controlConnection?.disconnect()
                controlConnection = null
                processManager.stopAll()

                killSwitchJob?.cancel()
                bandwidthUpdateJob?.cancel()
                periodicSaveJob?.cancel()
                bandwidthTracker.markIdle()
                saveBandwidthTotals()

                torState = TorState.IDLE
                updateNotification("Tor disconnected")
                broadcastStatus()
                Log.i(tag, "Tor stopped")

            } catch (e: Exception) {
                Log.e(tag, "Error stopping Tor", e)
                torState = TorState.ERROR
                broadcastStatus()
            }
        }
    }

    private suspend fun waitForBootstrap(): Boolean {
        val cookiePath = "${processManager.torDataDir}/${Constants.TOR_CONTROL_COOKIE}"
        var retries = 0

        while (retries < 90) {
            delay(1000)
            if (!processManager.isTorRunning()) return false
            try {
                val cookieFile = java.io.File(cookiePath)
                if (cookieFile.exists() && cookieFile.length() > 0) return true
            } catch (_: Exception) {}
            retries++
            if (retries % 10 == 0) {
                updateNotification("Bootstrapping Tor... ~${retries * 100 / 90}%")
            }
        }
        return processManager.isTorRunning()
    }

    private fun connectControlPort() {
        val cookiePath = "${processManager.torDataDir}/${Constants.TOR_CONTROL_COOKIE}"
        controlConnection = TorControlConnection()
        controlConnection?.connect(cookiePath, object : TorControlConnection.ControlPortListener {
            override fun onBandwidthUpdate(bytesRead: Long, bytesWritten: Long) {
                bandwidthTracker.onBandwidthEvent(bytesRead, bytesWritten)
            }
            override fun onCircuitStatus(status: String, circuitId: String, path: String) {
                Log.d(tag, "Circuit $circuitId: $status")
            }
            override fun onStatusChanged(status: String) {
                Log.d(tag, "Tor status: $status")
                if (status.contains("BOOTSTRAP 100%")) {
                    torState = TorState.CONNECTED
                    updateNotification("Tor connected — browsing is anonymous")
                    broadcastStatus()
                }
            }
            override fun onError(message: String) {
                Log.e(tag, "Control port error: $message")
                if (torState == TorState.CONNECTED) killSwitch.onTorDisconnected()
            }
        })
    }

    private fun toggleSnowflake() {
        isSnowflakeEnabled = !isSnowflakeEnabled
        bridgeConfig.snowflakeEnabled = isSnowflakeEnabled
        if (torState == TorState.CONNECTED) {
            serviceScope.launch {
                stopTor()
                delay(2000)
                startTor()
            }
        }
    }

    private fun onVpnStarted() {
        isVpnActive = true
        killSwitch.setVpnMode(true)
    }

    private fun onVpnStopped() {
        isVpnActive = false
        killSwitch.setVpnMode(false)
    }

    private fun stopVpnService() {
        val intent = Intent(this, TorVpnService::class.java)
        intent.action = Constants.ACTION_STOP_VPN
        startService(intent)
        onVpnStopped()
    }

    // ══════════════════════════════════════════════════════════
    // Bandwidth & Kill Switch Monitoring
    // ══════════════════════════════════════════════════════════

    private fun startBandwidthUpdates() {
        bandwidthUpdateJob?.cancel()
        bandwidthUpdateJob = serviceScope.launch {
            while (isActive) {
                val snapshot = bandwidthTracker.getSnapshot()
                val intent = Intent(Constants.ACTION_BANDWIDTH_UPDATE).apply {
                    putExtra(Constants.EXTRA_SPEED_DOWN, snapshot.speedDown)
                    putExtra(Constants.EXTRA_SPEED_UP, snapshot.speedUp)
                    putExtra(Constants.EXTRA_BYTES_DOWN, snapshot.totalBytesDown)
                    putExtra(Constants.EXTRA_BYTES_UP, snapshot.totalBytesUp)
                    setPackage(packageName)
                }
                sendBroadcast(intent)
                updateNotification("Tor: ↓${snapshot.speedDownFormatted} ↑${snapshot.speedUpFormatted}")
                delay(1000)
            }
        }
    }

    private fun startKillSwitchMonitor() {
        killSwitchJob?.cancel()
        killSwitchJob = serviceScope.launch {
            while (isActive) {
                delay(Constants.KILLSWITCH_CHECK_INTERVAL_MS)
                if (torState == TorState.CONNECTED && !processManager.isTorRunning()) {
                    Log.w(tag, "Tor process died unexpectedly!")
                    killSwitch.onTorDisconnected()
                }
            }
        }
    }

    private fun startPeriodicSave() {
        periodicSaveJob?.cancel()
        periodicSaveJob = serviceScope.launch {
            while (isActive) {
                delay(30_000)
                saveBandwidthTotals()
            }
        }
    }

    private fun saveBandwidthTotals() {
        try {
            val snapshot = bandwidthTracker.getSnapshot()
            val prefs = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)
            prefs.edit()
                .putLong(Constants.PREF_LAST_TOTAL_DOWN, snapshot.totalBytesDown)
                .putLong(Constants.PREF_LAST_TOTAL_UP, snapshot.totalBytesUp)
                .apply()
        } catch (e: Exception) {
            Log.e(tag, "Failed to save bandwidth totals", e)
        }
    }

    // ══════════════════════════════════════════════════════════
    // Notification
    // ══════════════════════════════════════════════════════════

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                Constants.NOTIFICATION_CHANNEL_ID,
                "PhantomVPN Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows Tor connection status and bandwidth"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val disconnectIntent = Intent(this, TorService::class.java).apply {
            action = Constants.ACTION_STOP_TOR
        }
        val disconnectPending = PendingIntent.getService(
            this, 1, disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = Notification.Builder(this)
            .setContentTitle("PhantomVPN")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pendingIntent)
            .setOngoing(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setChannelId(Constants.NOTIFICATION_CHANNEL_ID)
        } else {
            builder.setPriority(Notification.PRIORITY_LOW)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Disconnect",
                disconnectPending
            )
        }

        return builder.build()
    }

    private fun updateNotification(text: String) {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(Constants.NOTIFICATION_ID, buildNotification(text))
        } catch (e: Exception) {
            Log.e(tag, "Failed to update notification", e)
        }
    }

    // ══════════════════════════════════════════════════════════
    // Broadcast
    // ══════════════════════════════════════════════════════════

    private fun broadcastStatus() {
        val statusString = when (torState) {
            TorState.IDLE -> "DISCONNECTED"
            TorState.STARTING -> "STARTING"
            TorState.BOOTSTRAPPING -> "CONNECTING"
            TorState.CONNECTED -> "CONNECTED"
            TorState.DISCONNECTING -> "DISCONNECTING"
            TorState.ERROR -> "ERROR"
        }
        val intent = Intent(Constants.ACTION_STATUS_UPDATE).apply {
            putExtra(Constants.EXTRA_TOR_STATUS, statusString)
            putExtra(Constants.EXTRA_SNOWFLAKE_STATUS, isSnowflakeEnabled)
            putExtra(Constants.EXTRA_VPN_STATUS, isVpnActive)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }
}
