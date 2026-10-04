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
import java.io.File
import java.util.concurrent.Executors
import org.onionroute.lite.Constants
import org.onionroute.lite.MainActivity
import org.onionroute.lite.service.AppSelectionManager
import org.onionroute.lite.service.RoutingMode
import org.onionroute.lite.tor.Tun2SocksNative

/**
 * VPN service that routes all device traffic through the Tor SOCKS5 proxy.
 *
 * Creates a TUN interface via [VpnService.Builder.establish] and hands the
 * resulting file descriptor to hev-socks5-tunnel, which forwards TCP and UDP
 * through Tor's SOCKS5 port (9050).
 *
 * hev-socks5-tunnel is LOADED AS A LIBRARY, not exec'd. It has never had a
 * CLI option for an existing TUN descriptor, and its shared-library build has
 * no PT_INTERP, so a ProcessBuilder approach cannot work at all. See
 * `org.onionroute.lite.tor.Tun2SocksNative` for the full contract.
 *
 * The VPN stays active as a foreground service. When Tor disconnects
 * (and kill switch is on), the TUN remains up but the forwarder
 * stops — effectively blocking all traffic.
 */
class TorVpnService : VpnService() {

    private val tag = "TorVpnService"

    private var vpnInterface: ParcelFileDescriptor? = null
    private var isRunning = false

    /**
     * Single daemon thread for tunnel shutdown.
     *
     * hev's quit path is a blocking pthread_join whose cost depends on how many
     * SOCKS5 sessions are still live, so it must not run on the service main
     * thread. Single-threaded on purpose: teardown must not be able to overlap
     * itself, and one at a time is all a phone ever needs.
     */
    private val shutdownExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Tun2SocksShutdown").apply { isDaemon = true }
    }

    /**
     * Set once VPN mode has failed because libtun2socks.so could not be loaded.
     *
     * Without this, always-on VPN turns the failure into a foreground-service
     * spin: failAndStop() ends in stopSelf(), Lockdown mode redelivers
     * ACTION_VPN_ALWAYS_ON, and the cycle repeats with no progress — each pass
     * calling startForeground and establishing a TUN it immediately throws away.
     * START_STICKY compounds it, since a null-intent redelivery took the
     * `else -> startVpn()` branch.
     *
     * A missing or unloadable native library cannot become loadable without a
     * reinstall, so failing once and refusing thereafter is both correct and
     * the only thing that keeps the battery alive.
     */
    @Volatile
    private var forwarderPermanentlyUnavailable = false

    /**
     * Set when the tunnel cannot currently be established — VPN consent missing
     * (establish() returned null) or SELECTED_ONLY with nothing selected.
     *
     * `forwarderPermanentlyUnavailable` only covers the missing-native-library
     * case, and it is latched from a single check. Always-on VPN is
     * SYSTEM-initiated: Android relaunches the service immediately after it
     * stops, so a refusal that ends in stopSelf() became a spin —
     * startForeground, establish() (or the SELECTED_ONLY refusal), failAndStop,
     * stopSelf, repeat — once per redelivery, forever, with no progress. That is
     * reachable in exactly the reboot case: BootReceiver starts
     * ACTION_VPN_ALWAYS_ON whenever PREF_VPN_ENABLED was set, including after
     * the reboot that revoked consent, and consent cannot be re-requested from a
     * receiver.
     *
     * Cleared by an explicit user action (restartVpn) so the user can retry
     * after granting consent.
     */
    @Volatile
    private var tunnelUnavailable = false

    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "onionroute_vpn"

        /**
         * Name of the generated hev-socks5-tunnel config inside `filesDir`.
         *
         * Rewritten on every start. It lives in internal storage rather than
         * a cache dir because it is read on the critical path by native code
         * immediately after being written.
         */
        private const val TUN2SOCKS_CONFIG_FILE = "hev-tun2socks.conf"
    }

    // ── Service Lifecycle ─────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.d(tag, "TorVpnService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // UNCONDITIONALLY promote to the foreground — see TorService for the
        // full reasoning. Briefly: the previous version called startForeground
        // only on API 26+, so on Android 6.0-7.1 (this app's primary target is
        // Android 6.0) the VPN service ran as an ordinary killable background service.
        // A notify() with setOngoing(true) does not protect a service; only
        // startForeground() does. The 2-arg overload is valid from API 1 and
        // needs no channel, so it is correct at minSdk 23.
        //
        // Only the service TYPE argument is version-gated (API 29+).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(Constants.VPN_NOTIFICATION_ID, buildNotification("VPN active"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(Constants.VPN_NOTIFICATION_ID, buildNotification("VPN active"))
        }

        when (intent?.action) {
            Constants.ACTION_STOP_VPN -> stopVpn()
            Constants.ACTION_VPN_ALWAYS_ON -> startVpn(isAlwaysOn = true)
            Constants.ACTION_RESTART_VPN -> restartVpn()
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

        // Update preferences so the UI reflects the revoked state.
        //
        // PREF_VPN_ENABLED only — deliberately NOT PREF_TOR_CONNECTED. That flag
        // is TorService's, and TorService's daemon is still running: revoking a
        // VPN says nothing about tor. Clearing it used to flip the UI to
        // "Disconnected" and the QS tile to "Connect", and tapping Connect then
        // got "Tor is already running" from startTorLocked, so the tile wedged.
        val prefs = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)
        prefs.edit()
            .putBoolean(Constants.PREF_VPN_ENABLED, false)
            .apply()

        // Tell the UI the VPN is gone, as its OWN status — the Tor connection is
        // untouched and must keep reporting as before. EXTRA_VPN_STATUS had no
        // reader at all, so a revocation previously surfaced as a generic
        // DISCONNECTED and the user could not tell that tor was still up.
        broadcastVpnStatus("REVOKED")

        val revokeIntent = Intent(Constants.ACTION_STATUS_UPDATE).apply {
            putExtra(Constants.EXTRA_VPN_STATUS, "REVOKED")
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
            // Refuse BEFORE establishing a TUN or doing any setup if we already
            // know the forwarder cannot run. See
            // forwarderPermanentlyUnavailable for why this is a hard stop
            // rather than a retry: with always-on VPN, failAndStop() ends in
            // stopSelf() and Lockdown mode immediately redelivers, so without
            // this the service spins, calling startForeground and establishing a
            // TUN it immediately throws away, once per redelivery.
            if (forwarderPermanentlyUnavailable || tunnelUnavailable) {
                Log.w(tag, "VPN start refused — tun2socks unavailable on this install")
                stopSelf()
                return
            }

            val builder = Builder()
                .setSession("OnionRoute Lite")
                .setMtu(Constants.VPN_MTU)
                .addAddress(Constants.VPN_ADDRESS, Constants.VPN_ROUTE_PREFIX)
                .addAddress(Constants.VPN_ADDRESS_V6, 126)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                .addDnsServer(Constants.VPN_DNS)

            // PRIVACY: the IPv6 route is critical. With only 0.0.0.0/0 routed,
            // apps on an IPv6-capable network (most LTE/WiFi today) could send
            // traffic over ::/0 OUTSIDE the TUN — completely bypassing Tor
            // while the app claimed everything was routed. Capturing ::/0
            // means IPv6 either goes through tun2socks → SOCKS5 → Tor, or
            // fails closed (no leak). Orbot does the same.

            // Apply per-app routing rules.
            //
            // IMPORTANT: VpnService.Builder allows EITHER an allowed list OR a
            // disallowed list — never both. Mixing them throws
            // UnsupportedOperationException (see VpnService.Builder Javadoc),
            // so our own package is only excluded via the disallowed list in
            // ALL mode. In SELECTED_ONLY mode the own package cannot be part
            // of the allowed list anyway (AppSelectionManager.getInstalledApps()
            // filters it out), so no explicit exclusion is needed there.
            val appSelectionManager = AppSelectionManager(this)
            when (appSelectionManager.getRoutingMode()) {
                RoutingMode.ALL -> {
                    // Route all apps through Tor; exclude ourselves and any
                    // user-selected apps from the TUN interface.
                    try {
                        builder.addDisallowedApplication(packageName)
                    } catch (e: Exception) {
                        Log.w(tag, "Cannot exclude own package", e)
                    }
                    for (excludedPkg in appSelectionManager.getExcludedApps()) {
                        if (excludedPkg == packageName) continue  // already added above
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
                    // — an allowed list that is empty means ALL apps are
                    // routed, which would silently contradict the user's
                    // SELECTED_ONLY choice. The safest behavior is to not
                    // start the VPN at all and tell the user to select apps.
                    // NOTE: we must NOT call addDisallowedApplication here —
                    // mixing allowed and disallowed lists throws
                    // UnsupportedOperationException. Our own package cannot
                    // appear in the selected list (AppSelectionManager filters
                    // it out), so VPN recursion is impossible in this mode.
                    val selectedApps = appSelectionManager.getSelectedApps()
                    if (selectedApps.isEmpty()) {
                        Log.w(tag, "SELECTED_ONLY mode with no apps selected — refusing to start VPN")
                        // Refuse AND release the foreground promotion that
                        // onStartCommand already performed, so this does not
                        // leave a live FGS with no TUN behind it.
                        tunnelUnavailable = true
                        failAndStop("No apps selected — choose apps in App Routing first")
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
                // Do not leave the service promoted to the foreground with a
                // "VPN active" notification when there is no interface at all.
                // Almost always "VPN consent was revoked" — most commonly by a
                // reboot, and on some OEMs by their own battery manager. Latch it
                // so always-on redelivery cannot spin; cleared by restartVpn().
                tunnelUnavailable = true
                failAndStop("Could not establish VPN interface")
                return
            }

            // Start the forwarder. If this fails the TUN is already up with
            // 0.0.0.0/0 and ::/0 routes, so we MUST tear it down rather than
            // reporting success — otherwise every app on the device loses
            // connectivity into a black hole while the UI claims a healthy VPN.
            if (!startTun2Socks()) {
                // A missing or unloadable .so cannot become loadable without a
                // reinstall, so stop retrying — see the field's comment.
                if (!Tun2SocksNative.isAvailable) {
                    forwarderPermanentlyUnavailable = true
                }
                failAndStop("Traffic forwarder failed to start — VPN mode unavailable")
                return
            }

            isRunning = true
            // Now that a TUN is up AND a forwarder is reading it, tell the UI
            // the truth. Previously it was told "active" before either existed.
            broadcastVpnStatus("ACTIVE")

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

    /**
     * Aborts a VPN start attempt, leaving no trace behind.
     *
     * `onStartCommand` promotes this service to the foreground BEFORE dispatching
     * the action, so every early-return path has to undo that. Previously the
     * pre-`establish()` failure paths just `return`ed, which left the service in
     * the foreground with a permanent "VPN active" notification and no TUN.
     * That state is reachable in normal use: ConnectTileService sends
     * ACTION_STOP_VPN unconditionally, TorService sends it whenever
     * PREF_VPN_ENABLED is set, and `builder.establish()` returns null after a
     * reboot revokes VPN consent (the exact case BootReceiver guards).
     *
     * Idempotent: safe to call when nothing was ever established.
     *
     * @param reason Human-readable cause, logged and shown in the notification.
     */
    private fun failAndStop(reason: String) {
        Log.w(tag, "VPN start aborted: $reason")
        broadcastVpnStatus("FAILED")
        teardownTunnel()
        // DETACH, not REMOVE. REMOVE (and stopForeground(true) on API 23) deleted
        // the notification posted one line above, so "VPN unavailable" was
        // created and torn down inside the same main-loop turn and was never
        // visible to anyone. Posting the reason only helps if it survives.
        updateNotification("OnionRoute Lite — VPN unavailable ($reason)")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(false)
        }
        stopSelf()
    }

    /**
     * Tears down the TUN interface and the forwarder WITHOUT touching the
     * foreground notification or the service lifetime. Used by [restartVpn]
     * so routing-rule changes can be applied seamlessly (no notification
     * flicker, no service restart churn).
     *
     * Each step gets its own try/catch on purpose. An earlier version wrapped
     * them in a single try, so any failure skipped the rest — including
     * stopping the forwarder, which would leave a live TUN with no reader.
     */
    private fun teardownTunnel() {
        // Stop the forwarder BEFORE closing the fd. hev is blocked reading that
        // descriptor, and TProxyStopService joins its worker thread, so it must
        // finish using the fd first.
        // TProxyStopService() -> hev_socks5_tunnel_quit() + pthread_join(), and
        // the join waits for the worker to terminate EVERY live SOCKS5 session
        // and for the lwip IO task to observe run = 0. That cost scales with
        // concurrent sessions, so on a 1.4GHz device it is squarely ANR
        // territory — and it was being run on the service main thread, because
        // teardownTunnel is reached from stopVpn, restartVpn, failAndStop,
        // onDestroy and onRevoke, all of which are main-thread callbacks.
        //
        // Moved to a dedicated daemon thread. The fd is closed in that task's
        // completion, after the join, so the "stop before close" ordering is
        // preserved: hev must be finished with the descriptor before
        // VpnService lets go of it.
        if (Tun2SocksNative.isAvailable) {
            val fdToClose = vpnInterface
            vpnInterface = null
            shutdownExecutor.execute {
                try {
                    Tun2SocksNative.TProxyStopService()
                } catch (e: Exception) {
                    Log.e(tag, "Error stopping tun2socks forwarder", e)
                } catch (e: UnsatisfiedLinkError) {
                    Log.e(tag, "tun2socks JNI methods unbound", e)
                } finally {
                    try {
                        fdToClose?.close()
                        Log.i(tag, "hev-socks5-tunnel stopped, TUN descriptor closed")
                    } catch (e: Exception) {
                        Log.e(tag, "Error closing TUN descriptor", e)
                    }
                }
            }
        } else {
            try {
                vpnInterface?.close()
            } catch (e: Exception) {
                Log.e(tag, "Error closing VPN interface", e)
            }
            vpnInterface = null
        }

        isRunning = false

        val prefs = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)
        prefs.edit().putBoolean(Constants.PREF_VPN_ENABLED, false).apply()
    }

    private fun stopVpn() {
        // Idempotent, and deliberately NOT gated on `isRunning`. onStartCommand
        // promotes this service to the foreground for every action, including
        // ACTION_STOP_VPN, so returning early when no tunnel was up would strand
        // the service in the foreground with a stale "VPN active" notification.
        // teardownTunnel() and the stopForeground/stopSelf pair are both safe to
        // run when nothing was established.
        if (isRunning) {
            Log.i(tag, "Stopping VPN tunnel…")
        }
        teardownTunnel()
        Log.i(tag, "VPN stopped")

        // Clear the stale "VPN active" foreground notification and stop the
        // service. Without this, the FGS (and its misleading notification)
        // lingered after ACTION_STOP_VPN until the system killed the process.
        updateNotification("OnionRoute Lite — VPN stopped")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    /**
     * Seamlessly rebuilds the VPN with the CURRENT per-app routing rules.
     * Called via ACTION_RESTART_VPN when the user changes routing mode or
     * app selections while the VPN is up — previously those changes were
     * silently ignored until the user manually toggled the VPN off/on.
     */
    private fun restartVpn() {
        // An explicit user action is the signal that whatever blocked us may have
        // been fixed (consent granted, apps selected), so allow another attempt.
        tunnelUnavailable = false
        if (!isRunning) {
            Log.d(tag, "restartVpn: VPN not running — starting fresh")
            startVpn()
            return
        }
        Log.i(tag, "Restarting VPN to apply updated routing rules…")
        teardownTunnel()
        startVpn()
    }

    // ── tun2socks ─────────────────────────────────────────────────

    /**
     * Starts the hev-socks5-tunnel forwarder on the current TUN descriptor.
     *
     * @return true if the forwarder is running.
     *
     * WHY THIS RETURNS A BOOLEAN
     * ---------------------------
     * The previous version returned Unit and only logged on failure, while the
     * caller went on to set `isRunning = true`, `PREF_VPN_ENABLED = true` and
     * post a "VPN active" notification. The result was a TUN with 0.0.0.0/0 and
     * ::/0 routes and NO process reading it — every app's packets vanishing
     * into a black hole, while the UI and the QS tile reported a healthy VPN.
     * That is indistinguishable from Tor being down, so the caller now tears
     * the tunnel down instead of claiming success.
     *
     * @throws IllegalStateException if the forwarder could not be started; the
     *   caller is responsible for tearing down the TUN.
     */
    private fun startTun2Socks(): Boolean {
        val fd = vpnInterface?.fd ?: run {
            Log.e(tag, "No VPN file descriptor available")
            return false
        }

        if (!Tun2SocksNative.isAvailable) {
            Log.e(tag, "libtun2socks.so unavailable (${Tun2SocksNative.loadError()}); " +
                "VPN mode cannot forward traffic. Run scripts/build-tun2socks.sh for this ABI.")
            return false
        }

        // hev reads its settings from a FILE PATH, not from a string: upstream's
        // native_start_service() strdups the Java string and hands it to
        // hev_socks5_tunnel_main(path, fd), which calls hev_config_init_from_file.
        val configFile = File(filesDir, TUN2SOCKS_CONFIG_FILE)
        return try {
            configFile.writeText(buildTun2SocksConfig())
            Tun2SocksNative.TProxyStartService(configFile.absolutePath, fd)
            Log.i(
                tag, "hev-socks5-tunnel started — fd=$fd, " +
                    "socks5=127.0.0.1:${Constants.TOR_SOCKS_PORT}, " +
                    "dns=${Constants.VPN_DNS} via Tor"
            )
            true
        } catch (e: Exception) {
            Log.e(tag, "Failed to start hev-socks5-tunnel", e)
            false
        } catch (e: UnsatisfiedLinkError) {
            // JNI_OnLoad ran but RegisterNatives did not bind these methods,
            // e.g. the library was built without the -DPKGNAME/-DCLSNAME flags.
            Log.e(tag, "hev-socks5-tunnel JNI methods unbound — was the library " +
                "built by scripts/build-tun2socks.sh?", e)
            false
        }
    }

    /**
     * Builds the hev-socks5-tunnel YAML configuration.
     *
     * Only `socks5`, `mapdns` and `misc` are emitted, and that is deliberate.
     * `tunnel_init()` returns as soon as it sees a non-negative fd — before it
     * would read any `tunnel:` key — because the interface already exists and
     * is owned by [VpnService]. Emitting `tunnel.mtu`, `tunnel.ipv4` or
     * `tunnel.name` here would be dead configuration at best; setting them would
     * imply hev is managing the interface, which would double-configure it.
     *
     * Value formats are not free-form: hev parses `address`, `network` and
     * `netmask` with inet_pton (dotted-quad) and `port`/`cache-size` with
     * strtoul (decimal) — see hev_config_parse_mapdns(), hev-config.c:296-306.
     */
    /**
     * Broadcasts a VPN lifecycle state so the UI can reflect it.
     *
     * The VPN's state previously reached the UI only optimistically:
     * MainActivity.startVpn() set vpnMode = true, wrote PREF_VPN_ENABLED = true
     * and toasted "VPN mode active" BEFORE any TUN existed. The failure paths
     * (failAndStop) logged, posted a detached notification and called
     * stopSelf() — nothing was broadcast, and EXTRA_VPN_STATUS had no reader at
     * all. So a VPN that never started still looked started in the UI and in the
     * persisted preference.
     *
     * Status values: STARTING, ACTIVE, REVOKED, FAILED.
     */
    private fun broadcastVpnStatus(status: String) {
        try {
            sendBroadcast(Intent(Constants.ACTION_STATUS_UPDATE).apply {
                putExtra(Constants.EXTRA_VPN_STATUS, status)
                // Explicit component: the broadcast is app-internal and the
                // receiver is registered NOT_EXPORTED, so this keeps it that way.
                setPackage(packageName)
            })
        } catch (e: Exception) {
            Log.w(tag, "Failed to broadcast VPN status $status: ${e.message}")
        }
    }

    private fun buildTun2SocksConfig(): String = buildString {
        appendLine("# Generated by OnionRoute Lite. Rewritten on every VPN start.")
        appendLine("# Do not edit: this file is owned by TorVpnService.")
        appendLine("socks5:")
        appendLine("  address: 127.0.0.1")
        appendLine("  port: ${Constants.TOR_SOCKS_PORT}")
        // 'udp' selects UDP_IN_UDP, i.e. datagrams relayed over a SOCKS5 TCP
        // stream. This is upstream's documented default.
        //
        // Scope note: mapped DNS does NOT depend on this — hev answers port-53
        // itself in-process (hev_mapped_dns_handle) over that TCP stream. And
        // Tor's SOCKS5 port 9050 implements CONNECT only, not UDP ASSOCIATE, so
        // non-DNS UDP such as QUIC/HTTP3 will still fail to establish; `udp:
        // tcp` would not help, since UDP_IN_TCP uses the same SOCKS5 server.
        appendLine("  udp: udp")
        appendLine("mapdns:")
        // Must equal the DNS server advertised in Builder.addDnsServer(), or
        // hev forwards the query to a black hole instead of resolving it.
        appendLine("  address: ${Constants.VPN_MAPDNS_ADDRESS}")
        appendLine("  port: ${Constants.VPN_MAPDNS_PORT}")
        // hev synthesises reply addresses as `network | index` and reverse
        // looks them up with `ip & ~netmask`, so netmask must isolate exactly
        // the varying low bits.
        appendLine("  network: ${Constants.TUN2SOCKS_MAPDNS_POOL_NETWORK}")
        appendLine("  netmask: ${Constants.TUN2SOCKS_MAPDNS_POOL_NETMASK}")
        // Must be non-zero: mapped_dns_init() returns early without it, which
        // silently disables all DNS interception.
        appendLine("  cache-size: ${Constants.TUN2SOCKS_MAPDNS_CACHE_SIZE}")
        appendLine("misc:")
        // Matches Builder.setMtu(); unused because hev does not own the
        // interface, but keeping it aligned avoids confusion if it ever reads it.
        appendLine("  log-level: error")
        // 128KB task stacks: comfortably above hev's ~86KB suggestion while
        // staying modest for a 1.5GB device with few concurrent sessions.
        appendLine("  task-stack-size: 131072")
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
