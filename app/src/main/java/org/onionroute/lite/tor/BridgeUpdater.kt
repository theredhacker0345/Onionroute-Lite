package org.onionroute.lite.tor

import android.content.Context
import android.util.Log
import org.onionroute.lite.Constants
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/**
 * Fetches alive bridges from the canonical GitLab project at runtime.
 *
 * A manually started GitLab diagnostic job can produce a proposed bridge
 * report for maintainer review. This class fetches the reviewed repository
 * file so the app can use published bridge data without needing an app update.
 *
 * Priority chain:
 *   1. Runtime JSON (freshest, from GitLab raw-file URL)
 *   2. Local cache (last successful fetch, stored in filesDir)
 *   3. Hardcoded fallback (Constants.kt — may be stale but works offline)
 *
 * No user data sent. No tracking. Just a GET request for a public JSON file.
 *
 * Implemented as a process-wide singleton so the in-memory cache is shared
 * between MainActivity (which triggers fetchBridges on startup) and
 * TorProcessManager (which reads getBridges at torrc-generation time).
 * Previously each call site created its own instance, which meant the
 * cache loaded from disk on every Tor start even if MainActivity had
 * already fetched a fresher copy in memory.
 */
class BridgeUpdater private constructor(private val context: Context) {

    private val tag = "BridgeUpdater"

    companion object {
        private const val BRIDGES_URL =
            "https://gitlab.com/theredhacker0345/Onionroute-Lite/-/raw/main/bridge-health/alive_bridges.json"
        private const val CACHE_FILE = "alive_bridges.json"
        private val CACHE_MAX_AGE_MS = TimeUnit.HOURS.toMillis(24) // 24h cache validity

        @Volatile private var INSTANCE: BridgeUpdater? = null
        private val initLock = Any()

        /** Returns the process-wide singleton. Thread-safe. */
        fun get(context: Context): BridgeUpdater {
            val cached = INSTANCE
            if (cached != null) return cached
            synchronized(initLock) {
                // Double-check inside the lock
                INSTANCE?.let { return it }
                val newInstance = BridgeUpdater(context.applicationContext)
                INSTANCE = newInstance
                return newInstance
            }
        }
    }

    /** Cached bridges grouped by transport type. */
    @Volatile private var cachedBridges: Map<String, List<String>> = emptyMap()
    @Volatile private var lastFetchTime: Long = 0

    init {
        loadFromCache()
    }

    /**
     * Fetch alive bridges from GitLab. Call this on a background thread.
     *
     * @return true if fetch succeeded (or cache is still valid), false if
     *   using hardcoded fallback
     */
    /**
     * Chooses whether this fetch goes through Tor's SOCKS5 port.
     *
     * WHY NOT ALWAYS
     * --------------
     * TorVpnService excludes our own package from the TUN in ALL mode
     * (addDisallowedApplication) to avoid VPN recursion, so this fetch does NOT
     * get captured by the tunnel. Always forcing it through SOCKS therefore
     * looked like the fix for a real leak — the request was otherwise leaving
     * in CLEARTEXT, exposing the user's real IP to GitLab and their ISP every
     * time the 24h cache expired, which for a Tor client is serious.
     *
     * But it broke the feature outright, and this was caught on a real device:
     * the very first fetch happens from `init` and on a bridge toggle, both of
     * which can occur while Tor is stopped. Nothing listens on 9050 then, and
     * the log showed "Malformed reply from SOCKS server" followed by "Fetch
     * failed: ... using cache or hardcoded fallback". The bridge list is needed
     * MOST when Tor is not yet working — that is the whole point of it.
     *
     * So: use Tor when it is connected, and fall back to a direct connection
     * when it is not. The leak only ever mattered for a fetch made while
     * connected, and that is exactly the case this routes through Tor.
     *
     * @return a SOCKS proxy when Tor is reachable, otherwise DIRECT.
     */
    private fun preferProxy(): java.net.Proxy {
        if (!isTorReachable()) return java.net.Proxy.NO_PROXY
        return java.net.Proxy(
            java.net.Proxy.Type.SOCKS,
            java.net.InetSocketAddress("127.0.0.1", Constants.TOR_SOCKS_PORT)
        )
    }

    /**
     * True when something accepts a TCP connection on Tor's SOCKS port.
     *
     * A cheap liveness probe rather than an assumption: tor's SocksPort can be
     * bound while it is still bootstrapping and will refuse a SOCKS handshake,
     * and it is torn down on stop. Probing costs one loopback connect and
     * avoids both the "nothing listening" and "listening but not ready" cases.
     */
    private fun isTorReachable(): Boolean = try {
        java.net.Socket().use { sock ->
            sock.connect(java.net.InetSocketAddress("127.0.0.1", Constants.TOR_SOCKS_PORT), 800)
            true
        }
    } catch (e: Exception) {
        false
    }

    fun fetchBridges(): Boolean {
        // If cache is still fresh (< 24h old), use it
        if (System.currentTimeMillis() - lastFetchTime < CACHE_MAX_AGE_MS && cachedBridges.isNotEmpty()) {
            Log.d(tag, "Cache still fresh, using cached bridges")
            return true
        }

        return try {
            Log.i(tag, "Fetching alive bridges from GitLab...")
            val url = URL(BRIDGES_URL)
            // Proxy only when Tor is actually up — see preferProxy().
            val conn = (url.openConnection(preferProxy()) as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "OnionRoute-Lite/${Constants.APP_VERSION}")
            }

            if (conn.responseCode != 200) {
                Log.w(tag, "GitLab returned HTTP ${conn.responseCode}")
                return cachedBridges.isNotEmpty()
            }

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            parseAndPersistFetched(body)
            Log.i(tag, "Fetched ${cachedBridges.values.sumOf { it.size }} alive bridges from GitLab")
            true
        } catch (e: Exception) {
            Log.w(tag, "Fetch failed: ${e.message} — using cache or hardcoded fallback")
            cachedBridges.isNotEmpty()
        }
    }

    /**
     * Parse the JSON response and save to cache.
     *
     * BridgeDB (the source the CI fetches from) HTML-encodes the `+` character
     * in obfs4 cert strings as `&#43;` on its web page. Tor cannot parse a
     * cert with `&#43;` — it needs the literal `+`. We decode all known
     * HTML entities here so the bridge lines are valid when written to torrc.
     */
    private fun parseBridges(json: String): Map<String, List<String>> {
        val obj = JSONObject(json)
        val bridges = mutableMapOf<String, List<String>>()

        for (transport in listOf("obfs4", "webtunnel", "snowflake")) {
            val arr = obj.optJSONArray(transport)
            if (arr != null && arr.length() > 0) {
                bridges[transport] = (0 until arr.length())
                    .map { decodeHtmlEntities(arr.getString(it)) }
                    .filter { it.isNotEmpty() }
            }
        }
        return bridges
    }

    /**
     * Parses a freshly FETCHED payload, stamps the fetch time and rewrites the
     * cache file.
     *
     * Split from [parseBridges] so that loading the cache cannot masquerade as a
     * successful fetch. Previously both paths ran this one function, which set
     * `lastFetchTime = now` and re-wrote the file — so `loadFromCache()` on every
     * process start made the cache look freshly fetched, and `fetchBridges()`
     * then short-circuited on the 24h check without ever touching the network.
     *
     * The net effect was that the bridge list was frozen permanently after the
     * first successful download: dead bridges accumulated and were never
     * replaced, which in a censored country is precisely the failure this
     * updater exists to prevent. The `last_updated` field it wrote was never
     * read back by anything. The rewrite also cost one flash write per app
     * start for no benefit.
     *
     * The cache stores the DECODED bridges so future loads skip decoding.
     */
    private fun parseAndPersistFetched(json: String) {
        cachedBridges = parseBridges(json)
        val fetchedAt = System.currentTimeMillis()
        lastFetchTime = fetchedAt

        try {
            val cacheObj = JSONObject()
            cacheObj.put("last_updated", isoUtcNow())
            // Absolute epoch millis alongside the human-readable form, so the
            // cache can be aged without re-parsing a date string on every start.
            cacheObj.put("last_fetched_at", fetchedAt)
            for ((transport, list) in cachedBridges) {
                cacheObj.put(transport, org.json.JSONArray(list))
            }
            File(context.filesDir, CACHE_FILE).writeText(cacheObj.toString())
        } catch (e: Exception) {
            Log.w(tag, "Failed to save cache: ${e.message}")
        }
    }

    /**
     * Current UTC time as an ISO-8601 string.
     *
     * API-23 COMPAT: `java.time.Instant` is API 26+ and this app's minSdk is
     * 23 (its primary target is a 1.5GB Android 6.0 device). Formatting
     * directly avoids enabling core-library desugaring just for a cosmetic
     * cache timestamp. Produces the same shape as `Instant.now().toString()`,
     * e.g. `2026-07-13T16:09:14.123Z`.
     *
     * SimpleDateFormat is not thread-safe; a fresh instance is used per call.
     */
    private fun isoUtcNow(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date())
    }

    /**
     * Decodes the most common HTML entities found in BridgeDB output.
     * BridgeDB web pages HTML-encode `<`, `>`, `&`, `+`, `"`, `'`.
     * We decode these back to literal characters so Tor can parse the bridge lines.
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
     * Load cached bridges from local file (if exists).
     */
    private fun loadFromCache() {
        try {
            val cacheFile = File(context.filesDir, CACHE_FILE)
            if (!cacheFile.exists()) return

            val json = cacheFile.readText()
            // Parse WITHOUT stamping lastFetchTime — see parseAndPersistFetched.
            cachedBridges = parseBridges(json)

            // Restore the cache's real age from the epoch stamp written at fetch
            // time, so the 24h freshness check in fetchBridges() means what it
            // says. Falls back to parsing last_updated for caches written before
            // last_fetched_at existed.
            val obj = JSONObject(json)
            val stamped = obj.optLong("last_fetched_at", 0L)
            lastFetchTime = when {
                stamped > 0L -> stamped
                else -> parseIsoUtcMillis(obj.optString("last_updated", ""))
            }

            val ageHours = if (lastFetchTime > 0L) {
                (System.currentTimeMillis() - lastFetchTime) / 3_600_000L
            } else -1L
            Log.d(tag, "Loaded ${cachedBridges.values.sumOf { it.size }} bridges from cache " +
                "(age=${ageHours}h)")
        } catch (e: Exception) {
            Log.w(tag, "Failed to load cache: ${e.message}")
        }
    }

    /**
     * Parses the human-readable `last_updated` stamp (ISO-8601 UTC) to epoch
     * millis, or 0 if it is absent or unparsable.
     *
     * Returning 0 makes the cache read as infinitely old, which sends
     * fetchBridges() to the network — the safe direction: a stale bridge list is
     * worse than one extra request.
     */
    private fun parseIsoUtcMillis(raw: String): Long {
        if (raw.isBlank()) return 0L
        return try {
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("UTC")
            fmt.parse(raw)?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    /**
     * Get all alive bridges of a specific transport type.
     *
     * Falls back to hardcoded bridges in Constants.kt if no runtime data.
     */
    fun getBridges(transport: String): List<String> {
        // Try runtime cache first
        cachedBridges[transport]?.let { bridges ->
            if (bridges.isNotEmpty()) return bridges
        }

        // Fall back to hardcoded
        return when (transport) {
            "obfs4" -> Constants.DEFAULT_OBFS4_BRIDGES
            "webtunnel" -> Constants.DEFAULT_WEBTUNNEL_BRIDGES
            "snowflake" -> {
                // Snowflake uses a single constructed bridge line
                listOf(
                    "snowflake 192.0.2.3:80 2B280B23E1107BB62ABFC40DDCC8824814F80A72 " +
                    "fingerprint=2B280B23E1107BB62ABFC40DDCC8824814F80A72 " +
                    "url=${Constants.SNOWFLAKE_BROKER_URL} fronts=${Constants.SNOWFLAKE_FRONT_DOMAIN} " +
                    "ice=${Constants.SNOWFLAKE_ICE_SERVERS}"
                )
            }
            else -> emptyList()
        }
    }

}
