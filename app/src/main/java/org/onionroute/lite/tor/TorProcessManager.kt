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
 * - Installing native binaries (libtor.so, libobfs4proxy.so, libsnowflake.so)
 * - Generating the torrc configuration file
 * - Starting and stopping the Tor daemon process
 * - Monitoring the Tor process for unexpected exits
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
            installBinaries()
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

    // ── Binary Installation ───────────────────────────────────────

    private fun installBinaries() {
        val binaries = mapOf(
            Constants.BINARY_TOR to Constants.EXEC_TOR,
            Constants.BINARY_OBFS4PROXY to Constants.EXEC_OBFS4PROXY,
            Constants.BINARY_SNOWFLAKE to Constants.EXEC_SNOWFLAKE,
            Constants.BINARY_SNOWFLAKE_PROXY to Constants.EXEC_SNOWFLAKE_PROXY,
            Constants.BINARY_TUN2SOCKS to Constants.EXEC_TUN2SOCKS
        )

        binaries.forEach { (libName, execName) ->
            val target = File(appDataDir, execName)
            val source = File(nativeLibDir, libName)

            if (source.exists()) {
                if (!target.exists() || source.lastModified() > target.lastModified()) {
                    source.copyTo(target, overwrite = true)
                    target.setExecutable(true, false)
                    Log.d(tag, "Installed binary: $execName")
                }
            } else {
                Log.w(tag, "Native library not found: $libName")
            }
        }
    }

    // ── torrc Generation ──────────────────────────────────────────

    private fun generateTorrc(useSnowflake: Boolean, useObfs4: Boolean, useWebTunnel: Boolean) {
        val torDataDir = File(appDataDir, Constants.TOR_DATA_DIR)
        if (!torDataDir.exists()) torDataDir.mkdirs()

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

            if (useSnowflake) {
                appendLine("# Snowflake bridge configuration")
                appendLine("ClientTransportPlugin snowflake exec $appDataDir/${Constants.EXEC_SNOWFLAKE}")
                appendLine("Bridge snowflake 192.0.2.3:80 2B280B23E1107BB62ABFC40DDCC8824814F80A72 fingerprint=2B280B23E1107BB62ABFC40DDCC8824814F80A72 url=${Constants.SNOWFLAKE_BROKER_URL} fronts=${Constants.SNOWFLAKE_FRONT_DOMAIN} ice=${Constants.SNOWFLAKE_ICE_SERVERS}")
                appendLine("UseBridges 1")
                appendLine()
            }

            if (useObfs4) {
                appendLine("# obfs4 bridge configuration")
                appendLine("ClientTransportPlugin obfs4 exec $appDataDir/${Constants.EXEC_OBFS4PROXY}")
                Constants.DEFAULT_OBFS4_BRIDGES.forEach { bridge ->
                    appendLine("Bridge $bridge")
                }
                appendLine("UseBridges 1")
                appendLine()
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
        val torExecutable = File(appDataDir, Constants.EXEC_TOR)
        if (!torExecutable.exists()) {
            Log.e(tag, "Tor executable not found at ${torExecutable.absolutePath}")
            return false
        }

        val torrcPath = File(appDataDir, Constants.TORRC_FILENAME).absolutePath

        try {
            val processBuilder = ProcessBuilder(
                torExecutable.absolutePath,
                "-f", torrcPath,
                "--verify-config"
            )
            processBuilder.environment()["HOME"] = appDataDir
            processBuilder.redirectErrorStream(true)

            val verifyProcess = processBuilder.start()
            val verifyResult = verifyProcess.waitFor()

            if (verifyResult != 0) {
                Log.e(tag, "Tor config verification failed (exit code $verifyResult)")
                return false
            }

            Log.i(tag, "Tor config verified — launching daemon")

            val daemonBuilder = ProcessBuilder(
                torExecutable.absolutePath,
                "-f", torrcPath
            )
            daemonBuilder.environment()["HOME"] = appDataDir
            daemonBuilder.redirectErrorStream(true)

            torProcess = daemonBuilder.start()
            startProcessMonitor()

            Log.i(tag, "Tor process started (PID: ${getPid()})")
            return true

        } catch (e: Exception) {
            Log.e(tag, "Failed to start Tor process", e)
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
