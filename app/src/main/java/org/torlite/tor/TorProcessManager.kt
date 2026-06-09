package org.torlite.tor

import android.content.Context
import android.util.Log
import org.torlite.Constants
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader

/**
 * Manages the native Tor process and pluggable transport processes.
 *
 * This class is responsible for:
 * - Installing native binaries from the APK's jniLibs to the app's
 *   data directory (where they can be executed)
 * - Writing the torrc configuration file
 * - Starting and stopping the tor daemon, obfs4proxy, and snowflake
 * - Monitoring process health
 *
 * Android requires native executables to be in the app's private data
 * directory with execute permissions. We copy them from jniLibs and
 * create wrapper scripts with proper names for tor's configuration.
 *
 * The tor daemon runs as a separate process (not a library call), which
 * is the same approach Orbot uses. This provides process isolation and
 * allows tor to manage its own threads and signal handling.
 */
class TorProcessManager(private val context: Context) {

    private val tag = "TorProcessManager"

    // Running processes
    private var torProcess: Process? = null
    private var obfs4Process: Process? = null
    private var snowflakeProcess: Process? = null

    // Directories
    val appDataDir: String = context.filesDir.absolutePath
    val torBinDir: String = context.applicationInfo.nativeLibraryDir
    val torDataDir: String = "${context.filesDir.absolutePath}/${Constants.TOR_DATA_DIR}"

    /**
     * Install native binaries to the app's executable directory.
     *
     * On modern Android, the system extracts .so files from the APK
     * to nativeLibraryDir at install time. We create executable wrapper
     * scripts that invoke these .so files, since tor's ClientTransportPlugin
     * directive needs an executable path with a clean name.
     *
     * Security: Wrapper scripts are created with owner-only write
     * permissions to prevent tampering by other apps.
     */
    fun installBinaries(): Boolean {
        try {
            // Create tor data directory
            File(torDataDir).mkdirs()

            // Create executable wrappers in the app's files directory
            createWrapper(Constants.EXEC_TOR, Constants.BINARY_TOR)
            createWrapper(Constants.EXEC_OBFS4PROXY, Constants.BINARY_OBFS4PROXY)
            createWrapper(Constants.EXEC_SNOWFLAKE, Constants.BINARY_SNOWFLAKE)
            createWrapper(Constants.EXEC_TUN2SOCKS, Constants.BINARY_TUN2SOCKS)

            Log.i(tag, "Native binaries installed successfully")
            return true

        } catch (e: Exception) {
            Log.e(tag, "Failed to install binaries", e)
            return false
        }
    }

    /**
     * Create an executable wrapper script for a native binary.
     *
     * The wrapper is a shell script that execs the .so file from
     * nativeLibraryDir. This allows tor's config to reference a
     * clean path name instead of the libXXX.so naming convention.
     *
     * Example wrapper for tor:
     *   #!/system/bin/sh
     *   exec /data/app/.../lib/arm/libtor.so "$@"
     */
    private fun createWrapper(name: String, soName: String) {
        val wrapperFile = File(appDataDir, name)
        val soPath = "$torBinDir/$soName"

        // Verify the .so exists
        if (!File(soPath).exists()) {
            Log.w(tag, "Native binary not found: $soPath (will fail at runtime if used)")
        }

        val script = "#!/system/bin/sh\nexec $soPath \"\$@\"\n"

        FileOutputStream(wrapperFile).use { out ->
            out.write(script.toByteArray())
        }

        // Make executable and readable by owner only (security)
        wrapperFile.setExecutable(true, true)
        wrapperFile.setReadable(true, true)
        wrapperFile.setWritable(true, true)

        if (!wrapperFile.canExecute()) {
            throw IOException("Failed to make $name executable")
        }

        Log.d(tag, "Created wrapper: $wrapperFile -> $soPath")
    }

    /**
     * Write the torrc configuration file.
     *
     * This generates a minimal torrc with the necessary settings
     * for SOCKS5 proxy, HTTP CONNECT proxy, control port, and
     * any bridge configuration the user has enabled.
     */
    fun writeTorrc(bridgeConfig: BridgeConfig): Boolean {
        return try {
            val torrcFile = File(torDataDir, Constants.TORRC_FILENAME)
            val cookieFile = File(torDataDir, Constants.TOR_CONTROL_COOKIE)

            val torrcContent = buildString {
                appendLine("# TorLite torrc — auto-generated")
                appendLine()

                // SOCKS5 proxy for local apps
                appendLine("SocksPort ${Constants.TOR_SOCKS_PORT}")
                appendLine("SocksListenAddress 127.0.0.1")

                // HTTP CONNECT proxy for local apps
                appendLine("HTTPTunnelPort ${Constants.TOR_HTTP_PORT}")

                // DNS port (for VPN mode)
                appendLine("DNSPort ${Constants.TOR_DNS_PORT}")
                appendLine("AutomapHostsOnResolve 1")

                // Control port for bandwidth monitoring + circuit management
                appendLine("ControlPort ${Constants.TOR_CONTROL_PORT}")
                appendLine("CookieAuthentication 1")
                appendLine("CookieAuthFile ${cookieFile.absolutePath}")

                // Security & Privacy
                appendLine("AvoidDiskWrites 1")
                appendLine("SafeSocks 1")
                appendLine("TestSocks 1")
                appendLine("WarnPlaintextPorts 23,109,110,143,25")
                appendLine("RejectPlaintextPorts 21,109,110,143,25")

                // Performance (mobile-optimized)
                appendLine("NumEntryGuards 2")
                appendLine("NumDirectoryGuards 2")
                appendLine("UseEntryGuards 1")
                appendLine("LearnCircuitBuildTimeout 1")
                appendLine("CircuitBuildTimeout 60")

                // Data Directory
                appendLine("DataDirectory $torDataDir")
                appendLine("PidFile ${torDataDir}/tor.pid")

                // Logging
                appendLine("Log notice stderr")

                // Bridge Configuration
                val bridgeLines = bridgeConfig.generateTorrcLines(appDataDir)
                if (bridgeLines.isNotEmpty()) {
                    appendLine("UseBridges 1")
                    bridgeLines.forEach { appendLine(it) }
                }
            }

            torrcFile.writeText(torrcContent)
            Log.i(tag, "torrc written to ${torrcFile.absolutePath}")
            true

        } catch (e: Exception) {
            Log.e(tag, "Failed to write torrc", e)
            false
        }
    }

    /**
     * Start the tor daemon process.
     */
    fun startTor(): Boolean {
        return try {
            if (torProcess != null && torProcess?.isAlive == true) {
                Log.w(tag, "Tor is already running")
                return true
            }

            val torExec = File(appDataDir, Constants.EXEC_TOR)
            val torrcPath = File(torDataDir, Constants.TORRC_FILENAME).absolutePath

            val processBuilder = ProcessBuilder(torExec.absolutePath, "-f", torrcPath)
                .directory(File(appDataDir))
                .redirectErrorStream(true)

            val env = processBuilder.environment()
            env["HOME"] = appDataDir

            torProcess = processBuilder.start()
            startOutputLogger(torProcess!!, "tor")

            Log.i(tag, "Tor process started")
            true

        } catch (e: Exception) {
            Log.e(tag, "Failed to start tor", e)
            false
        }
    }

    /**
     * Stop all managed processes.
     */
    fun stopAll() {
        stopTor()
        stopObfs4()
        stopSnowflake()
    }

    fun stopTor() {
        try {
            torProcess?.destroy()
            torProcess = null
            Log.i(tag, "Tor process stopped")
        } catch (e: Exception) {
            Log.e(tag, "Error stopping tor", e)
        }
    }

    private fun stopObfs4() {
        try {
            obfs4Process?.destroy()
            obfs4Process = null
        } catch (e: Exception) {
            Log.e(tag, "Error stopping obfs4", e)
        }
    }

    private fun stopSnowflake() {
        try {
            snowflakeProcess?.destroy()
            snowflakeProcess = null
        } catch (e: Exception) {
            Log.e(tag, "Error stopping snowflake", e)
        }
    }

    fun isTorRunning(): Boolean {
        return torProcess?.isAlive == true
    }

    /**
     * Read tor's standard output/error in a background thread.
     *
     * This is critical: if we don't consume the process output,
     * the output buffer fills up and the process blocks forever.
     */
    private fun startOutputLogger(process: Process, label: String) {
        Thread({
            try {
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    Log.d("TorOutput-$label", line ?: "")
                }
            } catch (_: Exception) {
                // Process died or was killed — that's expected
            }
        }, "OutputLogger-$label").start()
    }
}
