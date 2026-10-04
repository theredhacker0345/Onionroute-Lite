package org.onionroute.lite.tor

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

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
         * Parses tor's `TIME_CREATED` value into epoch milliseconds.
         *
         * The control port emits local wall-clock time with no zone suffix and
         * no fractional part, e.g. `2026-07-13T16:09:14`. A trailing `Z` or
         * numeric offset is tolerated in case a future tor adds one; it is
         * stripped and the value is interpreted in the device's default time
         * zone, matching what tor itself means by a zone-less timestamp.
         *
         * LOW-RAM / API-23: java.time is API 26+, and this app's minSdk is 23
         * (its primary target is a 1.5GB Android 6.0 device). Core-library
         * desugaring would pull in an extra artifact, so we format directly.
         *
         * SimpleDateFormat is not thread-safe, so a fresh instance is used per
         * call; parsing runs once per circuit per refresh, not per frame.
         *
         * @return epoch millis, or 0L if the value is blank or unparsable.
         */
        private fun parseTorTimestamp(raw: String): Long {
            val value = raw.trim()
            if (value.isEmpty()) return 0L
            // Split on the date/time separator, then drop any zone designator
            // from the time half ("16:09:14Z" / "16:09:14+02:00" -> "16:09:14").
            val separator = value.indexOf('T')
            if (separator <= 0) return 0L
            val datePart = value.substring(0, separator)
            val timePart = value.substring(separator + 1).substringBefore('+')
                .substringBefore('-').removeSuffix("Z")
            val candidate = "${datePart}T$timePart"
            for (pattern in TIMESTAMP_PATTERNS) {
                try {
                    val fmt = SimpleDateFormat(pattern, Locale.US)
                    fmt.isLenient = false
                    return fmt.parse(candidate)?.time ?: 0L
                } catch (_: Exception) {
                    // Try the next pattern.
                }
            }
            return 0L
        }

        private val TIMESTAMP_PATTERNS = listOf(
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm:ss.SSS"
        )

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
