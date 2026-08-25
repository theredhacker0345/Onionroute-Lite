package org.onionroute.lite.tor

import android.content.Context
import android.util.Log
import org.onionroute.lite.Constants
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
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
    fun fetchBridges(): Boolean {
        // If cache is still fresh (< 24h old), use it
        if (System.currentTimeMillis() - lastFetchTime < CACHE_MAX_AGE_MS && cachedBridges.isNotEmpty()) {
            Log.d(tag, "Cache still fresh, using cached bridges")
            return true
        }

        return try {
            Log.i(tag, "Fetching alive bridges from GitLab...")
            val url = URL(BRIDGES_URL)
            val conn = (url.openConnection() as HttpURLConnection).apply {
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

            parseAndCache(body)
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
    private fun parseAndCache(json: String) {
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

        cachedBridges = bridges
        lastFetchTime = System.currentTimeMillis()

        // Save to local cache file (save the DECODED version so future loads
        // don't need to decode again).
        try {
            // Re-serialize the decoded bridges back to JSON for caching.
            val cacheObj = JSONObject()
            cacheObj.put("last_updated", java.time.Instant.now().toString())
            for ((transport, list) in bridges) {
                cacheObj.put(transport, org.json.JSONArray(list))
            }
            File(context.filesDir, CACHE_FILE).writeText(cacheObj.toString())
        } catch (e: Exception) {
            Log.w(tag, "Failed to save cache: ${e.message}")
        }
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
            if (cacheFile.exists()) {
                val json = cacheFile.readText()
                parseAndCache(json)
                Log.d(tag, "Loaded ${cachedBridges.values.sumOf { it.size }} bridges from cache")
            }
        } catch (e: Exception) {
            Log.w(tag, "Failed to load cache: ${e.message}")
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

    /**
     * Get all alive bridges across all transports.
     */
    fun getAllBridges(): List<String> {
        val all = mutableListOf<String>()
        for (transport in listOf("obfs4", "webtunnel", "snowflake")) {
            all.addAll(getBridges(transport))
        }
        return all
    }

    /**
     * Get the timestamp of the last successful fetch (for UI display).
     */
    fun getLastFetchTime(): Long = lastFetchTime

    /**
     * Check if we have runtime data (vs using hardcoded fallback).
     */
    fun hasRuntimeData(): Boolean = cachedBridges.values.any { it.isNotEmpty() }
}
