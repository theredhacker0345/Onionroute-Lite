package org.onionroute.lite.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import org.onionroute.lite.Constants
import org.onionroute.lite.MainActivity
import org.onionroute.lite.service.AppSelectionManager
import org.onionroute.lite.service.RoutingMode

/**
 * VPN service that routes all device traffic through the Tor SOCKS5 proxy.
 *
 * Creates a TUN interface and configures tun2socks to forward all TCP
 * connections through the local Tor SOCKS5 port (9050). DNS queries are
 * resolved through Tor's DNS port (5400).
 *
 * The VPN stays active as a foreground service. When Tor disconnects
 * (and kill switch is on), the TUN remains up but the SOCKS5 forwarder
 * stops — effectively blocking all traffic.
 */
class TorVpnService : VpnService() {

    private val tag = "TorVpnService"

    private var vpnInterface: ParcelFileDescriptor? = null
    private var tun2socksProcess: Process? = null
    private var isRunning = false

    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "onionroute_vpn"
    }

    // ── Service Lifecycle ─────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.d(tag, "TorVpnService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(Constants.VPN_NOTIFICATION_ID, buildNotification("VPN active"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForeground(Constants.VPN_NOTIFICATION_ID, buildNotification("VPN active"))
        }

        when (intent?.action) {
            Constants.ACTION_STOP_VPN -> stopVpn()
            Constants.ACTION_VPN_ALWAYS_ON -> startVpn(isAlwaysOn = true)
            else -> startVpn()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    override fun onRevoke() {
        Log.w(tag, "VPN permission revoked — always-on VPN disabled or permission withdrawn")
        stopVpn()

        // Update preferences so the UI reflects the revoked state
        val prefs = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)
        prefs.edit()
            .putBoolean(Constants.PREF_VPN_ENABLED, false)
            .putBoolean(Constants.PREF_TOR_CONNECTED, false)
            .apply()

        // Notify the UI that VPN was revoked
        val revokeIntent = Intent(Constants.ACTION_STATUS_UPDATE).apply {
            putExtra(Constants.EXTRA_TOR_STATUS, "DISCONNECTED")
            putExtra(Constants.EXTRA_VPN_STATUS, "REVOKED")
            setPackage(packageName)
        }
        sendBroadcast(revokeIntent)

        super.onRevoke()
    }

    // ── VPN Control ───────────────────────────────────────────────

    /**
     * Starts the VPN tunnel.
     *
     * @param isAlwaysOn When true, the VPN was started by Android's always-on VPN
     *   system. In this mode we skip any UI permission prompts and start
     *   unconditionally — the user has already granted always-on permission
     *   at the system level.
     */
    private fun startVpn(isAlwaysOn: Boolean = false) {
        if (isRunning) {
            Log.w(tag, "VPN already running")
            return
        }

        if (isAlwaysOn) {
            Log.i(tag, "Starting VPN tunnel (always-on mode)…")
        } else {
            Log.i(tag, "Starting VPN tunnel…")
        }

        try {
            // Build VPN interface with per-app routing
            val builder = Builder()
                .setSession("OnionRoute Lite")
                .setMtu(Constants.VPN_MTU)
                .addAddress(Constants.VPN_ADDRESS, Constants.VPN_ROUTE_PREFIX)
                .addRoute("0.0.0.0", 0)
                .addDnsServer(Constants.VPN_DNS)

            // Always exclude our own package to prevent VPN recursion
            builder.addDisallowedApplication(packageName)

            // Apply per-app routing rules
            val appSelectionManager = AppSelectionManager(this)
            when (appSelectionManager.getRoutingMode()) {
                RoutingMode.ALL -> {
                    // Route all apps through Tor; exclude user-selected apps
                    for (excludedPkg in appSelectionManager.getExcludedApps()) {
                        try {
                            builder.addDisallowedApplication(excludedPkg)
                        } catch (e: Exception) {
                            Log.w(tag, "Cannot exclude unknown package: $excludedPkg", e)
                        }
                    }
                }
                RoutingMode.SELECTED_ONLY -> {
                    // Only route selected apps through Tor.
                    //
                    // If no apps are selected, REFUSE to start the VPN. The
                    // VpnService.Builder API doesn't support "block all apps"
                    // — calling addAllowedApplication(packageName) here would
                    // throw IllegalArgumentException because packageName is
                    // already in the disallowed list (line above). And calling
                    // addAllowedApplication on a non-existent package throws
                    // NameNotFoundException. The safest behavior is to not
                    // start the VPN at all and tell the user to select apps.
                    val selectedApps = appSelectionManager.getSelectedApps()
                    if (selectedApps.isEmpty()) {
                        Log.w(tag, "SELECTED_ONLY mode with no apps selected — refusing to start VPN")
                        updateNotification("VPN disabled — select apps in App Routing first")
                        // Update preferences so the UI reflects the actual state
                        val p = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)
                        p.edit().putBoolean(Constants.PREF_VPN_ENABLED, false).apply()
                        return
                    }
                    for (selectedPkg in selectedApps) {
                        try {
                            builder.addAllowedApplication(selectedPkg)
                        } catch (e: Exception) {
                            Log.w(tag, "Cannot allow unknown package: $selectedPkg", e)
                        }
                    }
                }
            }

            // Always-on VPN compatibility: no HTTP proxy needed.
            // Traffic is routed through tun2socks → SOCKS5 (127.0.0.1:9050),
            // so setHttpProxy would be redundant and may cause issues on some devices.
            // The VPN builder already routes all traffic through the TUN interface.

            vpnInterface = builder.establish()

            if (vpnInterface == null) {
                Log.e(tag, "Failed to establish VPN interface" +
                    if (isAlwaysOn) " (always-on mode — user may need to re-grant VPN permission)" else "")
                return
            }

            // Start tun2socks to forward traffic through Tor SOCKS5
            startTun2Socks()

            isRunning = true

            // Also ensure Tor is started when triggered by always-on VPN
            if (isAlwaysOn) {
                ensureTorStarted()
            }

            // Update preferences
            // NOTE: only write PREF_VPN_ENABLED here. Do NOT touch
            // PREF_TOR_CONNECTED — VPN being up doesn't imply Tor is up.
            // (Previously this set PREF_TOR_CONNECTED=true unconditionally,
            //  which made the QS tile show "Connected" even when Tor wasn't
            //  running. The tile state now correctly reflects Tor's actual
            //  state, which is managed by TorService.broadcastStatus().)
            val prefs = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)
            prefs.edit()
                .putBoolean(Constants.PREF_VPN_ENABLED, true)
                .apply()

            updateNotification("OnionRoute Lite — VPN active")
            Log.i(tag, "VPN started successfully" +
                if (isAlwaysOn) " (always-on)" else "")

        } catch (e: Exception) {
            Log.e(tag, "Error starting VPN", e)
            stopVpn()
        }
    }

    /**
     * Ensures the Tor daemon is running.
     *
     * Called when the VPN is started via always-on mode. If Tor isn't
     * already running, it starts [TorService] so the VPN has a proxy
     * to forward traffic through.
     */
    private fun ensureTorStarted() {
        val prefs = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)
        val torConnected = prefs.getBoolean(Constants.PREF_TOR_CONNECTED, false)

        if (!torConnected) {
            Log.i(tag, "Always-on VPN: starting TorService to ensure proxy is available")
            val torIntent = Intent(this, TorService::class.java).apply {
                action = Constants.ACTION_START_TOR
            }
            startService(torIntent)
        }
    }

    private fun stopVpn() {
        if (!isRunning) return

        Log.i(tag, "Stopping VPN tunnel…")

        try {
            tun2socksProcess?.destroy()
            tun2socksProcess = null
        } catch (e: Exception) {
            Log.e(tag, "Error stopping tun2socks", e)
        }

        try {
            vpnInterface?.close()
            vpnInterface = null
        } catch (e: Exception) {
            Log.e(tag, "Error closing VPN interface", e)
        }

        isRunning = false
        Log.i(tag, "VPN stopped")

        // Update preferences
        val prefs = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)
        prefs.edit().putBoolean(Constants.PREF_VPN_ENABLED, false).apply()
    }

    // ── tun2socks ─────────────────────────────────────────────────

    private fun startTun2Socks() {
        val fd = vpnInterface?.fd ?: run {
            Log.e(tag, "No VPN file descriptor available")
            return
        }

        // Execute libtun2socks.so directly from nativeLibraryDir.
        // The app's filesDir is mounted noexec on Android 10+,
        // so we must execute from nativeLibraryDir which has exec permission.
        val tun2socksPath = "${applicationInfo.nativeLibraryDir}/${Constants.BINARY_TUN2SOCKS}"
        val tun2socksFile = java.io.File(tun2socksPath)
        if (!tun2socksFile.exists()) {
            Log.e(tag, "libtun2socks.so not found at $tun2socksPath")
            return
        }

        val socksAddr = "127.0.0.1:${Constants.TOR_SOCKS_PORT}"
        val dnsAddr = "127.0.0.1:${Constants.TOR_DNS_PORT}"

        try {
            val processBuilder = ProcessBuilder(
                tun2socksPath,
                "--tun-fd", fd.toString(),
                "--proxy", socksAddr,
                "--dns", dnsAddr,
                "--loglevel", "error"
            )
            tun2socksProcess = processBuilder.start()

            // Drain tun2socks stdout in a background thread. Even at
            // --loglevel error, tun2socks prints error messages to stdout
            // which would fill the OS pipe buffer (~64KB) and block the
            // process. Same fix as TorProcessManager.startStdoutPump().
            Thread({
                try {
                    val reader = tun2socksProcess?.inputStream?.bufferedReader()
                        ?: return@Thread
                    var line = reader.readLine()
                    while (line != null) {
                        Log.d(tag, "tun2socks: $line")
                        line = reader.readLine()
                    }
                } catch (_: InterruptedException) {
                } catch (e: Exception) {
                    if (tun2socksProcess != null) Log.e(tag, "tun2socks stdout pump error", e)
                }
            }, "Tun2SocksStdoutPump").apply {
                isDaemon = true
                start()
            }

            Log.i(tag, "tun2socks started — fd=$fd, proxy=$socksAddr, dns=$dnsAddr")
        } catch (e: Exception) {
            Log.e(tag, "Failed to start tun2socks from $tun2socksPath", e)
        }
    }

    // ── Notification ──────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "OnionRoute Lite VPN",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent notification for OnionRoute Lite VPN service"
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

        val stopIntent = Intent(this, TorVpnService::class.java).apply {
            action = Constants.ACTION_STOP_VPN
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
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
            notificationManager.notify(Constants.VPN_NOTIFICATION_ID, buildNotification(text))
        } catch (e: Exception) {
            Log.e(tag, "Failed to update notification", e)
        }
    }
}
