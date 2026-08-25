package org.onionroute.lite.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.onionroute.lite.ui.screens.AppSelectionScreen
import org.onionroute.lite.ui.screens.HomeScreen
import org.onionroute.lite.ui.screens.SettingsScreen
import org.onionroute.lite.ui.screens.BridgeScreen
import org.onionroute.lite.ui.screens.BandwidthScreen
import org.onionroute.lite.ui.screens.KindnessScreen
import org.onionroute.lite.ui.theme.ConnectionState

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Settings : Screen("settings")
    data object Bridges : Screen("bridges")
    data object Bandwidth : Screen("bandwidth")
    data object AppSelection : Screen("app_selection")
    data object Kindness : Screen("kindness")
}

class OnionRouteAppState {

    var currentScreen by mutableStateOf<Screen>(Screen.Home)
        internal set

    var connectionState by mutableStateOf<ConnectionState>(ConnectionState.Disconnected)
        internal set

    /** Current Tor exit node IP, or empty string if unknown/disconnected.
     *  Updated every 15s by TorService via ACTION_EXIT_IP_UPDATE broadcast. */
    var exitIp by mutableStateOf("")
        internal set

    var isVpnMode by mutableStateOf(false)
        internal set

    var isKillSwitchEnabled by mutableStateOf(true)
        internal set

    var isAutoStart by mutableStateOf(false)
        internal set

    var isSnowflakeEnabled by mutableStateOf(false)
        internal set

    var isObfs4Enabled by mutableStateOf(false)
        internal set

    var isWebTunnelEnabled by mutableStateOf(false)
        internal set

    var isKindnessEnabled by mutableStateOf(false)
        internal set

    var customBridges by mutableStateOf<List<String>>(emptyList())
        internal set

    // ── Service Callbacks (set by MainActivity) ─────────────

    var onVpnToggle: ((Boolean) -> Unit)? = null
    var onKillSwitchToggle: ((Boolean) -> Unit)? = null
    var onSnowflakeToggle: ((Boolean) -> Unit)? = null
    var onObfs4Toggle: ((Boolean) -> Unit)? = null
    var onWebTunnelToggle: ((Boolean) -> Unit)? = null
    var onKindnessToggle: ((Boolean) -> Unit)? = null
    var onAutoStartToggle: ((Boolean) -> Unit)? = null
    var onConnectToggle: (() -> Unit)? = null
    var onImportBridges: ((List<String>) -> Unit)? = null

    var uploadSpeed by mutableStateOf("0 KB/s")
        internal set

    var downloadSpeed by mutableStateOf("0 KB/s")
        internal set

    var totalUploaded by mutableStateOf("0 B")
        internal set

    var totalDownloaded by mutableStateOf("0 B")
        internal set

    var speedDataPoints by mutableStateOf<List<Float>>(emptyList())
        internal set

    var connectionProgress by mutableFloatStateOf(0f)
        internal set

    var currentIcon by mutableStateOf("default")
        internal set

    // ── Kindness Stats ─────────────────────────────────────────

    var kindnessDataDonated by mutableStateOf(0L)
        internal set

    var kindnessPeopleNow by mutableStateOf(0L)
        internal set

    var kindnessPeopleTotal by mutableStateOf(0L)
        internal set

    var kindnessSessionStart by mutableStateOf(0L)
        internal set

    fun updateCurrentIcon(icon: String) { currentIcon = icon }

    fun updateCustomBridges(bridges: List<String>) { customBridges = bridges }

    // ── Navigation ────────────────────────────────────────────

    fun navigateTo(screen: Screen) {
        currentScreen = screen
    }

    fun navigateBack() {
        currentScreen = when (currentScreen) {
            Screen.Home -> Screen.Home
            Screen.Settings -> Screen.Home
            Screen.Bridges -> Screen.Settings
            Screen.Bandwidth -> Screen.Home
            Screen.AppSelection -> Screen.Settings
            Screen.Kindness -> Screen.Settings
        }
    }

    // ── State Mutators ────────────────────────────────────────

    fun updateConnectionState(state: ConnectionState) {
        connectionState = state
        connectionProgress = when (state) {
            is ConnectionState.Disconnected -> 0f
            is ConnectionState.Connecting -> 0.5f
            is ConnectionState.Connected -> 1f
            is ConnectionState.Error -> 0f
        }
        // Clear exit IP when disconnected so UI doesn't show stale IP
        if (state is ConnectionState.Disconnected || state is ConnectionState.Error) {
            exitIp = ""
        }
    }

    /** Called by MainActivity when ACTION_EXIT_IP_UPDATE broadcast is received. */
    fun updateExitIp(ip: String) {
        exitIp = ip
    }

    fun updateVpnMode(enabled: Boolean) {
        if (isVpnMode == enabled) return
        isVpnMode = enabled
        onVpnToggle?.invoke(enabled)
    }

    fun updateKillSwitch(enabled: Boolean) {
        if (isKillSwitchEnabled == enabled) return
        isKillSwitchEnabled = enabled
        onKillSwitchToggle?.invoke(enabled)
    }

    fun updateAutoStart(enabled: Boolean) {
        if (isAutoStart == enabled) return
        isAutoStart = enabled
        onAutoStartToggle?.invoke(enabled)
    }

    fun updateSnowflake(enabled: Boolean) {
        if (isSnowflakeEnabled == enabled) return
        isSnowflakeEnabled = enabled
        onSnowflakeToggle?.invoke(enabled)
    }

    fun updateObfs4(enabled: Boolean) {
        if (isObfs4Enabled == enabled) return
        isObfs4Enabled = enabled
        onObfs4Toggle?.invoke(enabled)
    }

    fun updateWebTunnel(enabled: Boolean) {
        if (isWebTunnelEnabled == enabled) return
        isWebTunnelEnabled = enabled
        onWebTunnelToggle?.invoke(enabled)
    }

    fun updateKindness(enabled: Boolean) {
        if (isKindnessEnabled == enabled) return
        isKindnessEnabled = enabled
        onKindnessToggle?.invoke(enabled)
    }

    /**
     * Updates the Kindness Mode UI state WITHOUT invoking [onKindnessToggle].
     * Used when the service reports its actual state via broadcast — routing
     * that through the toggle callback would re-send start/stop commands and
     * create a feedback loop.
     */
    fun updateKindnessState(enabled: Boolean) {
        isKindnessEnabled = enabled
    }

    fun updateBandwidth(upload: String, download: String, totalUp: String, totalDown: String) {
        uploadSpeed = upload
        downloadSpeed = download
        totalUploaded = totalUp
        totalDownloaded = totalDown
    }

    fun updateKindnessStats(dataDonated: Long, peopleNow: Long, peopleTotal: Long, sessionStart: Long) {
        kindnessDataDonated = dataDonated
        kindnessPeopleNow = peopleNow
        kindnessPeopleTotal = peopleTotal
        kindnessSessionStart = sessionStart
    }

    fun addSpeedDataPoint(speed: Float) {
        val current = speedDataPoints.toMutableList()
        current.add(speed)
        if (current.size > 60) current.removeAt(0)
        speedDataPoints = current
    }

    fun toggleConnection() {
        connectionState = when (connectionState) {
            is ConnectionState.Disconnected -> ConnectionState.Connecting()
            is ConnectionState.Connecting -> ConnectionState.Disconnected
            is ConnectionState.Connected -> ConnectionState.Disconnected
            is ConnectionState.Error -> ConnectionState.Connecting()
        }
    }
}

@Composable
fun OnionRouteNavigation(appState: OnionRouteAppState) {
    Crossfade(
        targetState = appState.currentScreen,
        animationSpec = tween(durationMillis = 200),
        label = "screenTransition"
    ) { screen ->
        when (screen) {
            Screen.Home -> HomeScreen(appState = appState)
            Screen.Settings -> SettingsScreen(appState = appState)
            Screen.Bridges -> BridgeScreen(appState = appState)
            Screen.Bandwidth -> BandwidthScreen(appState = appState)
            Screen.AppSelection -> AppSelectionScreen(appState = appState)
            Screen.Kindness -> KindnessScreen(appState = appState)
        }
    }
}
