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

    // PERF: precompiled once — parseProxyLine runs for EVERY line the proxy
    // prints, and Regex compilation is expensive (pattern parsing + native
    // structure). The old code recompiled these on every single line.
    private val DATA_RELAYED_REGEX = Regex("""data\s+relayed:\s+(\d+)""")

    // Word-boundary matchers for the proxy's client lifecycle lines.
    //
    // The previous `trimmed.contains("connected") && !contains("disconnected")`
    // inflated the "PEOPLE HELPED" counter permanently: "reconnected" contains
    // "connected" with no word boundary, as do "connecting", "connection
    // established" and any diagnostic mentioning the word. \b requires a
    // non-word character on both sides, so "reconnected" (word char before)
    // and "connecting" (no literal "connected") both correctly fail to match.
    private val CONNECTED_REGEX = Regex("""\bconnected\b""")
    private val DISCONNECTED_REGEX = Regex("""\bdisconnected\b""")

    /**
     * Serializes proxy start/stop so they cannot interleave.
     *
     * Three racing paths used to exist: onStartCommand (main thread), the
     * monitor thread's own restart after `Thread.sleep(backoff)`, and
     * onDestroy. The dangerous interleaving was monitor-past-sleep →
     * startProxy() running CONCURRENTLY with stopProxyInternal(): the stop
     * nulls proxyProcess and returns, then the restart assigns a fresh
     * Process to it. Nothing ever destroys that process, so the snowflake
     * proxy keeps running — connected to the broker, relaying for peers —
     * after the user turned Kindness Mode off, holding the service alive and
     * its memory resident for as long as the process lived.
     *
     * startProxy and stopProxyInternal both take this lock, so a restart either
     * completes before the stop begins (and is then destroyed) or observes the
     * stopped state and declines to start.
     */
    private val proxyLifecycleLock = Any()

    /**
     * The running proxy, or null.
     *
     * @Volatile because it is written on the main thread (onStartCommand,
     * onDestroy) and read on the monitor and stdout threads. Without it the
     * monitor thread could observe a stale non-null value and act on a process
     * that had already been destroyed, or miss the exit it was waiting on.
     */
    @Volatile
    private var proxyProcess: Process? = null

    /** @Volatile for the same reason: read/written across the main, monitor
     *  and stdout threads. */
    @Volatile
    private var monitorThread: Thread? = null

    @Volatile
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
            Constants.ACTION_RESTART_KINDNESS_PROXY -> {
                // Apply a changed donation level (-capacity) without touching
                // the enabled state or notification flow: only meaningful if
                // the proxy is currently running.
                if (proxyProcess != null) {
                    Log.i(tag, "Restarting proxy to apply new donation level")
                    stopProxyInternal()
                    startProxy()
                }
            }
            null -> handleStickyRestart()
            else -> {
                consecutiveFailures.set(0)
                startProxy()
            }
        }

        return START_STICKY
    }

    /**
     * START_STICKY redelivery: Android restarted this service with a null
     * intent after the process was killed.
     *
     * The previous `else -> startProxy()` branch also handled this case, which
     * meant a sticky restart ignored PREF_KINDNESS_ENABLED entirely: if the user
     * turned Kindness Mode off and the process was later killed (low-memory kill
     * on this 1.5GB device is routine), the system would bring the service back
     * and it would re-register with the Snowflake broker and relay for peers the
     * user had chosen not to help.
     *
     * Honour the persisted intent instead: resume only if Kindness Mode was
     * actually enabled, otherwise tear the foreground promotion down and stop.
     */
    private fun handleStickyRestart() {
        val prefs = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)
        val kindnessEnabled = prefs.getBoolean(Constants.PREF_KINDNESS_ENABLED, false)
        if (kindnessEnabled) {
            Log.i(tag, "Sticky restart \u2014 restoring previous Kindness session")
            consecutiveFailures.set(0)
            startProxy()
        } else {
            Log.i(tag, "Sticky restart \u2014 Kindness Mode was not enabled; stopping")
            stopProxyInternal()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
        }
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
        // Serialize the whole start against stopProxyInternal(). Without this,
        // a monitor-thread restart that had just woken from its backoff sleep
        // could be mid-start when the user hit STOP: the stop would null
        // proxyProcess and return, and this method would then assign a brand-new
        // Process to it. Nothing would ever destroy that process — an orphaned
        // snowflake proxy still connected to the broker and relaying for peers
        // after Kindness Mode was switched off, holding its memory resident for
        // as long as it lived.
        synchronized(proxyLifecycleLock) { startProxyLocked() }
    }

    /**
     * Body of [startProxy]. Must be called with [proxyLifecycleLock] held.
     *
     * The already-running check is repeated inside the lock because the caller's
     * own check happens before it: another thread can start a proxy in the gap,
     * and skipping that would leave two processes racing to bind the proxy.
     */
    private fun startProxyLocked() {
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
            //   -capacity N     — max concurrent clients (0/omitted = unlimited)
            //   -unsafe-logging — show IP addresses in logs for stats parsing
            //
            // DO NOT USE: -front (removed in v2), -ice (renamed to -stun),
            //   -log info (takes filename not level)
            //
            // Donation Level (Settings → KINDNESS): -capacity caps concurrent
            // clients, which effectively bounds donated bandwidth. 0 = omit
            // the flag entirely (upstream default = unlimited).
            val capacity = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)
                .getInt(Constants.PREF_KINDNESS_CAPACITY, Constants.KINDNESS_CAPACITY_DEFAULT)

            val args = mutableListOf(
                proxyBinary.absolutePath,
                "-broker", Constants.SNOWFLAKE_BROKER_URL,
                "-stun", Constants.SNOWFLAKE_ICE_SERVERS,
                "-relay", "wss://snowflake.torproject.net/"
            )
            if (capacity > 0) {
                args.add("-capacity")
                args.add(capacity.toString())
            }
            args.add("-unsafe-logging")

            val processBuilder = ProcessBuilder(args)

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
            // Flush the live counters BEFORE any restart path re-reads them from
        // prefs. Both restart paths (the monitor's own backoff restart and
        // ACTION_RESTART_KINDNESS_PROXY) call startProxyLocked directly and so
        // never went through stopProxy()'s exact flush; without this, the
        // restore below would overwrite the in-memory totals with the last
        // THROTTLED value, permanently losing up to 30s of relayed bytes and
        // helped-client counts.
        persistStats()
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
        // Take the lifecycle lock so this cannot interleave with a startProxy()
        // that a monitor-thread restart is already inside. See the note on
        // proxyLifecycleLock for the orphaned-process failure this prevents.
        //
        // Also join the monitor thread before returning, so a caller that is
        // about to start a replacement (ACTION_RESTART_KINDNESS_PROXY) cannot
        // have the old monitor wake up afterwards and start a SECOND proxy on
        // top of it.
        val orphanedMonitor = synchronized(proxyLifecycleLock) {
            val m = monitorThread
            monitorThread = null
            m
        }
        val orphanedStdout = synchronized(proxyLifecycleLock) {
            val s = stdoutThread
            stdoutThread = null
            s
        }
        val orphanedProcess = synchronized(proxyLifecycleLock) {
            val p = proxyProcess
            proxyProcess = null
            p
        }

        // PER-STEP try/catch. These were one block, so an exception from the
        // first interrupt skipped process destruction entirely — leaving the
        // proxy running and unreachable. Each cleanup step is independent, and
        // destroying the process is the one that must never be skipped.
        // Destroy BEFORE joining. The monitor is parked in Process.waitFor(),
        // which returns only when the process actually exits, so joining first
        // always burned the full timeout on a healthy proxy — on the main thread,
        // since stopProxyInternal is reached from onStartCommand/onDestroy. That
        // was a guaranteed visible freeze on every stop, on every donation-level
        // restart, and on service teardown.
        try {
            orphanedProcess?.destroy()
        } catch (e: Exception) {
            Log.e(tag, "Error destroying snowflake proxy", e)
        }

        try {
            orphanedStdout?.interrupt()
        } catch (e: Exception) {
            Log.w(tag, "Error interrupting snowflake stdout reader", e)
        }

        try {
            orphanedMonitor?.interrupt()
        } catch (e: Exception) {
            Log.w(tag, "Error interrupting snowflake monitor", e)
        }

        // Bounded join: the monitor is parked in Process.waitFor() or in the
        // restart backoff, and interrupt only breaks the sleep. Give it a
        // moment to notice so it cannot fire a restart after we return.
        try {
            orphanedMonitor?.join(MONITOR_JOIN_TIMEOUT_MS)
            if (orphanedMonitor?.isAlive == true) {
                Log.w(tag, "Snowflake monitor still alive after " +
                    "${MONITOR_JOIN_TIMEOUT_MS}ms — not starting a replacement")
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            Log.w(tag, "Error joining snowflake monitor", e)
        }

        Log.i(tag, "Snowflake proxy stopped")

        // Reset active connection count since proxy is stopped
        peopleHelpedNow.set(0)
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
     *
     * NOTE: we deliberately parse ONLY per-client disconnect lines.
     * Earlier revisions ALSO added bytes from periodic/session-summary
     * lines ("... data relayed ... session ..." and any "<n> bytes
     * sent/received" with a <10MB guess), which double-counted the same
     * traffic that was already counted when each client disconnected,
     * inflating the DATA DONATED stat.
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

        // "disconnected" also matches \bconnected\b? No — "disconnected" has a
        // word character before "connected", so CONNECTED_REGEX cannot match
        // inside it. Still test disconnect first and let it win, so a line
        // carrying both cannot be counted as a new client.
        val isDisconnected = DISCONNECTED_REGEX.containsMatchIn(trimmed)
        val isConnected = !isDisconnected && CONNECTED_REGEX.containsMatchIn(trimmed)

        // Client connected
        if (isConnected) {
            peopleHelpedNow.incrementAndGet()
            peopleHelpedTotal.incrementAndGet()
            broadcastKindnessStats()
        }

        // Client disconnected — look for data relayed
        if (isDisconnected) {
            decrementClampedAtZero(peopleHelpedNow)
            // Extract bytes: "data relayed: 1234567 B" or "data relayed: 1234567 bytes"
            DATA_RELAYED_REGEX.find(trimmed)?.groupValues?.get(1)?.toLongOrNull()?.let { bytes ->
                dataDonated.addAndGet(bytes)
            }
            broadcastKindnessStats()
        }
    }

    // ── Stats Persistence & Broadcasting ──────────────────────────

    /**
     * Decrements [counter] by one, clamping at zero.
     *
     * API-23 COMPAT: `AtomicLong.getAndUpdate` is a Java 8 default method that
     * Android only added in API 24. This app's minSdk is 23 and its primary
     * target is an Android 6.0 device, so calling it there throws
     * NoSuchMethodError — and it fired from the proxy's stdout reader thread on
     * every "disconnected" line, i.e. the crash path was reachable in normal
     * use, not just at startup. A compare-and-set loop gives identical
     * semantics on every supported API level.
     *
     * CAS loop rather than a synchronized block: the stdout reader runs on a
     * dedicated daemon thread while getStats() reads from the UI thread, and
     * the increment side already uses lock-free atomics, so this keeps both
     * halves consistent without introducing a lock.
     */
    private fun decrementClampedAtZero(counter: AtomicLong) {
        while (true) {
            val current = counter.get()
            if (current <= 0L) return
            // compareAndSet fails only if another thread changed the value
            // between get() and compareAndSet(); re-read and retry then.
            if (counter.compareAndSet(current, current - 1L)) return
        }
    }

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

    /**
     * Wall-clock of the last successful [persistStatsThrottled] write.
     * 0 means "never written", so the first event always persists.
     */
    private val lastPersistMs = AtomicLong(0L)

    /**
     * Persists stats at most once every [PERSIST_THROTTLE_MS].
     *
     * Broadcasting is cheap and stays per-event. Persisting is not:
     * SharedPreferences.apply() rewrites the backing XML and fsyncs, so doing
     * it on every client connect/disconnect meant a flash write per event for
     * as long as the proxy ran. On an 8GB device with a busy relay that is real
     * wear for data that is at most a few counters.
     *
     * The exact total is not worth a write per event — it is a "people helped"
     * counter, not a ledger — and the value is always flushed exactly by
     * stopProxy() before the process can end, so a crash can lose at most one
     * throttle window.
     */
    private fun persistStatsThrottled() {
        val now = System.currentTimeMillis()
        val last = lastPersistMs.get()
        if (last != 0L && now - last < PERSIST_THROTTLE_MS) return
        // CAS so concurrent events cannot all decide to write.
        if (!lastPersistMs.compareAndSet(last, now)) return
        persistStats()
    }

    private fun broadcastKindnessStats() {
        persistStatsThrottled()

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
                // Identity-checked. An unconditional `proxyProcess = null` here
                // could land AFTER stopProxyInternal() returned and a
                // replacement proxy had already been assigned, wiping the NEW
                // process's handle. stopProxyInternal() would then see null and
                // never destroy it — reintroducing exactly the orphaned proxy
                // that proxyLifecycleLock was added to prevent, and defeating
                // the `if (proxyProcess != null)` guard so a second proxy could
                // be started on top of the first.
                if (proxyProcess === process) {
                    proxyProcess = null
                }

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

        /**
         * How long stopProxyInternal() waits for the monitor thread to notice
         * its interrupt before proceeding.
         *
         * The monitor is parked either in Process.waitFor() or in the restart
         * backoff sleep; interrupt breaks the sleep but waitFor() returns only
         * when the process actually dies. One second is ample for the sleep
         * case and bounded for the waitFor case, because destroy() below is
         * what unblocks it — the join happens before destroy() only to keep the
         * window small, so an orphaned proxy is never left waiting on a
         * thread that intends to restart it.
         */
        private const val MONITOR_JOIN_TIMEOUT_MS = 1000L

        /**
         * Minimum interval between SharedPreferences writes of the kindness
         * counters. 30s bounds flash churn to two writes a minute while still
         * losing at most 30s of totals on an unexpected kill — and
         * stopProxy() always writes the exact figures before exiting.
         */
        private const val PERSIST_THROTTLE_MS = 30_000L
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
