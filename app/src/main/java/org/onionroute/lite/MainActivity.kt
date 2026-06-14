package org.onionroute.lite

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import org.onionroute.lite.service.KindnessService
import org.onionroute.lite.service.TorService
import org.onionroute.lite.service.TorVpnService
import org.onionroute.lite.tor.BridgeConfig
import org.onionroute.lite.ui.OnionRouteAppState
import org.onionroute.lite.ui.OnionRouteNavigation
import org.onionroute.lite.ui.theme.ConnectionState
import org.onionroute.lite.ui.theme.OnionRouteTheme

/**
 * Main activity for OnionRoute Lite — OnionRoute UI with Jetpack Compose.
 *
 * The entire UI is rendered through Compose using the OnionRoute design system:
 * OLED black (#000000), Jade accent (#00E5C4), Swiss precision typography.
 * ComponentActivity is required for Compose's setContent extension.
 */
class MainActivity : ComponentActivity() {

    private val tag = "MainActivity"

    private val appState = OnionRouteAppState()

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) startVpn()
        else {
            appState.updateVpnMode(false)
            Toast.makeText(this, "VPN permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Constants.ACTION_STATUS_UPDATE -> handleStatusUpdate(intent)
                Constants.ACTION_BANDWIDTH_UPDATE -> handleBandwidthUpdate(intent)
                Constants.ACTION_KINDNESS_STATS -> handleKindnessStats(intent)
            }
        }
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)
        appState.updateVpnMode(prefs.getBoolean(Constants.PREF_VPN_ENABLED, false))
        appState.updateKillSwitch(KillSwitch.isEnabledInPrefs(this))
        appState.updateAutoStart(prefs.getBoolean(Constants.PREF_AUTO_START, false))
        appState.updateSnowflake(prefs.getBoolean(Constants.PREF_SNOWFLAKE_ENABLED, false))
        appState.updateObfs4(prefs.getBoolean(Constants.PREF_OBFS4_ENABLED, false))

        // Load custom bridges from BridgeConfig
        val bridgeConfig = BridgeConfig(this)
        appState.updateCustomBridges(bridgeConfig.getCustomBridges())

        // ── Restore camouflage icon preference ────────────────────
        val savedIconKey = prefs.getString(Constants.PREF_APP_ICON, "default") ?: "default"
        appState.updateCurrentIcon(savedIconKey)

        // ── Wire appState callbacks to service commands ────────────
        appState.onVpnToggle = { enabled ->
            if (enabled) requestVpnPermission()
            else stopVpn()
        }
        appState.onKillSwitchToggle = { enabled ->
            KillSwitch.setEnabledInPrefs(this, enabled)
        }
        appState.onSnowflakeToggle = { enabled ->
            val p = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)
            p.edit().putBoolean(Constants.PREF_SNOWFLAKE_ENABLED, enabled).apply()
            sendCommandToService(Constants.ACTION_TOGGLE_SNOWFLAKE)
        }
        appState.onObfs4Toggle = { enabled ->
            val p = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)
            p.edit().putBoolean(Constants.PREF_OBFS4_ENABLED, enabled).apply()
            sendCommandToService(Constants.ACTION_TOGGLE_OBFS4)
        }
        appState.onWebTunnelToggle = { enabled ->
            val p = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)
            p.edit().putBoolean(Constants.PREF_WEBTUNNEL_ENABLED, enabled).apply()
            sendCommandToService(Constants.ACTION_TOGGLE_WEBTUNNEL)
        }
        appState.onKindnessToggle = { enabled ->
            val p = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)
            p.edit().putBoolean(Constants.PREF_KINDNESS_ENABLED, enabled).apply()
            if (enabled) KindnessService.start(this) else KindnessService.stop(this)
        }
        appState.onAutoStartToggle = { enabled ->
            val p = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)
            p.edit().putBoolean(Constants.PREF_AUTO_START, enabled).apply()
        }
        appState.onImportBridges = { bridges ->
            val bridgeConfig = BridgeConfig(this)
            bridgeConfig.setCustomBridges(bridges)
        }
        appState.onConnectToggle = {
            when (appState.connectionState) {
                is ConnectionState.Disconnected, is ConnectionState.Error -> startTor()
                is ConnectionState.Connected, is ConnectionState.Connecting -> stopTor()
            }
        }

        setContent {
            OnionRouteTheme {
                OnionRouteNavigation(appState = appState)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        registerStatusReceiver()
    }

    override fun onPause() {
        try { unregisterReceiver(statusReceiver) } catch (_: Exception) {}
        super.onPause()
    }

    override fun onDestroy() {
        try { unregisterReceiver(statusReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    fun startTor() {
        appState.updateConnectionState(ConnectionState.Connecting)
        sendCommandToService(Constants.ACTION_START_TOR)
    }

    fun stopTor() {
        appState.updateConnectionState(ConnectionState.Disconnected)
        sendCommandToService(Constants.ACTION_STOP_TOR)
        if (appState.isVpnMode) {
            stopVpn()
            appState.updateVpnMode(false)
        }
    }

    fun requestVpnPermission() {
        if (appState.connectionState !is ConnectionState.Connected) {
            Toast.makeText(this, "Connect to Tor first", Toast.LENGTH_SHORT).show()
            appState.updateVpnMode(false)
            return
        }
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            startVpn()
        }
    }

    private fun startVpn() {
        appState.updateVpnMode(true)
        val p = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)
        p.edit().putBoolean(Constants.PREF_VPN_ENABLED, true).apply()

        val intent = Intent(this, TorVpnService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Toast.makeText(this, "VPN mode active", Toast.LENGTH_SHORT).show()
    }

    private fun stopVpn() {
        appState.updateVpnMode(false)
        val p = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)
        p.edit().putBoolean(Constants.PREF_VPN_ENABLED, false).apply()

        val intent = Intent(this, TorVpnService::class.java)
        intent.action = Constants.ACTION_STOP_VPN
        startService(intent)
        sendCommandToService(Constants.ACTION_STOP_VPN)
    }

    private fun handleStatusUpdate(intent: Intent) {
        val torStatus = intent.getStringExtra(Constants.EXTRA_TOR_STATUS) ?: "UNKNOWN"
        runOnUiThread {
            when (torStatus) {
                "CONNECTED" -> appState.updateConnectionState(
                    ConnectionState.Connected(exitNodeIp = "tor.0.4.8.14")
                )
                "CONNECTING", "STARTING" -> appState.updateConnectionState(
                    ConnectionState.Connecting
                )
                "DISCONNECTED" -> appState.updateConnectionState(
                    ConnectionState.Disconnected
                )
                "ERROR" -> {
                    val errMsg = intent.getStringExtra("error_message") ?: "Unknown error"
                    appState.updateConnectionState(ConnectionState.Error(errMsg))
                }
            }
        }
    }

    private fun handleBandwidthUpdate(intent: Intent) {
        val speedDown = intent.getLongExtra(Constants.EXTRA_SPEED_DOWN, 0)
        val speedUp = intent.getLongExtra(Constants.EXTRA_SPEED_UP, 0)
        val totalDown = intent.getLongExtra(Constants.EXTRA_BYTES_DOWN, 0)
        val totalUp = intent.getLongExtra(Constants.EXTRA_BYTES_UP, 0)

        runOnUiThread {
            appState.updateBandwidth(
                upload = formatSpeed(speedUp),
                download = formatSpeed(speedDown),
                totalUp = formatBytes(totalUp),
                totalDown = formatBytes(totalDown)
            )
            appState.addSpeedDataPoint(speedDown.toFloat())
        }
    }

    private fun handleKindnessStats(intent: Intent) {
        val dataDonated = intent.getLongExtra(Constants.EXTRA_KINDNESS_DATA_DONATED, 0)
        val peopleNow = intent.getLongExtra(Constants.EXTRA_KINDNESS_PEOPLE_NOW, 0)
        val peopleTotal = intent.getLongExtra(Constants.EXTRA_KINDNESS_PEOPLE_TOTAL, 0)

        val prefs = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)
        val sessionStart = prefs.getLong(Constants.PREF_KINDNESS_SESSION_START, 0L)

        runOnUiThread {
            appState.updateKindnessStats(
                dataDonated = dataDonated,
                peopleNow = peopleNow,
                peopleTotal = peopleTotal,
                sessionStart = sessionStart
            )
        }
    }

    private fun sendCommandToService(action: String) {
        val intent = Intent(this, TorService::class.java).apply { this.action = action }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun registerStatusReceiver() {
        val filter = IntentFilter().apply {
            addAction(Constants.ACTION_STATUS_UPDATE)
            addAction(Constants.ACTION_BANDWIDTH_UPDATE)
            addAction(Constants.ACTION_KINDNESS_STATS)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(statusReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statusReceiver, filter)
        }
    }

    private fun formatSpeed(bytesPerSec: Long): String = when {
        bytesPerSec >= 1_048_576 -> "%.1f MB/s".format(bytesPerSec / 1_048_576.0)
        bytesPerSec >= 1024 -> "%.1f KB/s".format(bytesPerSec / 1024.0)
        bytesPerSec > 0 -> "$bytesPerSec B/s"
        else -> "0 KB/s"
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes > 0 -> "$bytes B"
        else -> "0 B"
    }
}
