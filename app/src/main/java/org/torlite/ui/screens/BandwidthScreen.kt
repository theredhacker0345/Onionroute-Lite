// === BandwidthScreen.kt ===
package org.torlite.ui.screens

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.torlite.R
import org.torlite.ui.theme.DmSansFontFamily
import org.torlite.ui.theme.JetBrainsMonoFontFamily
import org.torlite.ui.theme.PhantomColors
import org.torlite.ui.theme.PhantomTypography
import org.torlite.ui.theme.SyneFontFamily

// ═══════════════════════════════════════════════════════════
// BANDWIDTH MONITOR SCREEN
// ═══════════════════════════════════════════════════════════

@Composable
fun BandwidthScreen(
    downloadSpeed: String,
    uploadSpeed: String,
    totalDownload: String,
    totalUpload: String,
    sessionDuration: String,
    exitNode: String,
    entryGuard: String,
    speedHistory: List<Float>,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PhantomColors.Black)
    ) {
        // ── Top Bar ──────────────────────────────────────
        BandwidthTopBar(onBackClick = onBackClick)

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Session duration
            item {
                Text(
                    text = "Session: $sessionDuration",
                    style = PhantomTypography.LabelMedium.copy(
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 13.sp
                    ),
                    color = PhantomColors.TextSecondary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            // Line chart
            item {
                SpeedChart(
                    data = speedHistory,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                )
            }

            // Metric cards row
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MetricCard(
                        value = downloadSpeed,
                        label = "Download",
                        modifier = Modifier.weight(1f)
                    )
                    MetricCard(
                        value = uploadSpeed,
                        label = "Upload",
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Info grid
            item {
                InfoGrid(
                    entries = listOf(
                        "Total Downloaded" to totalDownload,
                        "Total Uploaded" to totalUpload,
                        "Entry Guard" to entryGuard,
                        "Exit Node" to exitNode
                    )
                )
            }

            // Bottom spacing
            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
// BANDWIDTH TOP BAR
// ═══════════════════════════════════════════════════════════

@Composable
private fun BandwidthTopBar(
    onBackClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, top = 12.dp, end = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onBackClick,
            modifier = Modifier.semantics { contentDescription = "Go back" }
        ) {
            Icon(
                imageVector = ImageVector.vectorResource(R.drawable.ic_back),
                contentDescription = null,
                tint = PhantomColors.TextSecondary,
                modifier = Modifier.size(24.dp)
            )
        }

        Text(
            text = "Bandwidth",
            style = PhantomTypography.HeadlineMedium.copy(
                fontFamily = SyneFontFamily,
                fontWeight = FontWeight.SemiBold
            ),
            color = PhantomColors.TextPrimary
        )
    }
}

// ═══════════════════════════════════════════════════════════
// SPEED CHART — Canvas-rendered line chart
// X-axis: last 60 data points, scrolling right-to-left
// Y-axis: auto-scaling, no grid lines, only max label
// Line: 2dp Jade, no fill
// ═══════════════════════════════════════════════════════════

@Composable
private fun SpeedChart(
    data: List<Float>,
    modifier: Modifier = Modifier
) {
    if (data.isEmpty()) {
        // Empty state
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier
                .clip(RoundedCornerShape(16.dp))
                .background(PhantomColors.Surface1)
                .border(
                    width = 1.dp,
                    color = PhantomColors.BorderSubtle,
                    shape = RoundedCornerShape(16.dp)
                )
                .semantics { contentDescription = "Speed chart: no data yet" }
        ) {
            Text(
                text = "Waiting for data...",
                style = PhantomTypography.LabelMedium.copy(
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = 13.sp
                ),
                color = PhantomColors.TextTertiary
            )
        }
        return
    }

    val maxValue = data.maxOrNull() ?: 0f
    val maxLabel = formatSpeedValue(maxValue)

    androidx.compose.foundation.Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(PhantomColors.Surface1)
            .border(
                width = 1.dp,
                color = PhantomColors.BorderSubtle,
                shape = RoundedCornerShape(16.dp)
            )
            .semantics { contentDescription = "Speed chart showing last 60 seconds" }
    ) {
        val chartPadding = 16.dp.toPx()
        val chartWidth = size.width - (chartPadding * 2)
        val chartHeight = size.height - (chartPadding * 2)

        if (chartWidth <= 0f || chartHeight <= 0f || data.isEmpty()) return@Canvas

        // Draw max value label (top-right)
        drawContext.canvas.nativeCanvas.drawText(
            maxLabel,
            size.width - chartPadding,
            chartPadding + 10.dp.toPx(),
            android.graphics.Paint().apply {
                color = android.graphics.Color.argb(
                    0x44, 0x44, 0x44, 0x44
                )
                textSize = 10.dp.toPx()
                typeface = android.graphics.Typeface.MONOSPACE
                textAlign = android.graphics.Paint.Align.RIGHT
            }
        )

        // Build the path
        val safeMax = if (maxValue > 0f) maxValue else 1f
        val visibleData = data.takeLast(60)
        val pointSpacing = if (visibleData.size > 1) chartWidth / (visibleData.size - 1) else chartWidth

        val path = Path()
        visibleData.forEachIndexed { index, value ->
            val x = chartPadding + (index * pointSpacing)
            val y = chartPadding + chartHeight - ((value / safeMax) * chartHeight)
            if (index == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }

        // Draw the line — 2dp Jade, no fill
        drawPath(
            path = path,
            color = PhantomColors.Jade,
            style = Stroke(
                width = 2.dp.toPx(),
                cap = StrokeCap.Round,
                join = androidx.compose.ui.graphics.StrokeJoin.Round
            )
        )
    }
}

// ═══════════════════════════════════════════════════════════
// METRIC CARD — Large value display
// ═══════════════════════════════════════════════════════════

@Composable
private fun MetricCard(
    value: String,
    label: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(PhantomColors.Surface2)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Split value and unit
        val parts = value.split(" ")
        val numPart = parts.getOrElse(0) { value }
        val unitPart = parts.getOrElse(1) { "" }

        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = numPart,
                style = PhantomTypography.LabelLarge.copy(
                    fontFamily = SyneFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 28.sp
                ),
                color = PhantomColors.TextPrimary
            )
            if (unitPart.isNotEmpty()) {
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = unitPart,
                    style = PhantomTypography.BodyMedium.copy(
                        fontFamily = DmSansFontFamily,
                        fontSize = 13.sp
                    ),
                    color = PhantomColors.TextTertiary,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = label,
            style = PhantomTypography.BodyMedium.copy(
                fontFamily = DmSansFontFamily,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            ),
            color = PhantomColors.TextSecondary
        )
    }
}

// ═══════════════════════════════════════════════════════════
// INFO GRID — Key-value rows in a card
// ═══════════════════════════════════════════════════════════

@Composable
private fun InfoGrid(
    entries: List<Pair<String, String>>
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(PhantomColors.Surface1)
            .border(
                width = 1.dp,
                color = PhantomColors.BorderSubtle,
                shape = RoundedCornerShape(16.dp)
            )
    ) {
        entries.forEachIndexed { index, (label, value) ->
            if (index > 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .height(0.5.dp)
                        .background(PhantomColors.BorderSubtle)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    style = PhantomTypography.BodyMedium.copy(
                        fontFamily = DmSansFontFamily,
                        fontSize = 14.sp
                    ),
                    color = PhantomColors.TextSecondary
                )

                Text(
                    text = value,
                    style = PhantomTypography.LabelMedium.copy(
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 13.sp
                    ),
                    color = PhantomColors.TextPrimary
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
// HELPERS
// ═══════════════════════════════════════════════════════════

private fun formatSpeedValue(bytesPerSec: Float): String {
    return when {
        bytesPerSec < 1024 -> "${bytesPerSec.toInt()} B/s"
        bytesPerSec < 1024 * 1024 -> "${(bytesPerSec / 1024).toInt()} KB/s"
        else -> String.format("%.1f MB/s", bytesPerSec / (1024 * 1024))
    }
}
