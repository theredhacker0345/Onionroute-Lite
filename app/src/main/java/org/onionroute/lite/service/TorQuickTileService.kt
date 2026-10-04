package org.onionroute.lite.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import org.onionroute.lite.Constants
import org.onionroute.lite.R

/**
 * Quick Settings tile for toggling the Tor connection on/off.
 *
 * When active: shows "OnionRoute — Connected" with green tile state.
 * When inactive: shows "OnionRoute — Disconnected" with gray tile state.
 *
 * Clicking the tile starts or stops [TorService] depending on the current
 * connection state stored in [SharedPreferences].
 *
 * The tile listens for [Constants.ACTION_STATUS_UPDATE] broadcasts so that
 * its state stays in sync even when toggled from the main activity or
 * other entry points.
 */
/**
 * Quick Settings tile: toggle Tor on/off.
 *
 * API-24 CLASS ON A minSdk-23 APP: `TileService` itself is API 24+, so this
 * class cannot even be loaded on Android 6.0. That is safe because Quick
 * Settings tiles do not exist before API 24 — the system has no
 * ServiceInfo.action.TILE_ACTION to resolve, so it never binds or instantiates
 * this component there, and the class is never loaded. @RequiresApi states
 * that contract explicitly instead of leaving the NewApi warnings to be
 * suppressed file-wide.
 *
 * Declared in the manifest for all API levels, so nothing here may assume it
 * is the only entry point to connecting.
 */
@RequiresApi(Build.VERSION_CODES.N)
class ConnectTileService : TileService() {

    private val tag = "ConnectTileService"

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)
    }

    private var statusReceiver: BroadcastReceiver? = null

    // ── Tile Lifecycle ─────────────────────────────────────────────

    override fun onTileAdded() {
        super.onTileAdded()
        Log.d(tag, "Tile added")
        updateTile()
    }

    override fun onStartListening() {
        super.onStartListening()
        Log.d(tag, "Tile start listening")

        // Register for status updates so tile reflects real-time state
        statusReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val status = intent.getStringExtra(Constants.EXTRA_TOR_STATUS)
                Log.d(tag, "Status update received: $status")
                val connected = status == "CONNECTED"
                prefs.edit().putBoolean(Constants.PREF_TOR_CONNECTED, connected).apply()
                updateTile()
            }
        }

        // ContextCompat, not the platform 3-arg overload: the platform flags
        // overload needs API 26+, while this app supports 23. ContextCompat
        // dispatches on API level internally and applies the
        // RECEIVER_NOT_EXPORTED semantics the tile needs to keep Tor status
        // broadcasts app-private on every supported version.
        ContextCompat.registerReceiver(
            this, statusReceiver, statusFilter, ContextCompat.RECEIVER_NOT_EXPORTED
        )

        updateTile()
    }

    override fun onStopListening() {
        super.onStopListening()
        Log.d(tag, "Tile stop listening")
        try {
            statusReceiver?.let { unregisterReceiver(it) }
        } catch (_: Exception) {}
        statusReceiver = null
    }

    override fun onTileRemoved() {
        super.onTileRemoved()
        Log.d(tag, "Tile removed")
    }

    // ── Tile Click ─────────────────────────────────────────────────

    override fun onClick() {
        super.onClick()

        val isConnected = prefs.getBoolean(Constants.PREF_TOR_CONNECTED, false)

        if (isConnected) {
            // Stop Tor
            Log.i(tag, "Toggling OFF — stopping Tor")
            val intent = Intent(this, TorService::class.java).apply {
                action = Constants.ACTION_STOP_TOR
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }

            // Also stop VPN if running
            val vpnIntent = Intent(this, TorVpnService::class.java).apply {
                action = Constants.ACTION_STOP_VPN
            }
            startService(vpnIntent)

            // Don't write PREF_TOR_CONNECTED here — TorService.broadcastStatus()
            // will write it when it broadcasts DISCONNECTED. Writing it here
            // prematurely would cause a stale state if Tor fails to stop.
            // (VPN_ENABLED is OK to clear here because we just sent STOP_VPN.)
            prefs.edit().putBoolean(Constants.PREF_VPN_ENABLED, false).apply()

            // Optimistic UI feedback — show "Disconnecting" subtitle while
            // we wait for the DISCONNECTED broadcast. Use STATE_INACTIVE
            // (not STATE_ACTIVE) so the tile visually starts turning gray
            // immediately when the user taps to disconnect. The previous
            // code used STATE_ACTIVE which kept the tile green during
            // disconnect — visually confusing because the user just tapped
            // to turn it OFF.
            qsTile?.let { t ->
                t.state = Tile.STATE_INACTIVE
                t.setStatusSubtitle("Disconnecting…")
                t.updateTile()
            }
        } else {
            // Start Tor
            Log.i(tag, "Toggling ON — starting Tor")
            val intent = Intent(this, TorService::class.java).apply {
                action = Constants.ACTION_START_TOR
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }

            // Don't write PREF_TOR_CONNECTED=true here — TorService will
            // write it when bootstrap completes and it broadcasts CONNECTED.
            // Writing it prematurely caused the tile to show "Connected"
            // even when Tor failed to bootstrap.

            // Optimistic UI feedback — show "Connecting…" subtitle while
            // we wait for BOOTSTRAPPING / CONNECTED broadcasts. Without
            // this, the tile looks unresponsive for several seconds
            // until the first broadcast arrives.
            qsTile?.let { t ->
                t.state = Tile.STATE_UNAVAILABLE
                t.setStatusSubtitle("Connecting…")
                t.updateTile()
            }
        }
    }

    // ── Tile State ─────────────────────────────────────────────────

    private fun updateTile() {
        val tile = qsTile ?: return
        val isConnected = prefs.getBoolean(Constants.PREF_TOR_CONNECTED, false)

        if (isConnected) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = "OnionRoute"
            tile.setStatusSubtitle("Connected")
            tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_onion)
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "OnionRoute"
            tile.setStatusSubtitle("Disconnected")
            tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_onion)
        }

        tile.updateTile()
    }

    companion object {
        private val statusFilter = IntentFilter(Constants.ACTION_STATUS_UPDATE)
    }
}

/**
 * Quick Settings tile for requesting a new Tor circuit (SIGNAL NEWNYM).
 *
 * Sends a NEWNYM signal to the Tor control port which rotates the
 * exit node and establishes fresh circuits. The tile is only active
 * when Tor is currently connected; otherwise it appears unavailable.
 *
 * This gives users a one-tap "New Identity" feature from the
 * quick settings panel without opening the app.
 */
/**
 * Quick Settings tile: build a new Tor circuit.
 *
 * API-24 CLASS ON A minSdk-23 APP — see ConnectTileService for why this is safe
 * and why the class is never loaded below API 24.
 */
@RequiresApi(Build.VERSION_CODES.N)
class RefreshTileService : TileService() {

    private val tag = "RefreshTileService"

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)
    }

    private var statusReceiver: BroadcastReceiver? = null

    // ── Tile Lifecycle ─────────────────────────────────────────────

    override fun onTileAdded() {
        super.onTileAdded()
        Log.d(tag, "Tile added")
        updateTile()
    }

    override fun onStartListening() {
        super.onStartListening()
        Log.d(tag, "Tile start listening")

        statusReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val status = intent.getStringExtra(Constants.EXTRA_TOR_STATUS)
                Log.d(tag, "Status update received: $status")
                val connected = status == "CONNECTED"
                prefs.edit().putBoolean(Constants.PREF_TOR_CONNECTED, connected).apply()
                updateTile()
            }
        }

        // ContextCompat, not the platform 3-arg overload: the platform flags
        // overload needs API 26+, while this app supports 23. ContextCompat
        // dispatches on API level internally and applies the
        // RECEIVER_NOT_EXPORTED semantics the tile needs to keep Tor status
        // broadcasts app-private on every supported version.
        ContextCompat.registerReceiver(
            this, statusReceiver, statusFilter, ContextCompat.RECEIVER_NOT_EXPORTED
        )

        updateTile()
    }

    override fun onStopListening() {
        super.onStopListening()
        Log.d(tag, "Tile stop listening")
        try {
            statusReceiver?.let { unregisterReceiver(it) }
        } catch (_: Exception) {}
        statusReceiver = null
    }

    override fun onTileRemoved() {
        super.onTileRemoved()
        Log.d(tag, "Tile removed")
    }

    // ── Tile Click ─────────────────────────────────────────────────

    override fun onClick() {
        super.onClick()

        val isConnected = prefs.getBoolean(Constants.PREF_TOR_CONNECTED, false)
        if (!isConnected) {
            Log.w(tag, "Cannot request new circuit — Tor not connected")
            return
        }

        Log.i(tag, "Requesting new Tor circuit (NEWNYM)")

        // Send NEWNYM request to TorService via startService(). Previously
        // this used sendBroadcast(), but NOTHING registered a receiver for
        // ACTION_NEW_CIRCUIT (TorService only handles it as a started-service
        // intent action, and no manifest receiver exists) — so the tile was
        // a silent no-op. TorService owns the single control connection and
        // safely serializes the SIGNAL via its request lock.
        // NOTE: starting an FGS from an interactive QS tile is an explicitly
        // allowed foreground-service-start exemption (same pattern as
        // ConnectTileService above).
        val intent = Intent(this, TorService::class.java).apply {
            action = Constants.ACTION_NEW_CIRCUIT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        // Brief visual feedback — flash the tile active then revert
        val tile = qsTile
        if (tile != null) {
            tile.state = Tile.STATE_ACTIVE
            tile.setStatusSubtitle("New Circuit Requested")
            tile.updateTile()

            // Revert after a short delay
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                updateTile()
            }, 2000)
        }
    }

    // ── Tile State ─────────────────────────────────────────────────

    private fun updateTile() {
        val tile = qsTile ?: return
        val isConnected = prefs.getBoolean(Constants.PREF_TOR_CONNECTED, false)

        if (isConnected) {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "New Circuit"
            tile.setStatusSubtitle("Refresh Tor path")
            tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_refresh)
        } else {
            tile.state = Tile.STATE_UNAVAILABLE
            tile.label = "New Circuit"
            tile.setStatusSubtitle("Tor not connected")
            tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_refresh)
        }

        tile.updateTile()
    }

    companion object {
        private val statusFilter = IntentFilter(Constants.ACTION_STATUS_UPDATE)
    }
}


/**
 * Sets a tile's secondary line, degrading gracefully below API 29.
 *
 * `Tile.setSubtitle` was added in Android 10 (API 29). On API 24-28 the tile
 * shows only its label, which is acceptable: the subtitle is purely cosmetic
 * ("Connecting…", "Connected"), never the sole carrier of state — the label
 * and icon carry the connection state in `updateTile()`.
 *
 * Wrapping the call once here keeps the version check out of all seven call
 * sites and means a future subtitle can never reintroduce an unguarded call.
 */
private fun Tile.setStatusSubtitle(text: String) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        subtitle = text
    }
}
