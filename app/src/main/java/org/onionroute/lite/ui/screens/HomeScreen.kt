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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.onionroute.lite.ui.OnionRouteAppState
import org.onionroute.lite.ui.Screen
import org.onionroute.lite.ui.theme.ConnectionState
import org.onionroute.lite.ui.theme.OnionRouteColors
import org.onionroute.lite.ui.theme.OnionRouteTypography
import org.onionroute.lite.ui.theme.SyneFontFamily

// ═══════════════════════════════════════════════════════════════════
// Hoisted paint / style constants
//
// PERF: every Brush built here allocates a List<Color>, boxes the Colors and
// creates a native android.graphics shader — expensive enough to notice when it
// happens on each recomposition or each drawn frame. Both gradients keep an
// infinite end, so the shader is still resolved against the size of the node it
// paints: hoisting them changes nothing visually. TextStyle.copy() allocates a
// new immutable style for identical values, so the "CONNECTING" label style is
// built once as well.
// ═══════════════════════════════════════════════════════════════════

private val CONNECTION_BUTTON_BACKGROUND = Brush.radialGradient(
    colors = listOf(
        OnionRouteColors.Surface2,
        OnionRouteColors.Surface1
    )
)

private val SPEED_GRAPH_FILL_GRADIENT = Brush.verticalGradient(
    colors = listOf(
        OnionRouteColors.Jade.copy(alpha = 0.15f),
        OnionRouteColors.Jade.copy(alpha = 0.02f)
    )
)

private val SPEED_GRAPH_LINE_COLOR = OnionRouteColors.Jade.copy(alpha = 0.6f)

private val CONNECTING_LABEL_STYLE = OnionRouteTypography.LabelMedium.copy(
    color = OnionRouteColors.StatusAmber
)

private val POWER_ICON_SIZE = 72.dp

// Static arc geometry. The connecting spin comes from a rotating graphicsLayer,
// so the drawn arc itself never has to change.
private const val CONNECTING_ARC_START_ANGLE = 0f
private const val CONNECTING_ARC_SWEEP_ANGLE = 270f
private const val CONNECTED_ARC_START_ANGLE = 0f
private const val CONNECTED_ARC_SWEEP_ANGLE = 360f
private const val DISCONNECTED_ARC_START_ANGLE = 135f
private const val DISCONNECTED_ARC_SWEEP_ANGLE = 270f

// The "CONNECTING…" label cycles through 0..MAX_CONNECTING_DOTS dots. The
// animation targets MAX + 1 because toInt() truncates: targeting MAX itself
// would top out at MAX - 1 dots and the last dot could never be drawn.
private const val MAX_CONNECTING_DOTS = 3

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
            .verticalScroll(rememberScrollState())
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

        Spacer(modifier = Modifier.height(32.dp))

        // ── Mode explainer card ──────────────────────────────────
        // Explains the difference between Normal Tor Connect (just tap the
        // power button) and VPN Mode (toggle the VPN chip after connecting).
        // Many users were confused about which apps get anonymized by each
        // mode — this card makes it clear at the bottom of the home screen.
        ModeExplainerCard(isVpnActive = appState.isVpnMode)

        // Bottom padding so the last toggle row is reachable above the
        // gesture nav bar. Previously used Spacer(weight=1f) which is a
        // no-op inside a vertically scrollable column.
        Spacer(modifier = Modifier.height(48.dp))
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

    // PERF: the pulsing infinite transition previously existed unconditionally,
    // driving continuous recomposition/redraw even in DISCONNECTED state where
    // its value was never used. It's now composed ONLY while connecting, so an
    // idle home screen costs zero animation frames.
    //
    // PERF: the pulse used to animate Modifier.size() directly — that forced a
    // full measure/layout pass of this whole scrollable Column every frame.
    // The State is now read DEFERRED inside the graphicsLayer lambda: the
    // animation updates only layer properties (draw phase) with zero
    // recomposition and zero re-layout.
    val pulseScaleState = if (isConnecting) {
        rememberInfiniteTransition(label = "pulse").animateFloat(
            initialValue = 0.95f,
            targetValue = 1.05f,
            animationSpec = infiniteRepeatable(
                animation = tween(800, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulse_scale"
        )
    } else {
        null
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(200.dp)
            .graphicsLayer {
                val s = pulseScaleState?.value ?: 1f
                scaleX = s
                scaleY = s
            }
            .shadow(
                elevation = if (isConnected || isConnecting) 24.dp else 0.dp,
                shape = CircleShape,
                ambientColor = buttonColor.copy(alpha = glowAlpha),
                spotColor = buttonColor.copy(alpha = glowAlpha)
            )
            .clip(CircleShape)
            .background(brush = CONNECTION_BUTTON_BACKGROUND)
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
    // PERF: the spinning transition is now created ONLY while connecting.
    // The previous version created an unconditional infinite transition,
    // keeping an animation clock ticking (and the canvas redrawing at 60fps)
    // even when the app was idle or connected. Compose disposes the branch's
    // transition as soon as isConnecting flips to false.
    //
    // PERF: the rotation is kept as a State and never read in the composable
    // body. Reading .value there invalidated PowerIcon itself 60 times a second
    // — a fresh Canvas draw lambda plus a measure/layout of the icon on every
    // single frame of a 30-90s Tor bootstrap. It is read DEFERRED inside the
    // graphicsLayer lambda below, so the animation only updates a layer
    // property: draw phase, zero recomposition, zero re-layout.
    val rotationState = if (isConnecting) {
        val infiniteTransition = rememberInfiniteTransition(label = "power_pulse")
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(2000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "rotation"
        )
    } else {
        null
    }

    Box(modifier = Modifier.size(POWER_ICON_SIZE)) {
        // Arc layer. The spin lives in the layer's rotationZ rather than in a
        // per-frame startAngle passed to drawArc, so the arc geometry stays
        // constant. Confining the layer to this node also leaves the power line
        // below upright — rotating the whole icon would swing the line around,
        // which is not what the old startAngle-based spin drew.
        Canvas(
            modifier = Modifier
                .size(POWER_ICON_SIZE)
                .graphicsLayer { rotationState?.let { rotationZ = it.value } }
        ) {
            val strokeWidth = 4.dp.toPx()
            val radius = (size.minDimension - strokeWidth) / 2
            val center = Offset(size.width / 2, size.height / 2)

            if (isConnecting) {
                // Spinning arc for connecting state
                drawArc(
                    color = color,
                    startAngle = CONNECTING_ARC_START_ANGLE,
                    sweepAngle = CONNECTING_ARC_SWEEP_ANGLE,
                    useCenter = false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(radius * 2, radius * 2),
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            } else {
                // Full circle for connected, partial for disconnected
                val sweepAngle =
                    if (isConnected) CONNECTED_ARC_SWEEP_ANGLE else DISCONNECTED_ARC_SWEEP_ANGLE
                val startAngle =
                    if (isConnected) CONNECTED_ARC_START_ANGLE else DISCONNECTED_ARC_START_ANGLE
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
        }

        // Power line (vertical gap at top)
        Canvas(modifier = Modifier.size(POWER_ICON_SIZE)) {
            val strokeWidth = 4.dp.toPx()
            val radius = (size.minDimension - strokeWidth) / 2
            val center = Offset(size.width / 2, size.height / 2)
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
                AnimatedConnectingText(progress = state.progress)
            }
            is ConnectionState.Connected -> {
                Text(
                    text = "CONNECTED",
                    style = OnionRouteTypography.LabelMedium.copy(
                        color = OnionRouteColors.Jade
                    )
                )
                // Show the current Tor exit IP (updated every 15s by TorService).
                // Empty until the first successful fetch completes (usually within
                // a few seconds of CONNECTED — see startExitIpUpdates()).
                if (appState.exitIp.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Exit: ${appState.exitIp}",
                        style = OnionRouteTypography.LabelSmall.copy(
                            color = OnionRouteColors.TextSecondary
                        )
                    )
                } else {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Resolving exit IP…",
                        style = OnionRouteTypography.LabelSmall.copy(
                            color = OnionRouteColors.TextTertiary
                        )
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                // Links to the Circuit Viewer and Connection Health dashboard.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "VIEW CIRCUITS \u2192",
                        fontSize = 11.sp,
                        fontFamily = SyneFontFamily,
                        color = OnionRouteColors.Jade,
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { appState.navigateTo(Screen.Circuits) }
                    )
                    Text(
                        text = "HEALTH \u2192",
                        fontSize = 11.sp,
                        fontFamily = SyneFontFamily,
                        color = OnionRouteColors.Jade,
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { appState.navigateTo(Screen.Health) }
                    )
                }
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
fun AnimatedConnectingText(progress: Int = 0) {
    // PERF: the dots are only shown when there is no bootstrap progress yet —
    // once progress arrives the label becomes "CONNECTING n%". The transition
    // used to exist unconditionally, so it kept the frame clock ticking at 60fps
    // and still built a discarded String (plus a TextStyle.copy) on every frame
    // for the whole 30-90s bootstrap. No transition, no frames.
    val showDots = progress <= 0

    val dotCountState = if (showDots) {
        val infiniteTransition = rememberInfiniteTransition(label = "dots")
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = (MAX_CONNECTING_DOTS + 1).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(1200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "dot_count"
        )
    } else {
        null
    }

    // Only four distinct dot counts exist, so the String is rebuilt on a change
    // of count instead of once per animation frame.
    val dotCount = dotCountState?.value?.toInt() ?: 0
    val dots = remember(dotCount) {
        ".".repeat(dotCount.coerceIn(0, MAX_CONNECTING_DOTS))
    }

    Text(
        text = if (showDots) "CONNECTING$dots" else "CONNECTING $progress%",
        style = CONNECTING_LABEL_STYLE
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

    // PERF: drawWithCache instead of a Canvas draw block. The two Paths, the
    // max/stepX math and the Stroke are built when the data (or the size)
    // changes; the previous version rebuilt all of them — plus a List<Color>
    // and a native gradient shader — on every draw, and the gradient Brush was
    // recreated on every recomposition as well.
    Spacer(
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
            .drawWithCache {
                // Need at least 2 data points to draw a meaningful line.
                if (dataPoints.size < 2) {
                    return@drawWithCache onDrawBehind { }
                }

                val maxSpeed = dataPoints.maxOrNull()?.coerceAtLeast(1f) ?: 1f
                // Safe: dataPoints.size >= 2, so (size - 1) >= 1
                val stepX = size.width / (dataPoints.size - 1)

                // Draw filled area
                val fillPath = Path().apply {
                    moveTo(0f, size.height)
                    dataPoints.forEachIndexed { index, speed ->
                        val x = index * stepX
                        val y = size.height - (speed / maxSpeed) * size.height * 0.8f
                        if (index == 0) moveTo(x, y) else lineTo(x, y)
                    }
                    lineTo(size.width, size.height)
                    close()
                }

                // Draw line
                val linePath = Path().apply {
                    dataPoints.forEachIndexed { index, speed ->
                        val x = index * stepX
                        val y = size.height - (speed / maxSpeed) * size.height * 0.8f
                        if (index == 0) moveTo(x, y) else lineTo(x, y)
                    }
                }

                val lineStroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)

                onDrawBehind {
                    drawPath(
                        path = fillPath,
                        brush = SPEED_GRAPH_FILL_GRADIENT
                    )
                    drawPath(
                        path = linePath,
                        color = SPEED_GRAPH_LINE_COLOR,
                        style = lineStroke
                    )
                }
            }
    )
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

// ═══════════════════════════════════════════════════════════════════
// Mode Explainer Card
// ═══════════════════════════════════════════════════════════════════

/**
 * Card explaining the difference between the two connection modes:
 * - Normal Tor Connect (just tap the power button): only Tor-aware apps
 *   that are manually configured to use SOCKS5 127.0.0.1:9050 are
 *   anonymized. Other apps connect directly with your real IP.
 * - VPN Mode (toggle the VPN chip after connecting): ALL device traffic
 *   is routed through Tor via a TUN interface + tun2socks. No per-app
 *   configuration needed; your real IP is hidden from every app.
 *
 * Placed at the bottom of HomeScreen so users see it after the quick
 * toggles. The card visually highlights which mode is currently active
 * (or neither, if just connected without VPN).
 */
@Composable
fun ModeExplainerCard(isVpnActive: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(OnionRouteColors.Surface1)
            .border(
                width = 1.dp,
                color = OnionRouteColors.BorderSubtle,
                shape = RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Text(
            text = "HOW CONNECTING WORKS",
            style = OnionRouteTypography.SectionHeader,
            modifier = Modifier.padding(bottom = 10.dp)
        )

        // Normal mode row
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier.padding(bottom = 10.dp)
        ) {
            // Indicator dot
            Box(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        if (!isVpnActive) OnionRouteColors.Jade
                        else OnionRouteColors.TextTertiary
                    )
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = if (!isVpnActive) "Normal Connect (active)" else "Normal Connect",
                    style = OnionRouteTypography.LabelMedium.copy(
                        color = if (!isVpnActive) OnionRouteColors.Jade
                                else OnionRouteColors.TextPrimary
                    )
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Tor SOCKS5 runs on 127.0.0.1:9050. Apps must be " +
                           "manually configured to use that proxy. Apps that " +
                           "don't → connect with your real IP.",
                    style = OnionRouteTypography.LabelSmall.copy(
                        color = OnionRouteColors.TextSecondary,
                        lineHeight = 16.sp
                    )
                )
            }
        }

        // VPN mode row
        Row(verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        if (isVpnActive) OnionRouteColors.Jade
                        else OnionRouteColors.TextTertiary
                    )
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = if (isVpnActive) "VPN Mode (active)" else "VPN Mode",
                    style = OnionRouteTypography.LabelMedium.copy(
                        color = if (isVpnActive) OnionRouteColors.Jade
                                else OnionRouteColors.TextPrimary
                    )
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "All device traffic is routed through Tor via a " +
                           "TUN interface. Every app is anonymized automatically " +
                           "(no per-app config). Toggle the VPN chip above to enable.",
                    style = OnionRouteTypography.LabelSmall.copy(
                        color = OnionRouteColors.TextSecondary,
                        lineHeight = 16.sp
                    )
                )
            }
        }
    }
}
