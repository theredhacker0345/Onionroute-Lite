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
class TorProcessManager(private val context: Context) {

    private val tag = "TorProcessManager"

    private var torProcess: Process? = null
    private var monitorThread: Thread? = null

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
            Constants.BINARY_TUN2SOCKS
        )

        var torValid = false
        var missingCount = 0
        var stubCount = 0

        for (libName in requiredBinaries) {
            val file = File(nativeLibDir, libName)
            if (!file.exists()) {
                Log.e(tag, "CRITICAL: Native binary NOT FOUND: $libName at $nativeLibDir/$libName")
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

        val torrcContent = buildString {
            appendLine("# OnionRoute Lite torrc — auto-generated")
            appendLine()
            appendLine("SocksPort ${Constants.TOR_SOCKS_PORT}")
            appendLine("HTTPTunnelPort ${Constants.TOR_HTTP_PORT}")
            appendLine("ControlPort ${Constants.TOR_CONTROL_PORT}")
            appendLine("DNSPort 127.0.0.1:${Constants.TOR_DNS_PORT}")
            appendLine("CookieAuthentication 1")
            appendLine("CookieAuthFileGroupReadable 1")
            appendLine("AvoidDiskWrites 1")
            appendLine("StrictNodes 1")
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

            if (useSnowflake && snowflakePath != null) {
                appendLine("# Snowflake bridge configuration")
                appendLine("ClientTransportPlugin snowflake exec $snowflakePath")
                appendLine("Bridge snowflake 192.0.2.3:80 2B280B23E1107BB62ABFC40DDCC8824814F80A72 fingerprint=2B280B23E1107BB62ABFC40DDCC8824814F80A72 url=${Constants.SNOWFLAKE_BROKER_URL} fronts=${Constants.SNOWFLAKE_FRONT_DOMAIN} ice=${Constants.SNOWFLAKE_ICE_SERVERS}")
                appendLine("UseBridges 1")
                appendLine()
            } else if (useSnowflake && snowflakePath == null) {
                appendLine("# WARNING: Snowflake requested but libsnowflake.so not found")
                appendLine("# Falling back to direct connection")
                Log.e(tag, "Snowflake requested but libsnowflake.so not found in $nativeLibDir")
            }

            if (useObfs4 && obfs4Path != null) {
                appendLine("# obfs4 bridge configuration")
                appendLine("ClientTransportPlugin obfs4 exec $obfs4Path")
                Constants.DEFAULT_OBFS4_BRIDGES.forEach { bridge ->
                    appendLine("Bridge $bridge")
                }
                appendLine("UseBridges 1")
                appendLine()
            } else if (useObfs4 && obfs4Path == null) {
                appendLine("# WARNING: obfs4 requested but libobfs4proxy.so not found")
                Log.e(tag, "obfs4 requested but libobfs4proxy.so not found in $nativeLibDir")
            }

            if (useWebTunnel) {
                appendLine("# WebTunnel bridge configuration")
                Constants.DEFAULT_WEBTUNNEL_BRIDGES.forEach { bridge ->
                    appendLine("Bridge $bridge")
                }
                appendLine("UseBridges 1")
                appendLine()
            }
        }

        val torrcFile = File(appDataDir, Constants.TORRC_FILENAME)
        FileOutputStream(torrcFile).use { it.write(torrcContent.toByteArray()) }
        Log.d(tag, "Generated torrc at ${torrcFile.absolutePath}")
    }

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
            } catch (e: InterruptedException) {
                Log.d(tag, "Process monitor interrupted")
            } catch (e: Exception) {
                Log.e(tag, "Process monitor error", e)
            }
        }, "TorProcessMonitor")
        monitorThread?.isDaemon = true
        monitorThread?.start()
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
