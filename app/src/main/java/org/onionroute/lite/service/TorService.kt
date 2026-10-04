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
import org.onionroute.lite.tor.TorCircuit
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

    // PERF: precompiled once — fetchExitIp() runs every 15s while connected;
    // Regex compilation per call wasted CPU on a recurring path.
    private val IS_TOR_REGEX = Regex("\"IsTor\"\\s*:\\s*(true|false)")
    private val IP_REGEX = Regex("\"IP\"\\s*:\\s*\"([^\"]+)\"")

    /** Extracts progress from a `GETINFO status/bootstrap-phase` reply, which
     *  reads `NOTICE BOOTSTRAP PROGRESS=100 TAG=done …`. Precompiled like the
     *  two above rather than built inline on each connect. */
    private val BOOTSTRAP_PROGRESS_REGEX = Regex("PROGRESS=(\\d+)")

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

    /**
     * Latch for the bootstrap wait of the in-flight startTor, if any.
     *
     * The wait is a blocking CountDownLatch.await on the service's IO
     * dispatcher, so job cancellation cannot interrupt it — the coroutine is
     * parked inside a non-cancellable call. Without a handle on the latch, a
     * user-initiated disconnect during bootstrap left the thread blocked for
     * the full 120s timeout and then emitted a spurious "bootstrap timed out"
     * ERROR broadcast *after* DISCONNECTED, leaving the UI showing an error for
     * a Tor session the user had simply cancelled.
     *
     * stopTorInternal() counts this down so the await returns immediately and
     * the existing stopRequested checkpoint takes over. Guarded by its own lock
     * because stopTor runs on the main thread while the wait is set up on IO.
     */
    private val bootstrapLatchLock = Any()
    @Volatile
    private var bootstrapLatch: CountDownLatch? = null

    /**
     * True when the in-flight bootstrap was released by something other than
     * Tor reaching 100%.
     *
     * A [CountDownLatch] cannot express WHY it was counted down: the release
     * path and the genuine `PROGRESS=100` path both arrive at
     * `countDown()`, and the waiter cannot tell them apart. Releasing the latch
     * when tor crashes mid-bootstrap therefore made the waiter take the SUCCESS
     * branch — `isTorRunning = true`, `broadcastStatus("CONNECTED")`,
     * `PREF_TOR_CONNECTED = true` — for a process that was already dead.
     *
     * That was worse than the 120s stall it fixed, because it also suppressed
     * recovery: the auto-restart coroutine's teardown ran *after* the waiter had
     * already claimed success, so its subsequent `startTor()` hit the
     * `if (isTorRunning) return` guard and was dropped. End state: no tunnel, no
     * retry, and the UI, the QS tile and the persisted preference all reporting
     * CONNECTED until the process was killed.
     *
     * This flag is the missing "why". It is set under [bootstrapLatchLock]
     * immediately before the countDown, reset when a new wait is published, and
     * checked by the waiter alongside [stopRequested].
     */
    private var bootstrapAborted = false

    /**
     * Releases the in-flight bootstrap wait, if there is one, so the thread
     * parked in `CountDownLatch.await` resumes immediately.
     *
     * That await is a blocking call which coroutine cancellation cannot
     * interrupt, so without this the starting thread stays parked for up to the
     * full 120s timeout and then reports a timeout that is no longer true.
     *
     * Needed from two places, and missing from the second was the bug: a
     * deliberate stop (stopTorInternal), and tor dying on its own mid-bootstrap
     * (onTorProcessExited). The latter left the UI showing "CONNECTING 40%"
     * for two minutes after tor had already exited, followed by a bogus
     * "bootstrap timed out" error, and — because the handler also bailed out
     * before its restart logic — never actually reconnected at all.
     *
     * @return true if a wait was released.
     */
    private fun releaseBootstrapWait(): Boolean {
        synchronized(bootstrapLatchLock) {
            val latch = bootstrapLatch ?: return false
            // Record the reason BEFORE the countDown, and inside the same lock,
            // so the waiter cannot observe an aborted latch as a success.
            bootstrapAborted = true
            Log.d(tag, "Releasing in-flight bootstrap wait (aborted)")
            latch.countDown()
            return true
        }
    }

    /** True while a startTor() is blocked waiting for bootstrap. */
    private fun isBootstrapInProgress(): Boolean =
        synchronized(bootstrapLatchLock) { bootstrapLatch != null }

    /**
     * True if the wait we just performed ended because it was released rather
     * than because Tor reported 100%.
     *
     * @see bootstrapAborted
     */
    private fun wasBootstrapAborted(): Boolean =
        synchronized(bootstrapLatchLock) { bootstrapAborted }

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
        // Sync the in-memory enable flag from prefs. Previously setEnabled()
        // was NEVER called anywhere, so isEnabled stayed false forever and
        // onTorDisconnected() always early-returned — the kill switch was
        // dead code despite its UI toggle writing the preference.
        killSwitch?.setEnabled(KillSwitch.isEnabledInPrefs(this))
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

        val action = intent?.action
        when (action) {
            Constants.ACTION_START_TOR -> startTorAsync()
            Constants.ACTION_STOP_TOR -> stopTor(startId)
            Constants.ACTION_STOP_VPN -> handleVpnStop()
            Constants.ACTION_TOGGLE_SNOWFLAKE -> toggleSnowflake()
            Constants.ACTION_TOGGLE_OBFS4 -> toggleObfs4()
            Constants.ACTION_TOGGLE_WEBTUNNEL -> toggleWebTunnel()
            Constants.ACTION_NEW_CIRCUIT -> requestNewCircuit()
            Constants.ACTION_GET_CIRCUITS -> requestCircuits()
            Constants.ACTION_SET_EXIT_COUNTRY -> applyExitCountry(intent.getStringExtra(Constants.EXTRA_EXIT_COUNTRY))
            null -> handleStickyRestart()
            else -> Log.w(tag, "Unknown action: $action")
        }

        return START_STICKY
    }

    /**
     * START_STICKY redelivery: the system restarted this service with a null
     * intent after the process was killed. Previously this fell into the
     * generic branch and left a foreground service stuck forever on
     * "Starting Tor…" while tor wasn't running at all. Restore the previous
     * state instead: reconnect if Tor was connected (or auto-start is on),
     * otherwise stop cleanly.
     */
    private fun handleStickyRestart() {
        val prefs = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)
        val wasConnected = prefs.getBoolean(Constants.PREF_TOR_CONNECTED, false)
        val autoStart = prefs.getBoolean(Constants.PREF_AUTO_START, false)
        if (wasConnected || autoStart) {
            Log.i(tag, "Sticky restart — restoring previous Tor session")
            startTorAsync()
        } else {
            Log.i(tag, "Sticky restart — Tor was not connected and auto-start is off; stopping")
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // Mark the stop as initiated so an in-flight startTor() aborts quietly
        // instead of broadcasting a spurious ERROR *after* the service is being
        // destroyed. Otherwise the UI could be left showing "bootstrap timed
        // out" for a session the user had ended, since the coroutine was already
        // past its last checkpoint when the service died.
        stopRequested = true
        stopTorInternal()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ── Tor Control ───────────────────────────────────────────────

    /** Set when a stop is requested so an in-flight startTor() can abort
     *  quietly instead of broadcasting a spurious ERROR after the user
     *  already saw DISCONNECTED. */
    @Volatile private var stopRequested = false

    /** Serializes start requests. isTorRunning only becomes true AFTER
     *  bootstrap completes (up to 120s) — without this guard, duplicate
     *  ACTION_START_TOR intents (QS tile re-tap, BootReceiver + always-on
     *  VPN both firing on boot) each spawned their own tor process, leaked
     *  the first one via field overwrite and double-subscribed control
     *  events. */
    private val startMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * Launches [startTor] on [Dispatchers.IO] to avoid NetworkOnMainThreadException.
     * All socket I/O (control port connection, bandwidth events) must happen off
     * the main thread — Android StrictMode forbids network access on the UI thread.
     *
     * @param clearStopFlag User-initiated starts clear a previously-set stop
     *   flag; auto-restart passes false so a concurrent user stop is honored.
     */
    private fun startTorAsync(clearStopFlag: Boolean = true) {
        serviceScope.launch {
            startTor(clearStopFlag)
        }
    }

    /**
     * Starts the Tor daemon and connects to the control port.
     * MUST be called from a background thread (via [startTorAsync]) because it
     * performs blocking socket I/O to the Tor control port.
     */
    private suspend fun startTor(clearStopFlag: Boolean = true) {
        // Serialize concurrent start attempts — see [startMutex] docs.
        if (!startMutex.tryLock()) {
            Log.w(tag, "startTor: another start attempt is in progress — ignoring duplicate request")
            return
        }
        try {
            startTorLocked(clearStopFlag)
        } finally {
            startMutex.unlock()
        }
    }

    private suspend fun startTorLocked(clearStopFlag: Boolean = true) {
        if (isTorRunning) {
            Log.w(tag, "Tor is already running")
            broadcastStatus("CONNECTED")
            return
        }

        Log.i(tag, "Starting Tor daemon…")
        if (clearStopFlag) stopRequested = false
        broadcastStatus("CONNECTING")

        try {
            val prefs = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)
            isSnowflakeEnabled = prefs.getBoolean(Constants.PREF_SNOWFLAKE_ENABLED, false)
            isObfs4Enabled = prefs.getBoolean(Constants.PREF_OBFS4_ENABLED, false)
            isWebTunnelEnabled = prefs.getBoolean(Constants.PREF_WEBTUNNEL_ENABLED, false)

            val processManager = TorProcessManager(this@TorService) { exitCode ->
                onTorProcessExited(exitCode)
            }
            torProcessManager = processManager
            val configResult = processManager.startTor(
                useSnowflake = isSnowflakeEnabled,
                useObfs4 = isObfs4Enabled,
                useWebTunnel = isWebTunnelEnabled
            )

            if (!configResult) {
                Log.e(tag, "Failed to start Tor process")
                broadcastStatus("ERROR", "Failed to start Tor process")
                return
            }

            // Declared BEFORE the control-port wait loop because the latch is
            // published from inside it (see the comment there): everything
            // between the control port coming up and the await below is
            // blocking work on the thread holding startMutex, and a user stop
            // during that window must be able to release the wait.
            val thisLatch = CountDownLatch(1)

            // Wait for Tor to initialize and open the control port.
            // The daemon needs time to bind the control port after launch.
            // Retry with exponential backoff: 500ms, 1s, 1.5s, 2s, 2.5s, 3s.
            val maxRetries = 6
            var connected = false
            var controlConnection: TorControlConnection? = null

            for (attempt in 1..maxRetries) {
                delay(attempt * 500L)

                // Abort quietly if the user stopped us mid-connect.
                if (stopRequested) {
                    Log.i(tag, "startTor aborted — stop requested during control-port retry")
                    return
                }

                try {
                    val conn = TorControlConnection(this@TorService)
                    connected = conn.connect(
                        Constants.TOR_CONTROL_PORT,
                        processManager.controlCookieFile
                    )
                    if (connected) {
                        controlConnection = conn
                        // Publish the bootstrap latch NOW, before the GETINFO and
                        // before the await. Everything between here and the
                        // await is blocking work on a thread holding
                        // startMutex, so a user stop or a crash in that window
                        // must already be able to release the wait.
                        //
                        // Counting down a latch nobody awaits yet is harmless:
                        // the await below returns immediately and the
                        // bootstrapAborted check rejects the result.
                        synchronized(bootstrapLatchLock) {
                            bootstrapAborted = false
                            bootstrapLatch = thisLatch
                        }
                        break
                    } else {
                        conn.disconnect()
                    }
                } catch (e: Exception) {
                    Log.d(tag, "Control port attempt $attempt failed: ${e.message}")
                }
            }

            if (!connected || controlConnection == null) {
                Log.e(tag, "Failed to connect to Tor control port after $maxRetries attempts")
                broadcastStatus("ERROR", "Failed to connect to Tor control port")
                // Clean up the half-started stack — without this the tor
                // daemon kept running orphaned (holding ports 9050/9051),
                // breaking every subsequent start attempt.
                stopTorInternal(unexpectedDisconnect = true)
                return
            }
            torControlConnection = controlConnection

            // Start bandwidth monitoring + bootstrap listener.
            // We do NOT broadcast CONNECTED yet — Tor has only just opened its
            // control port; it still needs to download consensus, build circuits,
            // and establish a path through the Tor network (bootstrap, 0→100%).
            // Premature "CONNECTED" was the previous behaviour — the UI would
            // show CONNECTED while Tor was actually still bootstrapping (or had
            // failed to bootstrap at all).
            val lastProgress = AtomicInteger(0)

            bandwidthTracker?.start(controlConnection) { progress, bootstrapTag ->
                Log.i(tag, "Bootstrap: $progress% ($bootstrapTag)")
                lastProgress.set(progress)
                // Broadcast intermediate progress so the UI shows "CONNECTING 45%"
                broadcastBootstrapProgress(progress)
                if (progress >= 100) {
                    thisLatch.countDown()
                }
            }

            // Defensive: if Tor finished bootstrapping BEFORE we subscribed to
            // BOOTSTRAP events (e.g. very fast direct connection on a clean
            // network — Tor can reach 100% in under a second), we would miss
            // the BOOTSTRAP PROGRESS=100 event and time out at 120s. Query the
            // current bootstrap phase via GETINFO; if it's already 100%, count
            // down the latch immediately.
            try {
                val phase = controlConnection.getInfo("status/bootstrap-phase")
                if (phase != null) {
                    // Response format: NOTICE BOOTSTRAP PROGRESS=100 TAG=done ...
                    val progressMatch = BOOTSTRAP_PROGRESS_REGEX.find(phase)
                    val currentProgress = progressMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    if (currentProgress >= 100) {
                        Log.i(tag, "Tor already bootstrapped to 100% before subscription — counting down")
                        lastProgress.set(100)
                        thisLatch.countDown()
                    } else if (currentProgress > 0) {
                        Log.i(tag, "Tor is mid-bootstrap at $currentProgress% (caught up via GETINFO)")
                        lastProgress.set(currentProgress)
                        broadcastBootstrapProgress(currentProgress)
                    }
                }
            } catch (e: Exception) {
                Log.d(tag, "GETINFO status/bootstrap-phase failed (non-fatal): ${e.message}")
                // Non-fatal — we'll just wait for the BOOTSTRAP event
            }

            // Wait up to 120 seconds for Tor to reach 100% bootstrap.
            // Tor on a clean connection typically bootstraps in 5–30s; 120s
            // gives headroom for slow networks or bridge connections.
            //
            // The latch was already PUBLISHED above (before the GETINFO), so a
            // stop arriving at any point from the moment the control port came
            // up can release this wait. The blocking await below is NOT
            // cancellable — cancelling the job does not interrupt a thread
            // parked in CountDownLatch.await — so without that early handle a
            // disconnect during the (up to 10s) GETINFO would leave the latch
            // unpublished, and the start would park for the full 120s holding
            // startMutex, silently dropping every later start request via
            // tryLock().
            val bootstrapped = try {
                thisLatch.await(120, TimeUnit.SECONDS)
            } finally {
                // Never leave a stale latch behind: a later start would
                // otherwise be able to release the wrong one.
                synchronized(bootstrapLatchLock) {
                    if (bootstrapLatch === thisLatch) bootstrapLatch = null
                }
            }

            // Abort quietly if the user stopped us mid-bootstrap:
            // stopTor()/stopTorInternal() already cleaned up and broadcast
            // DISCONNECTED — don't report a confusing ERROR afterwards.
            if (stopRequested || wasBootstrapAborted()) {
                Log.i(tag, "startTor aborted during bootstrap (deliberate stop=" +
                    "$stopRequested, tor died=" + wasBootstrapAborted() + ")")
                return
            }

            if (!bootstrapped) {
                Log.e(tag, "Tor bootstrap timed out at ${lastProgress.get()}% after 120s")
                broadcastStatus("ERROR", "Tor bootstrap timed out at ${lastProgress.get()}%")
                // The forwarder died on its own, so the kill switch has work to
                // do in VPN mode: traffic must fail closed rather than fall back
                // to the cleartext interface.
                stopTorInternal(unexpectedDisconnect = true)
                return
            }

            isTorRunning = true
            autoRestartAttempts.set(0)
            killSwitch?.setVpnMode(isVpnActive())
            killSwitch?.setEnabled(KillSwitch.isEnabledInPrefs(this))
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

    /** Number of auto-restart attempts since the last successful connect. */
    private val autoRestartAttempts = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Called by [TorProcessManager] when the tor process exits WITHOUT an
     * explicit stop request — i.e. it crashed or was killed by the system.
     *
     * SELF-HEALING: instead of immediately surfacing an ERROR (the old
     * behavior), transparently restarts tor up to
     * [Constants.TOR_AUTO_RESTART_ATTEMPTS] times with linear backoff while
     * the UI shows "CONNECTING". This recovers gracefully from transient
     * kills (OOM, network switch, OEM battery manager) with zero user
     * interaction. Only after exhausting attempts does the user see ERROR.
     *
     * Note: this fires even when the crash happens mid-bootstrap (while
     * [isTorRunning] is still false) — in that case startTorLocked's own
     * timeout path owns the error, so no auto-restart is attempted here.
     */
    private fun onTorProcessExited(exitCode: Int) {
        // Sample the in-progress state FIRST, then release. Order matters: the
        // awaiting thread clears the field in its own finally block, so reading
        // it after the release could observe "false" purely because the waiter
        // got scheduled quickly, and we would then skip the restart.
        val wasBootstrapping = isBootstrapInProgress()

        // Release any bootstrap wait BEFORE the early return below. Tor exiting
        // mid-bootstrap is the case this exists for: the waiter must not sit out
        // the full 120s timeout for a process that is already gone.
        releaseBootstrapWait()

        serviceScope.launch {
            // An intentional stop, or a start that had already failed before it
            // ever reached the bootstrap wait: don't interfere.
            //
            // `wasBootstrapping` matters here. The previous condition was
            // `!isTorRunning`, which is also true while a bootstrap is still in
            // progress (isTorRunning is only set after 100%). So tor dying at
            // 40% returned early: no auto-restart, and the UI sat on
            // "CONNECTING 40%" until the latch timed out and reported a
            // misleading "bootstrap timed out". Retry instead.
            if (stopRequested || (!isTorRunning && !wasBootstrapping)) return@launch

            val attempt = autoRestartAttempts.incrementAndGet()
            if (attempt > Constants.TOR_AUTO_RESTART_ATTEMPTS) {
                autoRestartAttempts.set(0)
                Log.e(tag, "Auto-restart exhausted after $exitCode exit — giving up")
                broadcastStatus("ERROR", "Tor exited repeatedly (last exit code $exitCode)")
                // Tor crashed out repeatedly and we are giving up, so from here
                // on there is no tunnel at all. Fire the kill switch so VPN mode
                // blocks rather than silently leaking.
                stopTorInternal(unexpectedDisconnect = true)
                return@launch
            }

            Log.w(tag, "Tor exited unexpectedly (code $exitCode) — " +
                "auto-restarting (attempt $attempt/${Constants.TOR_AUTO_RESTART_ATTEMPTS})")
            broadcastStatus("CONNECTING")
            updateNotification("OnionRoute Lite — Reconnecting…")

            // Tear down the dead stack first so startTor() starts fresh.
            stopTorInternal()

            // Linear backoff: 3s, 6s, … gives the OS/network time to settle.
            delay(Constants.TOR_AUTO_RESTART_DELAY_BASE_MS * attempt)

            // A user stop during the backoff window must be honored; the flag
            // is NOT cleared by this auto-restart path.
            if (stopRequested) {
                Log.i(tag, "Auto-restart aborted — user requested stop during backoff")
                return@launch
            }
            startTor(clearStopFlag = false)
        }
    }

    private fun stopTor(startId: Int) {
        // User-intent flag FIRST so any in-flight startTor() aborts quietly
        // at its next checkpoint. Internal failure paths (bootstrap timeout,
        // control-connect failure, crash give-up) deliberately do NOT set
        // this — otherwise auto-restart would see a stale "user wants off"
        // flag and refuse to reconnect.
        stopRequested = true

        stopTorInternal()
        broadcastStatus("DISCONNECTED")
        updateNotification("OnionRoute Lite — Disconnected")

        // Release the foreground promotion taken in onStartCommand.
        //
        // Without this, ACTION_STOP_TOR left the service in the foreground
        // indefinitely with a "Disconnected" notification the user could not
        // dismiss, still holding the service alive after Tor was gone. Reachable
        // in normal use: the Home toggle, the QS tile, and TorService's own
        // "stop if the VPN is off" path all end up here.
        //
        // stopTor() has exactly one caller (ACTION_STOP_TOR), so this only ever
        // runs for a user/system-initiated stop — the internal failure paths
        // call stopTorInternal() directly and keep the service alive so
        // auto-restart can still use it. stopSelf() lets the system call
        // onDestroy, which is where the scope is cancelled, so it goes last.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        // stopSelfResult, not stopSelf: stopTor() runs from onStartCommand, so a
        // fast QS-tile double toggle queues ACTION_STOP_TOR then
        // ACTION_START_TOR. Plain stopSelf() tears the service down after the
        // first one, so the START is delivered into a dying service and dropped
        // — the tile then showed "on" with no Tor behind it. stopSelfResult only
        // stops if this startId is still the most recent one.
        stopSelfResult(startId)
    }

    /**
     * Tears down tor, the control connection and the polling jobs.
     *
     * @param unexpectedDisconnect true only when tor failed on its own (as
     *   opposed to the user, a restart, or service teardown asking for it).
     *
     *   This selects whether the KILL SWITCH fires, and it matters more than it
     *   looks. `onTorDisconnected()` broadcasts
     *   "ERROR — Kill switch triggered, Tor disconnected unexpectedly", and this
     *   method used to call it unconditionally. So every ordinary disconnect —
     *   the Home toggle, the QS tile, the service shutting down — surfaced a
     *   spurious error banner claiming an unexpected failure. Since
     *   PREF_KILL_SWITCH_ENABLED defaults to TRUE, that was the behaviour for
     *   every user, every time.
     *
     *   Defaulting to false means a caller must now state explicitly that a
     *   failure was unexpected, so the error is raised when it means something.
     */
    private fun stopTorInternal(unexpectedDisconnect: Boolean = false) {
        if (!isTorRunning) {
            // Even if Tor wasn't fully running, we may have a half-started
            // process + control connection to clean up. Also: this is called
            // from the bootstrap-failure path where isTorRunning is still
            // false, so we MUST still clean up here. Don't early-return.
            Log.d(tag, "stopTorInternal: Tor wasn't fully running, cleaning up anyway")
        }

        Log.i(tag, "Stopping Tor daemon…")

        releaseBootstrapWait()

        // Stop the exit-IP polling job and clear the cached value so
        // the UI doesn't show a stale IP after disconnect.
        exitIpUpdateJob?.cancel()
        exitIpUpdateJob = null
        lastExitIp = ""

        bandwidthTracker?.stop()
        // Broadcast zero bandwidth so the UI clears stale totals/speeds
        // when Tor disconnects. Without this, the HomeScreen keeps showing
        // the last-known totals (e.g. "22.2 MB / 1.2 MB") until the next
        // connection, which is misleading.
        broadcastBandwidth(0, 0, 0, 0)
        torControlConnection?.disconnect()
        torProcessManager?.stopTor()

        torProcessManager = null
        torControlConnection = null
        isTorRunning = false

        // If VPN is active and Tor died (intentionally or unexpectedly),
        // we must also stop the VPN. Without this, the TUN interface stays
        // up but tun2socks can't reach 127.0.0.1:9050 (Tor's SOCKS5) —
        // the user would see "VPN active" but every app would fail to
        // load anything. Stopping the VPN gives the user a clear signal
        // that the connection is gone.
        //
        // If kill switch is ENABLED, we intentionally keep the VPN up so
        // traffic is blocked (no SOCKS5 forwarder → no traffic flows).
        // The user explicitly opted into "block everything if Tor dies".
        val killSwitchOn = killSwitch?.let { KillSwitch.isEnabledInPrefs(this) } ?: false
        // Re-sync the KillSwitch instance from prefs before firing the
        // disconnect hook — the user may have toggled the pref after the
        // service was created (the UI writes prefs directly, not the instance).
        killSwitch?.setEnabled(killSwitchOn)
        val vpnActive = isVpnActive()
        if (vpnActive && !killSwitchOn) {
            Log.i(tag, "Stopping VPN because Tor disconnected (kill switch OFF)")
            val vpnStopIntent = Intent(this, TorVpnService::class.java).apply {
                action = Constants.ACTION_STOP_VPN
            }
            startService(vpnStopIntent)
            // Update prefs so the QS tile / MainActivity reflect the actual state
            getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(Constants.PREF_VPN_ENABLED, false)
                .apply()
        }

        // Only an unexpected loss of tor is a kill-switch event. See the
        // unexpectedDisconnect parameter.
        if (unexpectedDisconnect) {
            killSwitch?.onTorDisconnected()
        }
        Log.i(tag, "Tor stopped")
    }

    private fun toggleSnowflake() {
        val oldValue = isSnowflakeEnabled
        isSnowflakeEnabled = !isSnowflakeEnabled
        val prefs = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)
        prefs.edit().putBoolean(Constants.PREF_SNOWFLAKE_ENABLED, isSnowflakeEnabled).apply()
        Log.i(tag, "Snowflake ${if (isSnowflakeEnabled) "enabled" else "disabled"}")

        // Restart Tor with new bridge config if running. If the restart
        // fails (e.g. invalid bridge config, network issue), revert the
        // preference and local state so the UI toggle stays consistent
        // with what Tor is actually using.
        if (isTorRunning) {
            restartTorWithRevert(
                prefKey = Constants.PREF_SNOWFLAKE_ENABLED,
                oldValue = oldValue,
                onRevert = { isSnowflakeEnabled = oldValue }
            )
        }
    }

    private fun toggleObfs4() {
        val oldValue = isObfs4Enabled
        isObfs4Enabled = !isObfs4Enabled
        val prefs = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)
        prefs.edit().putBoolean(Constants.PREF_OBFS4_ENABLED, isObfs4Enabled).apply()
        Log.i(tag, "obfs4 ${if (isObfs4Enabled) "enabled" else "disabled"}")

        if (isTorRunning) {
            restartTorWithRevert(
                prefKey = Constants.PREF_OBFS4_ENABLED,
                oldValue = oldValue,
                onRevert = { isObfs4Enabled = oldValue }
            )
        }
    }

    private fun toggleWebTunnel() {
        val oldValue = isWebTunnelEnabled
        isWebTunnelEnabled = !isWebTunnelEnabled
        val prefs = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)
        prefs.edit().putBoolean(Constants.PREF_WEBTUNNEL_ENABLED, isWebTunnelEnabled).apply()
        Log.i(tag, "WebTunnel ${if (isWebTunnelEnabled) "enabled" else "disabled"}")

        if (isTorRunning) {
            restartTorWithRevert(
                prefKey = Constants.PREF_WEBTUNNEL_ENABLED,
                oldValue = oldValue,
                onRevert = { isWebTunnelEnabled = oldValue }
            )
        }
    }

    /**
     * Restarts Tor after a bridge toggle. If the restart fails (Tor doesn't
     * reach CONNECTED state), reverts the preference and calls [onRevert]
     * so the local variable is restored to its pre-toggle value.
     *
     * This prevents the situation where:
     *   1. User toggles Snowflake ON while Tor is running
     *   2. Tor stops, tries to restart with Snowflake, fails
     *   3. Prefs say Snowflake is enabled, but Tor isn't running
     *   4. UI shows bridge toggle ON + connection ERROR (confusing)
     *
     * With this fix:
     *   1. User toggles Snowflake ON while Tor is running
     *   2. Tor stops, tries to restart with Snowflake, fails
     *   3. Preference reverted to OFF, local var reverted to false
     *   4. UI shows bridge toggle OFF + connection ERROR (consistent)
     *
     * The user can then try a different bridge or connect without bridges.
     */
    private fun restartTorWithRevert(
        prefKey: String,
        oldValue: Boolean,
        onRevert: () -> Unit
    ) {
        serviceScope.launch {
            stopTorInternal()
            startTor()
            // startTor() broadcasts ERROR on failure and leaves isTorRunning=false.
            // If Tor didn't connect, revert the preference and local state.
            if (!isTorRunning) {
                Log.w(tag, "Tor restart failed after bridge toggle — reverting $prefKey to $oldValue")
                getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putBoolean(prefKey, oldValue)
                    .apply()
                onRevert()
                // Broadcast the reverted state so the UI updates.
                // The ERROR broadcast from startTor() already informed the
                // user that the connection failed; this just ensures the
                // bridge toggle chip shows the correct state.
            }
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

    /**
     * Circuit Viewer: fetches `GETINFO circuit-status` over the control port
     * and broadcasts the parsed list to the UI as a JSON array.
     * Called by the Circuits screen every few seconds while it is visible.
     */
    private fun requestCircuits() {
        val conn = torControlConnection
        if (conn == null || !isTorRunning) return

        serviceScope.launch {
            try {
                val raw = conn.getInfo("circuit-status")
                val json = TorCircuit.toJsonArray(TorCircuit.parseAll(raw))
                val intent = Intent(Constants.ACTION_CIRCUITS_UPDATE).apply {
                    putExtra(Constants.EXTRA_CIRCUITS_JSON, json)
                    setPackage(packageName)
                }
                sendBroadcast(intent)
            } catch (e: Exception) {
                Log.d(tag, "GETINFO circuit-status failed: ${e.message}")
            }
        }
    }

    /**
     * Exit Country Selector: applies the chosen restriction to a RUNNING tor
     * session via SETCONF (no restart needed). The choice is also persisted
     * in prefs and written into future torrc files by generateTorrc().
     */
    private fun applyExitCountry(code: String?) {
        if (!isTorRunning) {
            // Not connected — nothing to apply live; the pref is already
            // saved and will land in the next torrc generation.
            Log.i(tag, "Exit country '$code' saved (applies on next connect)")
            return
        }

        serviceScope.launch {
            try {
                val ok = torControlConnection?.setExitCountry(code) ?: false
                Log.i(tag, "Exit country applied ('$code'): $ok")
            } catch (e: Exception) {
                Log.e(tag, "Failed to apply exit country", e)
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
            // PERF: back off exponentially when fetches fail (tor degraded,
            // circuit churn, no network). A healthy connection polls every
            // 15s as before; after repeated failures the interval stretches
            // up to 4x (60s), instead of firing a doomed HTTPS-through-SOCKS
            // request every 15s forever.
            var consecutiveFailures = 0
            while (isTorRunning && isActive && torControlConnection != null) {
                try {
                    // HEALTH: the probe doubles as a real end-to-end latency
                    // sample (SOCKS → tor circuit → guard → … → exit →
                    // check.torproject.org). Timing the existing request adds
                    // a connection-health datapoint at zero extra network cost.
                    val startMs = android.os.SystemClock.elapsedRealtime()
                    val ip = fetchExitIp()
                    val latencyMs = android.os.SystemClock.elapsedRealtime() - startMs

                    if (ip != null) {
                        consecutiveFailures = 0
                        broadcastExitIp(ip)
                        broadcastHealthSample(latencyMs)
                    } else {
                        Log.d(tag, "Exit IP fetch returned null — Tor may not be carrying traffic yet")
                        consecutiveFailures++
                        broadcastHealthSample(-1L)
                    }
                } catch (e: Exception) {
                    Log.w(tag, "Exit IP fetch failed: ${e.message}")
                    consecutiveFailures++
                    broadcastHealthSample(-1L)
                }
                val backoffFactor = 1L + consecutiveFailures.coerceAtMost(3)
                delay(Constants.EXIT_IP_UPDATE_INTERVAL_MS * backoffFactor)
            }
            Log.i(tag, "Exit IP update job ended")
        }
    }

    /** Broadcasts one connection-health latency sample to the UI. */
    private fun broadcastHealthSample(latencyMs: Long) {
        val intent = Intent(Constants.ACTION_HEALTH_UPDATE).apply {
            putExtra(Constants.EXTRA_HEALTH_LATENCY_MS, latencyMs)
            putExtra(Constants.EXTRA_HEALTH_TIMESTAMP, System.currentTimeMillis())
            setPackage(packageName)
        }
        sendBroadcast(intent)
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
            val isTorMatch = IS_TOR_REGEX.find(body)
            val ipMatch = IP_REGEX.find(body)
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
