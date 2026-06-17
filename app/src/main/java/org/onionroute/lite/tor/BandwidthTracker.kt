package org.onionroute.lite.tor

import android.util.Log
import java.util.concurrent.atomic.AtomicLong

/**
 * Tracks bandwidth usage via Tor control port BW events.
 *
 * Receives real-time bandwidth events from the Tor control connection
 * and calculates current speed and cumulative totals.
 *
 * Previously polled GETINFO traffic/read and traffic/written as a fallback,
 * but this caused a race condition with the event listener thread on the
 * shared InputStream. Now we rely exclusively on async BW events.
 */
class BandwidthTracker(
    private val onUpdate: (speedDown: Long, speedUp: Long, totalDown: Long, totalUp: Long) -> Unit
) {

    private val tag = "BandwidthTracker"

    private var controlConnection: TorControlConnection? = null

    private val totalDown = AtomicLong(0)
    private val totalUp = AtomicLong(0)
    private val lastDown = AtomicLong(0)
    private val lastUp = AtomicLong(0)
    private val lastTimestamp = AtomicLong(0)

    // ── Public API ────────────────────────────────────────────────

    /**
     * Starts bandwidth tracking using the given control connection.
     *
     * @param connection An authenticated [TorControlConnection]
     * @param onBootstrap Optional bootstrap progress callback (progress 0..100, tag).
     *   The caller (TorService) uses this to wait for Tor to finish bootstrapping
     *   before declaring CONNECTED.
     */
    fun start(
        connection: TorControlConnection,
        onBootstrap: (progress: Int, tag: String) -> Unit = { _, _ -> }
    ) {
        controlConnection = connection
        lastTimestamp.set(System.currentTimeMillis())

        // Subscribe to async bandwidth + bootstrap events — no GETINFO polling needed
        connection.subscribeEvents(
            onBandwidth = { down, up ->
                handleBandwidthEvent(down, up)
            },
            onBootstrap = onBootstrap
        )

        Log.i(tag, "BandwidthTracker started")
    }

    /**
     * Stops bandwidth tracking.
     */
    fun stop() {
        controlConnection = null

        totalDown.set(0)
        totalUp.set(0)
        lastDown.set(0)
        lastUp.set(0)

        Log.i(tag, "BandwidthTracker stopped")
    }

    /**
     * Resets cumulative totals.
     */
    fun resetTotals() {
        totalDown.set(0)
        totalUp.set(0)
        lastDown.set(0)
        lastUp.set(0)
        Log.d(tag, "Bandwidth totals reset")
    }

    // ── Internal ──────────────────────────────────────────────────

    private fun handleBandwidthEvent(down: Long, up: Long) {
        val now = System.currentTimeMillis()
        val elapsed = (now - lastTimestamp.get()).coerceAtLeast(1)

        // Calculate speed (bytes per second)
        val speedDown = ((down - lastDown.get()).coerceAtLeast(0) * 1000L) / elapsed
        val speedUp = ((up - lastUp.get()).coerceAtLeast(0) * 1000L) / elapsed

        // Update totals
        totalDown.addAndGet((down - lastDown.get()).coerceAtLeast(0))
        totalUp.addAndGet((up - lastUp.get()).coerceAtLeast(0))

        lastDown.set(down)
        lastUp.set(up)
        lastTimestamp.set(now)

        onUpdate(speedDown, speedUp, totalDown.get(), totalUp.get())
    }
}
