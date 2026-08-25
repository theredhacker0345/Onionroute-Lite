package org.onionroute.lite.ui.screens

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.onionroute.lite.ui.OnionRouteAppState
import org.onionroute.lite.ui.theme.ConnectionState
import org.onionroute.lite.ui.theme.OnionRouteColors
import org.onionroute.lite.ui.theme.OnionRouteTypography
import org.onionroute.lite.ui.theme.SyneFontFamily

/**
 * Connection Health Dashboard.
 *
 * Shows round-trip latency of REAL requests through the full Tor path
 * (app → SOCKS → guard → middle → exit → destination), sampled every ~15s by
 * TorService's exit-IP poller. No extra network traffic is generated — each
 * sample simply times the existing probe request.
 */
@Composable
fun HealthScreen(appState: OnionRouteAppState) {
    val samples = appState.healthPoints
    // -1 when there are no samples yet (or when the last probe failed).
    val latest = samples.lastOrNull() ?: -1L

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(OnionRouteColors.Black)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
    ) {
        Spacer(modifier = Modifier.height(48.dp))

        // ── Header ──────────────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically) {
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
                text = "CONNECTION HEALTH",
                style = OnionRouteTypography.HeadlineMedium.copy(color = OnionRouteColors.TextPrimary)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Round-trip latency through the Tor network, sampled every 15s",
            style = OnionRouteTypography.LabelSmall.copy(color = OnionRouteColors.TextSecondary)
        )

        Spacer(modifier = Modifier.height(24.dp))

        if (samples.isEmpty()) {
            val hint = when (appState.connectionState) {
                is ConnectionState.Connected -> "Collecting first sample\u2026"
                else -> "Connect to Tor to start measuring connection health."
            }
            Text(
                text = hint,
                style = OnionRouteTypography.BodyMedium.copy(color = OnionRouteColors.TextSecondary),
                modifier = Modifier.padding(vertical = 32.dp)
            )
        } else {
            // ── Current status card ─────────────────────────────
            val statusColor = healthColor(latest)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(OnionRouteColors.Surface2)
                    .border(1.dp, statusColor.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                    .padding(20.dp)
            ) {
                Column {
                    Text(
                        text = healthLabel(latest),
                        style = OnionRouteTypography.SectionHeader.copy(color = statusColor)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (latest < 0) "last probe failed" else "$latest ms",
                        style = OnionRouteTypography.HeadlineMedium.copy(color = OnionRouteColors.TextPrimary)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MetricChip("SAMPLES", "${samples.size}", Modifier.weight(1f))
                val okSamples = samples.filter { it >= 0 }
                MetricChip(
                    "AVG",
                    if (okSamples.isEmpty()) "\u2014" else "${okSamples.average().toInt()} ms",
                    Modifier.weight(1f)
                )
                val reliability = if (samples.isEmpty()) 0 else okSamples.size * 100 / samples.size
                MetricChip("RELIABILITY", "$reliability%", Modifier.weight(1f))
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ── Latency history graph (lower = better) ──────────
            Text(text = "LATENCY HISTORY", style = OnionRouteTypography.SectionHeader)
            Spacer(modifier = Modifier.height(8.dp))

            LatencyGraph(samples = samples)

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Excellent < 1s \u00b7 Good < 2.5s \u00b7 Slow < 5s \u00b7 red dots = failed probe",
                style = OnionRouteTypography.LabelSmall.copy(color = OnionRouteColors.TextTertiary)
            )
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

/**
 * Latency sparkline. The y-axis is clamped to the ~90th percentile of samples
 * (max 10s) so one outlier spike doesn't flatten all other data. Failed probes
 * render as red dots along the bottom and break the connecting line.
 */
@Composable
private fun LatencyGraph(samples: List<Long>) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(OnionRouteColors.Surface1)
            .border(1.dp, OnionRouteColors.BorderSubtle, RoundedCornerShape(8.dp))
    ) {
        if (samples.size < 2) return@Canvas

        val validSorted = samples.filter { it >= 0 }.sorted()
        if (validSorted.isEmpty()) return@Canvas
        val scaleMax = minOf(
            validSorted[validSorted.size * 9 / 10].toFloat(),
            10_000f
        ).coerceAtLeast(100f)

        val stepX = size.width / (samples.size - 1)

        fun yFor(ms: Long): Float =
            size.height - ((ms.toFloat() / scaleMax).coerceIn(0f, 1f) * size.height * 0.85f) - size.height * 0.05f

        fun xFor(i: Int): Float = i * stepX

        // Area fill under successful points
        val areaPath = Path().apply {
            moveTo(0f, size.height)
            var drewPoint = false
            samples.forEachIndexed { i, ms ->
                if (ms >= 0) {
                    lineTo(xFor(i), yFor(ms))
                    drewPoint = true
                }
            }
            if (drewPoint) lineTo(size.width, size.height)
            close()
        }
        drawPath(
            path = areaPath,
            brush = Brush.verticalGradient(
                colors = listOf(
                    OnionRouteColors.Jade.copy(alpha = 0.15f),
                    OnionRouteColors.Jade.copy(alpha = 0.02f)
                )
            )
        )

        // Line segments between consecutive successful points (broken by failures)
        var last: Offset? = null
        samples.forEachIndexed { i, ms ->
            if (ms >= 0) {
                val point = Offset(xFor(i), yFor(ms))
                val from = last
                if (from != null && i > 0 && samples[i - 1] >= 0) {
                    drawLine(
                        color = OnionRouteColors.Jade.copy(alpha = 0.7f),
                        start = from,
                        end = point,
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
                last = point
            } else {
                // Failure marker at the bottom edge
                drawCircle(
                    color = OnionRouteColors.StatusRed.copy(alpha = 0.85f),
                    radius = 3.dp.toPx(),
                    center = Offset(xFor(i), size.height - 6.dp.toPx())
                )
                last = null
            }
        }
    }
}

private fun healthLabel(latencyMs: Long): String = when {
    latencyMs < 0 -> "OFFLINE"
    latencyMs < 1_000 -> "EXCELLENT"
    latencyMs < 2_500 -> "GOOD"
    latencyMs < 5_000 -> "SLOW"
    else -> "POOR"
}

private fun healthColor(latencyMs: Long): Color = when {
    latencyMs < 0 -> OnionRouteColors.StatusRed
    latencyMs < 1_000 -> OnionRouteColors.Jade
    latencyMs < 5_000 -> OnionRouteColors.StatusAmber
    else -> OnionRouteColors.StatusRed
}

@Composable
private fun MetricChip(label: String, value: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(OnionRouteColors.Surface2)
            .border(1.dp, OnionRouteColors.BorderSubtle, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Column {
            Text(text = label, style = OnionRouteTypography.LabelSmall.copy(color = OnionRouteColors.TextTertiary))
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                style = OnionRouteTypography.BodyMedium.copy(color = OnionRouteColors.TextPrimary)
            )
        }
    }
}
