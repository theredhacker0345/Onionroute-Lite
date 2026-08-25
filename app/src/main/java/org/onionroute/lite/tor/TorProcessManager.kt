package org.onionroute.lite.tor

import android.content.Context
import android.util.Log
import org.onionroute.lite.Constants
import java.io.File
import java.io.FileOutputStream

/**
 * Manages the native Tor process lifecycle.
 *
 * Responsible for:
 * - Locating and verifying native binaries in the extracted nativeLibraryDir
 * - Generating the torrc configuration file
 * - Starting and stopping the Tor daemon process
 * - Monitoring the Tor process for unexpected exits
 *
 * Binary execution strategy:
 *   Native binaries (libtor.so, libobfs4proxy.so, etc.) are PIE executables
 *   that must be run via ProcessBuilder. Android only allows executing files
 *   from the nativeLibraryDir — the app's filesDir is mounted noexec on
 *   Android 10+. We require android:extractNativeLibs="true" + useLegacyPackaging
 *   so that Android extracts the .so files to disk where we can exec() them.
 *   We execute them directly from nativeLibraryDir without copying.
 */
class TorProcessManager(
    private val context: Context,
    /** Invoked when the tor process exits on its own (NOT after an explicit
     *  [stopTor] call). Runs on the monitor thread — dispatch appropriately. */
    private val onProcessExited: ((exitCode: Int) -> Unit)? = null
) {

    private val tag = "TorProcessManager"

    private var torProcess: Process? = null
    private var monitorThread: Thread? = null

    /** Set to true by [stopTor] so the monitor thread can distinguish an
     *  intentional shutdown (destroy()) from an unexpected tor crash. */
    @Volatile private var stopRequested = false

    val controlCookieFile: String
        get() = File(context.filesDir, "${Constants.TOR_DATA_DIR}/${Constants.TOR_CONTROL_COOKIE}").absolutePath

    private val appDataDir: String
        get() = context.filesDir.absolutePath

    private val nativeLibDir: String
        get() = context.applicationInfo.nativeLibraryDir

    /** Minimum valid binary size in bytes. Anything smaller is a placeholder/stub. */
    companion object {
        private const val MIN_BINARY_SIZE = 1024L
    }

    // ── Public API ────────────────────────────────────────────────

    /**
     * Starts the Tor daemon with the specified bridge configuration.
     *
     * @param useSnowflake Whether to use Snowflake bridges
     * @param useObfs4 Whether to use obfs4 bridges
     * @param useWebTunnel Whether to use WebTunnel bridges
     * @return true if Tor was started successfully, false otherwise
     */
    fun startTor(useSnowflake: Boolean = false, useObfs4: Boolean = false, useWebTunnel: Boolean = false): Boolean {
        if (torProcess != null) {
            Log.w(tag, "Tor process already running")
            return true
        }

        return try {
            if (!verifyBinaries()) {
                Log.e(tag, "Native binary verification failed — cannot start Tor")
                return false
            }
            generateTorrc(useSnowflake, useObfs4, useWebTunnel)
            startTorProcess()
        } catch (e: Exception) {
            Log.e(tag, "Failed to start Tor", e)
            false
        }
    }

    /**
     * Stops the Tor daemon process.
     */
    fun stopTor() {
        try {
            // Mark intentional shutdown BEFORE destroy() so the monitor
            // thread doesn't report this exit as an unexpected crash.
            stopRequested = true

            monitorThread?.interrupt()
            monitorThread = null

            torProcess?.destroy()
            torProcess = null

            Log.i(tag, "Tor process stopped")
        } catch (e: Exception) {
            Log.e(tag, "Error stopping Tor process", e)
        }
    }

    // ── Binary Verification ───────────────────────────────────────

    /**
     * Verifies that all required native binaries exist in nativeLibraryDir
     * and are real compiled binaries (not placeholder stubs).
     *
     * With android:extractNativeLibs="true" and useLegacyPackaging, Android
     * extracts .so files from the APK to the nativeLibraryDir on install.
     * We execute them directly from there — no copying to filesDir needed.
     *
     * The nativeLibraryDir is the ONLY directory on Android where exec() is
     * guaranteed to work. The app's filesDir is mounted noexec on Android 10+.
     *
     * @return true if the critical Tor binary was found and is valid
     */
    private fun verifyBinaries(): Boolean {
        val requiredBinaries = listOf(
            Constants.BINARY_TOR,
            Constants.BINARY_OBFS4PROXY,
            Constants.BINARY_SNOWFLAKE,
            Constants.BINARY_SNOWFLAKE_PROXY,
            Constants.BINARY_TUN2SOCKS,
            Constants.BINARY_WEBTUNNEL
        )

        var torValid = false
        var missingCount = 0
        var stubCount = 0

        for (libName in requiredBinaries) {
            val file = File(nativeLibDir, libName)
            if (!file.exists()) {
                if (libName == Constants.BINARY_TOR) Log.e(tag, "CRITICAL: Native binary NOT FOUND: $libName at $nativeLibDir/$libName") else Log.w(tag, "WARNING: Optional binary not found: $libName — bridge feature unavailable")
                missingCount++
                continue
            }

            // Validate binary is a real compiled binary, not a placeholder stub
            val size = file.length()
            if (size < MIN_BINARY_SIZE) {
                Log.e(tag, "CRITICAL: $libName is only $size bytes — rejecting as placeholder/stub")
                Log.e(tag, "Real binaries should be at least several hundred KB. " +
                    "Rebuild with: ./scripts/build-all.sh")
                stubCount++
                continue
            }

            // Ensure execute permission (should already be set, but be safe)
            if (!file.canExecute()) {
                val set = file.setExecutable(true, false)
                Log.d(tag, "Set executable on $libName: $set")
            }

            // Also chmod as safety net for certain Android versions/filesystems
            try {
                Runtime.getRuntime().exec(arrayOf("chmod", "755", file.absolutePath)).waitFor()
            } catch (e: Exception) {
                Log.w(tag, "chmod fallback failed for ${file.absolutePath}: ${e.message}")
            }

            Log.d(tag, "Found native binary: $libName ($size bytes, executable=${file.canExecute()})")

            if (libName == Constants.BINARY_TOR) {
                torValid = true
            }
        }

        if (missingCount > 0 || stubCount > 0) {
            Log.e(tag, "$missingCount missing, $stubCount stub binaries. nativeLibraryDir=$nativeLibDir")
            Log.e(tag, "Ensure android:extractNativeLibs=true, useLegacyPackaging=true, " +
                "and jniLibs/ contains real compiled .so files (not placeholders)")
        }

        return torValid
    }

    /**
     * Returns the path to a native binary in nativeLibraryDir.
     * Returns null if the binary doesn't exist or is a placeholder stub.
     */
    private fun getBinaryPath(libName: String): String? {
        val file = File(nativeLibDir, libName)
        if (!file.exists()) return null
        if (file.length() < MIN_BINARY_SIZE) {
            Log.w(tag, "Skipping stub binary: $libName (${file.length()} bytes)")
            return null
        }
        return file.absolutePath
    }

    // ── torrc Generation ──────────────────────────────────────────

    private fun generateTorrc(useSnowflake: Boolean, useObfs4: Boolean, useWebTunnel: Boolean) {
        val torDataDir = File(appDataDir, Constants.TOR_DATA_DIR)
        if (!torDataDir.exists()) torDataDir.mkdirs()

        // Resolve executable paths from nativeLibraryDir
        val snowflakePath = getBinaryPath(Constants.BINARY_SNOWFLAKE)
        val obfs4Path = getBinaryPath(Constants.BINARY_OBFS4PROXY)
        val webtunnelPath = getBinaryPath(Constants.BINARY_WEBTUNNEL)

        // BridgeUpdater — fetches alive bridges from GitHub at runtime.
        // Falls back to hardcoded bridges in Constants.kt if fetch fails.
        // Use the process-wide singleton so the cache is shared with
        // MainActivity (which triggers fetchBridges on startup).
        val bridgeUpdater = BridgeUpdater.get(context)

        // Bridge lines and their ClientTransportPlugin registrations are
        // collected first, then emitted. Tor REQUIRES a ClientTransportPlugin
        // for EVERY transport referenced by a Bridge line — without it, tor
        // passes --verify-config but bootstrap stalls forever at 5% with
        // PT_MISSING ("can't find a pluggable transport proxy supporting
        // 'webtunnel'"). So a bridge line is only emitted when its plugin
        // binary actually exists, and 'UseBridges 1' is only emitted when at
        // least one bridge line survived (UseBridges 1 with zero bridges =
        // tor can never connect).
        val pluginLines = LinkedHashSet<String>()
        val bridgeLines = mutableListOf<String>()

        fun transportOf(line: String): String? = line.trim().split(" ").firstOrNull()?.lowercase()

        fun pluginAvailable(transport: String): Boolean = when (transport) {
            "obfs4" -> obfs4Path != null
            "snowflake" -> snowflakePath != null
            "webtunnel" -> webtunnelPath != null
            else -> false  // meek & other transports have no bundled binary
        }

        fun registerPlugin(transport: String) {
            when (transport) {
                "obfs4" -> obfs4Path?.let { pluginLines += "ClientTransportPlugin obfs4 exec $it" }
                "snowflake" -> snowflakePath?.let { pluginLines += "ClientTransportPlugin snowflake exec $it" }
                "webtunnel" -> webtunnelPath?.let { pluginLines += "ClientTransportPlugin webtunnel exec $it" }
            }
        }

        fun addBridges(lines: List<String>, source: String) {
            for (bridge in lines) {
                val transport = transportOf(bridge)
                if (transport == null || !pluginAvailable(transport)) {
                    Log.w(tag, "$source: skipping bridge line — no plugin binary for transport '$transport': ${bridge.take(48)}…")
                    continue
                }
                registerPlugin(transport)
                bridgeLines += bridge
            }
        }

        if (useSnowflake) {
            if (pluginAvailable("snowflake")) {
                val bridges = bridgeUpdater.getBridges("snowflake")
                if (bridges.isNotEmpty()) {
                    Log.i(tag, "Using ${bridges.size} snowflake bridges from BridgeUpdater")
                } else {
                    Log.i(tag, "No runtime snowflake bridges — using hardcoded")
                }
                addBridges(bridges.ifEmpty { listOf(snowflakeFallbackLine()) }, "snowflake")
            } else {
                Log.e(tag, "Snowflake requested but libsnowflake.so not found in $nativeLibDir — skipping Snowflake bridges")
            }
        }

        if (useObfs4) {
            if (pluginAvailable("obfs4")) {
                val bridges = bridgeUpdater.getBridges("obfs4")
                if (bridges.isNotEmpty()) {
                    Log.i(tag, "Using ${bridges.size} obfs4 bridges from BridgeUpdater")
                } else {
                    Log.i(tag, "No runtime obfs4 bridges — using hardcoded")
                }
                addBridges(bridges.ifEmpty { Constants.DEFAULT_OBFS4_BRIDGES }, "obfs4")
            } else {
                Log.e(tag, "obfs4 requested but libobfs4proxy.so not found in $nativeLibDir — skipping obfs4 bridges")
            }
        }

        if (useWebTunnel) {
            if (pluginAvailable("webtunnel")) {
                val bridges = bridgeUpdater.getBridges("webtunnel")
                if (bridges.isNotEmpty()) {
                    Log.i(tag, "Using ${bridges.size} webtunnel bridges from BridgeUpdater")
                } else {
                    Log.i(tag, "No runtime webtunnel bridges — using hardcoded")
                }
                addBridges(bridges.ifEmpty { Constants.DEFAULT_WEBTUNNEL_BRIDGES }, "webtunnel")
            } else {
                // WebTunnel IS a pluggable transport: it needs its own client
                // binary (libwebtunnel.so) registered via ClientTransportPlugin.
                // Emitting bare webtunnel Bridge lines without the plugin makes
                // tor stall at PT_MISSING — worse than not using WebTunnel at
                // all. Skip with a loud error instead of producing a config
                // that can never bootstrap.
                Log.e(tag, "WebTunnel requested but libwebtunnel.so not found in $nativeLibDir — skipping WebTunnel bridges. " +
                    "Rebuild natives with ./scripts/build-webtunnel.sh to enable WebTunnel.")
            }
        }

        // Custom bridges (manually added by user via Bridge screen) are added
        // IN ADDITION to any toggle-controlled bridge sets. Each line is
        // checked against available plugins so an unsupported custom transport
        // can't stall the whole bootstrap.
        val customBridges = BridgeConfig(context).getCustomBridges()
        if (customBridges.isNotEmpty()) {
            addBridges(customBridges, "custom")
            Log.i(tag, "Added ${customBridges.size} custom bridges from user prefs")
        }

        val torrcContent = buildString {
            appendLine("# OnionRoute Lite torrc — auto-generated")
            appendLine()
            appendLine("SocksPort ${Constants.TOR_SOCKS_PORT}")
            appendLine("HTTPTunnelPort ${Constants.TOR_HTTP_PORT}")
            appendLine("ControlPort ${Constants.TOR_CONTROL_PORT}")
            appendLine("DNSPort 127.0.0.1:${Constants.TOR_DNS_PORT}")
            appendLine("CookieAuthentication 1")
            // Explicit cookie file path — TorProcessManager.controlCookieFile
            // reads from this exact path. Without this directive Tor defaults
            // to DataDirectory/control_auth_cookie which happens to match,
            // but making it explicit avoids surprises if DataDirectory changes.
            appendLine("CookieAuthFile ${File(torDataDir, Constants.TOR_CONTROL_COOKIE).absolutePath}")
            appendLine("CookieAuthFileGroupReadable 1")
            appendLine("AvoidDiskWrites 1")
            appendLine("StrictNodes 1")
            // LOW-RAM: bound tor's in-memory queue buffers (default scales
            // with system RAM and can grow under heavy multi-app VPN load).
            // See Constants.TOR_MAX_MEM_IN_QUEUE.
            appendLine("MaxMemInQueue ${Constants.TOR_MAX_MEM_IN_QUEUE}")
            appendLine("DataDirectory ${torDataDir.absolutePath}")
            appendLine("Log notice stdout")

            // GeoIP files are optional — Tor works without them but logs warnings.
            // Only include the directives if the files exist to avoid startup errors.
            val geoipFile = File(appDataDir, "geoip")
            val geoip6File = File(appDataDir, "geoip6")
            if (geoipFile.exists()) {
                appendLine("GeoIPFile ${geoipFile.absolutePath}")
            } else {
                Log.w(tag, "GeoIP file not found — country-based routing unavailable")
            }
            if (geoip6File.exists()) {
                appendLine("GeoIPv6File ${geoip6File.absolutePath}")
            }
            appendLine()

            // Pluggable transport plugins + bridge lines
            if (pluginLines.isNotEmpty() || bridgeLines.isNotEmpty()) {
                appendLine("# Pluggable transport clients")
                pluginLines.forEach { appendLine(it) }
                appendLine()
                if (bridgeLines.isNotEmpty()) {
                    appendLine("# Bridges (${bridgeLines.size})")
                    bridgeLines.forEach { appendLine("Bridge $it") }
                    appendLine("UseBridges 1")
                    appendLine()
                    Log.i(tag, "torrc: ${bridgeLines.size} bridge line(s), ${pluginLines.size} plugin(s), UseBridges 1")
                }
            }
        }

        val torrcFile = File(appDataDir, Constants.TORRC_FILENAME)
        FileOutputStream(torrcFile).use { it.write(torrcContent.toByteArray()) }
        Log.d(tag, "Generated torrc at ${torrcFile.absolutePath}")
    }

    /** Hardcoded fallback snowflake bridge line (used only when the runtime
     *  bridge list is empty AND libsnowflake.so exists). */
    private fun snowflakeFallbackLine(): String =
        "snowflake 192.0.2.3:80 2B280B23E1107BB62ABFC40DDCC8824814F80A72 " +
            "fingerprint=2B280B23E1107BB62ABFC40DDCC8824814F80A72 " +
            "url=${Constants.SNOWFLAKE_BROKER_URL} fronts=${Constants.SNOWFLAKE_FRONT_DOMAIN} " +
            "ice=${Constants.SNOWFLAKE_ICE_SERVERS}"

    // ── Process Management ────────────────────────────────────────

    private fun startTorProcess(): Boolean {
        // Execute libtor.so directly from nativeLibraryDir
        val torPath = getBinaryPath(Constants.BINARY_TOR)
        if (torPath == null) {
            Log.e(tag, "libtor.so not found or is a stub in $nativeLibDir — cannot start Tor")
            return false
        }

        // Final sanity check on binary size
        val torFile = File(torPath)
        if (torFile.length() < MIN_BINARY_SIZE) {
            Log.e(tag, "Tor binary is only ${torFile.length()} bytes — not a real binary (expected several MB)")
            return false
        }

        if (!torFile.canExecute()) {
            Log.e(tag, "Tor binary at $torPath is not executable")
            return false
        }

        val torrcPath = File(appDataDir, Constants.TORRC_FILENAME).absolutePath

        try {
            // Step 1: Verify config with --verify-config
            val verifyBuilder = ProcessBuilder(
                torPath,
                "-f", torrcPath,
                "--verify-config"
            )
            verifyBuilder.environment()["HOME"] = appDataDir
            verifyBuilder.redirectErrorStream(true)

            val verifyProcess = verifyBuilder.start()
            val verifyOutput = verifyProcess.inputStream.bufferedReader().readText()
            val verifyResult = verifyProcess.waitFor()

            if (verifyResult != 0) {
                Log.e(tag, "Tor config verification failed (exit code $verifyResult)")
                if (verifyOutput.isNotBlank()) {
                    Log.e(tag, "Tor verify output: $verifyOutput")
                }

                // Check for common errors in the output
                if (verifyOutput.contains("Exec format error", ignoreCase = true) ||
                    verifyOutput.contains("not found", ignoreCase = true) ||
                    verifyOutput.contains("inaccessible", ignoreCase = true) ||
                    verifyOutput.contains("Placeholder", ignoreCase = true)) {
                    Log.e(tag, "HINT: The native binary may be a placeholder stub or wrong architecture. " +
                        "Ensure ./scripts/build-all.sh ran successfully in CI.")
                }

                return false
            }

            Log.i(tag, "Tor config verified — launching daemon from $torPath")

            // Step 2: Start the Tor daemon
            val daemonBuilder = ProcessBuilder(
                torPath,
                "-f", torrcPath
            )
            daemonBuilder.environment()["HOME"] = appDataDir
            daemonBuilder.redirectErrorStream(true)

            torProcess = daemonBuilder.start()

            // CRITICAL: Tor is configured with 'Log notice stdout' so all of
            // its bootstrap logs go to stdout. If we don't drain that pipe,
            // the OS pipe buffer (~64KB) fills up and Tor blocks forever on
            // its next write — bootstrap silently stalls, control port never
            // opens, app shows ERROR. Pump stdout to logcat in a background
            // thread so the buffer stays drained and we get diagnostic logs.
            startStdoutPump(torProcess!!)

            startProcessMonitor()

            Log.i(tag, "Tor process started (PID: ${getPid()})")
            return true

        } catch (e: Exception) {
            Log.e(tag, "Failed to start Tor process from $torPath", e)
            return false
        }
    }

    private fun startProcessMonitor() {
        monitorThread = Thread({
            try {
                val process = torProcess ?: return@Thread
                val exitCode = process.waitFor()
                Log.w(tag, "Tor process exited with code $exitCode")
                torProcess = null

                // Notify the owner about UNEXPECTED exits only. An intentional
                // stop (stopTor) sets stopRequested BEFORE destroy(), so if we
                // reach this point with the flag clear, tor died on its own —
                // previously this was silently swallowed and the app kept
                // showing CONNECTED forever.
                if (!stopRequested) {
                    onProcessExited?.invoke(exitCode)
                }
            } catch (e: InterruptedException) {
                Log.d(tag, "Process monitor interrupted")
            } catch (e: Exception) {
                Log.e(tag, "Process monitor error", e)
            }
        }, "TorProcessMonitor")
        monitorThread?.isDaemon = true
        monitorThread?.start()
    }

    /**
     * Pumps Tor's stdout (bootstrap logs, notices, warnings) to logcat
     * under the TorProcessManager tag. Without this, the OS pipe buffer
     * (~64KB) fills up and Tor blocks on its next log write — bootstrap
     * silently stalls.
     *
     * Lines starting with [notice] are logged at INFO level.
     * Lines starting with [warn] are logged at WARN level.
     * Lines starting with [err] are logged at ERROR level.
     * Everything else is logged at DEBUG.
     */
    private fun startStdoutPump(process: Process) {
        val pump = Thread({
            try {
                val reader = process.inputStream.bufferedReader()
                var line = reader.readLine()
                while (line != null) {
                    when {
                        line.contains("[notice]") -> Log.i(tag, "tor: $line")
                        line.contains("[warn]") -> Log.w(tag, "tor: $line")
                        line.contains("[err]") -> Log.e(tag, "tor: $line")
                        else -> Log.d(tag, "tor: $line")
                    }
                    line = reader.readLine()
                }
            } catch (e: InterruptedException) {
                // Normal — pump is interrupted when Tor is stopped
            } catch (e: Exception) {
                if (torProcess != null) {
                    Log.e(tag, "Stdout pump error", e)
                }
            }
        }, "TorStdoutPump")
        pump.isDaemon = true
        pump.start()
    }

    private fun getPid(): Long {
        return try {
            // Process.pid() is API 26+; use reflection for backward compatibility with minSdk 23
            val p = torProcess ?: return -1L
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                // Use reflection to avoid compile-time reference to API 26+ method
                val method = Process::class.java.getMethod("pid")
                (method.invoke(p) as? Long) ?: -1L
            } else {
                // Fallback: try to read /proc/self on older APIs (best effort)
                -1L
            }
        } catch (e: Exception) {
            -1L
        }
    }
}
