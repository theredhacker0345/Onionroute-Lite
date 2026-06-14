package org.onionroute.lite.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.onionroute.lite.ui.OnionRouteAppState
import org.onionroute.lite.ui.Screen
import org.onionroute.lite.ui.theme.ConnectionState
import org.onionroute.lite.ui.theme.OnionRouteColors
import org.onionroute.lite.ui.theme.OnionRouteTypography

/**
 * Home screen — the primary UI for OnionRoute Lite.
 *
 * Features a large centered connection button with animated power icon,
 * real-time bandwidth strip, and quick toggles for VPN, Kill Switch,
 * Snowflake, and obfs4 bridges.
 */
@Composable
fun HomeScreen(appState: OnionRouteAppState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(OnionRouteColors.Black)
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ── Top Bar ──────────────────────────────────────────────
        Spacer(modifier = Modifier.height(48.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "OnionRoute Lite",
                style = OnionRouteTypography.HeadlineMedium.copy(
                    color = OnionRouteColors.TextPrimary
                )
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = "⚡",
                    color = OnionRouteColors.TextSecondary,
                    fontSize = 20.sp,
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { appState.navigateTo(Screen.Bandwidth) }
                )
                Text(
                    text = "⚙",
                    color = OnionRouteColors.TextSecondary,
                    fontSize = 20.sp,
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { appState.navigateTo(Screen.Settings) }
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "ONIONROUTE PROTOCOL",
            style = OnionRouteTypography.SectionHeader
        )

        Spacer(modifier = Modifier.height(48.dp))

        // ── Connection Button ────────────────────────────────────
        ConnectionButton(appState = appState)

        Spacer(modifier = Modifier.height(32.dp))

        // ── Status Display ───────────────────────────────────────
        StatusDisplay(appState = appState)

        Spacer(modifier = Modifier.height(32.dp))

        // ── Bandwidth Strip ──────────────────────────────────────
        BandwidthStrip(appState = appState)

        Spacer(modifier = Modifier.height(24.dp))

        // ── Speed Graph ──────────────────────────────────────────
        SpeedGraph(appState = appState)

        Spacer(modifier = Modifier.height(24.dp))

        // ── Quick Toggles ────────────────────────────────────────
        QuickToggleRow(appState = appState)

        Spacer(modifier = Modifier.weight(1f))
    }
}

// ═══════════════════════════════════════════════════════════════════
// Connection Button
// ═══════════════════════════════════════════════════════════════════

@Composable
fun ConnectionButton(appState: OnionRouteAppState) {
    val isConnected = appState.connectionState is ConnectionState.Connected
    val isConnecting = appState.connectionState is ConnectionState.Connecting

    val buttonColor by animateColorAsState(
        targetValue = when (appState.connectionState) {
            is ConnectionState.Connected -> OnionRouteColors.Jade
            is ConnectionState.Connecting -> OnionRouteColors.StatusAmber
            is ConnectionState.Error -> OnionRouteColors.StatusRed
            else -> OnionRouteColors.BorderMid
        },
        animationSpec = tween(500),
        label = "button_color"
    )

    val glowAlpha by animateFloatAsState(
        targetValue = when (appState.connectionState) {
            is ConnectionState.Connected -> 0.3f
            is ConnectionState.Connecting -> 0.15f
            else -> 0f
        },
        animationSpec = tween(500),
        label = "glow_alpha"
    )

    // Pulsing animation for connecting state
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    val scale = if (isConnecting) pulseScale else 1f

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(200.dp * scale)
            .shadow(
                elevation = if (isConnected || isConnecting) 24.dp else 0.dp,
                shape = CircleShape,
                ambientColor = buttonColor.copy(alpha = glowAlpha),
                spotColor = buttonColor.copy(alpha = glowAlpha)
            )
            .clip(CircleShape)
            .background(
                brush = Brush.radialGradient(
                    colors = listOf(
                        OnionRouteColors.Surface2,
                        OnionRouteColors.Surface1
                    )
                )
            )
            .border(
                width = 3.dp,
                color = buttonColor,
                shape = CircleShape
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                appState.onConnectToggle?.invoke()
            }
    ) {
        PowerIcon(
            color = buttonColor,
            isConnected = isConnected,
            isConnecting = isConnecting
        )
    }
}

// ═══════════════════════════════════════════════════════════════════
// Power Icon
// ═══════════════════════════════════════════════════════════════════

@Composable
fun PowerIcon(
    color: Color,
    isConnected: Boolean,
    isConnecting: Boolean
) {
    val infiniteTransition = rememberInfiniteTransition(label = "power_pulse")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    Canvas(modifier = Modifier.size(72.dp)) {
        val strokeWidth = 4.dp.toPx()
        val radius = (size.minDimension - strokeWidth) / 2
        val center = Offset(size.width / 2, size.height / 2)

        if (isConnecting) {
            // Spinning arc for connecting state
            drawArc(
                color = color,
                startAngle = rotation,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
        } else {
            // Full circle for connected, partial for disconnected
            val sweepAngle = if (isConnected) 360f else 270f
            val startAngle = if (isConnected) 0f else 135f
            drawArc(
                color = color,
                startAngle = startAngle,
                sweepAngle = sweepAngle,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
        }

        // Power line (vertical gap at top)
        val gapLength = radius * 0.4f
        drawLine(
            color = color,
            start = Offset(center.x, center.y - radius + strokeWidth),
            end = Offset(center.x, center.y - radius + gapLength + strokeWidth),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )
    }
}

// ═══════════════════════════════════════════════════════════════════
// Status Display
// ═══════════════════════════════════════════════════════════════════

@Composable
fun StatusDisplay(appState: OnionRouteAppState) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        when (val state = appState.connectionState) {
            is ConnectionState.Disconnected -> {
                Text(
                    text = "DISCONNECTED",
                    style = OnionRouteTypography.LabelMedium.copy(
                        color = OnionRouteColors.TextTertiary
                    )
                )
            }
            is ConnectionState.Connecting -> {
                AnimatedConnectingText()
            }
            is ConnectionState.Connected -> {
                Text(
                    text = "CONNECTED",
                    style = OnionRouteTypography.LabelMedium.copy(
                        color = OnionRouteColors.Jade
                    )
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = state.exitNodeIp,
                    style = OnionRouteTypography.LabelSmall.copy(
                        color = OnionRouteColors.TextSecondary
                    )
                )
            }
            is ConnectionState.Error -> {
                Text(
                    text = "ERROR",
                    style = OnionRouteTypography.LabelMedium.copy(
                        color = OnionRouteColors.StatusRed
                    )
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = state.message,
                    style = OnionRouteTypography.LabelSmall.copy(
                        color = OnionRouteColors.StatusRed
                    ),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// Animated Connecting Text
// ═══════════════════════════════════════════════════════════════════

@Composable
fun AnimatedConnectingText() {
    val infiniteTransition = rememberInfiniteTransition(label = "dots")
    val dotCount by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "dot_count"
    )

    val dots = ".".repeat(dotCount.toInt().coerceIn(0, 3))
    Text(
        text = "CONNECTING$dots",
        style = OnionRouteTypography.LabelMedium.copy(
            color = OnionRouteColors.StatusAmber
        )
    )
}

// ═══════════════════════════════════════════════════════════════════
// Bandwidth Strip
// ═══════════════════════════════════════════════════════════════════

@Composable
fun BandwidthStrip(appState: OnionRouteAppState) {
    val isConnected = appState.connectionState is ConnectionState.Connected
    val stripColor = if (isConnected) OnionRouteColors.Surface2 else OnionRouteColors.Surface1

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(stripColor)
            .border(
                width = 1.dp,
                color = OnionRouteColors.BorderSubtle,
                shape = RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Download
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "↓ DOWN",
                style = OnionRouteTypography.SectionHeader.copy(
                    color = OnionRouteColors.TextTertiary
                )
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = appState.downloadSpeed,
                style = OnionRouteTypography.LabelMedium.copy(
                    color = if (isConnected) OnionRouteColors.Jade else OnionRouteColors.TextTertiary
                )
            )
        }

        // Divider
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(36.dp)
                .background(OnionRouteColors.BorderSubtle)
        )

        // Upload
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "↑ UP",
                style = OnionRouteTypography.SectionHeader.copy(
                    color = OnionRouteColors.TextTertiary
                )
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = appState.uploadSpeed,
                style = OnionRouteTypography.LabelMedium.copy(
                    color = if (isConnected) OnionRouteColors.Jade else OnionRouteColors.TextTertiary
                )
            )
        }

        // Divider
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(36.dp)
                .background(OnionRouteColors.BorderSubtle)
        )

        // Total
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "TOTAL",
                style = OnionRouteTypography.SectionHeader.copy(
                    color = OnionRouteColors.TextTertiary
                )
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${appState.totalDownloaded} / ${appState.totalUploaded}",
                style = OnionRouteTypography.LabelSmall.copy(
                    color = OnionRouteColors.TextSecondary
                )
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// Speed Graph
// ═══════════════════════════════════════════════════════════════════

@Composable
fun SpeedGraph(appState: OnionRouteAppState) {
    val dataPoints = appState.speedDataPoints
    val isConnected = appState.connectionState is ConnectionState.Connected

    if (dataPoints.isEmpty() && !isConnected) return

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(OnionRouteColors.Surface1)
            .border(
                width = 1.dp,
                color = OnionRouteColors.BorderSubtle,
                shape = RoundedCornerShape(8.dp)
            )
    ) {
        if (dataPoints.isEmpty()) return@Canvas

        val maxSpeed = dataPoints.maxOrNull()?.coerceAtLeast(1f) ?: 1f
        val stepX = size.width / (dataPoints.size - 1).coerceAtLeast(1)

        // Draw filled area
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(0f, size.height)
            dataPoints.forEachIndexed { index, speed ->
                val x = index * stepX
                val y = size.height - (speed / maxSpeed) * size.height * 0.8f
                if (index == 0) moveTo(x, y) else lineTo(x, y)
            }
            lineTo(size.width, size.height)
            close()
        }

        drawPath(
            path = path,
            brush = Brush.verticalGradient(
                colors = listOf(
                    OnionRouteColors.Jade.copy(alpha = 0.15f),
                    OnionRouteColors.Jade.copy(alpha = 0.02f)
                )
            )
        )

        // Draw line
        val linePath = androidx.compose.ui.graphics.Path().apply {
            dataPoints.forEachIndexed { index, speed ->
                val x = index * stepX
                val y = size.height - (speed / maxSpeed) * size.height * 0.8f
                if (index == 0) moveTo(x, y) else lineTo(x, y)
            }
        }

        drawPath(
            path = linePath,
            color = OnionRouteColors.Jade.copy(alpha = 0.6f),
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}

// ═══════════════════════════════════════════════════════════════════
// Quick Toggle Row
// ═══════════════════════════════════════════════════════════════════

@Composable
fun QuickToggleRow(appState: OnionRouteAppState) {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = "QUICK SETTINGS",
            style = OnionRouteTypography.SectionHeader,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ToggleChip(
                label = "VPN",
                isActive = appState.isVpnMode,
                modifier = Modifier.weight(1f),
                onToggle = { appState.updateVpnMode(!appState.isVpnMode) }
            )
            ToggleChip(
                label = "KILL SWITCH",
                isActive = appState.isKillSwitchEnabled,
                modifier = Modifier.weight(1f),
                onToggle = { appState.updateKillSwitch(!appState.isKillSwitchEnabled) }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ToggleChip(
                label = "SNOWFLAKE",
                isActive = appState.isSnowflakeEnabled,
                modifier = Modifier.weight(1f),
                onToggle = { appState.updateSnowflake(!appState.isSnowflakeEnabled) }
            )
            ToggleChip(
                label = "OBFS4",
                isActive = appState.isObfs4Enabled,
                modifier = Modifier.weight(1f),
                onToggle = { appState.updateObfs4(!appState.isObfs4Enabled) }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ToggleChip(
                label = "KINDNESS",
                isActive = appState.isKindnessEnabled,
                modifier = Modifier.weight(1f),
                onToggle = {
                    val enabling = !appState.isKindnessEnabled
                    appState.updateKindness(enabling)
                    // Navigate to kindness stats screen when enabling
                    if (enabling) {
                        appState.navigateTo(Screen.Kindness)
                    }
                }
            )
            ToggleChip(
                label = "WEBTUNNEL",
                isActive = appState.isWebTunnelEnabled,
                modifier = Modifier.weight(1f),
                onToggle = { appState.updateWebTunnel(!appState.isWebTunnelEnabled) }
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// Toggle Chip
// ═══════════════════════════════════════════════════════════════════

@Composable
fun ToggleChip(
    label: String,
    isActive: Boolean,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit = {}
) {
    val bgColor by animateColorAsState(
        targetValue = if (isActive) OnionRouteColors.JadeGlow else OnionRouteColors.Surface2,
        animationSpec = tween(300),
        label = "chip_bg"
    )

    val borderColor by animateColorAsState(
        targetValue = if (isActive) OnionRouteColors.Jade.copy(alpha = 0.5f) else OnionRouteColors.BorderSubtle,
        animationSpec = tween(300),
        label = "chip_border"
    )

    val textColor by animateColorAsState(
        targetValue = if (isActive) OnionRouteColors.Jade else OnionRouteColors.TextSecondary,
        animationSpec = tween(300),
        label = "chip_text"
    )

    val dotColor by animateColorAsState(
        targetValue = if (isActive) OnionRouteColors.Jade else OnionRouteColors.TextTertiary,
        animationSpec = tween(300),
        label = "chip_dot"
    )

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .border(
                width = 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(10.dp)
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                onToggle()
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Status dot
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(dotColor)
        )

        Text(
            text = label,
            style = OnionRouteTypography.LabelSmall.copy(
                color = textColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        )
    }
}
