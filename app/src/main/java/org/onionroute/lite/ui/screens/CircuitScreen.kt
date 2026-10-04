package org.onionroute.lite.ui.screens

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.onionroute.lite.tor.TorCircuit
import org.onionroute.lite.ui.OnionRouteAppState
import org.onionroute.lite.ui.theme.ConnectionState
import org.onionroute.lite.ui.theme.OnionRouteColors
import org.onionroute.lite.ui.theme.OnionRouteTypography
import org.onionroute.lite.ui.theme.SyneFontFamily

/**
 * Circuit Viewer — shows the live Tor circuits (entry → middle → exit hops)
 * fetched from the control port's `GETINFO circuit-status`.
 *
 * Auto-refreshes every 5s while visible. Orbot removed this view; it's one
 * of the most-requested transparency features.
 */
@Composable
fun CircuitScreen(appState: OnionRouteAppState) {
    // Auto-refresh while the screen is composed. LaunchedEffect is cancelled
    // automatically when the user navigates away — no leaks.
    LaunchedEffect(Unit) {
        while (true) {
            appState.onRefreshCircuits?.invoke()
            delay(5000L)
        }
    }

    // Clock backing the per-card "age" labels.
    //
    // Bucketed to MINUTES_AGE_RESOLUTION rather than ticking every second.
    // nowMs was passed to all MAX_SHOWN cards, so each tick recomposed and
    // re-measured every visible card: ~20 layout passes plus per-card
    // buildList/joinToString/TextStyle.copy allocations, once a second, for as
    // long as the user watched the screen.
    //
    // Age is rendered in seconds below one minute, then minutes, then hours
    // and days. Quantising the clock to 30s means a sub-minute age label is at
    // most half a minute stale — imperceptible — while cutting the
    // recomposition rate by 30x. Anything coarser would visibly lag.
    var nowBucket by remember { mutableLongStateOf(System.currentTimeMillis() / AGE_BUCKET_MS) }
    LaunchedEffect(Unit) {
        while (true) {
            nowBucket = System.currentTimeMillis() / AGE_BUCKET_MS
            delay(AGE_BUCKET_MS)
        }
    }
    val nowMs = nowBucket * AGE_BUCKET_MS

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
                text = "CIRCUITS",
                style = OnionRouteTypography.HeadlineMedium.copy(color = OnionRouteColors.TextPrimary)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Live paths your traffic takes through the Tor network",
            style = OnionRouteTypography.LabelSmall.copy(color = OnionRouteColors.TextSecondary)
        )

        Spacer(modifier = Modifier.height(24.dp))

        val circuits = appState.circuits
        if (circuits.isEmpty()) {
            val hint = when (appState.connectionState) {
                is ConnectionState.Connected ->
                    "No circuit information yet — tor is still building circuits…"
                else ->
                    "Connect to Tor to see live circuits."
            }
            Text(
                text = hint,
                style = OnionRouteTypography.BodyMedium.copy(color = OnionRouteColors.TextSecondary),
                modifier = Modifier.padding(vertical = 32.dp)
            )
        } else {
            circuits.take(MAX_SHOWN).forEach { circuit ->
                CircuitCard(circuit = circuit, nowMs = nowMs)
                Spacer(modifier = Modifier.height(10.dp))
            }
            if (circuits.size > MAX_SHOWN) {
                Text(
                    text = "+ ${circuits.size - MAX_SHOWN} more internal circuits",
                    style = OnionRouteTypography.LabelSmall.copy(color = OnionRouteColors.TextTertiary),
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                )
            }
        }

        item0Spacer()
    }
}

private const val MAX_SHOWN = 20

/**
 * Resolution of the "age" clock, in ms.
 *
 * Bounds how stale an age label can be to 30s while cutting the per-card
 * recomposition rate from 1Hz to once per 30s. Sub-minute ages are shown in
 * whole seconds, so this is the trade between an imperceptible lag and 20
 * avoided re-layouts every second.
 */
private const val AGE_BUCKET_MS = 30_000L

/** Bottom scroll padding (kept as a helper for readability). */
@Composable
private fun item0Spacer() {
    Spacer(modifier = Modifier.height(32.dp))
}

@Composable
private fun CircuitCard(circuit: TorCircuit, nowMs: Long) {
    val statusColor = when (circuit.status) {
        "BUILT" -> OnionRouteColors.Jade
        "EXTENDED", "LAUNCHED" -> OnionRouteColors.StatusAmber
        "FAILED", "CLOSED" -> OnionRouteColors.StatusRed
        else -> OnionRouteColors.BorderMid
    }

    val ageText = if (circuit.timeCreatedMs > 0) {
        val ageSec = ((nowMs - circuit.timeCreatedMs) / 1000L).coerceAtLeast(0)
        when {
            ageSec < 60 -> "${ageSec}s ago"
            ageSec < 3600 -> "${ageSec / 60}m ago"
            else -> "${ageSec / 3600}h ago"
        }
    } else ""

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(OnionRouteColors.Surface2)
            .border(1.dp, OnionRouteColors.BorderSubtle, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (circuit.isActive) statusColor else statusColor.copy(alpha = 0.5f))
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "#${circuit.id}",
                    style = OnionRouteTypography.LabelMedium.copy(color = OnionRouteColors.TextSecondary)
                )
            }
            Text(
                text = circuit.status,
                fontSize = 11.sp,
                fontFamily = SyneFontFamily,
                color = statusColor
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (circuit.hops.isNotEmpty()) {
            Text(
                text = circuit.hops.joinToString("  \u2192  "),
                style = OnionRouteTypography.BodyMedium.copy(color = OnionRouteColors.TextPrimary)
            )
        } else {
            Text(
                text = "Building path\u2026",
                style = OnionRouteTypography.BodyMedium.copy(color = Color(0xFF6B7280))
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        val metaParts = buildList {
            add(circuit.purpose)
            if (ageText.isNotEmpty()) add(ageText)
        }
        Text(
            text = metaParts.joinToString("  \u00b7  "),
            style = OnionRouteTypography.LabelSmall.copy(color = OnionRouteColors.TextTertiary)
        )
    }
}
