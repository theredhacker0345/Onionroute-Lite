package org.onionroute.lite.tor

import android.content.Context
import android.util.Log
import org.onionroute.lite.Constants
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.InetSocketAddress
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

    /**
     * Insertion order of the keys in [pendingResponses].
     *
     * The control port is strictly FIFO: replies come back in the order the
     * commands were written. `ConcurrentHashMap.keys` is *hash-bucket* order, so
     * `keys.firstOrNull()` — which the `250 ` handler used to pick the request
     * to unblock — is arbitrary whenever more than one request is outstanding.
     * A bare `250 OK` carries no keyword to match on, so it has to be attributed
     * to *some* waiter; attributing it to a hash-order entry rather than the
     * oldest one can hand a caller the reply belonging to a different command,
     * which then consumes it and leaves the real waiter to time out.
     *
     * Tracks order explicitly so the attribution matches the protocol. In
     * practice getInfo/signal are serialized by [requestLock], so this only
     * matters when an entry has been left behind by a timeout — which is
     * exactly when mis-attribution is hardest to diagnose.
     */
    private val pendingOrder = java.util.concurrent.ConcurrentLinkedQueue<String>()

    private companion object {
        /**
         * TCP connect timeout for the control port.
         *
         * Bounded because connect() runs while the caller holds TorService's
         * startMutex; an unbounded connect stalls every later start attempt.
         */
        const val CONNECT_TIMEOUT_MS = 3000

        /**
         * Read timeout for the AUTHENTICATE exchange only.
         *
         * Reset to 0 (infinite) once authenticated, before the listener thread
         * starts, because the listener legitimately blocks for long stretches
         * waiting for the next async event.
         */
        const val AUTH_TIMEOUT_MS = 5000

        /**
         * Length of tor's control authentication cookie (AUTHENTICATION_COOKIE_LEN).
         *
         * Checked because tor writes the cookie as it opens the control port; a
         * read that lands mid-write yields a short or empty file, and tor
         * rejects it with "Wrong length on authentication cookie".
         */
        const val CONTROL_COOKIE_LENGTH = 32
    }

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

            // Bounded connect, unlike Socket(host, port) which defers to the
            // OS default (minutes on some devices). This runs while the caller
            // holds TorService's startMutex, so an unbounded connect would stall
            // every subsequent start/reconnect attempt.
            controlSocket = Socket()
            controlSocket?.connect(InetSocketAddress("127.0.0.1", port), CONNECT_TIMEOUT_MS)

            // IMPORTANT: soTimeout applies to ALL reads on this socket,
            // including the listener thread's blocking readLineFromStream().
            // The listener blocks indefinitely waiting for the next async
            // event (BW / BOOTSTRAP / CIRC). If we set a 15s timeout here,
            // the listener will throw SocketTimeoutException every 15s
            // when no event arrives (which is normal — Tor only emits BW
            // events ~1/sec and BOOTSTRAP events only during bootstrap).
            //
            // The previous 15s timeout caused the listener thread to die
            // with SocketTimeoutException during the long consensus
            // download (5-15s of silence between BOOTSTRAP events), so
            // bootstrap callbacks never fired and the app declared
            // "timed out at 0%" even though Tor was actually still
            // bootstrapping successfully.
            //
            // So: a bounded timeout WHILE AUTHENTICATING (see below), then
            // reset to infinite before the listener ever runs. The auth read
            // is the one read on this socket that can block forever if the peer
            // accepts the connection and then says nothing, and it is the read
            // that gates the entire start sequence — an unbounded wait there
            // would pin TorService's startMutex permanently and make every
            // future start/reconnect tryLock()-fail with no way out short of
            // killing the process.
            controlSocket?.soTimeout = AUTH_TIMEOUT_MS
            // Keep alive so the connection isn't killed by NAT idle
            // timeouts on long-running sessions.
            controlSocket?.keepAlive = true

            inputStream = controlSocket?.getInputStream()
            outputStream = controlSocket?.getOutputStream()

            // Authenticate using cookie — read response directly before listener starts
            val cookieFile = File(cookieFilePath)
            // Length check, not just exists(). Tor writes the cookie as it opens
            // the control port, so a read that lands mid-write (or on a
            // zero-byte file) would otherwise produce `AUTHENTICATE \r\n`,
            // which tor rejects with "Wrong length on authentication cookie".
            // That turned a transient race into a failed connect and burned one
            // of only six start attempts.
            if (!cookieFile.exists()) {
                Log.e(tag, "Cookie file not found: $cookieFilePath")
                disconnect()
                return false
            }
            val cookieBytes = cookieFile.readBytes()
            if (cookieBytes.size != CONTROL_COOKIE_LENGTH) {
                Log.e(tag, "Cookie file is ${cookieBytes.size} bytes, expected " +
                    "$CONTROL_COOKIE_LENGTH — tor is probably still writing it")
                disconnect()
                return false
            }
            val cookieHex = cookieBytes.joinToString("") { "%02x".format(it) }
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

            // Authenticated. The listener is about to start blocking on this
            // socket waiting for async events, which is correct — it must not
            // time out. Clear the auth-phase timeout now, or the listener would
            // inherit it and die on the first quiet period.
            controlSocket?.soTimeout = 0

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

            // Release any waiter still blocked on a reply. Without this, a
            // getInfo/signal issued just before a disconnect waits out its full
            // 5-10s timeout instead of failing immediately, and its key stays
            // queued to be misattributed to the NEXT connection's first reply.
            //
            // Snapshot the keys first, then remove AND count down, then clear
            // the order queue and cached values. Clearing the map before
            // counting down would drop the latches on the floor and leave the
            // callers blocked for the full timeout anyway.
            val orphaned = pendingResponses.keys.toList()
            for (key in orphaned) {
                pendingResponses.remove(key)?.countDown()
            }
            pendingOrder.clear()
            responseValues.clear()

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

        // Subscribe to async events.
        //
        // IMPORTANT: Tor's control port does NOT have a standalone "BOOTSTRAP"
        // event type. Bootstrap progress is delivered as a STATUS_CLIENT event
        // with a BOOTSTRAP severity:
        //   650 STATUS_CLIENT NOTICE BOOTSTRAP PROGRESS=100 TAG=done
        //
        // The previous code subscribed to "BOOTSTRAP" as if it were a separate
        // event type, which caused Tor to reject the entire SETEVENTS command:
        //   552 Unrecognized event "BOOTSTRAP"
        // When SETEVENTS is rejected, NO events are subscribed — not even BW
        // or CIRC. This is why the listener never received any 650 events and
        // the app declared "Tor bootstrap timed out at 0%" even though Tor
        // had actually finished bootstrapping.
        //
        // Fix: subscribe only to BW, CIRC, and STATUS_CLIENT. Bootstrap
        // progress is parsed from STATUS_CLIENT events in the listener.
        //
        // Consume the SETEVENTS reply synchronously, BEFORE the listener
        // exists. Previously this was a fire-and-forget write, leaving a
        // dangling "250 OK" on the socket. The listener then started and handed
        // that reply to whichever request happened to be pending — and
        // TorService registers its GETINFO status/bootstrap-phase latch
        // microseconds later, so the SETEVENTS ack was routinely delivered as
        // the GETINFO response. The GETINFO waiter then saw no PROGRESS= and
        // gave up, while the real GETINFO reply arrived with no waiter and was
        // dropped: exactly the "timed out at 0% while Tor was actually
        // bootstrapped" failure this code exists to prevent.
        //
        // Reading the ack here is safe because no listener thread is running,
        // so this is the only reader on the socket.
        val setEventsReply = try {
            synchronized(outputStream!!) {
                outputStream!!.write("SETEVENTS BW CIRC STATUS_CLIENT\r\n".toByteArray())
                outputStream!!.flush()
            }
            readLineSync()
        } catch (e: Exception) {
            Log.e(tag, "SETEVENTS failed", e)
            null
        }
        if (setEventsReply != null && !setEventsReply.startsWith("250")) {
            // e.g. 552 Unrecognized event. Nothing is subscribed, so bootstrap
            // progress can never arrive. Fail loudly instead of waiting out a
            // 120s timeout that can no longer be satisfied.
            Log.e(tag, "SETEVENTS rejected: $setEventsReply — no events subscribed")
        } else if (setEventsReply != null) {
            Log.i(tag, "Subscribed to BW, CIRC, STATUS_CLIENT events")
        }

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
            pendingOrder.add(keyword)

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
            pendingOrder.add(key)

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

    /**
     * Restricts exit nodes to a country at RUNTIME (applies immediately to a
     * connected session, no tor restart needed).
     *
     * @param code ISO-3166 alpha-2 country code (e.g. "de"), or null/blank to
     *   remove the restriction. Tor builds fresh circuits for new connections
     *   after this — existing circuits keep their old exits until rotated.
     * @return true if tor accepted the SETCONF.
     */
    fun setExitCountry(code: String?): Boolean {
        if (!isConnected) return false

        requestLock.lock()
        try {
            val key = "SETCONF_ExitNodes"
            val latch = CountDownLatch(1)
            pendingResponses[key] = latch
            pendingOrder.add(key)

            // Per control-spec: SETCONF with a value sets it; SETCONF with a
            // bare keyword resets that option to its compiled default, which
            // is exactly what "no exit-country restriction" means here.
            val command = if (code.isNullOrBlank()) "SETCONF ExitNodes\r\n"
                          else "SETCONF ExitNodes={$code}\r\n"
            sendCommand(command)

            if (!latch.await(5, TimeUnit.SECONDS)) {
                pendingResponses.remove(key)
                return false
            }

            val resp = responseValues.remove(key)
            return resp?.startsWith("250") == true
        } catch (e: Exception) {
            Log.e(tag, "SETCONF ExitNodes failed", e)
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
     * Attributes an unkeyed reply (bare `250 OK`, or any `5xx` error) to the
     * OLDEST pending request and releases it.
     *
     * The tor control port is strictly FIFO, so the reply belongs to whichever
     * command was written first. [pendingOrder] carries that insertion order;
     * picking from `pendingResponses.keys` instead would use hash-bucket order
     * and could hand a caller the reply for a different command.
     *
     * Entries whose latch has already been completed or timed out are skipped,
     * so a stale key cannot swallow a live reply.
     *
     * @param value the reply line to record for the caller.
     * @param onlyIfAbsent when true, keep any value already recorded (a `250 OK`
     *   must not overwrite the keyword=value payload a GETINFO already stored).
     * @return true if a waiter was released.
     */
    private fun completeOldestPending(value: String, onlyIfAbsent: Boolean): Boolean {
        while (true) {
            val candidate = pendingOrder.poll() ?: return false
            if (!pendingResponses.containsKey(candidate)) continue // stale, skip
            if (onlyIfAbsent && responseValues.containsKey(candidate)) {
                // Payload already delivered by the keyword branch; just release.
                return pendingResponses.remove(candidate)?.countDown() != null
            }
            responseValues[candidate] = value
            return pendingResponses.remove(candidate)?.countDown() != null
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
     *
     * Exits cleanly when the socket is closed (readLineFromStream returns
     * null on EOF). On exit, unblocks any pending GETINFO/SIGNAL latches
     * so callers don't wait the full timeout after a disconnect.
     */
    private fun listenForEvents() {
        try {
            val stream = inputStream ?: return

            while (isConnected && !Thread.currentThread().isInterrupted) {
                val line = readLineFromStream(stream) ?: break  // null = EOF
                if (line.isEmpty()) continue

                when {
                    // Async events (650 ...)
                    line.startsWith("650 BW ") -> parseBandwidthEvent(line)
                    line.startsWith("650 CIRC ") -> onCircuitEvent?.invoke(line)
                    // STATUS_CLIENT events include BOOTSTRAP progress notices:
                    //   650 STATUS_CLIENT NOTICE BOOTSTRAP PROGRESS=100 TAG=done
                    // We route these to BOTH onStatusEvent (raw line) AND
                    // parseBootstrapEvent (extracts PROGRESS + TAG) so the
                    // TorService bootstrap latch can fire when PROGRESS=100.
                    line.startsWith("650 STATUS_CLIENT ") -> {
                        onStatusEvent?.invoke(line)
                        if (line.contains(" BOOTSTRAP ")) {
                            parseBootstrapEvent(line)
                        }
                    }

                    // Multiline GETINFO values use the "+" data-reply format
                    // (control-spec §2.3):
                    //   250+circuit-status=
                    //   <line1>
                    //   <line2>
                    //   .
                    //   250 OK
                    // `GETINFO circuit-status` REQUIRES this path — its value
                    // contains embedded newlines, one per circuit.
                    line.startsWith("250+") -> {
                        val eqIdx = line.indexOf('=', 4)
                        if (eqIdx > 4) {
                            val keyword = line.substring(4, eqIdx)
                            val data = StringBuilder()
                            var terminated = false
                            while (!terminated && isConnected && !Thread.currentThread().isInterrupted) {
                                val dataLine = readLineFromStream(stream) ?: break  // EOF mid-reply
                                if (dataLine == ".") {
                                    terminated = true
                                } else {
                                    if (data.isNotEmpty()) data.append('\n')
                                    // Spec: lines starting with '.' are escaped by
                                    // doubling the dot. Circuit paths never start with
                                    // one, but unescape defensively anyway.
                                    data.append(if (dataLine.startsWith("..")) dataLine.drop(1) else dataLine)
                                }
                            }
                            responseValues[keyword] = data.toString()
                            pendingResponses.remove(keyword)?.countDown()
                        }
                    }

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
                        // Final line of a response: 250 OK. A bare `250 OK`
                        // carries no keyword, so it must be attributed to the
                        // OLDEST waiter — the control port is FIFO.
                        // See pendingOrder for why hash order was wrong here.
                        completeOldestPending(line, onlyIfAbsent = true)
                    }
                    line.startsWith("5") -> {
                        // Error response — attribute to the same oldest waiter
                        // and overwrite any stored value so the caller sees the
                        // error rather than a stale success line.
                        completeOldestPending(line, onlyIfAbsent = false)
                    }
                }
            }
            // Loop exited — either EOF, interrupted, or isConnected=false.
            // Unblock any pending GETINFO/SIGNAL callers so they don't wait
            // the full 5-10s timeout. They'll see null/false (no response
            // value stored) which is the correct "disconnected" behaviour.
            Log.w(tag, "Listener: event loop exited (isConnected=$isConnected, interrupted=${Thread.currentThread().isInterrupted})")
            for ((_, latch) in pendingResponses) {
                latch.countDown()
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

    /**
     * Reads a line terminated by \n from the stream.
     * Returns null on EOF (so the caller can distinguish "blank line" from
     * "stream closed"). Returns empty string for a blank line.
     *
     * The previous implementation returned "" on EOF, which caused the
     * listener loop to spin forever (continue on empty) consuming 100% CPU
     * after the Tor process died and the socket closed.
     */
    private fun readLineFromStream(stream: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = stream.read()
            if (b == -1) {
                // EOF — if we have accumulated data, return it; otherwise null
                return if (sb.isEmpty()) null else sb.toString()
            }
            if (b == 10) break  // \n
            if (b != 13) sb.append(b.toChar())  // skip \r
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
