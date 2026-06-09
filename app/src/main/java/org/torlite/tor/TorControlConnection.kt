package org.torlite.tor

import android.util.Log
import org.torlite.Constants
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Socket

/**
 * Manages the Tor Control Port connection.
 *
 * The Tor Control Port (9051) allows external programs to query and
 * control the tor daemon. We use it for:
 * - Authentication (cookie-based)
 * - Bandwidth monitoring (BW events)
 * - Circuit status monitoring (CIRC events)
 * - Signal handling (NEWNYM for new identity, SHUTDOWN for clean exit)
 *
 * Protocol reference: https://spec.torproject.org/control-spec/
 *
 * Thread safety: This class is designed to be used from a single
 * background thread. All socket operations are blocking.
 */
class TorControlConnection {

    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private var reader: BufferedReader? = null
    private var listener: ControlPortListener? = null

    private val tag = "TorControlConn"

    interface ControlPortListener {
        fun onBandwidthUpdate(bytesRead: Long, bytesWritten: Long)
        fun onCircuitStatus(status: String, circuitId: String, path: String)
        fun onStatusChanged(status: String)
        fun onError(message: String)
    }

    /**
     * Connect to the Tor Control Port using cookie authentication.
     *
     * Cookie authentication is the default and most secure method.
     * The tor daemon writes a cookie file at startup, and we read
     * it to authenticate. This avoids storing passwords in the app.
     *
     * @param cookiePath Path to the control_auth_cookie file
     * @param listener Callback for async events from tor
     */
    fun connect(cookiePath: String, listener: ControlPortListener) {
        this.listener = listener

        try {
            socket = Socket("127.0.0.1", Constants.TOR_CONTROL_PORT)
            socket?.soTimeout = 5000
            outputStream = socket?.getOutputStream()
            reader = BufferedReader(InputStreamReader(socket?.getInputStream()))

            // Read the initial status line
            val initLine = reader?.readLine()
            Log.d(tag, "Control port connected: $initLine")

            // Authenticate using cookie
            val cookie = readCookieFile(cookiePath)
            val cookieHex = cookie.joinToString("") { "%02x".format(it) }
            sendCommand("AUTHENTICATE $cookieHex")

            val authResponse = readResponse()
            if (!authResponse.startsWith("250")) {
                listener.onError("Authentication failed: $authResponse")
                return
            }

            Log.d(tag, "Control port authenticated successfully")

            // Subscribe to events we care about
            sendCommand("SETEVENTS BW CIRC STATUS_CLIENT")
            val eventsResponse = readResponse()
            if (!eventsResponse.startsWith("250")) {
                listener.onError("Failed to subscribe to events: $eventsResponse")
                return
            }

            // Start event listener thread
            startEventListener()

        } catch (e: Exception) {
            Log.e(tag, "Control port connection failed", e)
            listener.onError("Connection failed: ${e.message}")
        }
    }

    /**
     * Disconnect from the control port cleanly.
     */
    fun disconnect() {
        try {
            sendCommand("SIGNAL SHUTDOWN")
        } catch (_: Exception) {
            // Already disconnected, that's fine
        }

        try {
            reader?.close()
            outputStream?.close()
            socket?.close()
        } catch (_: Exception) {
            // Ignore close errors
        }

        socket = null
        outputStream = null
        reader = null
    }

    /**
     * Request a new Tor circuit (new identity).
     *
     * This clears the DNS cache and builds new circuits,
     * effectively giving the user a new exit IP address.
     * Should be used sparingly as it puts load on the network.
     */
    fun newIdentity() {
        try {
            sendCommand("SIGNAL NEWNYM")
            Log.d(tag, "Requested new identity")
        } catch (e: Exception) {
            Log.e(tag, "Failed to request new identity", e)
        }
    }

    /**
     * Get the current Tor status info.
     */
    fun getInfo(key: String): String? {
        return try {
            sendCommand("GETINFO $key")
            val response = readResponse()
            if (response.startsWith("250")) {
                // Parse the value from "250-key=value" format
                val valuePart = response.substringAfter("$key=")
                valuePart.substringBefore("\r\n").substringBefore("\n").trim()
            } else null
        } catch (e: Exception) {
            Log.e(tag, "GETINFO failed for $key", e)
            null
        }
    }

    // ── Private Helpers ────────────────────────────────────────

    private fun readCookieFile(path: String): ByteArray {
        val file = java.io.File(path)
        if (!file.exists()) {
            throw IllegalStateException("Cookie file not found: $path")
        }
        return file.readBytes()
    }

    private fun sendCommand(command: String) {
        val data = "$command\r\n"
        outputStream?.write(data.toByteArray())
        outputStream?.flush()
    }

    private fun readResponse(): String {
        val sb = StringBuilder()
        var line: String?

        while (reader?.readLine().also { line = it } != null) {
            sb.append(line)
            sb.append("\n")

            // Tor control protocol: lines starting with 250- are
            // multi-line, 250 (space) ends the response
            val trimmed = line?.trim() ?: ""
            if (trimmed.matches("^\\d{3}\\s.*".toRegex()) && !trimmed.contains("-")) {
                break
            }
        }

        return sb.toString().trim()
    }

    /**
     * Background thread that reads async events from the control port.
     *
     * Tor sends events asynchronously after we subscribed with SETEVENTS.
     * This thread parses those events and forwards them to the listener.
     *
     * Event format examples:
     *   650 BW 1234 5678
     *   650 CIRC 123 LAUNCHED
     *   650 STATUS_CLIENT BOOTSTRAP PROGRESS=100
     */
    private fun startEventListener() {
        Thread({
            try {
                var line: String?
                while (reader?.readLine().also { line = it } != null) {
                    val currentLine = line ?: break

                    val trimmed = currentLine.trim()
                    if (!trimmed.startsWith("650 ")) continue

                    val eventLine = trimmed.substring(4) // strip "650 "
                    val parts = eventLine.split(" ")

                    when (parts.getOrNull(0)) {
                        "BW" -> parseBandwidthEvent(parts)
                        "CIRC" -> parseCircuitEvent(parts)
                        "STATUS_CLIENT" -> parseStatusEvent(eventLine)
                    }
                }
            } catch (e: Exception) {
                if (socket?.isClosed == false) {
                    Log.e(tag, "Event listener error", e)
                    listener?.onError("Event listener died: ${e.message}")
                }
            }
        }, "TorControlEventListener").start()
    }

    private fun parseBandwidthEvent(parts: List<String>) {
        // Format: BW <bytes_read> <bytes_written>
        if (parts.size >= 3) {
            try {
                val bytesRead = parts[1].toLong()
                val bytesWritten = parts[2].toLong()
                listener?.onBandwidthUpdate(bytesRead, bytesWritten)
            } catch (_: NumberFormatException) {
                // Malformed event, skip
            }
        }
    }

    private fun parseCircuitEvent(parts: List<String>) {
        // Format: CIRC <circuit_id> <status> [path]
        if (parts.size >= 3) {
            val circuitId = parts[1]
            val status = parts[2]
            val path = if (parts.size > 3) parts.drop(3).joinToString(" ") else ""
            listener?.onCircuitStatus(status, circuitId, path)
        }
    }

    private fun parseStatusEvent(eventLine: String) {
        // Format: STATUS_CLIENT BOOTSTRAP PROGRESS=100 SUMMARY="Done"
        if (eventLine.contains("BOOTSTRAP")) {
            val progressMatch = "PROGRESS=(\\d+)".toRegex().find(eventLine)
            val progress = progressMatch?.groupValues?.get(1) ?: "0"
            listener?.onStatusChanged("BOOTSTRAP $progress%")
        }
    }
}
