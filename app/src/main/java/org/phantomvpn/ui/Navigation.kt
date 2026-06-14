// === Navigation.kt ===
package org.phantomvpn.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.phantomvpn.ui.screens.BandwidthScreen
import org.phantomvpn.ui.screens.BridgeScreen
import org.phantomvpn.ui.screens.HomeScreen
import org.phantomvpn.ui.screens.SettingsScreen
import org.phantomvpn.ui.theme.ConnectionState

// ═══════════════════════════════════════════════════════════
// NAVIGATION — Phantom design system
// Crossfade only at 200ms. No slide transitions. No spring.
// ═══════════════════════════════════════════════════════════

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Settings : Screen("settings")
    data object Bridges : Screen("bridges")
    data object Bandwidth : Screen("bandwidth")
}

// ═══════════════════════════════════════════════════════════
// APP STATE — Hoisted state for the entire application
//
// Note: Properties use internal set to avoid JVM signature
// clashes with explicit setter functions. The private set
// delegate generates setXxx() which clashes with fun setXxx().
// Using internal set avoids this while still restricting
// external mutation.
// ═══════════════════════════════════════════════════════════

class PhantomAppState {
    var currentScreen by mutableStateOf<Screen>(Screen.Home)
        internal set

    var connectionState by mutableStateOf<ConnectionState>(ConnectionState.Disconnected)
        internal set

    var uploadSpeed by mutableStateOf("0 KB/s")
        internal set

    var downloadSpeed by mutableStateOf("0 KB/s")
        internal set

    var totalUpload by mutableStateOf("0 B")
        internal set

    var totalDownload by mutableStateOf("0 B")
        internal set

    var isVpnMode by mutableStateOf(true)
        internal set

    var isKillSwitch by mutableStateOf(false)
        internal set

    var isAutoStart by mutableStateOf(false)
        internal set

    var bridgeType by mutableStateOf("None")
        internal set

    var snowflakeBrokerUrl by mutableStateOf("https://snowflake-broker.torproject.net.global.prod.fastly.net/")
        internal set

    var snowflakeFrontDomains by mutableStateOf("cdn.sstatic.net, ajax.googleapis.com")
        internal set

    var isSnowflakeActive by mutableStateOf(false)
        internal set

    var obfs4BridgeLines by mutableStateOf("")
        internal set

    var customBridgeLines by mutableStateOf("")
        internal set

    var sessionDuration by mutableStateOf("00:00:00")
        internal set

    var exitNode by mutableStateOf("")
        internal set

    var entryGuard by mutableStateOf("[redacted]")
        internal set

    var speedHistory by mutableStateOf<List<Float>>(emptyList())
        internal set

    // ── Navigation ─────────────────────────────────────────

    fun navigateTo(screen: Screen) {
        currentScreen = screen
    }

    fun navigateBack() {
        currentScreen = when (currentScreen) {
            Screen.Home -> Screen.Home
            Screen.Settings -> Screen.Home
            Screen.Bridges -> Screen.Settings
            Screen.Bandwidth -> Screen.Home
        }
    }

    // ── Connection ─────────────────────────────────────────

    fun toggleConnection() {
        connectionState = when (connectionState) {
            is ConnectionState.Disconnected -> ConnectionState.Connecting
            is ConnectionState.Connecting -> ConnectionState.Disconnected
            is ConnectionState.Connected -> ConnectionState.Disconnected
            is ConnectionState.Error -> ConnectionState.Connecting
        }
    }

    fun updateConnectionState(state: ConnectionState) {
        connectionState = state
    }

    // ── Bandwidth ──────────────────────────────────────────

    fun updateBandwidth(upload: String, download: String, totalUp: String, totalDown: String) {
        uploadSpeed = upload
        downloadSpeed = download
        totalUpload = totalUp
        totalDownload = totalDown
    }

    fun updateVpnMode(enabled: Boolean) { isVpnMode = enabled }
    fun updateKillSwitch(enabled: Boolean) { isKillSwitch = enabled }
    fun updateAutoStart(enabled: Boolean) { isAutoStart = enabled }
    fun updateBridgeType(type: String) { bridgeType = type }
    fun updateSnowflakeBroker(url: String) { snowflakeBrokerUrl = url }
    fun updateSnowflakeFrontDomains(domains: String) { snowflakeFrontDomains = domains }
    fun updateObfs4Bridges(lines: String) { obfs4BridgeLines = lines }
    fun updateCustomBridges(lines: String) { customBridgeLines = lines }
    fun updateSessionDuration(duration: String) { sessionDuration = duration }
    fun updateExitNode(node: String) { exitNode = node }
    fun updateEntryGuard(guard: String) { entryGuard = guard }

    fun addSpeedDataPoint(bytesPerSec: Float) {
        speedHistory = (speedHistory + bytesPerSec).takeLast(60)
    }
}

// ═══════════════════════════════════════════════════════════
// MAIN NAVIGATION COMPOSABLE
// ═══════════════════════════════════════════════════════════

@Composable
fun PhantomNavigation(
    appState: PhantomAppState = remember { PhantomAppState() },
    modifier: Modifier = Modifier
) {
    Crossfade(
        targetState = appState.currentScreen,
        animationSpec = tween(durationMillis = 200),
        label = "screenTransition",
        modifier = modifier
    ) { screen ->
        when (screen) {
            Screen.Home -> HomeScreen(
                connectionState = appState.connectionState,
                uploadSpeed = appState.uploadSpeed,
                downloadSpeed = appState.downloadSpeed,
                totalUpload = appState.totalUpload,
                totalDownload = appState.totalDownload,
                isVpnMode = appState.isVpnMode,
                isKillSwitch = appState.isKillSwitch,
                onConnectClick = { appState.toggleConnection() },
                onVpnModeToggle = { appState.updateVpnMode(!appState.isVpnMode) },
                onKillSwitchToggle = { appState.updateKillSwitch(!appState.isKillSwitch) },
                onSettingsClick = { appState.navigateTo(Screen.Settings) },
                onMenuClick = { /* Could open drawer */ },
                onBandwidthClick = { appState.navigateTo(Screen.Bandwidth) }
            )

            Screen.Settings -> SettingsScreen(
                isVpnMode = appState.isVpnMode,
                isKillSwitch = appState.isKillSwitch,
                isAutoStart = appState.isAutoStart,
                bridgeType = appState.bridgeType,
                onVpnModeChange = { appState.updateVpnMode(it) },
                onKillSwitchChange = { appState.updateKillSwitch(it) },
                onAutoStartChange = { appState.updateAutoStart(it) },
                onBridgeTypeChange = { appState.updateBridgeType(it) },
                onBridgeConfigClick = { appState.navigateTo(Screen.Bridges) },
                onBackClick = { appState.navigateBack() }
            )

            Screen.Bridges -> BridgeScreen(
                snowflakeBrokerUrl = appState.snowflakeBrokerUrl,
                snowflakeFrontDomains = appState.snowflakeFrontDomains,
                isSnowflakeActive = appState.isSnowflakeActive,
                obfs4BridgeLines = appState.obfs4BridgeLines,
                customBridgeLines = appState.customBridgeLines,
                onSnowflakeBrokerChange = { appState.updateSnowflakeBroker(it) },
                onSnowflakeFrontDomainsChange = { appState.updateSnowflakeFrontDomains(it) },
                onObfs4BridgesChange = { appState.updateObfs4Bridges(it) },
                onCustomBridgesChange = { appState.updateCustomBridges(it) },
                onSaveBridges = { /* Persist bridges */ },
                onBackClick = { appState.navigateBack() }
            )

            Screen.Bandwidth -> BandwidthScreen(
                downloadSpeed = appState.downloadSpeed,
                uploadSpeed = appState.uploadSpeed,
                totalDownload = appState.totalDownload,
                totalUpload = appState.totalUpload,
                sessionDuration = appState.sessionDuration,
                exitNode = appState.exitNode,
                entryGuard = appState.entryGuard,
                speedHistory = appState.speedHistory,
                onBackClick = { appState.navigateBack() }
            )
        }
    }
}
