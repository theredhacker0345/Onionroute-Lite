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
        /**
         * Smallest plausible size for a real native binary.
         *
         * Was 1KB, which accepted essentially anything — including the
         * placeholder .so files the build scripts warn about, so the "reject
         * stubs" check could never reject anything. The comment beside it even
         * claimed "several hundred KB".
         *
         * 128KB rather than something larger: the smallest real binary is
         * libtun2socks.so, and armeabi-v7a builds of it come out noticeably
         * below the x86_64 build (~340KB). 128KB stays comfortably clear of a
         * text placeholder — which is a few hundred bytes at most — without
         * risking a false rejection of a legitimate 32-bit binary, which would
         * disable a transport on exactly the low-end devices this app targets.
         */
        private const val MIN_BINARY_SIZE = 128L * 1024L

        /**
         * The exit-country preference must look like an ISO-3166 alpha-2 code
         * and nothing else, because it is interpolated into `ExitNodes {…}` in
         * the torrc. Anchored and two letters exactly: a brace, a space or a
         * newline in this value would end the brace group and turn the rest of
         * the string into further torrc directives. Case-insensitive because
         * Constants.EXIT_COUNTRY_OPTIONS stores lowercase ("de"); the value is
         * upper-cased on the way out.
         */
        private val EXIT_COUNTRY_CODE = Regex("\\A[A-Za-z]{2}\\z")
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
     * EXCEPTION — libtun2socks.so is LOADED, not executed. hev-socks5-tunnel
     * is built as a shared library with no PT_INTERP and has no CLI for an
     * existing TUN fd, so it is consumed via System.loadLibrary + JNI
     * (see Tun2SocksNative). It is still verified here so a missing library is
     * reported as a clear build/install problem rather than as a silent
     * "VPN unavailable" at runtime.
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

            // Ensure execute permission, but ONLY if it is actually missing.
            //
            // The previous code additionally forked `Runtime.exec("chmod 755 …")`
            // unconditionally for every binary, on every start and every
            // auto-restart. That is six extra processes per start; each fork
            // duplicates the parent's address space, and this app's heap holds
            // the circuit list, bandwidth history and icon bitmaps, so on a
            // 1.5GB device each fork is a real cost for a no-op. waitFor() also
            // had no timeout and its streams were never closed, so a wedged
            // chmod would hang whatever thread called this — the same thread
            // that holds startMutex, which would then block every later
            // start/reconnect.
            //
            // Files under nativeLibraryDir are extracted by the installer with
            // 0755 already set, so canExecute() is true in practice and the
            // fork never ran usefully anyway.
            if (!file.canExecute() && !file.setExecutable(true, false)) {
                Log.w(tag, "Cannot set execute permission on $libName " +
                    "(size=$size) — that transport will be unavailable")
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
                // SECURITY: the single choke point where an outside string
                // becomes a `Bridge` directive, and therefore a torrc line.
                // A bridge "line" is not necessarily one line — a JSON string in
                // the fetched bridge report may carry a real CR/LF, and pasted
                // or QR-scanned text is arbitrary — so an unvalidated line can
                // split into several directives and, because tor takes the last
                // occurrence of a single-valued option, override our own
                // ControlPort / CookieAuthentication / DisableNetwork /
                // DataDirectory. Validate first, before anything is inspected
                // or emitted, and DROP anything not recognised. Never
                // sanitise-and-keep: tor's config parser has no escaping.
                if (!BridgeConfig.validateBridgeLine(bridge)) {
                    Log.w(tag, "$source: DROPPED invalid bridge line (${BridgeConfig.describeRejected(bridge)}) — " +
                        "not a complete obfs4/snowflake/webtunnel bridge line, or contains control characters")
                    continue
                }
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

        // Defence in depth: every line in bridgeLines already passed
        // BridgeConfig.validateBridgeLine inside addBridges(), but this is the
        // last point at which a string could still become a torrc directive, so
        // it is re-checked rather than trusted. Rejections are logged, never
        // silently dropped — a line that fails here means something upstream
        // stopped validating and needs looking at.
        val safeBridges = bridgeLines.filter { line ->
            val ok = BridgeConfig.validateBridgeLine(line)
            if (!ok) {
                Log.w(tag, "torrc: DROPPED invalid bridge line at write time (${BridgeConfig.describeRejected(line)})")
            }
            ok
        }

        val torrcContent = buildString {
            appendLine("# OnionRoute Lite torrc — auto-generated")
            appendLine()

            // ── Outside-controlled section ─────────────────────────────
            //
            // Pluggable-transport registrations and Bridge lines are the only
            // content here that did not originate in this method: the bridge
            // list is fetched from a third-party repository at runtime
            // (BridgeUpdater) and custom bridges are typed, pasted, imported or
            // QR-scanned by the user. Each one is written verbatim, so each one
            // must have passed BridgeConfig.validateBridgeLine first.
            //
            // This block is written FIRST, deliberately. Tor's torrc parser is
            // line-oriented — one directive per line, no quoting or escaping —
            // and for every SINGLE-VALUED option the LAST occurrence wins.
            // Emitting the app-owned directives after this block therefore
            // means our value overrides theirs, not the reverse: a payload that
            // somehow slipped past validation could not redefine ControlPort,
            // CookieAuthentication, CookieAuthFile, DataDirectory,
            // DisableNetwork, StrictNodes or the DNSPort/SocksPort binds.
            //
            // Do not move this block below the app-owned section, and do not
            // append anything after it that a bridge could influence.
            if (pluginLines.isNotEmpty() || safeBridges.isNotEmpty()) {
                appendLine("# Pluggable transport clients")
                pluginLines.forEach { appendLine(it) }
                appendLine()
                if (safeBridges.isNotEmpty()) {
                    appendLine("# Bridges (${safeBridges.size})")
                    safeBridges.forEach { appendLine("Bridge $it") }
                    appendLine("UseBridges 1")
                    appendLine()
                    Log.i(tag, "torrc: ${safeBridges.size} bridge line(s), ${pluginLines.size} plugin(s), UseBridges 1")
                }
            }

            // ── App-owned section ─────────────────────────────────────
            //
            // Everything from here to EOF is generated from app constants, the
            // app's own filesDir and validated preferences — no value below is
            // bridge- or network-supplied.

            appendLine("# OnionRoute Lite settings (app-owned — must stay AFTER the bridges, see note above)")
            appendLine("SocksPort ${Constants.TOR_SOCKS_PORT}")
            appendLine("HTTPTunnelPort ${Constants.TOR_HTTP_PORT}")
            // Loopback is spelled out rather than left to tor's default so the
            // bind is unambiguous in the file. Tor enforces
            // CL_PORT_FORBID_NONLOCAL, which makes a non-loopback ControlPort
            // abort startup — but the daemon refusing to listen is a worse
            // outcome than never being offered the address, and a file that
            // merely reads "ControlPort 9051" is what an injected directive
            // looks like. CookieAuthentication 1 below is what makes the port
            // safe to expose even on loopback.
            appendLine("ControlPort 127.0.0.1:${Constants.TOR_CONTROL_PORT}")
            appendLine("DNSPort 127.0.0.1:${Constants.TOR_DNS_PORT}")
            appendLine("CookieAuthentication 1")
            // Explicit cookie file path — TorProcessManager.controlCookieFile
            // reads from this exact path. Without this directive Tor defaults
            // to DataDirectory/control_auth_cookie which happens to match,
            // but making it explicit avoids surprises if DataDirectory changes.
            appendLine("CookieAuthFile ${File(torDataDir, Constants.TOR_CONTROL_COOKIE).absolutePath}")
            appendLine("CookieAuthFileGroupReadable 1")
            // Pinned explicitly (the default) so that no earlier line — however
            // it got there — can leave the daemon running with the network
            // switched off, which presents as "connected but nothing loads".
            appendLine("DisableNetwork 0")
            appendLine("ClientOnly 1")

            // Orphan watchdog.
            //
            // Without this, tor can outlive the app: the process is killed (an
            // OEM low-memory kill on a 1.5GB device is routine, as is the
            // user's "clear recent apps"), but the tor daemon is a separate
            // process and keeps running. It then holds SOCKS/control/DNS ports,
            // and worse, the app's next launch authenticates against that
            // orphan using the same on-disk cookie — driving a process it does
            // not own, with no way to stop it. On this target hardware, where
            // low-memory kills are the norm rather than the exception, that is
            // not a corner case.
            //
            // __OwningControllerProcess makes tor poll our pid every 15s and
            // self-terminate when we are gone. Cheap insurance against exactly
            // that failure mode.
            appendLine("__OwningControllerProcess ${android.os.Process.myPid()}")

            appendLine("AvoidDiskWrites 1")
            // StrictNodes 1 is emitted only where it has an effect, alongside
            // ExcludeNodes below. Emitting it unconditionally was misleading:
            // with no ExcludeNodes in the torrc it changes nothing at all, and
            // the adjacent comment wrongly implied it hardened ExitNodes too.
            // LOW-RAM: bound tor's in-memory queue buffers (default scales
            // with system RAM and can grow under heavy multi-app VPN load).
            // See Constants.TOR_MAX_MEM_IN_QUEUE.
            appendLine("MaxMemInQueue ${Constants.TOR_MAX_MEM_IN_QUEUE}")
            // Exit-country restriction (Settings → EXIT NODE). Persisted here
            // so it survives restarts. Changes made while connected are applied
            // live via SETCONF — this line keeps the same choice for the NEXT
            // daemon start.
            //
            // THIS IS A PREFERENCE, NOT A GUARANTEE.
            //
            // The previous comment claimed "StrictNodes 1 above makes the
            // restriction hard (no fallback to other exits)". That is not what
            // tor does. Per the tor(1) manual, StrictNodes 1 makes only
            // ExcludeNodes a requirement: "StrictNodes does not apply to
            // ExcludeExitNodes, ExitNodes, MiddleNodes, or MapAddress".
            // StrictExitNodes, the directive one would reach for instead, is
            // marked OBSOLETE and is a no-op.
            //
            // So when no exit in the requested country will serve the
            // destination, tor may still pick one elsewhere rather than fail.
            // The UI must not promise otherwise; the honest description is a
            // preference. Enforcing it truly would mean validating the achieved
            // exit with `GETINFO ip-to-country` and signalling NEWNYM until it
            // matched, at the cost of failing closed when the country has no
            // usable exit. That is a product decision, so the behaviour is left
            // as-is here with the limitation documented instead of silently
            // overstated.
            //
            // SECURITY: this value is interpolated into an `ExitNodes {…}`
            // brace group, so an embedded newline (or a crafted `}`) would
            // close the group and append further torrc directives. It is
            // therefore restricted to exactly two letters before interpolation;
            // anything else is discarded and tor starts unrestricted, which is
            // the safe direction to fail — never a config that fails to start.
            val exitCountry = context.getSharedPreferences(
                Constants.PREFS_NAME, android.content.Context.MODE_PRIVATE
            ).getString(Constants.PREF_EXIT_COUNTRY, "") ?: ""
            if (EXIT_COUNTRY_CODE.matches(exitCountry)) {
                // SettingsScreen stores these lowercase ("de"); emit the
                // canonical uppercase form so the torrc reads consistently.
                appendLine("ExitNodes {${exitCountry.uppercase()}}")
                Log.i(tag, "torrc: exit country restricted to '${exitCountry.uppercase()}'")
            } else if (exitCountry.isNotBlank()) {
                Log.w(tag, "torrc: rejecting malformed exit country preference " +
                    "'${BridgeConfig.describeRejected(exitCountry)}' — starting with no country restriction")
            }
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
        }

        val torrcFile = File(appDataDir, Constants.TORRC_FILENAME)
        // Write to a sibling temp file and rename, rather than truncating torrc in
        // place. A kill between truncate and write — or ENOSPC on an 8GB
        // device — left a partial torrc, and every subsequent start then failed
        // `--verify-config` with "Failed to start Tor process" until some later
        // start happened to rewrite it cleanly. rename() within a directory is
        // atomic, so a reader ever only sees the old file or the new one.
        val torrcTmp = File(torrcFile.absolutePath + ".tmp")
        FileOutputStream(torrcTmp).use { it.write(torrcContent.toByteArray()) }
        if (!torrcTmp.renameTo(torrcFile)) {
            // renameTo can fail across some filesystems; fall back to a direct
            // write rather than leaving only the .tmp behind.
            Log.w(tag, "Atomic torrc rename failed; writing in place")
            torrcTmp.copyTo(torrcFile, overwrite = true)
            torrcTmp.delete()
        }
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
