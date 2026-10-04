package org.onionroute.lite.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.onionroute.lite.service.KindnessService
import org.onionroute.lite.service.KindnessStats
import org.onionroute.lite.ui.OnionRouteAppState
import org.onionroute.lite.ui.Screen
import org.onionroute.lite.ui.theme.OnionRouteColors
import org.onionroute.lite.ui.theme.OnionRouteTypography

// ═══════════════════════════════════════════════════════════════════
// Hoisted paint / geometry constants
//
// PERF: these used to be rebuilt on every draw or recomposition. The radial
// gradients allocate a List<Color>, box the Colors and build a native shader;
// the branch directions were a listOf(-1, 1) allocated inside the canvas draw
// block, i.e. six List<Int> per drawn frame on top of 24 Offsets and 24
// trig calls. Everything here is now created once at class load.
// ═══════════════════════════════════════════════════════════════════

private val KINDNESS_ACTIVE_GRADIENT = Brush.radialGradient(
    colors = listOf(OnionRouteColors.JadeGlow, OnionRouteColors.Surface1)
)

private val KINDNESS_INACTIVE_GRADIENT = Brush.radialGradient(
    colors = listOf(OnionRouteColors.Surface2, OnionRouteColors.Surface1)
)

private const val STATUS_INDICATOR_SIZE_DP = 140
private val STATUS_INDICATOR_SIZE = STATUS_INDICATOR_SIZE_DP.dp

// Snowflake geometry: 6 arms, each with 2 short branches.
private const val SNOWFLAKE_ARM_COUNT = 6
private const val SNOWFLAKE_ARM_ANGLE_DEGREES = 60.0
private const val SNOWFLAKE_BRANCH_ORIGIN = 0.6f
private const val SNOWFLAKE_BRANCH_LENGTH = 0.3f
private const val SNOWFLAKE_BRANCH_ANGLE_RADIANS = 0.5
private val SNOWFLAKE_BRANCH_DIRECTIONS = floatArrayOf(-1f, 1f)

/**
 * Kindness Mode stats screen — shows real-time Snowflake proxy statistics.
 *
 * Displays:
 * - Data donated (bytes relayed through the proxy)
 * - People being helped right now (active client connections)
 * - Total people helped (cumulative client connections)
 * - Session uptime
 * - Animated proxy status indicator
 */
@Composable
fun KindnessScreen(appState: OnionRouteAppState) {
    val context = LocalContext.current

    // Load persisted stats on first render
    val savedStats = remember { KindnessService.getStats(context) }
    val dataDonated = if (appState.kindnessDataDonated > 0) appState.kindnessDataDonated else savedStats.dataDonated
    val peopleNow = if (appState.kindnessPeopleNow > 0) appState.kindnessPeopleNow else savedStats.peopleNow
    val peopleTotal = if (appState.kindnessPeopleTotal > 0) appState.kindnessPeopleTotal else savedStats.peopleTotal
    val sessionStart = if (appState.kindnessSessionStart > 0) appState.kindnessSessionStart else savedStats.sessionStart

    val isActive = appState.isKindnessEnabled

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
                fontFamily = org.onionroute.lite.ui.theme.SyneFontFamily,
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { appState.navigateBack() }
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = "KINDNESS MODE",
                style = OnionRouteTypography.HeadlineMedium.copy(color = OnionRouteColors.TextPrimary)
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        // ── Proxy Status Indicator ────────────────────────────────
        ProxyStatusIndicator(isActive = isActive)

        Spacer(modifier = Modifier.height(32.dp))

        // ── Main Stats Cards ──────────────────────────────────────
        StatCard(
            label = "DATA DONATED",
            value = formatBytes(dataDonated),
            subtitle = "Total bandwidth shared with censored users",
            isActive = isActive
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatCard(
                label = "HELPING NOW",
                value = "$peopleNow",
                subtitle = "Active connections",
                isActive = isActive,
                modifier = Modifier.weight(1f)
            )
            StatCard(
                label = "PEOPLE HELPED",
                value = "$peopleTotal",
                subtitle = "Total connections served",
                isActive = isActive,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // ── Session Info ──────────────────────────────────────────
        if (sessionStart > 0) {
            val uptime = System.currentTimeMillis() - sessionStart
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(OnionRouteColors.Surface2)
                    .border(1.dp, OnionRouteColors.BorderSubtle, RoundedCornerShape(12.dp))
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "SESSION UPTIME",
                            style = OnionRouteTypography.SectionHeader
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = formatUptime(uptime),
                            style = OnionRouteTypography.LabelMedium.copy(
                                color = if (isActive) OnionRouteColors.Jade else OnionRouteColors.TextSecondary
                            )
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "STATUS",
                            style = OnionRouteTypography.SectionHeader
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (isActive) "ACTIVE" else "STOPPED",
                            style = OnionRouteTypography.LabelSmall.copy(
                                color = if (isActive) OnionRouteColors.Jade else OnionRouteColors.StatusRed,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // ── Info Card ─────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(OnionRouteColors.Surface1)
                .border(1.dp, OnionRouteColors.BorderSubtle, RoundedCornerShape(12.dp))
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Column {
                Text(
                    text = "HOW IT WORKS",
                    style = OnionRouteTypography.SectionHeader.copy(
                        color = OnionRouteColors.Jade
                    )
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "When Kindness Mode is active, your device acts as a Snowflake proxy. Censored users around the world discover your proxy through the Tor Project broker and route their Tor traffic through your connection via WebRTC. This uses minimal bandwidth and helps people in censored regions access the free internet.",
                    style = OnionRouteTypography.LabelSmall.copy(
                        color = OnionRouteColors.TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Your IP address is never exposed to the websites visited by the people you help. The proxy only relays encrypted Tor traffic between the client and the Tor network.",
                    style = OnionRouteTypography.LabelSmall.copy(
                        color = OnionRouteColors.TextTertiary,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // ── Toggle Button ─────────────────────────────────────────
        KindnessToggleButton(
            isActive = isActive,
            onToggle = { appState.updateKindness(!isActive) }
        )

        Spacer(modifier = Modifier.height(24.dp))
    }
}

// ═══════════════════════════════════════════════════════════════════
// Proxy Status Indicator
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun ProxyStatusIndicator(isActive: Boolean) {
    // PERF: the pulsing transition is composed ONLY while the proxy is active.
    // It used to be created unconditionally, so the InfiniteTransition stayed
    // registered with the frame clock even when its value was multiplied by 1f
    // and discarded — an idle "PROXY INACTIVE" screen still requested a vsync
    // frame 60 times a second. Compose disposes the branch's transition as
    // soon as isActive flips to false.
    val pulseScaleState = if (isActive) {
        val infiniteTransition = rememberInfiniteTransition(label = "kindness_pulse")
        infiniteTransition.animateFloat(
            initialValue = 0.92f,
            targetValue = 1.08f,
            animationSpec = infiniteRepeatable(
                animation = tween(1500, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulse"
        )
    } else {
        null
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(STATUS_INDICATOR_SIZE)
                // PERF: the pulse used to animate Modifier.size(), forcing a full
                // measure+layout pass of the whole verticalScroll Column on every
                // frame. The box is now a fixed size and the State is read DEFERRED
                // inside the graphicsLayer lambda, so the breathing scales the
                // layer in the draw phase with zero recomposition and zero
                // re-layout.
                .graphicsLayer {
                    val scale = pulseScaleState?.value ?: 1f
                    scaleX = scale
                    scaleY = scale
                }
                .clip(CircleShape)
                .background(
                    brush = if (isActive) {
                        KINDNESS_ACTIVE_GRADIENT
                    } else {
                        KINDNESS_INACTIVE_GRADIENT
                    }
                )
                .border(
                    width = 2.dp,
                    color = if (isActive) OnionRouteColors.Jade else OnionRouteColors.BorderMid,
                    shape = CircleShape
                )
        ) {
            // Snowflake-like icon. PERF: drawWithCache builds the 18 line
            // endpoints once per size change instead of on every drawn frame.
            Spacer(
                modifier = Modifier
                    .size(48.dp)
                    .drawWithCache {
                        val center = Offset(size.width / 2, size.height / 2)
                        val radius = size.minDimension / 2 - 4.dp.toPx()
                        val stroke = 2.5.dp.toPx()
                        val branchLength = radius * SNOWFLAKE_BRANCH_LENGTH
                        val armColor =
                            if (isActive) OnionRouteColors.Jade else OnionRouteColors.BorderMid
                        val branchColor = if (isActive) {
                            OnionRouteColors.Jade.copy(alpha = 0.6f)
                        } else {
                            OnionRouteColors.BorderMid.copy(alpha = 0.5f)
                        }

                        // PERF: the 6 arm ends and 12 branch segments are laid
                        // out here (once per size change) rather than allocating
                        // a listOf(-1, 1) plus 24 Offsets on every frame of the
                        // pulse. The draw block below only reads these arrays.
                        val branchSegmentCount =
                            SNOWFLAKE_ARM_COUNT * SNOWFLAKE_BRANCH_DIRECTIONS.size
                        val armEnds = Array(SNOWFLAKE_ARM_COUNT) { Offset.Zero }
                        val branchStarts = Array(branchSegmentCount) { Offset.Zero }
                        val branchEnds = Array(branchSegmentCount) { Offset.Zero }

                        var segment = 0
                        for (arm in 0 until SNOWFLAKE_ARM_COUNT) {
                            val angle = Math.toRadians((arm * SNOWFLAKE_ARM_ANGLE_DEGREES))
                            // Draw 6 lines radiating from center (snowflake)
                            armEnds[arm] = Offset(
                                center.x + radius * kotlin.math.cos(angle).toFloat(),
                                center.y + radius * kotlin.math.sin(angle).toFloat()
                            )
                            // Small branches
                            val branchX = center.x +
                                radius * SNOWFLAKE_BRANCH_ORIGIN * kotlin.math.cos(angle).toFloat()
                            val branchY = center.y +
                                radius * SNOWFLAKE_BRANCH_ORIGIN * kotlin.math.sin(angle).toFloat()
                            for (direction in SNOWFLAKE_BRANCH_DIRECTIONS) {
                                val branchAngle = angle + direction * SNOWFLAKE_BRANCH_ANGLE_RADIANS
                                branchStarts[segment] = Offset(branchX, branchY)
                                branchEnds[segment] = Offset(
                                    branchX + branchLength *
                                        kotlin.math.cos(branchAngle).toFloat(),
                                    branchY + branchLength *
                                        kotlin.math.sin(branchAngle).toFloat()
                                )
                                segment++
                            }
                        }

                        val branchStroke = stroke * 0.7f
                        val dotRadius = 4.dp.toPx()

                        onDrawBehind {
                            for (arm in 0 until armEnds.size) {
                                drawLine(
                                    color = armColor,
                                    start = center,
                                    end = armEnds[arm],
                                    strokeWidth = stroke,
                                    cap = StrokeCap.Round
                                )
                            }
                            for (branch in 0 until branchEnds.size) {
                                drawLine(
                                    color = branchColor,
                                    start = branchStarts[branch],
                                    end = branchEnds[branch],
                                    strokeWidth = branchStroke,
                                    cap = StrokeCap.Round
                                )
                            }
                            // Center dot
                            drawCircle(
                                color = armColor,
                                radius = dotRadius,
                                center = center
                            )
                        }
                    }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = if (isActive) "PROXY ACTIVE" else "PROXY INACTIVE",
            style = OnionRouteTypography.LabelMedium.copy(
                color = if (isActive) OnionRouteColors.Jade else OnionRouteColors.TextTertiary,
                fontSize = 14.sp
            )
        )
    }
}

// ═══════════════════════════════════════════════════════════════════
// Stat Card
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun StatCard(
    label: String,
    value: String,
    subtitle: String,
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (isActive) OnionRouteColors.JadeGlow else OnionRouteColors.Surface2)
            .border(
                width = 1.dp,
                color = if (isActive) OnionRouteColors.Jade.copy(alpha = 0.3f) else OnionRouteColors.BorderSubtle,
                shape = RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Column {
            Text(
                text = label,
                style = OnionRouteTypography.SectionHeader.copy(
                    color = if (isActive) OnionRouteColors.Jade else OnionRouteColors.TextTertiary
                )
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = value,
                style = OnionRouteTypography.LabelLarge.copy(
                    color = if (isActive) OnionRouteColors.Jade else OnionRouteColors.TextSecondary,
                    fontSize = 28.sp
                )
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = OnionRouteTypography.LabelSmall.copy(
                    color = OnionRouteColors.TextSecondary,
                    fontSize = 10.sp
                )
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// Kindness Toggle Button
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun KindnessToggleButton(isActive: Boolean, onToggle: () -> Unit) {
    val bgColor by animateColorAsState(
        targetValue = if (isActive) OnionRouteColors.StatusRed.copy(alpha = 0.15f) else OnionRouteColors.JadeGlow,
        animationSpec = tween(300),
        label = "toggle_bg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isActive) OnionRouteColors.StatusRed.copy(alpha = 0.5f) else OnionRouteColors.Jade.copy(alpha = 0.5f),
        animationSpec = tween(300),
        label = "toggle_border"
    )
    val textColor by animateColorAsState(
        targetValue = if (isActive) OnionRouteColors.StatusRed else OnionRouteColors.Jade,
        animationSpec = tween(300),
        label = "toggle_text"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onToggle() }
            .padding(horizontal = 20.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (isActive) "STOP KINDNESS MODE" else "START KINDNESS MODE",
            style = OnionRouteTypography.LabelMedium.copy(
                color = textColor,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
        )
    }
}

// ═══════════════════════════════════════════════════════════════════
// Formatters
// ═══════════════════════════════════════════════════════════════════

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> "%.2f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes > 0 -> "$bytes B"
    else -> "0 B"
}

private fun formatUptime(ms: Long): String {
    val seconds = ms / 1000
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m ${secs}s"
        minutes > 0 -> "${minutes}m ${secs}s"
        else -> "${secs}s"
    }
}
