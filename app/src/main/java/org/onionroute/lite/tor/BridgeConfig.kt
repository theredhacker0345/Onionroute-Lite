package org.onionroute.lite.tor

import android.content.Context
import android.util.Log
import org.onionroute.lite.Constants

/**
 * Manages bridge configuration for Tor circumvention.
 *
 * Supports:
 * - Snowflake (WebRTC-based transport)
 * - obfs4 (scramblesuit-based transport)
 * - WebTunnel (HTTPS-mimicking transport)
 * - Custom bridge lines
 *
 * Bridge preferences are stored in SharedPreferences and applied
 * when generating the torrc configuration file.
 */
class BridgeConfig(private val context: Context) {

    private val tag = "BridgeConfig"

    private val prefs by lazy {
        context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
    }

    // ── Snowflake ─────────────────────────────────────────────────

    /**
     * Checks if Snowflake is enabled in preferences.
     */
    fun isSnowflakeEnabled(): Boolean {
        return prefs.getBoolean(Constants.PREF_SNOWFLAKE_ENABLED, false)
    }

    /**
     * Enables or disables Snowflake bridge.
     */
    fun setSnowflakeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(Constants.PREF_SNOWFLAKE_ENABLED, enabled).apply()
        Log.i(tag, "Snowflake ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Returns the Snowflake bridge configuration lines for torrc.
     */
    fun getSnowflakeConfig(): List<String> {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        return listOf(
            "ClientTransportPlugin snowflake exec $nativeLibDir/${Constants.BINARY_SNOWFLAKE}",
            "Bridge snowflake 192.0.2.3:80 2B280B23E1107BB62ABFC40DDCC8824814F80A72 fingerprint=2B280B23E1107BB62ABFC40DDCC8824814F80A72 url=${Constants.SNOWFLAKE_BROKER_URL} fronts=${Constants.SNOWFLAKE_FRONT_DOMAIN} ice=${Constants.SNOWFLAKE_ICE_SERVERS}",
            "UseBridges 1"
        )
    }

    // ── obfs4 ─────────────────────────────────────────────────────

    /**
     * Checks if obfs4 is enabled in preferences.
     */
    fun isObfs4Enabled(): Boolean {
        return prefs.getBoolean(Constants.PREF_OBFS4_ENABLED, false)
    }

    /**
     * Enables or disables obfs4 bridge.
     */
    fun setObfs4Enabled(enabled: Boolean) {
        prefs.edit().putBoolean(Constants.PREF_OBFS4_ENABLED, enabled).apply()
        Log.i(tag, "obfs4 ${if (enabled) "enabled" else "disabled"}")
    }

    // ── WebTunnel ────────────────────────────────────────────────

    /**
     * Checks if WebTunnel is enabled in preferences.
     */
    fun isWebTunnelEnabled(): Boolean {
        return prefs.getBoolean(Constants.PREF_WEBTUNNEL_ENABLED, false)
    }

    /**
     * Enables or disables WebTunnel bridge.
     */
    fun setWebTunnelEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(Constants.PREF_WEBTUNNEL_ENABLED, enabled).apply()
        Log.i(tag, "WebTunnel ${if (enabled) "enabled" else "disabled"}")
    }

    // ── Custom Bridges ────────────────────────────────────────────

    /**
     * Returns all custom bridge lines stored in preferences.
     *
     * Rejected lines are logged rather than dropped in silence: this preference
     * is fed by import/paste/QR paths that bypass [parseAndAddBridges], so a
     * rejected line here means someone handed us something that is not a
     * bridge, and hiding that would also hide an injection attempt.
     */
    fun getCustomBridges(): List<String> {
        val bridgesStr = prefs.getString(Constants.PREF_CUSTOM_BRIDGES, "") ?: ""
        return bridgesStr.split("\n")
            .map { it.trim() }
            .filter { line ->
                if (line.isEmpty()) return@filter false
                if (!isValidBridgeLine(line)) {
                    Log.w(tag, "Stored custom bridge rejected: ${describeRejected(line)}")
                    return@filter false
                }
                true
            }
    }

    /**
     * Saves custom bridge lines to preferences.
     */
    fun setCustomBridges(bridges: List<String>) {
        prefs.edit().putString(Constants.PREF_CUSTOM_BRIDGES, bridges.joinToString("\n")).apply()
        Log.i(tag, "Saved ${bridges.size} custom bridge(s)")
    }

    /**
     * Parses bridge text from QR scan or paste, handling multiple formats:
     *
     * Supported formats:
     *   - Plain bridge lines: "obfs4 IP:PORT FINGERPRINT cert=... iat-mode=0"
     *   - URI format: "obfs4://IP:PORT/?cert=...&iat-mode=0"
     *   - snowflake:// URI
     *   - webtunnel:// URI
     *   - Multiple lines separated by newlines
     *
     * Also decodes HTML entities (e.g. `&#43;` → `+`) because BridgeDB's
     * web page returns HTML-encoded bridge lines — if a user copy-pastes
     * from BridgeDB, the cert string would contain `&#43;` instead of `+`
     * and Tor would silently fail to parse the cert.
     *
     * Returns the number of valid bridges added.
     */
    fun parseAndAddBridges(text: String): Int {
        val existing = getCustomBridges().toMutableList()
        val newBridges = mutableListOf<String>()

        text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { line ->
                // Defensive: decode HTML entities that may have come from a
                // copy-paste of BridgeDB's HTML-encoded bridge page.
                val entityDecoded = decodeHtmlEntities(line)
                val bridgeLine = when {
                    // Handle bridge:// URI prefix (some QR codes use this)
                    entityDecoded.startsWith("bridge://", ignoreCase = true) -> {
                        entityDecoded.removePrefix("bridge://").removePrefix("Bridge://")
                    }
                    // Handle obfs4:// URI format → convert to plain obfs4 line
                    entityDecoded.startsWith("obfs4://", ignoreCase = true) -> {
                        convertUriToBridgeLine("obfs4", entityDecoded)
                    }
                    // Handle snowflake:// URI format → convert to plain snowflake line
                    entityDecoded.startsWith("snowflake://", ignoreCase = true) -> {
                        convertUriToBridgeLine("snowflake", entityDecoded)
                    }
                    // Handle webtunnel:// URI format → convert to plain webtunnel line
                    entityDecoded.startsWith("webtunnel://", ignoreCase = true) -> {
                        convertUriToBridgeLine("webtunnel", entityDecoded)
                    }
                    // Already a plain bridge line
                    else -> entityDecoded
                }

                val trimmed = bridgeLine.trim()
                if (trimmed.isNotEmpty() && !isValidBridgeLine(trimmed)) {
                    // Not a complete bridge line for any transport we support.
                    // Tor's torrc parser has no quoting/escaping, so the only
                    // sound response to a line we do not fully understand is to
                    // refuse it — see validateBridgeLine.
                    Log.w(tag, "Rejected pasted/QR bridge line: ${describeRejected(trimmed)}")
                } else if (trimmed.isNotEmpty() &&
                    !existing.contains(trimmed) && !newBridges.contains(trimmed)
                ) {
                    newBridges.add(trimmed)
                }
            }

        if (newBridges.isNotEmpty()) {
            existing.addAll(newBridges)
            setCustomBridges(existing)
            Log.i(tag, "Parsed and added ${newBridges.size} new bridge(s)")
        }

        return newBridges.size
    }

    /**
     * Decodes common HTML entities in bridge lines (defensive).
     * Same set as BridgeUpdater.decodeHtmlEntities.
     */
    private fun decodeHtmlEntities(s: String): String {
        return s
            .replace("&#43;", "+")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&#x27;", "'")
    }

    /**
     * Converts a URI-style bridge line (e.g. "obfs4://1.2.3.4:443/?cert=abc&iat-mode=0")
     * to a plain bridge line (e.g. "obfs4 1.2.3.4:443 FINGERPRINT cert=abc iat-mode=0").
     *
     * The fingerprint may be absent in URI format; we preserve what we can.
     */
    private fun convertUriToBridgeLine(transport: String, uri: String): String {
        val withoutScheme = uri.removePrefix("$transport://").removePrefix("${transport.uppercase()}://")

        // Split host:port from query params
        val pathAndQuery = withoutScheme.split("?", limit = 2)
        val hostPort = pathAndQuery[0].trimEnd('/')

        val params = if (pathAndQuery.size > 1) {
            pathAndQuery[1]
                .split("&")
                .mapNotNull { param ->
                    val kv = param.split("=", limit = 2)
                    if (kv.size == 2) {
                        // Convert URL-encoded values
                        "${kv[0]}=${java.net.URLDecoder.decode(kv[1], "UTF-8")}"
                    } else null
                }
                .joinToString(" ")
        } else ""

        return "$transport $hostPort $params".trim()
    }

    /**
     * Checks if a line is a valid bridge line (a complete specification for one
     * of the transports this app ships a client binary for).
     *
     * This used to be a bare `startsWith("obfs4")` prefix test, which accepted
     * ANY suffix — including a carriage return followed by further torrc
     * directives. It now delegates to [validateBridgeLine], the very same
     * check TorProcessManager applies before writing, so a line can never be
     * accepted here and then judged by a weaker gate later.
     */
    private fun isValidBridgeLine(line: String): Boolean = validateBridgeLine(line)

    // ── Bridge Line Validation (the torrc injection boundary) ─────

    companion object {
        /**
         * Strict validation for a single torrc `Bridge` argument.
         *
         * This is the trust boundary for everything that becomes a `Bridge`
         * directive. All of it is attacker-influenced:
         *
         *   - the runtime bridge list is downloaded over HTTPS from a third-party
         *     repository (BridgeUpdater) and cached, and a JSON string may legally
         *     carry a real CR/LF (JSON `"\n"` decodes to a newline, not two
         *     characters). BridgeUpdater decodes HTML entities but does not strip
         *     control characters, and `&#10;`/`&#13;` style entities do not even
         *     need to be present for the payload to arrive.
         *   - custom bridges are typed, pasted, imported or QR-scanned by the user,
         *     i.e. arbitrary text from an arbitrary camera.
         *
         * Tor reads torrc one LINE at a time and turns every line into its own
         * directive, so a "bridge line" containing an embedded CR or LF stops
         * being one argument and becomes several directives. For every
         * single-valued option the LAST occurrence wins, so a payload that lands
         * after the app's own line silently overrides it:
         *
         *     obfs4 1.2.3.4:1 <fpr> cert=x
         *     CookieAuthentication 0
         *     ControlPort 0.0.0.0:9052
         *
         * That hands any device on the network an unauthenticated Tor control
         * port — exactly the exposure tor's `CL_PORT_FORBID_NONLOCAL` exists to
         * prevent. `DisableNetwork 1`, a redirected `DataDirectory`, and
         * `ClientTransportPlugin <t> exec <path>` (which swaps a transport proxy
         * for an arbitrary executable the daemon will run) are equally reachable,
         * as is a comment (`# …`) that hides the app's directives from a human
         * reading the file.
         *
         * Tor's config parser has NO quoting or escaping mechanism, so there is
         * nothing to sanitise with: a line is either a complete, valid bridge
         * specification for a transport we bundle a binary for, or it is rejected.
         * Rejecting is also the only failure mode that is safe — quietly keeping
         * a line we could not parse is how an injection gets through.
         *
         * Every pattern below is fully anchored (`\A` … `\z`) and every field is
         * restricted to an explicit character class, so no trailing junk, extra
         * argument or embedded whitespace can ride along.
         *
         * @param line a candidate bridge line, WITHOUT the `Bridge ` prefix
         * @return true only if [line] is safe to write verbatim into torrc
         */
        fun validateBridgeLine(line: String): Boolean {
            // Bounds: a blank line is meaningless, and an unbounded line is an
            // unbounded amount of attacker-chosen text in the config file.
            if (line.isEmpty() || line.length > MAX_BRIDGE_LINE_LENGTH) return false

            // The control-character check is what actually stops torrc injection:
            // CR/LF terminate a directive, and NUL (plus any other C0 byte and DEL)
            // is never legitimate bridge syntax. Tabs are covered here too, so the
            // patterns below only ever have to deal with single-space separators.
            if (containsControlChars(line)) return false

            return OBFS4_LINE.matches(line) ||
                WEBTUNNEL_LINE.matches(line) ||
                SNOWFLAKE_LINE.matches(line)
        }

        /**
         * True if [line] contains any C0 control character (< 0x20) or DEL (0x7F).
         *
         * Tor's torrc parser reads one directive per line, which makes CR and LF
         * directive separators rather than data; a NUL byte can truncate the line
         * in other config readers and is never valid bridge syntax either.
         */
        fun containsControlChars(line: String): Boolean =
            line.any { it.code < 0x20 || it.code == 0x7F }

        /**
         * Short, LOG-SAFE rendering of a rejected value, for use in log messages.
         *
         * Control characters are escaped rather than dropped: `android.util.Log`
         * writes each string as its own log line, so a rejected line carrying a
         * newline could otherwise forge additional log entries (hiding the
         * rejection, or faking a tor `[notice]`). Output is also truncated so a
         * hostile feed cannot flood logcat.
         */
        fun describeRejected(line: String): String {
            val clipped = if (line.length > REJECT_LOG_CLIP) line.take(REJECT_LOG_CLIP) + "…" else line
            return buildString {
                for (c in clipped) {
                    when {
                        c == '\n' -> append("\\n")
                        c == '\r' -> append("\\r")
                        c == '\t' -> append("\\t")
                        c.code < 0x20 || c.code == 0x7F -> append("\\u").append(
                            c.code.toString(16).padStart(4, '0')
                        )
                        else -> append(c)
                    }
                }
            }
        }

        /**
         * Longest bridge line accepted. The built-in snowflake line (see
         * `snowflakeFallbackLine` in TorProcessManager) is ~320 characters —
         * five STUN servers plus a broker URL — so 1024 leaves generous
         * headroom for a long certificate or STUN list while still bounding how
         * much attacker-chosen text can reach torrc.
         */
        const val MAX_BRIDGE_LINE_LENGTH = 1024

        /** How much of a rejected line ends up in the log message. */
        private const val REJECT_LOG_CLIP = 80

        // ── Bridge line grammar ──────────────────────────────────────
        //
        // Shapes taken from Constants.DEFAULT_OBFS4_BRIDGES,
        // Constants.DEFAULT_WEBTUNNEL_BRIDGES and the snowflake line in
        // BridgeConfig.getSnowflakeConfig():
        //
        //   obfs4    <addr> <fpr> cert=<base64> [iat-mode=N]
        //   webtunnel <addr> <fpr> url=<https url> [ver=<version>]
        //   snowflake <addr> <fpr> fingerprint=<fpr> url=<https url>
        //            [fronts=<domains>] [ice=<stun list>] [iceserver=...]
        //
        // `(?i)` mirrors tor, which matches transport and argument names
        // case-insensitively; it widens no character class.

        /** Host part of `<addr>`: DNS name or IPv4 literal. No `/`, `:`, `@`,
         *  shell metacharacter or whitespace can slip through. */
        private const val HOST = "[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?"
        private const val PORT = "[0-9]{1,5}"
        private const val ADDR = "$HOST:$PORT"

        /** Bridge identity: exactly 40 hex digits (RSA identity fingerprint).
         *  Anything else is not a bridge we can pin. */
        private const val FINGERPRINT = "[0-9A-Fa-f]{40}"

        /** obfs4 `cert=` is base64 in the standard alphabet, padded or not.
         *  `/` and `+` must stay in the class — real certs contain both. */
        private const val OBFS4_CERT = "[A-Za-z0-9+/=]{40,200}"

        /** HTTPS URL body. `#` is excluded on purpose: tor treats `#` as the
         *  comment marker while reading a config line. Quotes, braces, brackets
         *  and backslash are excluded so the value can never terminate the
         *  argument list or open a brace group. */
        private const val HTTPS_URL_BODY = "[A-Za-z0-9._~:/?@%&=-]{1,512}"

        /** Comma-separated domain list (`fronts=`). */
        private const val DOMAIN_LIST = "[A-Za-z0-9._,-]{1,256}"

        /** Comma-separated STUN/TURN server list (`ice=`, `iceserver=`). */
        private const val ICE_LIST = "[A-Za-z0-9._:,-]{1,512}"

        private val OBFS4_LINE = Regex(
            "(?i)\\Aobfs4 $ADDR $FINGERPRINT cert=$OBFS4_CERT" +
                "( iat-mode=[0-9]{1,2}(,[0-9]{1,2}){0,3})?\\z"
        )

        private val WEBTUNNEL_LINE = Regex(
            "(?i)\\Awebtunnel $ADDR $FINGERPRINT url=https://$HTTPS_URL_BODY" +
                "( ver=[0-9A-Za-z.+-]{1,16})?\\z"
        )

        private val SNOWFLAKE_LINE = Regex(
            "(?i)\\Asnowflake $ADDR $FINGERPRINT fingerprint=$FINGERPRINT" +
                " url=https://$HTTPS_URL_BODY" +
                "( fronts=$DOMAIN_LIST)?( ice=$ICE_LIST)?( iceserver=$ICE_LIST)?\\z"
        )
    }

    // ── Full Configuration ────────────────────────────────────────

}
