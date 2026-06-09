package org.onionroute.lite.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.onionroute.lite.ui.OnionRouteAppState
import org.onionroute.lite.ui.theme.*

@Composable
fun BandwidthScreen(appState: OnionRouteAppState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(OnionRouteColors.Black)
            .padding(horizontal = 24.dp)
    ) {
        Spacer(modifier = Modifier.height(48.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "←",
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
                text = "BANDWIDTH",
                style = OnionRouteTypography.HeadlineMedium.copy(color = OnionRouteColors.TextPrimary)
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Current speed
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            MetricCard(label = "DOWNLOAD", value = appState.downloadSpeed, modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.width(12.dp))
            MetricCard(label = "UPLOAD", value = appState.uploadSpeed, modifier = Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Totals
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            MetricCard(label = "TOTAL DOWN", value = appState.totalDownloaded, modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.width(12.dp))
            MetricCard(label = "TOTAL UP", value = appState.totalUploaded, modifier = Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Speed graph
        Text(text = "SPEED HISTORY", style = OnionRouteTypography.SectionHeader)
        Spacer(modifier = Modifier.height(8.dp))

        val dataPoints = appState.speedDataPoints
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(OnionRouteColors.Surface1)
                .border(1.dp, OnionRouteColors.BorderSubtle, RoundedCornerShape(8.dp))
        ) {
            if (dataPoints.isEmpty()) return@Canvas
            val maxSpeed = dataPoints.maxOrNull()?.coerceAtLeast(1f) ?: 1f
            val stepX = size.width / (dataPoints.size - 1).coerceAtLeast(1)

            // Filled area
            val path = Path().apply {
                moveTo(0f, size.height)
                dataPoints.forEachIndexed { index, speed ->
                    val x = index * stepX
                    val y = size.height - (speed / maxSpeed) * size.height * 0.8f
                    lineTo(x, y)
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

            // Line
            val linePath = Path().apply {
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
}

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(OnionRouteColors.Surface2)
            .border(1.dp, OnionRouteColors.BorderSubtle, RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        Column {
            Text(text = label, style = OnionRouteTypography.SectionHeader)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = value,
                style = OnionRouteTypography.LabelLarge.copy(color = OnionRouteColors.Jade)
            )
        }
    }
}
