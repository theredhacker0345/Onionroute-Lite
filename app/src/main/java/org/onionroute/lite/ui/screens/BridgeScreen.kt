package org.onionroute.lite.ui.screens

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.onionroute.lite.tor.BridgeConfig
import org.onionroute.lite.ui.OnionRouteAppState
import org.onionroute.lite.ui.theme.*

@Composable
fun BridgeScreen(appState: OnionRouteAppState) {
    val context = LocalContext.current
    val bridgeConfig = remember { BridgeConfig(context) }

    // Refresh custom bridges from BridgeConfig when screen is composed
    var customBridges by remember {
        mutableStateOf(bridgeConfig.getCustomBridges())
    }

    // Dialog state
    var showImportDialog by remember { mutableStateOf(false) }
    var showScanResultDialog by remember { mutableStateOf(false) }
    var scannedText by remember { mutableStateOf("") }
    var importFeedback by remember { mutableStateOf("") }

    // QR scanner launcher using ZXing-compatible intent
    val qrLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scanned = result.data?.getStringExtra("SCAN_RESULT")
                ?: result.data?.data?.toString()
                ?: ""
            if (scanned.isNotEmpty()) {
                scannedText = scanned
                showScanResultDialog = true
            }
        }
    }

    // ── Import dialog (manual paste) ─────────────────────────────
    if (showImportDialog) {
        BridgeImportDialog(
            onDismiss = { showImportDialog = false },
            onImport = { text ->
                val count = bridgeConfig.parseAndAddBridges(text)
                customBridges = bridgeConfig.getCustomBridges()
                appState.updateCustomBridges(customBridges)
                if (count > 0) {
                    importFeedback = "Imported $count bridge(s)"
                    appState.onImportBridges?.invoke(customBridges)
                } else {
                    importFeedback = "No valid bridges found"
                }
                showImportDialog = false
            }
        )
    }

    // ── Scan result confirmation dialog ──────────────────────────
    if (showScanResultDialog) {
        BridgeImportDialog(
            initialText = scannedText,
            onDismiss = {
                showScanResultDialog = false
                scannedText = ""
            },
            onImport = { text ->
                val count = bridgeConfig.parseAndAddBridges(text)
                customBridges = bridgeConfig.getCustomBridges()
                appState.updateCustomBridges(customBridges)
                if (count > 0) {
                    importFeedback = "Imported $count bridge(s) from QR"
                    appState.onImportBridges?.invoke(customBridges)
                } else {
                    importFeedback = "No valid bridges found in QR code"
                }
                showScanResultDialog = false
                scannedText = ""
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(OnionRouteColors.Black)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
    ) {
        Spacer(modifier = Modifier.height(48.dp))

        // Header with back button
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "\u2190",
                color = OnionRouteColors.TextSecondary,
                fontSize = 24.sp,
                fontFamily = SyneFontFamily,
                modifier = Modifier
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { appState.navigateBack() }
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = "BRIDGE CONFIG",
                style = OnionRouteTypography.HeadlineMedium.copy(color = OnionRouteColors.TextPrimary)
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        // ── Snowflake info ────────────────────────────────────────
        Text(text = "SNOWFLAKE", style = OnionRouteTypography.SectionHeader)
        Spacer(modifier = Modifier.height(8.dp))
        BridgeInfoCard(
            title = "Snowflake",
            description = "Uses WebRTC to route through volunteer proxies. Best for bypassing censorship with minimal configuration.",
            isActive = appState.isSnowflakeEnabled
        )

        Spacer(modifier = Modifier.height(24.dp))

        // ── obfs4 info ────────────────────────────────────────────
        Text(text = "OBFS4", style = OnionRouteTypography.SectionHeader)
        Spacer(modifier = Modifier.height(8.dp))
        BridgeInfoCard(
            title = "obfs4",
            description = "Disguises Tor traffic as random-looking data. Default bridges are built-in. Custom bridges can be configured.",
            isActive = appState.isObfs4Enabled
        )

        Spacer(modifier = Modifier.height(24.dp))

        // ── WebTunnel info ────────────────────────────────────────
        Text(text = "WEBTUNNEL", style = OnionRouteTypography.SectionHeader)
        Spacer(modifier = Modifier.height(8.dp))
        BridgeInfoCard(
            title = "WebTunnel",
            description = "Mimics HTTPS traffic to evade deep packet inspection. No extra binary needed \u2014 uses direct HTTPS connections to bridge endpoints.",
            isActive = appState.isWebTunnelEnabled
        )

        Spacer(modifier = Modifier.height(24.dp))

        // ── Default Bridges info ──────────────────────────────────
        Text(text = "DEFAULT BRIDGES", style = OnionRouteTypography.SectionHeader)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Built-in obfs4 and WebTunnel bridges are provided by the Tor Project. No configuration required \u2014 just enable the transport in Settings.",
            style = OnionRouteTypography.BodyMedium.copy(color = OnionRouteColors.TextSecondary)
        )

        Spacer(modifier = Modifier.height(32.dp))

        // ══════════════════════════════════════════════════════════
        // IMPORT BRIDGES section
        // ══════════════════════════════════════════════════════════
        Text(text = "IMPORT BRIDGES", style = OnionRouteTypography.SectionHeader)
        Spacer(modifier = Modifier.height(8.dp))

        // Scan QR Code button
        ImportActionCard(
            label = "Scan QR Code",
            subtitle = "Use an external QR scanner app to import bridge lines",
            onClick = {
                val scanIntent = Intent("com.google.zxing.client.android.SCAN")
                scanIntent.putExtra("SCAN_MODE", "QR_CODE_MODE")
                try {
                    qrLauncher.launch(scanIntent)
                } catch (e: ActivityNotFoundException) {
                    // Fallback: try generic scan intent
                    try {
                        val genericIntent = Intent(Intent.ACTION_SEND)
                        genericIntent.type = "text/plain"
                        genericIntent.putExtra(Intent.EXTRA_TEXT, "")
                        qrLauncher.launch(genericIntent)
                    } catch (e2: ActivityNotFoundException) {
                        Toast.makeText(
                            context,
                            "Install a QR scanner app (e.g. Binary Eye from F-Droid)",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Paste Bridges button
        ImportActionCard(
            label = "Paste Bridges",
            subtitle = "Manually paste bridge lines from clipboard or text",
            onClick = { showImportDialog = true }
        )

        // Import feedback toast
        if (importFeedback.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = importFeedback,
                style = OnionRouteTypography.LabelMedium.copy(color = OnionRouteColors.Jade),
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            // Auto-clear feedback after a delay
            LaunchedEffect(importFeedback) {
                kotlinx.coroutines.delay(3000)
                importFeedback = ""
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // ── Custom Bridges list ───────────────────────────────────
        if (customBridges.isNotEmpty()) {
            Text(text = "CUSTOM BRIDGES", style = OnionRouteTypography.SectionHeader)
            Spacer(modifier = Modifier.height(8.dp))

            customBridges.forEachIndexed { index, bridge ->
                CustomBridgeItem(
                    bridgeLine = bridge,
                    index = index,
                    onDelete = {
                        bridgeConfig.removeCustomBridge(index)
                        customBridges = bridgeConfig.getCustomBridges()
                        appState.updateCustomBridges(customBridges)
                    }
                )
                Spacer(modifier = Modifier.height(6.dp))
            }

            // Clear all button
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(OnionRouteColors.Surface2)
                    .border(1.dp, OnionRouteColors.StatusRed.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        bridgeConfig.setCustomBridges(emptyList())
                        customBridges = bridgeConfig.getCustomBridges()
                        appState.updateCustomBridges(customBridges)
                        importFeedback = "All custom bridges removed"
                    }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Clear All Custom Bridges",
                    style = OnionRouteTypography.BodyMedium.copy(color = OnionRouteColors.StatusRed)
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

// ── Bridge Info Card (existing component, unchanged) ────────────────

@Composable
private fun BridgeInfoCard(title: String, description: String, isActive: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isActive) OnionRouteColors.JadeGlow else OnionRouteColors.Surface2)
            .border(1.dp, if (isActive) OnionRouteColors.Jade.copy(alpha = 0.4f) else OnionRouteColors.BorderSubtle, RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        Column {
            Text(
                text = if (isActive) "$title \u2014 ACTIVE" else title,
                style = OnionRouteTypography.BodyMedium.copy(
                    color = if (isActive) OnionRouteColors.Jade else OnionRouteColors.TextPrimary
                )
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
                style = OnionRouteTypography.LabelSmall.copy(color = OnionRouteColors.TextSecondary)
            )
        }
    }
}

// ── Import Action Card ──────────────────────────────────────────────

@Composable
private fun ImportActionCard(
    label: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(OnionRouteColors.Surface2)
            .border(1.dp, OnionRouteColors.Jade.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = OnionRouteTypography.BodyMedium.copy(color = OnionRouteColors.Jade)
                )
                Text(
                    text = subtitle,
                    style = OnionRouteTypography.LabelSmall.copy(color = OnionRouteColors.TextSecondary)
                )
            }
            Text(
                text = "\u2192",
                color = OnionRouteColors.Jade,
                fontSize = 16.sp,
                fontFamily = SyneFontFamily
            )
        }
    }
}

// ── Custom Bridge Item with delete ──────────────────────────────────

@Composable
private fun CustomBridgeItem(
    bridgeLine: String,
    index: Int,
    onDelete: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(OnionRouteColors.Surface1)
            .border(1.dp, OnionRouteColors.BorderSubtle, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            // Bridge type indicator + line
            Column(modifier = Modifier.weight(1f)) {
                // Transport type badge
                val transport = when {
                    bridgeLine.startsWith("obfs4", ignoreCase = true) -> "obfs4"
                    bridgeLine.startsWith("snowflake", ignoreCase = true) -> "snowflake"
                    bridgeLine.startsWith("webtunnel", ignoreCase = true) -> "webtunnel"
                    bridgeLine.startsWith("meek", ignoreCase = true) -> "meek"
                    else -> "unknown"
                }

                Text(
                    text = transport.uppercase(),
                    style = OnionRouteTypography.LabelSmall.copy(
                        color = OnionRouteColors.Jade,
                        fontWeight = FontWeight.Bold
                    )
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = bridgeLine.take(80) + if (bridgeLine.length > 80) "\u2026" else "",
                    style = OnionRouteTypography.LabelSmall.copy(
                        color = OnionRouteColors.TextTertiary
                    )
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Delete button
            Text(
                text = "\u2715",
                color = OnionRouteColors.StatusRed.copy(alpha = 0.7f),
                fontSize = 14.sp,
                fontFamily = JetBrainsMonoFontFamily,
                modifier = Modifier
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onDelete() }
                    .padding(4.dp)
            )
        }
    }
}

// ── Bridge Import Dialog ────────────────────────────────────────────

@Composable
private fun BridgeImportDialog(
    initialText: String = "",
    onDismiss: () -> Unit,
    onImport: (String) -> Unit
) {
    var bridgeText by remember { mutableStateOf(initialText) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = OnionRouteColors.Surface2,
        title = {
            Text(
                text = "Import Bridges",
                style = OnionRouteTypography.HeadlineMedium.copy(
                    color = OnionRouteColors.TextPrimary,
                    fontSize = 18.sp
                )
            )
        },
        text = {
            Column {
                Text(
                    text = "Paste bridge lines (one per line). Supported: obfs4, snowflake, webtunnel",
                    style = OnionRouteTypography.LabelSmall.copy(
                        color = OnionRouteColors.TextSecondary
                    )
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = bridgeText,
                    onValueChange = { bridgeText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 240.dp),
                    textStyle = OnionRouteTypography.LabelMedium.copy(
                        color = OnionRouteColors.TextPrimary
                    ),
                    placeholder = {
                        Text(
                            text = "obfs4 192.168.1.1:443 FINGERPRINT cert=...\nwebtunnel 10.0.0.1:443 FINGERPRINT url=...",
                            style = OnionRouteTypography.LabelSmall.copy(
                                color = OnionRouteColors.TextTertiary
                            )
                        )
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = OnionRouteColors.Jade.copy(alpha = 0.5f),
                        unfocusedBorderColor = OnionRouteColors.BorderSubtle,
                        cursorColor = OnionRouteColors.Jade,
                        focusedContainerColor = OnionRouteColors.Surface1,
                        unfocusedContainerColor = OnionRouteColors.Surface1
                    ),
                    shape = RoundedCornerShape(8.dp)
                )
            }
        },
        confirmButton = {
            Text(
                text = "IMPORT",
                style = OnionRouteTypography.BodyMedium.copy(
                    color = if (bridgeText.isNotBlank()) OnionRouteColors.Jade else OnionRouteColors.TextTertiary
                ),
                modifier = Modifier
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        if (bridgeText.isNotBlank()) {
                            onImport(bridgeText)
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        },
        dismissButton = {
            Text(
                text = "CANCEL",
                style = OnionRouteTypography.BodyMedium.copy(color = OnionRouteColors.TextSecondary),
                modifier = Modifier
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onDismiss() }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    )
}
