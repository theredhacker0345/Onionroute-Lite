package org.onionroute.lite.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
    val infiniteTransition = rememberInfiniteTransition(label = "kindness_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val scale = if (isActive) pulseScale else 1f

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size((140.dp * scale))
                .clip(CircleShape)
                .background(
                    brush = Brush.radialGradient(
                        colors = if (isActive) {
                            listOf(OnionRouteColors.JadeGlow, OnionRouteColors.Surface1)
                        } else {
                            listOf(OnionRouteColors.Surface2, OnionRouteColors.Surface1)
                        }
                    )
                )
                .border(
                    width = 2.dp,
                    color = if (isActive) OnionRouteColors.Jade else OnionRouteColors.BorderMid,
                    shape = CircleShape
                )
        ) {
            // Snowflake-like icon using Canvas
            Canvas(modifier = Modifier.size(48.dp)) {
                val center = Offset(size.width / 2, size.height / 2)
                val radius = size.minDimension / 2 - 4.dp.toPx()
                val stroke = 2.5.dp.toPx()

                // Draw 6 lines radiating from center (snowflake)
                for (i in 0 until 6) {
                    val angle = Math.toRadians((i * 60.0))
                    val endX = center.x + radius * kotlin.math.cos(angle).toFloat()
                    val endY = center.y + radius * kotlin.math.sin(angle).toFloat()
                    drawLine(
                        color = if (isActive) OnionRouteColors.Jade else OnionRouteColors.BorderMid,
                        start = center,
                        end = Offset(endX, endY),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round
                    )
                    // Small branches
                    val branchLen = radius * 0.3f
                    val branchStart = 0.6f
                    for (dir in listOf(-1, 1)) {
                        val bx = center.x + radius * branchStart * kotlin.math.cos(angle).toFloat()
                        val by = center.y + radius * branchStart * kotlin.math.sin(angle).toFloat()
                        val bAngle = angle + dir * 0.5
                        val bEndX = bx + branchLen * kotlin.math.cos(bAngle).toFloat()
                        val bEndY = by + branchLen * kotlin.math.sin(bAngle).toFloat()
                        drawLine(
                            color = if (isActive) OnionRouteColors.Jade.copy(alpha = 0.6f) else OnionRouteColors.BorderMid.copy(alpha = 0.5f),
                            start = Offset(bx, by),
                            end = Offset(bEndX, bEndY),
                            strokeWidth = stroke * 0.7f,
                            cap = StrokeCap.Round
                        )
                    }
                }
                // Center dot
                drawCircle(
                    color = if (isActive) OnionRouteColors.Jade else OnionRouteColors.BorderMid,
                    radius = 4.dp.toPx(),
                    center = center
                )
            }
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
