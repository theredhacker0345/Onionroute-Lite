package org.torlite

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.torlite.service.TorService
import org.torlite.service.TorVpnService
import org.torlite.ui.TorLiteAppState
import org.torlite.ui.TorLiteNavigation
import org.torlite.ui.theme.ConnectionState
import org.torlite.ui.theme.TorLiteTheme

/**
 * Main activity for TorLite — Phantom UI with Jetpack Compose.
 *
 * The entire UI is rendered through Compose using the Phantom design system:
 * OLED black (#000000), Jade accent (#00E5C4), Swiss precision typography.
 * ComponentActivity is required for Compose's setContent extension.
 */
class MainActivity : ComponentActivity() {

    private val tag = "MainActivity"

    // ── Compose State ──────────────────────────────────────────
    private val appState = TorLiteAppState()

    // ── VPN permission launcher (Activity Result API) ──────────
    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) startVpn()
        else {
            appState.updateVpnMode(false)
            Toast.makeText(this, "VPN permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    // ── Broadcast Receiver for status updates from TorService ───
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Constants.ACTION_STATUS_UPDATE -> handleStatusUpdate(intent)
                Constants.ACTION_BANDWIDTH_UPDATE -> handleBandwidthUpdate(intent)
            }
        }
    }

    // ══════════════════════════════════════════════════════════
    // Activity Lifecycle
    // ══════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)

        // Restore state from preferences
        val prefs = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)
        appState.updateVpnMode(prefs.getBoolean(Constants.PREF_VPN_ENABLED, false))
        appState.updateKillSwitch(KillSwitch.isEnabledInPrefs(this))
        appState.updateAutoStart(prefs.getBoolean(Constants.PREF_AUTO_START, false))

        // Set Compose content with Phantom theme
        setContent {
            TorLiteTheme {
                TorLiteNavigation(appState = appState)
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

    // ══════════════════════════════════════════════════════════
    // Tor Control — wired to Compose state
    // ══════════════════════════════════════════════════════════

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

    // ══════════════════════════════════════════════════════════
    // VPN Control
    // ══════════════════════════════════════════════════════════

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

    // ══════════════════════════════════════════════════════════
    // Status Updates — bridges TorService broadcasts to Compose
    // ══════════════════════════════════════════════════════════

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

    // ══════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════

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
        }
        // API 33+ requires specifying RECEIVER_EXPORTED/NOT_EXPORTED flag
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
