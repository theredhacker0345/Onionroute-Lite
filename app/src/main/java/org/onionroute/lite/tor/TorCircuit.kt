package org.onionroute.lite.tor

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * A single Tor circuit parsed from the control port's
 * `GETINFO circuit-status` reply.
 *
 * Raw line format (control-spec 5.x):
 * ```
 * <CircID> <Status> [BuildFlags...] PURPOSE=<purpose> PATH=$FP~Nick,$FP~Nick,... TIME_CREATED=<iso-ts>
 * ```
 * Only the fields useful for display are extracted; unknown tokens are ignored
 * so future tor versions don't break parsing.
 */
data class TorCircuit(
    /** Numeric circuit identifier (per-session). */
    val id: String,
    /** LAUNCHED | EXTENDED | BUILT | FAILED | CLOSED */
    val status: String,
    /** GENERAL | CLIENT | HS_CLIENT_REND | TESTING_* | CONTROLLER_INTRO … */
    val purpose: String,
    /** Hop nicknames in order: entry → middle → exit. Empty until EXTENDED/BUILT. */
    val hops: List<String>,
    /** Epoch milliseconds of circuit creation, or 0 if unparsable. */
    val timeCreatedMs: Long
) {

    val isActive: Boolean get() = status == "LAUNCHED" || status == "EXTENDED" || status == "BUILT"

    companion object {

        /**
         * Parses the full `circuit-status` value (one circuit per line,
         * newline-separated). Malformed lines are skipped individually so a
         * single oddity never blanks the whole viewer.
         */
        fun parseAll(raw: String?): List<TorCircuit> {
            if (raw.isNullOrBlank()) return emptyList()
            return raw.lines()
                .mapNotNull { parseLine(it.trim()) }
                .sortedWith(compareByDescending<TorCircuit> { it.isActive }.thenByDescending { it.timeCreatedMs })
        }

        private fun parseLine(line: String): TorCircuit? {
            if (line.isEmpty()) return null
            val tokens = line.split(" ")
            if (tokens.size < 2) return null

            val id = tokens[0]
            val status = tokens[1].uppercase()

            var purpose = ""
            var path = ""
            var timeCreated = ""

            for (token in tokens.drop(2)) {
                when {
                    token.startsWith("PURPOSE=") -> purpose = token.removePrefix("PURPOSE=")
                    token.startsWith("PATH=") -> path = token.removePrefix("PATH=")
                    token.startsWith("TIME_CREATED=") -> timeCreated = token.removePrefix("TIME_CREATED=")
                }
            }

            val hops = path.split(",")
                .filter { it.isNotBlank() }
                .map { hopSegment ->
                    // Segment format: $<40-hex-fingerprint>~<Nickname>
                    val tilde = hopSegment.indexOf('~')
                    if (tilde > 0) hopSegment.substring(tilde + 1)
                    else hopSegment.take(8) // fingerprint prefix as fallback
                }

            val createdMs = parseTorTimestamp(timeCreated)

            return TorCircuit(
                id = id,
                status = status,
                purpose = purpose.ifBlank { "GENERAL" },
                hops = hops,
                timeCreatedMs = createdMs
            )
        }

        /**
         * Parses tor's `TIME_CREATED` value into epoch **seconds** × 1000.
         *
         * Format confirmed against the vendored tor 0.4.9.8 source:
         * `control_fmt.c:135-138` emits `TIME_CREATED=<buf>` where the buffer
         * comes from `format_iso_time_nospace_usec()`, i.e.
         * `format_iso_time_nospace()` + `snprintf(".%06d", tv_usec)`.
         * `format_iso_time()` is
         * `strftime("%Y-%m-%d %H:%M:%S", tor_gmtime_r(&t, &tm))`, so the value
         * is **UTC** in the form `2026-07-13T16:09:14.123456` — always with a
         * 6-digit microsecond fraction.
         *
         * Two subtleties that are easy to get wrong, and were:
         *
         *  1. The zone is UTC, not device-local. Parsing it as local time
         *     shifts every timestamp by the device's UTC offset, so
         *     CircuitScreen's `now - created` goes negative east of UTC, gets
         *     clamped by coerceAtLeast(0), and every circuit permanently reads
         *     "0s ago"; west of UTC every age is inflated by the offset. So the
         *     formatter pins UTC below.
         *
         *  2. The microsecond fraction is truncated rather than parsed. A
         *     3-digit `SSS` field cannot accept 6 digits when isLenient is
         *     false, so trying to keep milliseconds silently falls back to a
         *     fraction-less pattern anyway and loses them. Dropping them
         *     explicitly is honest and exact: CircuitScreen renders ages in
         *     whole seconds/minutes/hours, so sub-second precision is unused.
         *
         * API-23 COMPAT: java.time is API 26+ and this app's minSdk is 23 (its
         * primary target is a 1.5GB Android 6.0 device). Core-library
         * desugaring would pull in an extra artifact, so we format directly.
         *
         * SimpleDateFormat is not thread-safe, so a fresh instance is used per
         * call; parsing runs once per circuit per refresh, not per frame.
         *
         * @return epoch millis, floored to the second, or 0L if unparsable.
         */
        private fun parseTorTimestamp(raw: String): Long {
            val value = raw.trim()
            if (value.isEmpty()) return 0L
            // Keep only the whole-second part: YYYY-MM-DDTHH:MM:SS.
            val wholeSeconds = value.substringBefore('.').trim()
            if (wholeSeconds.length != TIMESTAMP_PATTERN_LENGTH) return 0L
            return try {
                val fmt = SimpleDateFormat(TIMESTAMP_PATTERN, Locale.US)
                fmt.isLenient = false
                // tor emits GMT. Without this the parse is device-local and every
                // circuit age is skewed by the device's UTC offset.
                fmt.timeZone = UTC
                fmt.parse(wholeSeconds)?.time ?: 0L
            } catch (_: Exception) {
                0L
            }
        }

        /** `yyyy-MM-ddTHH:mm:ss` — always exactly 19 characters. */
        private const val TIMESTAMP_PATTERN = "yyyy-MM-dd'T'HH:mm:ss"
        private const val TIMESTAMP_PATTERN_LENGTH = 19
        private val UTC: TimeZone = TimeZone.getTimeZone("UTC")

        // ── Broadcast JSON round-trip ─────────────────────────────
        // TorService owns the control connection; circuits cross the process
        // boundary to MainActivity as a JSON array string extra.

        fun toJsonArray(circuits: List<TorCircuit>): String {
            val arr = JSONArray()
            for (c in circuits) {
                arr.put(
                    JSONObject()
                        .put("id", c.id)
                        .put("status", c.status)
                        .put("purpose", c.purpose)
                        .put("hops", JSONArray(c.hops))
                        .put("created", c.timeCreatedMs)
                )
            }
            return arr.toString()
        }

        fun fromJsonArray(json: String?): List<TorCircuit> {
            if (json.isNullOrBlank()) return emptyList()
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    val hopsJson = o.optJSONArray("hops") ?: JSONArray()
                    TorCircuit(
                        id = o.optString("id", "?"),
                        status = o.optString("status", ""),
                        purpose = o.optString("purpose", "GENERAL"),
                        hops = (0 until hopsJson.length()).map { hopsJson.getString(it) },
                        timeCreatedMs = o.optLong("created", 0L)
                    )
                }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}
