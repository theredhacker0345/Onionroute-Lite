package org.onionroute.lite.tor

import android.util.Log
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Tracks bandwidth usage by polling the Tor control port.
 *
 * Periodically queries bandwidth statistics and calculates:
 * - Current speed (bytes/sec) for upload and download
 * - Cumulative totals for upload and download
 *
 * Results are delivered via the [onUpdate] callback on the main thread.
 */
class BandwidthTracker(
    private val onUpdate: (speedDown: Long, speedUp: Long, totalDown: Long, totalUp: Long) -> Unit
) {

    private val tag = "BandwidthTracker"

    private var scheduler: ScheduledExecutorService? = null
    private var controlConnection: TorControlConnection? = null

    private val totalDown = AtomicLong(0)
    private val totalUp = AtomicLong(0)
    private val lastDown = AtomicLong(0)
    private val lastUp = AtomicLong(0)
    private val lastTimestamp = AtomicLong(0)

    private val pollIntervalMs = 1000L

    // ── Public API ────────────────────────────────────────────────

    /**
     * Starts periodic bandwidth polling using the given control connection.
     *
     * @param connection An authenticated [TorControlConnection]
     */
    fun start(connection: TorControlConnection) {
        if (scheduler != null) {
            Log.w(tag, "BandwidthTracker already running")
            return
        }

        controlConnection = connection
        lastTimestamp.set(System.currentTimeMillis())

        // Subscribe to async bandwidth events
        connection.subscribeEvents(
            onBandwidth = { down, up ->
                handleBandwidthEvent(down, up)
            }
        )

        // Also schedule periodic GETINFO polling as a fallback
        scheduler = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "BandwidthTracker").apply { isDaemon = true }
        }

        scheduler?.scheduleAtFixedRate({
            pollBandwidth()
        }, pollIntervalMs, pollIntervalMs, TimeUnit.MILLISECONDS)

        Log.i(tag, "BandwidthTracker started")
    }

    /**
     * Stops bandwidth tracking.
     */
    fun stop() {
        scheduler?.shutdownNow()
        scheduler = null
        controlConnection = null

        totalDown.set(0)
        totalUp.set(0)
        lastDown.set(0)
        lastUp.set(0)
        lastTimestamp.set(0)

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
        totalDown.addAndGet(down - lastDown.get().coerceAtMost(down))
        totalUp.addAndGet(up - lastUp.get().coerceAtMost(up))

        lastDown.set(down)
        lastUp.set(up)
        lastTimestamp.set(now)

        onUpdate(speedDown, speedUp, totalDown.get(), totalUp.get())
    }

    private fun pollBandwidth() {
        val conn = controlConnection ?: return

        try {
            // Fallback: use GETINFO to query traffic stats
            val downStr = conn.getInfo("traffic/read")
            val upStr = conn.getInfo("traffic/written")

            if (downStr != null && upStr != null) {
                val down = downStr.toLongOrNull() ?: 0L
                val up = upStr.toLongOrNull() ?: 0L

                val now = System.currentTimeMillis()
                val elapsed = (now - lastTimestamp.get()).coerceAtLeast(1)

                val speedDown = ((down - lastDown.get()).coerceAtLeast(0) * 1000L) / elapsed
                val speedUp = ((up - lastUp.get()).coerceAtLeast(0) * 1000L) / elapsed

                totalDown.set(down)
                totalUp.set(up)

                lastDown.set(down)
                lastUp.set(up)
                lastTimestamp.set(now)

                onUpdate(speedDown, speedUp, down, up)
            }
        } catch (e: Exception) {
            Log.e(tag, "Bandwidth poll error", e)
        }
    }
}
