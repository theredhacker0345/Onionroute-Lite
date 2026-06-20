package org.onionroute.lite.tor

import android.util.Log
import java.util.concurrent.atomic.AtomicLong

/**
 * Tracks bandwidth usage via Tor control port BW events.
 *
 * Receives real-time bandwidth events from the Tor control connection
 * and calculates current speed and cumulative totals.
 *
 * Tor BW event format:
 *   650 BW <bytes-read-last-second> <bytes-written-last-second>
 *
 * IMPORTANT: Tor's BW event reports the BYTES TRANSFERRED IN THE LAST
 * SECOND (an instantaneous rate), NOT a cumulative counter. So:
 *   - `down` / `up` from the event are already a speed (bytes/sec).
 *   - To compute cumulative totals, we SUM the per-second deltas.
 *
 * Previously this code treated the values as cumulative and computed
 * `speed = delta/delta_time`, which gave wildly wrong speed numbers
 * (because `delta` was already a speed, not a delta) and totals that
 * drifted because `delta_time` was often ~0 between back-to-back events.
 */
class BandwidthTracker(
    private val onUpdate: (speedDown: Long, speedUp: Long, totalDown: Long, totalUp: Long) -> Unit
) {

    private val tag = "BandwidthTracker"

    private var controlConnection: TorControlConnection? = null

    private val totalDown = AtomicLong(0)
    private val totalUp = AtomicLong(0)

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

        Log.i(tag, "BandwidthTracker stopped")
    }

    /**
     * Resets cumulative totals.
     */
    fun resetTotals() {
        totalDown.set(0)
        totalUp.set(0)
        Log.d(tag, "Bandwidth totals reset")
    }

    // ── Internal ──────────────────────────────────────────────────

    /**
     * Handles a Tor BW event.
     *
     * `down` and `up` are bytes transferred in the last second (i.e. already
     * a speed in B/s). We add them to the cumulative totals.
     */
    private fun handleBandwidthEvent(down: Long, up: Long) {
        // Sanity: Tor sometimes reports 0 or negative on edge cases; coerce.
        val safeDown = down.coerceAtLeast(0)
        val safeUp = up.coerceAtLeast(0)

        // Accumulate cumulative totals. Each BW event represents 1 second
        // of traffic, so the values are both the speed (B/s) AND the
        // increment to the total.
        totalDown.addAndGet(safeDown)
        totalUp.addAndGet(safeUp)

        onUpdate(safeDown, safeUp, totalDown.get(), totalUp.get())
    }
}
