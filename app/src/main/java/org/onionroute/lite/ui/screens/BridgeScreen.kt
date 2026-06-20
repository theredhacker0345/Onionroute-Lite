package org.onionroute.lite.ui.screens

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.onionroute.lite.Constants
import org.onionroute.lite.tor.BridgeConfig
import org.onionroute.lite.ui.OnionRouteAppState
import org.onionroute.lite.ui.theme.*

@Composable
fun BridgeScreen(appState: OnionRouteAppState) {
    val context = LocalContext.current
    val bridgeConfig = remember { BridgeConfig(context) }

    var customBridges by remember { mutableStateOf(bridgeConfig.getCustomBridges()) }

    // Which bridge type section is expanded
    var expandedType by remember { mutableStateOf<String?>(null) }

    // Dialog state
    var showImportDialog by remember { mutableStateOf(false) }
    var importFeedback by remember { mutableStateOf("") }

    // QR scanner launcher
    val qrLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scanned = result.data?.getStringExtra("SCAN_RESULT")
                ?: result.data?.data?.toString()
                ?: ""
            if (scanned.isNotEmpty()) {
                val count = bridgeConfig.parseAndAddBridges(scanned)
                customBridges = bridgeConfig.getCustomBridges()
                appState.updateCustomBridges(customBridges)
                importFeedback = if (count > 0) "Imported $count bridge(s) from QR" else "No valid bridges in QR"
            }
        }
    }

    if (showImportDialog) {
        BridgeImportDialog(
            onDismiss = { showImportDialog = false },
            onImport = { text ->
                val count = bridgeConfig.parseAndAddBridges(text)
                customBridges = bridgeConfig.getCustomBridges()
                appState.updateCustomBridges(customBridges)
                importFeedback = if (count > 0) "Imported $count bridge(s)" else "No valid bridges found"
                showImportDialog = false
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

        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "\u2190",
                color = OnionRouteColors.TextSecondary,
                fontSize = 24.sp,
                fontFamily = SyneFontFamily,
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { appState.navigateBack() }
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = "BRIDGES",
                style = OnionRouteTypography.HeadlineMedium.copy(color = OnionRouteColors.TextPrimary)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Tap a bridge type to see available bridges. Add or remove bridges as needed.",
            style = OnionRouteTypography.BodyMedium.copy(color = OnionRouteColors.TextSecondary)
        )

        Spacer(modifier = Modifier.height(24.dp))

        // ── Snowflake section ──────────────────────────────────
        BridgeTypeSection(
            title = "Snowflake",
            description = "WebRTC-based transport. Routes through volunteer proxies.",
            isActive = appState.isSnowflakeEnabled,
            isExpanded = expandedType == "snowflake",
            onToggleExpand = {
                expandedType = if (expandedType == "snowflake") null else "snowflake"
            },
            onToggle = { appState.updateSnowflake(!appState.isSnowflakeEnabled) }
        ) {
            // Snowflake uses a single built-in bridge configured in torrc
            // No individual bridges to list — just show info
            BridgeInfoRow(
                label = "Built-in snowflake bridge",
                value = "Configured automatically via Tor Project broker",
                statusColor = if (appState.isSnowflakeEnabled) OnionRouteColors.Jade else OnionRouteColors.TextTertiary
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ── obfs4 section ──────────────────────────────────────
        val obfs4Builtin = remember { Constants.DEFAULT_OBFS4_BRIDGES }
        val obfs4Custom = customBridges.filter { it.startsWith("obfs4", ignoreCase = true) }

        BridgeTypeSection(
            title = "obfs4",
            description = "Disguises Tor traffic as random data. ${obfs4Builtin.size} built-in + ${obfs4Custom.size} custom",
            isActive = appState.isObfs4Enabled,
            isExpanded = expandedType == "obfs4",
            onToggleExpand = {
                expandedType = if (expandedType == "obfs4") null else "obfs4"
            },
            onToggle = { appState.updateObfs4(!appState.isObfs4Enabled) }
        ) {
            // Built-in bridges
            if (obfs4Builtin.isNotEmpty()) {
                Text(
                    text = "BUILT-IN (${obfs4Builtin.size})",
                    style = OnionRouteTypography.SectionHeader,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                obfs4Builtin.forEach { bridge ->
                    BridgeLineItem(bridgeLine = bridge, showDelete = false, onDelete = {})
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }

            // Custom obfs4 bridges
            if (obfs4Custom.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "CUSTOM (${obfs4Custom.size})",
                    style = OnionRouteTypography.SectionHeader,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                obfs4Custom.forEach { bridge ->
                    BridgeLineItem(
                        bridgeLine = bridge,
                        showDelete = true,
                        onDelete = {
                            val updated = customBridges.toMutableList()
                            updated.remove(bridge)
                            bridgeConfig.setCustomBridges(updated)
                            customBridges = updated
                            appState.updateCustomBridges(customBridges)
                        }
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ── WebTunnel section ──────────────────────────────────
        val webtunnelBuiltin = remember { Constants.DEFAULT_WEBTUNNEL_BRIDGES }
        val webtunnelCustom = customBridges.filter { it.startsWith("webtunnel", ignoreCase = true) }

        BridgeTypeSection(
            title = "WebTunnel",
            description = "Mimics HTTPS traffic. ${webtunnelBuiltin.size} built-in + ${webtunnelCustom.size} custom",
            isActive = appState.isWebTunnelEnabled,
            isExpanded = expandedType == "webtunnel",
            onToggleExpand = {
                expandedType = if (expandedType == "webtunnel") null else "webtunnel"
            },
            onToggle = { appState.updateWebTunnel(!appState.isWebTunnelEnabled) }
        ) {
            if (webtunnelBuiltin.isNotEmpty()) {
                Text(
                    text = "BUILT-IN (${webtunnelBuiltin.size})",
                    style = OnionRouteTypography.SectionHeader,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                webtunnelBuiltin.forEach { bridge ->
                    BridgeLineItem(bridgeLine = bridge, showDelete = false, onDelete = {})
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }

            if (webtunnelCustom.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "CUSTOM (${webtunnelCustom.size})",
                    style = OnionRouteTypography.SectionHeader,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                webtunnelCustom.forEach { bridge ->
                    BridgeLineItem(
                        bridgeLine = bridge,
                        showDelete = true,
                        onDelete = {
                            val updated = customBridges.toMutableList()
                            updated.remove(bridge)
                            bridgeConfig.setCustomBridges(updated)
                            customBridges = updated
                            appState.updateCustomBridges(customBridges)
                        }
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // ── Add bridges section ────────────────────────────────
        Text(text = "ADD BRIDGES", style = OnionRouteTypography.SectionHeader)
        Spacer(modifier = Modifier.height(8.dp))

        ImportActionCard(
            label = "Scan QR Code",
            subtitle = "Import bridge lines from a QR code",
            onClick = {
                val scanIntent = Intent("com.google.zxing.client.android.SCAN")
                scanIntent.putExtra("SCAN_MODE", "QR_CODE_MODE")
                try {
                    qrLauncher.launch(scanIntent)
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(context, "Install a QR scanner app (e.g. Binary Eye from F-Droid)", Toast.LENGTH_LONG).show()
                }
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        ImportActionCard(
            label = "Paste Bridge Lines",
            subtitle = "Manually enter obfs4, snowflake, or webtunnel bridges",
            onClick = { showImportDialog = true }
        )

        // Import feedback
        if (importFeedback.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = importFeedback,
                style = OnionRouteTypography.LabelMedium.copy(color = OnionRouteColors.Jade),
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            LaunchedEffect(importFeedback) {
                kotlinx.coroutines.delay(3000)
                importFeedback = ""
            }
        }

        // Clear all custom bridges
        if (customBridges.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
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
                        customBridges = emptyList()
                        appState.updateCustomBridges(emptyList())
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

        Spacer(modifier = Modifier.height(48.dp))
    }
}

// ── Bridge Type Section (expandable) ────────────────────────────────

@Composable
private fun BridgeTypeSection(
    title: String,
    description: String,
    isActive: Boolean,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isActive) OnionRouteColors.JadeGlow else OnionRouteColors.Surface2)
            .border(
                1.dp,
                if (isActive) OnionRouteColors.Jade.copy(alpha = 0.4f) else OnionRouteColors.BorderSubtle,
                RoundedCornerShape(12.dp)
            )
    ) {
        // Header row — tap to expand/collapse
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onToggleExpand() }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isActive) "$title \u2014 ACTIVE" else title,
                    style = OnionRouteTypography.BodyMedium.copy(
                        color = if (isActive) OnionRouteColors.Jade else OnionRouteColors.TextPrimary
                    )
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    style = OnionRouteTypography.LabelSmall.copy(color = OnionRouteColors.TextSecondary)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Enable/disable toggle
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isActive) OnionRouteColors.Jade.copy(alpha = 0.2f) else OnionRouteColors.Surface3)
                    .border(
                        1.dp,
                        if (isActive) OnionRouteColors.Jade.copy(alpha = 0.5f) else OnionRouteColors.BorderSubtle,
                        RoundedCornerShape(8.dp)
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onToggle() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isActive) "ON" else "OFF",
                    style = OnionRouteTypography.LabelSmall.copy(
                        color = if (isActive) OnionRouteColors.Jade else OnionRouteColors.TextTertiary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    )
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Expand/collapse indicator
            Text(
                text = if (isExpanded) "\u25B2" else "\u25BC",
                color = OnionRouteColors.TextTertiary,
                fontSize = 10.sp
            )
        }

        // Expanded content — list of bridges
        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                content = content
            )
        }
    }
}

// ── Bridge Line Item ────────────────────────────────────────────────

@Composable
private fun BridgeLineItem(
    bridgeLine: String,
    showDelete: Boolean,
    onDelete: () -> Unit
) {
    val transport = when {
        bridgeLine.startsWith("obfs4", ignoreCase = true) -> "obfs4"
        bridgeLine.startsWith("snowflake", ignoreCase = true) -> "snowflake"
        bridgeLine.startsWith("webtunnel", ignoreCase = true) -> "webtunnel"
        bridgeLine.startsWith("meek", ignoreCase = true) -> "meek"
        else -> "unknown"
    }

    // Extract IP:port for display
    val ipMatch = Regex("""(\d+\.\d+\.\d+\.\d+:\d+)""").find(bridgeLine)
    val displayId = ipMatch?.value ?: bridgeLine.take(30)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(OnionRouteColors.Surface1)
            .border(1.dp, OnionRouteColors.BorderSubtle, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = displayId,
                style = OnionRouteTypography.LabelMedium.copy(
                    color = OnionRouteColors.TextPrimary,
                    fontWeight = FontWeight.Medium
                )
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = bridgeLine.take(70) + if (bridgeLine.length > 70) "\u2026" else "",
                style = OnionRouteTypography.LabelSmall.copy(
                    color = OnionRouteColors.TextTertiary
                )
            )
        }

        if (showDelete) {
            Spacer(modifier = Modifier.width(8.dp))
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

// ── Bridge Info Row ─────────────────────────────────────────────────

@Composable
private fun BridgeInfoRow(label: String, value: String, statusColor: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(OnionRouteColors.Surface1)
            .border(1.dp, OnionRouteColors.BorderSubtle, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(statusColor)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = label,
                style = OnionRouteTypography.LabelMedium.copy(color = OnionRouteColors.TextPrimary)
            )
            Text(
                text = value,
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
                    style = OnionRouteTypography.LabelSmall.copy(color = OnionRouteColors.TextSecondary)
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = bridgeText,
                    onValueChange = { bridgeText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 240.dp),
                    textStyle = OnionRouteTypography.LabelMedium.copy(color = OnionRouteColors.TextPrimary),
                    placeholder = {
                        Text(
                            text = "obfs4 192.168.1.1:443 FINGERPRINT cert=...\nwebtunnel 10.0.0.1:443 FINGERPRINT url=...",
                            style = OnionRouteTypography.LabelSmall.copy(color = OnionRouteColors.TextTertiary)
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
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    if (bridgeText.isNotBlank()) onImport(bridgeText)
                }.padding(horizontal = 12.dp, vertical = 8.dp)
            )
        },
        dismissButton = {
            Text(
                text = "CANCEL",
                style = OnionRouteTypography.BodyMedium.copy(color = OnionRouteColors.TextSecondary),
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onDismiss() }.padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    )
}
