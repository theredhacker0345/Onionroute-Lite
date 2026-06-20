package org.onionroute.lite.tor

import android.content.Context
import android.util.Log
import org.onionroute.lite.Constants
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Connection to the Tor control port for monitoring and controlling the Tor daemon.
 *
 * All reads from the control socket are routed through a single listener thread
 * to avoid race conditions. GETINFO queries are handled via a pending-response map
 * that the listener thread populates when the corresponding response arrives.
 *
 * Supports:
 * - Authenticating via cookie
 * - Subscribing to bandwidth events (BW)
 * - Subscribing to circuit/status events (CIRC, STATUS_CLIENT)
 * - Querying Tor info via GETINFO (thread-safe)
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
    private var onBootstrapEvent: ((progress: Int, tag: String) -> Unit)? = null

    // ── Thread-safe GETINFO/SIGNAL support ────────────────────────
    // The Tor control port is a FIFO protocol: requests are answered in the
    // order they were sent. We use a ReentrantLock to serialize getInfo /
    // signal calls so that only ONE request is in-flight at a time. This
    // avoids the race condition where the 250-OK terminator from request A
    // would unblock request B's latch (the previous implementation called
    // `latch.countDown()` for ALL pending requests on every 250 OK).
    private val requestLock = java.util.concurrent.locks.ReentrantLock()
    private val pendingResponses = ConcurrentHashMap<String, CountDownLatch>()
    private val responseValues = ConcurrentHashMap<String, String>()

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
            controlSocket?.soTimeout = 15000

            inputStream = controlSocket?.getInputStream()
            outputStream = controlSocket?.getOutputStream()

            // Authenticate using cookie — read response directly before listener starts
            val cookieFile = File(cookieFilePath)
            if (!cookieFile.exists()) {
                Log.e(tag, "Cookie file not found: \$cookieFilePath")
                disconnect()
                return false
            }

            val cookieHex = cookieFile.readBytes().joinToString("") { "%02x".format(it) }
            synchronized(outputStream!!) {
                outputStream!!.write("AUTHENTICATE $cookieHex\r\n".toByteArray())
                outputStream!!.flush()
            }

            val response = readLineSync()
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

            // Don't send SIGNAL SHUTDOWN here — the caller (TorService) already
            // handles stopping the Tor process via Process.destroy(). Sending
            // SHUTDOWN could cause a race condition where Tor shuts down before
            // we've finished reading pending events.
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
        pendingResponses.clear()
        responseValues.clear()
        Log.d(tag, "Disconnected from control port")
    }

    /**
     * Subscribes to asynchronous events from the control port.
     *
     * @param onBootstrap Called with (progress 0..100, tag) for each
     *   `650 BOOTSTRAP` event. Progress 100 means Tor has finished
     *   building circuits and is ready to carry traffic.
     */
    fun subscribeEvents(
        onBandwidth: (down: Long, up: Long) -> Unit,
        onCircuit: (line: String) -> Unit = {},
        onStatus: (line: String) -> Unit = {},
        onBootstrap: (progress: Int, tag: String) -> Unit = { _, _ -> }
    ) {
        onBandwidthUpdate = onBandwidth
        onCircuitEvent = onCircuit
        onStatusEvent = onStatus
        onBootstrapEvent = onBootstrap

        // Subscribe to BW, CIRC, STATUS_CLIENT, and BOOTSTRAP events
        sendCommand("SETEVENTS BW CIRC STATUS_CLIENT BOOTSTRAP\r\n")

        // Start listener thread — this is the ONLY thread that reads from inputStream
        listenerThread = Thread({
            listenForEvents()
        }, "TorControlListener")
        listenerThread?.isDaemon = true
        listenerThread?.start()
    }

    /**
     * Sends a GETINFO query to the control port (thread-safe).
     * The response is collected by the listener thread, not read directly.
     *
     * Uses [requestLock] to ensure only one GETINFO/SIGNAL is in-flight
     * at a time, avoiding response mix-ups (the Tor control port is FIFO
     * but the previous listener implementation would unblock ALL pending
     * requests on every 250-OK terminator).
     */
    fun getInfo(keyword: String): String? {
        if (!isConnected) return null

        requestLock.lock()
        try {
            val latch = CountDownLatch(1)
            pendingResponses[keyword] = latch

            sendCommand("GETINFO $keyword\r\n")

            // Wait up to 10 seconds for the listener thread to deliver the response
            if (!latch.await(10, TimeUnit.SECONDS)) {
                Log.w(tag, "GETINFO timeout for $keyword")
                pendingResponses.remove(keyword)
                return null
            }

            return responseValues.remove(keyword)
        } catch (e: Exception) {
            Log.e(tag, "GETINFO failed for $keyword", e)
            pendingResponses.remove(keyword)
            return null
        } finally {
            requestLock.unlock()
        }
    }

    /**
     * Sends a SIGNAL to the control port.
     *
     * Uses a synthetic key "SIGNAL_<name>" so the listener can match the
     * 250-OK terminator to this request. Serialized via [requestLock].
     */
    fun signal(signal: String): Boolean {
        if (!isConnected) return false

        requestLock.lock()
        try {
            val key = "SIGNAL_$signal"
            val latch = CountDownLatch(1)
            pendingResponses[key] = latch

            sendCommand("SIGNAL $signal\r\n")

            if (!latch.await(5, TimeUnit.SECONDS)) {
                pendingResponses.remove(key)
                return false
            }

            val resp = responseValues.remove(key)
            return resp?.startsWith("250") == true
        } catch (e: Exception) {
            Log.e(tag, "SIGNAL $signal failed", e)
            return false
        } finally {
            requestLock.unlock()
        }
    }

    // ── Internal ──────────────────────────────────────────────────

    private fun sendCommand(command: String) {
        try {
            val os = outputStream ?: return
            synchronized(os) {
                os.write(command.toByteArray())
                os.flush()
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to send command: $command", e)
            isConnected = false
        }
    }

    /**
     * Synchronous readLine for initial auth before the listener thread starts.
     */
    private fun readLineSync(): String {
        val sb = StringBuilder()
        val stream = inputStream ?: return ""

        while (true) {
            val b = stream.read()
            if (b == -1 || b == 10) break
            if (b != 13) sb.append(b.toChar())
        }

        return sb.toString()
    }

    /**
     * Listener thread — the ONLY reader from inputStream after connect().
     * Routes responses to pending GETINFO requests or event callbacks.
     */
    private fun listenForEvents() {
        try {
            val stream = inputStream ?: return

            while (isConnected && !Thread.currentThread().isInterrupted) {
                val line = readLineFromStream(stream)
                if (line.isEmpty()) continue

                when {
                    // Async events (650 ...)
                    line.startsWith("650 BW ") -> parseBandwidthEvent(line)
                    line.startsWith("650 CIRC ") -> onCircuitEvent?.invoke(line)
                    line.startsWith("650 STATUS_CLIENT ") -> onStatusEvent?.invoke(line)
                    line.startsWith("650 BOOTSTRAP ") -> parseBootstrapEvent(line)

                    // GETINFO responses (250-keyword=value or 250 OK)
                    line.startsWith("250-") -> {
                        // Format: 250-keyword=value
                        val eqIdx = line.indexOf('=', 4)
                        if (eqIdx > 4) {
                            val keyword = line.substring(4, eqIdx)
                            val value = line.substring(eqIdx + 1)
                            responseValues[keyword] = value
                            pendingResponses.remove(keyword)?.countDown()
                        }
                    }
                    line.startsWith("250 ") -> {
                        // Final line of a response: 250 OK
                        // Only unblock the SINGLE oldest pending request — never
                        // all of them. Because getInfo/signal are serialized
                        // via requestLock, there is at most ONE pending request
                        // at a time. We pop it and store the 250 OK line so the
                        // caller (signal()) can check the response code.
                        //
                        // This is SIGNAL's response path (GETINFO's response is
                        // always the 250- line above; SIGNAL's response is just
                        // 250 OK with no preceding data line).
                        val firstKey = pendingResponses.keys.firstOrNull()
                        if (firstKey != null) {
                            if (!responseValues.containsKey(firstKey)) {
                                responseValues[firstKey] = line
                            }
                            pendingResponses.remove(firstKey)?.countDown()
                        }
                    }
                    line.startsWith("5") -> {
                        // Error response — unblock the SINGLE oldest pending
                        // request only (same reason as above).
                        val firstKey = pendingResponses.keys.firstOrNull()
                        if (firstKey != null) {
                            responseValues[firstKey] = line  // store error so caller sees it
                            pendingResponses.remove(firstKey)?.countDown()
                        }
                    }
                }
            }
        } catch (e: InterruptedException) {
            Log.d(tag, "Event listener interrupted")
        } catch (e: Exception) {
            if (isConnected) {
                Log.e(tag, "Event listener error", e)
            }
            isConnected = false
        }
    }

    private fun readLineFromStream(stream: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val b = stream.read()
            if (b == -1 || b == 10) break
            if (b != 13) sb.append(b.toChar())
        }
        return sb.toString()
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

    private fun parseBootstrapEvent(line: String) {
        // Format: 650 BOOTSTRAP PROGRESS=<n> TAG=<tag> [WARNING=...]
        // Example: 650 BOOTSTRAP PROGRESS=100 TAG=done
        try {
            var progress = 0
            var tag = ""
            for (part in line.split(" ")) {
                when {
                    part.startsWith("PROGRESS=") -> progress = part.removePrefix("PROGRESS=").toIntOrNull() ?: 0
                    part.startsWith("TAG=") -> tag = part.removePrefix("TAG=")
                }
            }
            onBootstrapEvent?.invoke(progress, tag)
        } catch (e: Exception) {
            Log.e(tag, "Failed to parse bootstrap event", e)
        }
    }
}
