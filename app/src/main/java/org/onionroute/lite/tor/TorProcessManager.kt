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
 * - Locating native binaries in the extracted nativeLibraryDir
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
            verifyBinaries()
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
     * Verifies that all required native binaries exist in nativeLibraryDir.
     *
     * With android:extractNativeLibs="true" and useLegacyPackaging, Android
     * extracts .so files from the APK to the nativeLibraryDir on install.
     * We execute them directly from there — no copying to filesDir needed.
     *
     * The nativeLibraryDir is the ONLY directory on Android where exec() is
     * guaranteed to work. The app's filesDir is mounted noexec on Android 10+.
     */
    private fun verifyBinaries() {
        val requiredBinaries = listOf(
            Constants.BINARY_TOR,
            Constants.BINARY_OBFS4PROXY,
            Constants.BINARY_SNOWFLAKE,
            Constants.BINARY_SNOWFLAKE_PROXY,
            Constants.BINARY_TUN2SOCKS
        )

        var missingCount = 0
        for (libName in requiredBinaries) {
            val file = File(nativeLibDir, libName)
            if (file.exists()) {
                // Ensure execute permission (should already be set, but be safe)
                if (!file.canExecute()) {
                    file.setExecutable(true, false)
                }
                Log.d(tag, "Found native binary: $libName (${file.length()} bytes)")
            } else {
                Log.e(tag, "CRITICAL: Native binary NOT FOUND: $libName at $nativeLibDir/$libName")
                missingCount++
            }
        }

        if (missingCount > 0) {
            Log.e(tag, "$missingCount native binaries missing! nativeLibraryDir=$nativeLibDir")
            Log.e(tag, "This usually means android:extractNativeLibs is not set to true, " +
                "or useLegacyPackaging is false, or the .so files are missing from jniLibs/")
        }
    }

    /**
     * Returns the path to a native binary in nativeLibraryDir.
     * Returns null if the binary doesn't exist.
     */
    private fun getBinaryPath(libName: String): String? {
        val file = File(nativeLibDir, libName)
        return if (file.exists()) file.absolutePath else null
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
            appendLine("GeoIPFile $appDataDir/geoip")
            appendLine("GeoIPv6File $appDataDir/geoip6")
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
            Log.e(tag, "libtor.so not found in $nativeLibDir — cannot start Tor")
            return false
        }

        val torrcPath = File(appDataDir, Constants.TORRC_FILENAME).absolutePath

        try {
            val processBuilder = ProcessBuilder(
                torPath,
                "-f", torrcPath,
                "--verify-config"
            )
            processBuilder.environment()["HOME"] = appDataDir
            processBuilder.redirectErrorStream(true)

            val verifyProcess = processBuilder.start()
            val verifyResult = verifyProcess.waitFor()

            if (verifyResult != 0) {
                Log.e(tag, "Tor config verification failed (exit code $verifyResult)")
                // Read error output for debugging
                val errorOutput = verifyProcess.inputStream.bufferedReader().readText()
                if (errorOutput.isNotBlank()) {
                    Log.e(tag, "Tor verify output: $errorOutput")
                }
                return false
            }

            Log.i(tag, "Tor config verified — launching daemon from $torPath")

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
