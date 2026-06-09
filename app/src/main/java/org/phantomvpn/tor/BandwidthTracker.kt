package org.phantomvpn.tor

import java.util.concurrent.atomic.AtomicLong

/**
 * Tracks bandwidth usage by reading Tor Control Port BW events.
 *
 * This class maintains both real-time speed and cumulative totals.
 * The Tor control port sends BW events every ~1 second with the
 * number of bytes read and written in that interval.
 *
 * Usage:
 *   - onBandwidthEvent() is called from the control port listener
 *   - UI reads current values via getSnapshot()
 *   - Totals persist across service restarts via SharedPreferences
 */
class BandwidthTracker {

    // Cumulative totals (bytes)
    private val totalBytesRead = AtomicLong(0)    // downloaded
    private val totalBytesWritten = AtomicLong(0) // uploaded

    // Current speed (bytes/second, updated by BW events)
    private val currentSpeedDown = AtomicLong(0)
    private val currentSpeedUp = AtomicLong(0)

    // Timestamps for speed calculation
    private var lastEventTimeMs: Long = 0

    /**
     * Called when a BW event arrives from the control port.
     *
     * Tor sends: 650 BW <bytes_read> <bytes_written>
     * Where bytes_read = downloaded, bytes_written = uploaded
     * These represent bytes in the last ~1 second.
     *
     * @param bytesRead Bytes received (downloaded) in this interval
     * @param bytesWritten Bytes sent (uploaded) in this interval
     */
    fun onBandwidthEvent(bytesRead: Long, bytesWritten: Long) {
        val now = System.currentTimeMillis()
        val elapsed = now - lastEventTimeMs

        if (lastEventTimeMs > 0 && elapsed > 0) {
            // Calculate real-time speed (bytes per second)
            currentSpeedDown.set((bytesRead * 1000) / elapsed)
            currentSpeedUp.set((bytesWritten * 1000) / elapsed)
        }

        lastEventTimeMs = now
        totalBytesRead.addAndGet(bytesRead)
        totalBytesWritten.addAndGet(bytesWritten)
    }

    /**
     * Get a snapshot of current bandwidth data.
     */
    fun getSnapshot(): BandwidthData {
        return BandwidthData(
            totalBytesDown = totalBytesRead.get(),
            totalBytesUp = totalBytesWritten.get(),
            speedDown = currentSpeedDown.get(),
            speedUp = currentSpeedUp.get()
        )
    }

    /**
     * Reset cumulative totals (e.g., on new session).
     */
    fun reset() {
        totalBytesRead.set(0)
        totalBytesWritten.set(0)
        currentSpeedDown.set(0)
        currentSpeedUp.set(0)
        lastEventTimeMs = 0
    }

    /**
     * Restore totals from persistent storage.
     */
    fun restoreTotals(totalDown: Long, totalUp: Long) {
        totalBytesRead.set(totalDown)
        totalBytesWritten.set(totalUp)
    }

    /**
     * Mark connection as idle — set speeds to zero.
     * Called when Tor disconnects or is stopped.
     */
    fun markIdle() {
        currentSpeedDown.set(0)
        currentSpeedUp.set(0)
    }
}

/**
 * Immutable snapshot of bandwidth data for UI consumption.
 */
data class BandwidthData(
    val totalBytesDown: Long,
    val totalBytesUp: Long,
    val speedDown: Long,
    val speedUp: Long
) {
    /**
     * Format total bytes as human-readable string.
     * Examples: 1.2 KB, 3.4 MB, 5.6 GB
     */
    fun formatTotalBytes(bytes: Long): String {
        return when {
            bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
            bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
            bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    /**
     * Format speed as human-readable string.
     * Examples: 12.3 KB/s, 1.2 MB/s
     */
    fun formatSpeed(bytesPerSec: Long): String {
        return when {
            bytesPerSec >= 1_048_576 -> "%.1f MB/s".format(bytesPerSec / 1_048_576.0)
            bytesPerSec >= 1024 -> "%.1f KB/s".format(bytesPerSec / 1024.0)
            else -> "$bytesPerSec B/s"
        }
    }

    val totalDownFormatted: String get() = formatTotalBytes(totalBytesDown)
    val totalUpFormatted: String get() = formatTotalBytes(totalBytesUp)
    val speedDownFormatted: String get() = formatSpeed(speedDown)
    val speedUpFormatted: String get() = formatSpeed(speedUp)
}
