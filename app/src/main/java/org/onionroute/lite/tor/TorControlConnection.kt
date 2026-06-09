package org.onionroute.lite.tor

import android.content.Context
import android.util.Log
import org.onionroute.lite.Constants
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/**
 * Connection to the Tor control port for monitoring and controlling the Tor daemon.
 *
 * Supports:
 * - Authenticating via cookie
 * - Subscribing to bandwidth events (BW)
 * - Subscribing to circuit/status events (CIRC, STATUS_CLIENT)
 * - Querying Tor info via GETINFO
 */
class TorControlConnection(private val context: Context) {

    private val tag = "TorControlConnection"

    private var controlSocket: Socket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var isConnected = false
    private var listenerThread: Thread? = null

    private var onBandwidthUpdate: ((down: Long, up: Long) -> Unit)? = null
    private var onCircuitEvent: ((line: String) -> Unit)? = null
    private var onStatusEvent: ((line: String) -> Unit)? = null

    // ── Public API ────────────────────────────────────────────────

    /**
     * Connects to the Tor control port and authenticates.
     *
     * @param port The control port number
     * @param cookieFilePath Path to the control auth cookie file
     * @return true if connected and authenticated successfully
     */
    fun connect(port: Int = Constants.TOR_CONTROL_PORT, cookieFilePath: String): Boolean {
        if (isConnected) {
            Log.w(tag, "Already connected to control port")
            return true
        }

        return try {
            Log.d(tag, "Connecting to Tor control port $port…")

            controlSocket = Socket("127.0.0.1", port)
            controlSocket?.soTimeout = 5000

            inputStream = controlSocket?.getInputStream()
            outputStream = controlSocket?.getOutputStream()

            // Authenticate using cookie
            val cookieFile = File(cookieFilePath)
            if (!cookieFile.exists()) {
                Log.e(tag, "Cookie file not found: $cookieFilePath")
                disconnect()
                return false
            }

            val cookieHex = cookieFile.readBytes().joinToString("") { "%02x".format(it) }
            val authCommand = "AUTHENTICATE $cookieHex\r\n"
            sendCommand(authCommand)

            val response = readLine()
            if (!response.startsWith("250")) {
                Log.e(tag, "Authentication failed: $response")
                disconnect()
                return false
            }

            Log.i(tag, "Authenticated to Tor control port")
            isConnected = true
            true

        } catch (e: Exception) {
            Log.e(tag, "Failed to connect to control port", e)
            disconnect()
            false
        }
    }

    /**
     * Disconnects from the Tor control port.
     */
    fun disconnect() {
        try {
            listenerThread?.interrupt()
            listenerThread = null

            sendCommand("SIGNAL SHUTDOWN\r\n")
        } catch (_: Exception) {}

        try {
            outputStream?.close()
            inputStream?.close()
            controlSocket?.close()
        } catch (e: Exception) {
            Log.e(tag, "Error closing control connection", e)
        }

        outputStream = null
        inputStream = null
        controlSocket = null
        isConnected = false
        Log.d(tag, "Disconnected from control port")
    }

    /**
     * Subscribes to asynchronous events from the control port.
     */
    fun subscribeEvents(
        onBandwidth: (down: Long, up: Long) -> Unit,
        onCircuit: (line: String) -> Unit = {},
        onStatus: (line: String) -> Unit = {}
    ) {
        onBandwidthUpdate = onBandwidth
        onCircuitEvent = onCircuit
        onStatusEvent = onStatus

        // Subscribe to BW, CIRC, and STATUS_CLIENT events
        sendCommand("SETEVENTS BW CIRC STATUS_CLIENT\r\n")

        // Start listener thread
        listenerThread = Thread({
            listenForEvents()
        }, "TorControlListener")
        listenerThread?.isDaemon = true
        listenerThread?.start()
    }

    /**
     * Sends a GETINFO query to the control port.
     */
    fun getInfo(keyword: String): String? {
        if (!isConnected) return null

        return try {
            sendCommand("GETINFO $keyword\r\n")
            val response = readLine()
            if (response.startsWith("250")) {
                // Parse the info value from 250-keyword=value
                val prefix = "250-$keyword="
                if (response.startsWith(prefix)) response.substring(prefix.length) else null
            } else null
        } catch (e: Exception) {
            Log.e(tag, "GETINFO failed for $keyword", e)
            null
        }
    }

    /**
     * Sends a SIGNAL to the control port.
     */
    fun signal(signal: String): Boolean {
        if (!isConnected) return false

        return try {
            sendCommand("SIGNAL $signal\r\n")
            val response = readLine()
            response.startsWith("250")
        } catch (e: Exception) {
            Log.e(tag, "SIGNAL $signal failed", e)
            false
        }
    }

    // ── Internal ──────────────────────────────────────────────────

    private fun sendCommand(command: String) {
        try {
            val os = outputStream ?: return
            os.write(command.toByteArray())
            os.flush()
        } catch (e: Exception) {
            Log.e(tag, "Failed to send command: $command", e)
            isConnected = false
        }
    }

    private fun readLine(): String {
        val sb = StringBuilder()
        val stream = inputStream ?: return ""

        while (true) {
            val b = stream.read()
            if (b == -1 || b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }

        return sb.toString()
    }

    private fun listenForEvents() {
        try {
            val stream = inputStream ?: return

            while (isConnected && !Thread.currentThread().isInterrupted) {
                val line = readLine()
                if (line.isEmpty()) continue

                when {
                    line.startsWith("650 BW ") -> parseBandwidthEvent(line)
                    line.startsWith("650 CIRC ") -> onCircuitEvent?.invoke(line)
                    line.startsWith("650 STATUS_CLIENT ") -> onStatusEvent?.invoke(line)
                }
            }
        } catch (e: InterruptedException) {
            Log.d(tag, "Event listener interrupted")
        } catch (e: Exception) {
            Log.e(tag, "Event listener error", e)
            isConnected = false
        }
    }

    private fun parseBandwidthEvent(line: String) {
        // Format: 650 BW <down> <up>
        try {
            val parts = line.split(" ")
            if (parts.size >= 4) {
                val down = parts[2].toLongOrNull() ?: 0L
                val up = parts[3].toLongOrNull() ?: 0L
                onBandwidthUpdate?.invoke(down, up)
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to parse bandwidth event", e)
        }
    }
}
