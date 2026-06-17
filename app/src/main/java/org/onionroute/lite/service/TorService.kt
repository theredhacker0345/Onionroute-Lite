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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
 *
 * Threading:
 *   All Tor operations (process start, control port connect, etc.) run on
 *   [Dispatchers.IO] via [serviceScope] to avoid NetworkOnMainThreadException
 *   and StrictMode violations. The main thread is only used for UI broadcasts
 *   and notification updates.
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

    /** Periodic job that fetches the Tor exit IP every 15s and broadcasts
     *  updates to the UI. Cancelled in stopTorInternal(). */
    private var exitIpUpdateJob: Job? = null
    private var lastExitIp: String = ""

    /** Coroutine scope tied to this service's lifecycle. Uses SupervisorJob so
     *  one failed child coroutine doesn't cancel siblings. */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(Constants.NOTIFICATION_ID, buildNotification("Starting Tor…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForeground(Constants.NOTIFICATION_ID, buildNotification("Starting Tor…"))
        }

        when (intent?.action) {
            Constants.ACTION_START_TOR -> startTorAsync()
            Constants.ACTION_STOP_TOR -> stopTor()
            Constants.ACTION_STOP_VPN -> handleVpnStop()
            Constants.ACTION_TOGGLE_SNOWFLAKE -> toggleSnowflake()
            Constants.ACTION_TOGGLE_OBFS4 -> toggleObfs4()
            Constants.ACTION_TOGGLE_WEBTUNNEL -> toggleWebTunnel()
            Constants.ACTION_NEW_CIRCUIT -> requestNewCircuit()
            else -> Log.w(tag, "Unknown action: ${intent?.action}")
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopTorInternal()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ── Tor Control ───────────────────────────────────────────────

    /**
     * Launches [startTor] on [Dispatchers.IO] to avoid NetworkOnMainThreadException.
     * All socket I/O (control port connection, bandwidth events) must happen off
     * the main thread — Android StrictMode forbids network access on the UI thread.
     */
    private fun startTorAsync() {
        serviceScope.launch {
            startTor()
        }
    }

    /**
     * Starts the Tor daemon and connects to the control port.
     * MUST be called from a background thread (via [startTorAsync]) because it
     * performs blocking socket I/O to the Tor control port.
     */
    private suspend fun startTor() {
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

            torProcessManager = TorProcessManager(this@TorService)
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

            // Wait for Tor to initialize and open the control port.
            // The daemon needs time to bind the control port after launch.
            // Retry with exponential backoff: 500ms, 1s, 1.5s, 2s, 2.5s, 3s.
            val maxRetries = 6
            var connected = false

            for (attempt in 1..maxRetries) {
                delay(attempt * 500L)

                try {
                    torControlConnection = TorControlConnection(this@TorService)
                    connected = torControlConnection!!.connect(
                        Constants.TOR_CONTROL_PORT,
                        torProcessManager!!.controlCookieFile
                    )
                    if (connected) break
                } catch (e: Exception) {
                    Log.d(tag, "Control port attempt $attempt failed: ${e.message}")
                }

                torControlConnection?.disconnect()
                torControlConnection = null
            }

            if (!connected) {
                Log.e(tag, "Failed to connect to Tor control port after $maxRetries attempts")
                broadcastStatus("ERROR", "Failed to connect to Tor control port")
                return
            }

            // Start bandwidth monitoring + bootstrap listener.
            // We do NOT broadcast CONNECTED yet — Tor has only just opened its
            // control port; it still needs to download consensus, build circuits,
            // and establish a path through the Tor network (bootstrap, 0→100%).
            // Premature "CONNECTED" was the previous behaviour — the UI would
            // show CONNECTED while Tor was actually still bootstrapping (or had
            // failed to bootstrap at all).
            val bootstrapLatch = CountDownLatch(1)
            val lastProgress = AtomicInteger(0)

            bandwidthTracker?.start(torControlConnection!!) { progress, bootstrapTag ->
                Log.i(tag, "Bootstrap: $progress% ($bootstrapTag)")
                lastProgress.set(progress)
                // Broadcast intermediate progress so the UI shows "CONNECTING 45%"
                broadcastBootstrapProgress(progress)
                if (progress >= 100) {
                    bootstrapLatch.countDown()
                }
            }

            // Wait up to 120 seconds for Tor to reach 100% bootstrap.
            // Tor on a clean connection typically bootstraps in 5–30s; 120s
            // gives headroom for slow networks or bridge connections.
            val bootstrapped = bootstrapLatch.await(120, TimeUnit.SECONDS)
            if (!bootstrapped) {
                Log.e(tag, "Tor bootstrap timed out at ${lastProgress.get()}% after 120s")
                broadcastStatus("ERROR", "Tor bootstrap timed out at ${lastProgress.get()}%")
                stopTorInternal()
                return
            }

            isTorRunning = true
            killSwitch?.setVpnMode(isVpnActive())
            broadcastStatus("CONNECTED")
            updateNotification("OnionRoute Lite — Connected")

            // Start periodic exit-IP polling (every 15s). Broadcasts the
            // Tor exit IP to the UI so the user can see which exit node
            // they're currently using, and notice when it changes (e.g.,
            // after a NEWNYM signal rotates the circuit).
            startExitIpUpdates()

            Log.i(tag, "Tor bootstrap complete — CONNECTED")

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

        // Stop the exit-IP polling job and clear the cached value so
        // the UI doesn't show a stale IP after disconnect.
        exitIpUpdateJob?.cancel()
        exitIpUpdateJob = null
        lastExitIp = ""

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
            startTorAsync()
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
            startTorAsync()
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
            startTorAsync()
        }
    }

    private fun handleVpnStop() {
        killSwitch?.setVpnMode(false)
        Log.d(tag, "VPN stop signal received")
    }

    /**
     * Sends a NEWNYM signal to Tor to establish a new circuit (new exit node).
     * Called from the Quick Settings "New Circuit" tile via broadcast.
     */
    private fun requestNewCircuit() {
        if (!isTorRunning) {
            Log.w(tag, "Cannot request new circuit — Tor not running")
            return
        }

        serviceScope.launch {
            try {
                val result = torControlConnection?.signal("NEWNYM")
                if (result == true) {
                    Log.i(tag, "New circuit requested (NEWNYM signal sent)")
                    updateNotification("OnionRoute Lite — New circuit")
                } else {
                    Log.w(tag, "NEWNYM signal failed — control connection may be closed")
                }
            } catch (e: Exception) {
                Log.e(tag, "Error sending NEWNYM signal", e)
            }
        }
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

        // Persist connection state to SharedPreferences so the Quick
        // Settings tile (and other components) can read the current state
        // even if they weren't listening when this broadcast was sent.
        // The tile only registers its receiver in onStartListening (when
        // visible in the QS panel) — without this prefs write, the tile
        // would show stale state when Tor was started from the app while
        // the tile wasn't visible.
        val connected = when (status) {
            "CONNECTED" -> true
            "DISCONNECTED", "ERROR" -> false
            else -> return  // CONNECTING, BOOTSTRAPPING — don't update prefs
        }
        getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(Constants.PREF_TOR_CONNECTED, connected)
            .apply()
    }

    /**
     * Broadcasts Tor bootstrap progress (0..100) so the UI can show
     * "CONNECTING 45%" while Tor is building circuits.
     */
    private fun broadcastBootstrapProgress(progress: Int) {
        val intent = Intent(Constants.ACTION_STATUS_UPDATE).apply {
            putExtra(Constants.EXTRA_TOR_STATUS, "BOOTSTRAPPING")
            putExtra(Constants.EXTRA_BOOTSTRAP_PROGRESS, progress)
            setPackage(packageName)
        }
        sendBroadcast(intent)
        Log.d(tag, "Bootstrap progress: $progress%")
    }

    /**
     * Broadcasts the Tor exit IP so the UI can display it under "CONNECTED".
     * Only broadcast when the IP changes (avoids spamming the UI every 15s
     * with the same value).
     */
    private fun broadcastExitIp(ip: String) {
        if (ip == lastExitIp) return
        lastExitIp = ip
        val intent = Intent(Constants.ACTION_EXIT_IP_UPDATE).apply {
            putExtra(Constants.EXTRA_EXIT_IP, ip)
            setPackage(packageName)
        }
        sendBroadcast(intent)
        Log.d(tag, "Exit IP update: $ip")
    }

    /**
     * Starts a background job that fetches the Tor exit IP every
     * [Constants.EXIT_IP_UPDATE_INTERVAL_MS] (15 seconds) by making an
     * HTTPS request to check.torproject.org/api/ip through the Tor SOCKS5
     * proxy on 127.0.0.1:9050.
     *
     * The first fetch happens immediately so the user sees their IP within
     * a few seconds of connecting. Subsequent fetches happen every 15s so
     * the user is notified when their exit node changes (e.g., after a
     * NEWNYM signal or circuit rotation).
     *
     * The job is cancelled in [stopTorInternal] when Tor disconnects.
     */
    private fun startExitIpUpdates() {
        exitIpUpdateJob?.cancel()
        exitIpUpdateJob = serviceScope.launch {
            Log.i(tag, "Starting exit IP update job (every ${Constants.EXIT_IP_UPDATE_INTERVAL_MS}ms)")
            while (isTorRunning && isActive) {
                try {
                    val ip = fetchExitIp()
                    if (ip != null) {
                        broadcastExitIp(ip)
                    } else {
                        Log.d(tag, "Exit IP fetch returned null — Tor may not be carrying traffic yet")
                    }
                } catch (e: Exception) {
                    Log.w(tag, "Exit IP fetch failed: ${e.message}")
                }
                delay(Constants.EXIT_IP_UPDATE_INTERVAL_MS)
            }
            Log.i(tag, "Exit IP update job ended")
        }
    }

    /**
     * Fetches the Tor exit IP by querying check.torproject.org/api/ip
     * through the Tor SOCKS5 proxy.
     *
     * Response format: {"IsTor":true,"IP":"1.2.3.4"}
     * Returns null if the request fails, IsTor is false, or the JSON
     * can't be parsed.
     */
    private fun fetchExitIp(): String? {
        var connection: HttpURLConnection? = null
        return try {
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", Constants.TOR_SOCKS_PORT))
            val url = URL(Constants.EXIT_IP_CHECK_URL)
            connection = (url.openConnection(proxy) as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "OnionRoute-Lite/1.0")
            }
            val responseCode = connection.responseCode
            if (responseCode != 200) {
                Log.w(tag, "Exit IP check returned HTTP $responseCode")
                return null
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            Log.d(tag, "Exit IP response: $body")
            // Parse {"IsTor":true,"IP":"1.2.3.4"} without a JSON library
            // (avoid adding org.json dependency for this small feature).
            val isTorMatch = Regex("\"IsTor\"\\s*:\\s*(true|false)").find(body)
            val ipMatch = Regex("\"IP\"\\s*:\\s*\"([^\"]+)\"").find(body)
            val isTor = isTorMatch?.groupValues?.get(1)?.toBoolean() ?: false
            val ip = ipMatch?.groupValues?.get(1)
            if (isTor && !ip.isNullOrEmpty()) ip else null
        } catch (e: Exception) {
            Log.w(tag, "fetchExitIp failed: ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
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
