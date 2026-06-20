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
 * Fetches alive bridges from GitHub at runtime.
 *
 * The CI workflow (bridge-health.yml) tests all bridges every 15 min
 * and writes alive_bridges.json to the repo. This class fetches that
 * file so the app always has fresh, verified-working bridges without
 * needing an app update.
 *
 * Priority chain:
 *   1. Runtime JSON (freshest, from GitHub raw URL)
 *   2. Local cache (last successful fetch, stored in filesDir)
 *   3. Hardcoded fallback (Constants.kt — may be stale but works offline)
 *
 * No user data sent. No tracking. Just a GET request for a public JSON file.
 */
class BridgeUpdater(private val context: Context) {

    private val tag = "BridgeUpdater"

    companion object {
        private const val BRIDGES_URL =
            "https://raw.githubusercontent.com/darkmaster0345/Onionroute-Lite/main/bridge-health/alive_bridges.json"
        private const val CACHE_FILE = "alive_bridges.json"
        private const val CACHE_MAX_AGE_MS = TimeUnit.HOURS.toMillis(24) // 24h cache validity
    }

    /** Cached bridges grouped by transport type. */
    private var cachedBridges: Map<String, List<String>> = emptyMap()
    private var lastFetchTime: Long = 0

    init {
        loadFromCache()
    }

    /**
     * Fetch alive bridges from GitHub. Call this on a background thread.
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
            Log.i(tag, "Fetching alive bridges from GitHub...")
            val url = URL(BRIDGES_URL)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "OnionRoute-Lite/${Constants.APP_VERSION}")
            }

            if (conn.responseCode != 200) {
                Log.w(tag, "GitHub returned HTTP ${conn.responseCode}")
                return cachedBridges.isNotEmpty()
            }

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            parseAndCache(body)
            Log.i(tag, "Fetched ${cachedBridges.values.sumOf { it.size }} alive bridges from GitHub")
            true
        } catch (e: Exception) {
            Log.w(tag, "Fetch failed: ${e.message} — using cache or hardcoded fallback")
            cachedBridges.isNotEmpty()
        }
    }

    /**
     * Parse the JSON response and save to cache.
     */
    private fun parseAndCache(json: String) {
        val obj = JSONObject(json)
        val bridges = mutableMapOf<String, List<String>>()

        for (transport in listOf("obfs4", "webtunnel", "snowflake")) {
            val arr = obj.optJSONArray(transport)
            if (arr != null && arr.length() > 0) {
                bridges[transport] = (0 until arr.length()).map { arr.getString(it) }
            }
        }

        cachedBridges = bridges
        lastFetchTime = System.currentTimeMillis()

        // Save to local cache file
        try {
            File(context.filesDir, CACHE_FILE).writeText(json)
        } catch (e: Exception) {
            Log.w(tag, "Failed to save cache: ${e.message}")
        }
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
