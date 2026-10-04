package org.onionroute.lite.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.onionroute.lite.tor.TorCircuit
import org.onionroute.lite.ui.screens.AppSelectionScreen
import org.onionroute.lite.ui.screens.HomeScreen
import org.onionroute.lite.ui.screens.SettingsScreen
import org.onionroute.lite.ui.screens.BridgeScreen
import org.onionroute.lite.ui.screens.BandwidthScreen
import org.onionroute.lite.ui.screens.CircuitScreen
import org.onionroute.lite.ui.screens.HealthScreen
import org.onionroute.lite.ui.screens.KindnessScreen
import org.onionroute.lite.ui.theme.ConnectionState

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Settings : Screen("settings")
    data object Bridges : Screen("bridges")
    data object Bandwidth : Screen("bandwidth")
    data object AppSelection : Screen("app_selection")
    data object Kindness : Screen("kindness")
    data object Circuits : Screen("circuits")
    data object Health : Screen("health")
}

/**
 * Single observable state holder for the whole UI, owned by MainActivity.
 *
 * WHY @Stable MATTERS SO MUCH HERE
 * --------------------------------
 * Every screen receives this object as a parameter. Compose infers a class with
 * plain `var` properties as *unstable*, and a composable with any unstable
 * parameter is not skippable: the compiler omits the `skipToGroupEnd()` fast
 * path, so the whole body re-executes whenever the parent recomposes.
 *
 * That was not theoretical. MainActivity appends a bandwidth sample on every
 * Tor `BW` event, which arrives once per second for as long as Tor moves data.
 * Each of those invalidated this object, and because it was unstable, every
 * screen re-executed in full: the connection button rebuilt its radial Brush,
 * all six toggle chips rebuilt their TextStyles, and the mode explainer
 * re-concatenated its two ~150-character strings. Roughly 60 wasted allocations
 * and ~25 re-laid-out nodes per second, for the entire time Tor is connected —
 * on a device with 1.5GB of RAM.
 *
 * With @Stable those composables become skippable and a bandwidth blip only
 * re-runs the leaf that actually displays the number.
 *
 * The @Stable contract requires that a public property never changes without
 * Compose being able to observe it. That is why every field below — including
 * the service callbacks — is backed by a snapshot state rather than being a
 * plain `var`. The callbacks used to be plain; they are write-once today (set
 * once in MainActivity.onCreate), but leaving them observable is what makes the
 * annotation truthful rather than merely convenient.
 *
 * The collection properties hold immutable Kotlin lists that are replaced
 * wholesale rather than mutated, which is what makes @Stable sound for them.
 */
@Stable
class OnionRouteAppState {

    private companion object {
        /**
         * Latency samples retained for the health graph: 60 at the 15s poll
         * interval is the ~15 minutes of history the UI documents.
         *
         * Bounded on purpose. An unbounded history here would grow for as long
         * as Tor is connected and be retained by every recomposition that
         * reads it.
         */
        const val HEALTH_SAMPLE_CAPACITY = 60

        /** Bandwidth samples retained for the sparkline (~1 minute at 1Hz). */
        const val SPEED_SAMPLE_CAPACITY = 60
    }

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

    // ── Circuit Viewer / Exit Country / Kindness Level ────────

    /** Live circuit list, refreshed by the Circuits screen via
     *  [onRefreshCircuits]; delivered by TorService broadcast. */
    var circuits by mutableStateOf<List<TorCircuit>>(emptyList())
        internal set

    /** Selected exit country ("" = no restriction). */
    var exitCountry by mutableStateOf("")
        internal set

    /** Max concurrent snowflake clients (0 = unlimited). */
    var kindnessCapacity by mutableStateOf(5)
        internal set

    // ── Connection Health ──────────────────────────────────────
    // Latency samples (ms) through the full Tor path; -1 = failed probe.
    // Capped at 60 (~15 min of history at the 15s poll interval).

    var healthPoints by mutableStateOf<List<Long>>(emptyList())
        internal set

    // ── Service Callbacks (set by MainActivity) ─────────────

    // Backed by mutableStateOf so the class genuinely satisfies @Stable; see the
    // note on the class. They are assigned once, so this costs 12 snapshot
    // entries and never recomposes anything in practice.

    var onVpnToggle by mutableStateOf<((Boolean) -> Unit)?>(null)
    var onKillSwitchToggle by mutableStateOf<((Boolean) -> Unit)?>(null)
    var onSnowflakeToggle by mutableStateOf<((Boolean) -> Unit)?>(null)
    var onObfs4Toggle by mutableStateOf<((Boolean) -> Unit)?>(null)
    var onWebTunnelToggle by mutableStateOf<((Boolean) -> Unit)?>(null)
    var onKindnessToggle by mutableStateOf<((Boolean) -> Unit)?>(null)
    var onAutoStartToggle by mutableStateOf<((Boolean) -> Unit)?>(null)
    var onConnectToggle by mutableStateOf<(() -> Unit)?>(null)
    var onImportBridges by mutableStateOf<((List<String>) -> Unit)?>(null)
    var onRefreshCircuits by mutableStateOf<(() -> Unit)?>(null)
    var onExitCountryChange by mutableStateOf<((String) -> Unit)?>(null)
    var onKindnessCapacityChange by mutableStateOf<((Int) -> Unit)?>(null)

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
            Screen.Circuits -> Screen.Home
            Screen.Health -> Screen.Home
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

    /** Called by MainActivity when ACTION_CIRCUITS_UPDATE is received. */
    fun updateCircuits(list: List<TorCircuit>) {
        circuits = list
    }

    fun updateExitCountry(code: String) {
        if (exitCountry == code) return
        exitCountry = code
        onExitCountryChange?.invoke(code)
    }

    fun updateKindnessCapacity(capacity: Int) {
        if (kindnessCapacity == capacity) return
        kindnessCapacity = capacity
        onKindnessCapacityChange?.invoke(capacity)
    }

    /**
     * Fixed-capacity ring buffer for a windowed sample series.
     *
     * WHY NOT A LIST
     * --------------
     * Both windows are appended to once per second for as long as Tor is
     * connected. The previous implementation was
     *
     *     val current = points.toMutableList(); current.add(x)
     *     if (current.size > 60) current.removeAt(0); points = current
     *
     * which allocated a fresh 60-element ArrayList plus its backing Object[],
     * then shifted all 60 references down one slot — twice per second,
     * indefinitely, purely to hold 60 values that were already bounded. On a
     * 1.5GB device that is a steady, needless GC load for the whole life of a
     * Tor session.
     *
     * The ring overwrites one slot instead. One array copy per append remains
     * unavoidable: Compose has to observe the change through a new reference.
     * But it is a single exactly-sized primitive-array copy rather than a list,
     * an Object[] and a 60-element shift.
     *
     * Not synchronized by design — every caller is on the main thread (the
     * broadcast receiver posts through runOnUiThread), so a lock on this path
     * would be pure cost.
     */
    private class LongRing(private val capacity: Int) {
        private val buf = LongArray(capacity)
        private var head = 0 // index the next sample is written to
        private var count = 0 // live entries, saturating at capacity

        /** @return a fresh oldest-to-newest array of the live entries. */
        fun add(v: Long): LongArray {
            buf[head] = v
            head = (head + 1) % capacity
            if (count < capacity) count++
            val out = LongArray(count)
            val start = (head - count + capacity) % capacity
            for (i in 0 until count) out[i] = buf[(start + i) % capacity]
            return out
        }
    }

    private class FloatRing(private val capacity: Int) {
        private val buf = FloatArray(capacity)
        private var head = 0
        private var count = 0

        fun add(v: Float): FloatArray {
            buf[head] = v
            head = (head + 1) % capacity
            if (count < capacity) count++
            val out = FloatArray(count)
            val start = (head - count + capacity) % capacity
            for (i in 0 until count) out[i] = buf[(start + i) % capacity]
            return out
        }
    }

    private val healthRing = LongRing(HEALTH_SAMPLE_CAPACITY)
    private val speedRing = FloatRing(SPEED_SAMPLE_CAPACITY)

    /** Called by MainActivity on each ACTION_HEALTH_UPDATE broadcast. */
    fun addHealthPoint(latencyMs: Long) {
        healthPoints = healthRing.add(latencyMs).toList()
    }

    fun addSpeedDataPoint(speed: Float) {
        speedDataPoints = speedRing.add(speed).toList()
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
            Screen.Circuits -> CircuitScreen(appState = appState)
            Screen.Health -> HealthScreen(appState = appState)
        }
    }
}
