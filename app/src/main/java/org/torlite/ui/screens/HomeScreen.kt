// === HomeScreen.kt ===
package org.torlite.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.torlite.R
import org.torlite.ui.theme.ConnectionState
import org.torlite.ui.theme.PhantomColors
import org.torlite.ui.theme.PhantomTypography
import org.torlite.ui.theme.SyneFontFamily
import org.torlite.ui.theme.JetBrainsMonoFontFamily
import org.torlite.ui.theme.DmSansFontFamily

// ═══════════════════════════════════════════════════════════
// HOME SCREEN — The Hero Screen
// ═══════════════════════════════════════════════════════════

@Composable
fun HomeScreen(
    connectionState: ConnectionState,
    uploadSpeed: String,
    downloadSpeed: String,
    totalUpload: String,
    totalDownload: String,
    isVpnMode: Boolean,
    isKillSwitch: Boolean,
    onConnectClick: () -> Unit,
    onVpnModeToggle: () -> Unit,
    onKillSwitchToggle: () -> Unit,
    onSettingsClick: () -> Unit,
    onMenuClick: () -> Unit,
    onBandwidthClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PhantomColors.Black)
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ── Top Bar ──────────────────────────────────────
        TopBar(
            onMenuClick = onMenuClick,
            onSettingsClick = onSettingsClick
        )

        // ── Spacer to push content down ──────────────────
        Spacer(modifier = Modifier.weight(0.3f))

        // ── Connection Button + Ambient Ring ─────────────
        ConnectionButton(
            connectionState = connectionState,
            onClick = onConnectClick
        )

        Spacer(modifier = Modifier.height(32.dp))

        // ── Status Text ─────────────────────────────────
        StatusDisplay(connectionState = connectionState)

        Spacer(modifier = Modifier.height(24.dp))

        // ── Bandwidth Strip ──────────────────────────────
        BandwidthStrip(
            uploadSpeed = uploadSpeed,
            downloadSpeed = downloadSpeed,
            totalUpload = totalUpload,
            totalDownload = totalDownload,
            onClick = onBandwidthClick
        )

        Spacer(modifier = Modifier.height(16.dp))

        // ── Quick Toggles ───────────────────────────────
        QuickToggleRow(
            isVpnMode = isVpnMode,
            isKillSwitch = isKillSwitch,
            onVpnModeToggle = onVpnModeToggle,
            onKillSwitchToggle = onKillSwitchToggle
        )

        Spacer(modifier = Modifier.weight(0.7f))
    }
}

// ═══════════════════════════════════════════════════════════
// TOP BAR — Transparent, no elevation
// ═══════════════════════════════════════════════════════════

@Composable
private fun TopBar(
    onMenuClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onMenuClick,
            modifier = Modifier.semantics { contentDescription = "Menu" }
        ) {
            Icon(
                imageVector = ImageVector.vectorResource(R.drawable.ic_menu),
                contentDescription = null,
                tint = PhantomColors.TextSecondary,
                modifier = Modifier.size(24.dp)
            )
        }

        Text(
            text = "Tor Lite",
            style = PhantomTypography.HeadlineMedium.copy(
                fontFamily = SyneFontFamily,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
            ),
            color = PhantomColors.TextPrimary
        )

        IconButton(
            onClick = onSettingsClick,
            modifier = Modifier.semantics { contentDescription = "Settings" }
        ) {
            Icon(
                imageVector = ImageVector.vectorResource(R.drawable.ic_settings),
                contentDescription = null,
                tint = PhantomColors.TextSecondary,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════
// CONNECTION BUTTON — 120dp circle with ambient ring
// ═══════════════════════════════════════════════════════════

@Composable
private fun ConnectionButton(
    connectionState: ConnectionState,
    onClick: () -> Unit
) {
    val isConnected = connectionState is ConnectionState.Connected
    val isConnecting = connectionState is ConnectionState.Connecting

    // ── Ambient Pulse Animation (connected state only) ──
    val infiniteTransition = rememberInfiniteTransition(label = "ambient")

    val ringAlpha by infiniteTransition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.40f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ringAlpha"
    )

    val ringScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.10f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ringScale"
    )

    // ── Press state for scale feedback ──────────────────
    var isPressed by remember { mutableStateOf(false) }
    val buttonScale = when {
        isPressed -> 0.96f
        isConnecting -> 0.90f
        else -> 1.0f
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(160.dp)
            .semantics { contentDescription = if (isConnected) "Disconnect" else "Connect to Tor" }
    ) {
        // ── Outer Ambient Ring (connected only) ─────────
        if (isConnected) {
            Canvas(
                modifier = Modifier
                    .size(140.dp * ringScale)
            ) {
                val strokeWidth = 2.dp.toPx()
                val radius = (size.minDimension - strokeWidth) / 2f
                drawCircle(
                    color = PhantomColors.Jade,
                    radius = radius,
                    center = Offset(size.width / 2f, size.height / 2f),
                    style = Stroke(width = strokeWidth),
                    alpha = ringAlpha
                )
            }
        }

        // ── Connect Button ──────────────────────────────
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(120.dp)
                .scale(buttonScale)
                .clip(CircleShape)
                .background(
                    when {
                        isConnected -> PhantomColors.JadeDim
                        isConnecting -> Color(0x20FFB300)
                        else -> PhantomColors.Surface1
                    }
                )
                .border(
                    width = 1.dp,
                    color = when {
                        isConnected -> PhantomColors.Jade.copy(alpha = 0.6f)
                        isConnecting -> PhantomColors.StatusAmber.copy(alpha = 0.4f)
                        else -> PhantomColors.BorderSubtle
                    },
                    shape = CircleShape
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick
                )
        ) {
            // Power icon
            PowerIcon(
                color = when {
                    isConnected -> PhantomColors.Jade
                    isConnecting -> PhantomColors.StatusAmber
                    else -> PhantomColors.TextSecondary
                },
                size = 36.dp
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════
// POWER ICON — Custom SVG power symbol
// ═══════════════════════════════════════════════════════════

@Composable
private fun PowerIcon(
    color: Color,
    size: androidx.compose.ui.unit.Dp
) {
    Canvas(modifier = Modifier.size(size)) {
        val stroke = 3.dp.toPx()
        val cx = size.toPx() / 2f
        val cy = size.toPx() / 2f
        val radius = (size.toPx() / 2f) * 0.65f

        // Vertical line at top
        drawLine(
            color = color,
            start = Offset(cx, cy - radius + stroke),
            end = Offset(cx, cy * 0.4f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )

        // Arc (open at top)
        drawArc(
            color = color,
            startAngle = -135f,
            sweepAngle = 270f,
            useCenter = false,
            topLeft = Offset(cx - radius, cy - radius + stroke * 2),
            size = Size(radius * 2, radius * 2),
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
    }
}

// ═══════════════════════════════════════════════════════════
// STATUS DISPLAY — Connection status text with states
// ═══════════════════════════════════════════════════════════

@Composable
private fun StatusDisplay(
    connectionState: ConnectionState
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        when (connectionState) {
            is ConnectionState.Disconnected -> {
                Text(
                    text = "Not connected",
                    style = PhantomTypography.DisplayMedium.copy(
                        fontSize = 32.sp,
                        fontFamily = SyneFontFamily,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                    ),
                    color = PhantomColors.TextSecondary
                )
            }
            is ConnectionState.Connecting -> {
                AnimatedConnectingText()
            }
            is ConnectionState.Connected -> {
                Text(
                    text = "CONNECTED",
                    style = PhantomTypography.DisplayLarge.copy(
                        fontSize = 48.sp,
                        fontFamily = SyneFontFamily,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        letterSpacing = (-1.5).sp
                    ),
                    color = PhantomColors.Jade
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = connectionState.exitNodeIp.ifEmpty { "tor.0.4.8.14" },
                    style = PhantomTypography.LabelMedium.copy(
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 13.sp
                    ),
                    color = PhantomColors.TextSecondary
                )
            }
            is ConnectionState.Error -> {
                Text(
                    text = "Connection failed",
                    style = PhantomTypography.DisplayMedium.copy(
                        fontSize = 32.sp,
                        fontFamily = SyneFontFamily,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                    ),
                    color = PhantomColors.StatusRed
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = connectionState.message,
                    style = PhantomTypography.BodyMedium.copy(
                        fontFamily = DmSansFontFamily,
                        fontSize = 13.sp
                    ),
                    color = PhantomColors.TextSecondary
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
// ANIMATED CONNECTING TEXT — Ellipsis fade sequence
// ═══════════════════════════════════════════════════════════

@Composable
private fun AnimatedConnectingText() {
    val infiniteTransition = rememberInfiniteTransition(label = "connecting")

    val dot1Alpha by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot1"
    )
    val dot2Alpha by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600, delayMillis = 200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot2"
    )
    val dot3Alpha by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600, delayMillis = 400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot3"
    )

    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Connecting",
            style = PhantomTypography.DisplayMedium.copy(
                fontSize = 32.sp,
                fontFamily = SyneFontFamily,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
            ),
            color = PhantomColors.StatusAmber
        )
        Spacer(modifier = Modifier.width(2.dp))
        Text(text = ".", color = PhantomColors.StatusAmber.copy(alpha = dot1Alpha), fontSize = 32.sp, fontFamily = SyneFontFamily)
        Text(text = ".", color = PhantomColors.StatusAmber.copy(alpha = dot2Alpha), fontSize = 32.sp, fontFamily = SyneFontFamily)
        Text(text = ".", color = PhantomColors.StatusAmber.copy(alpha = dot3Alpha), fontSize = 32.sp, fontFamily = SyneFontFamily)
    }
}

// ═══════════════════════════════════════════════════════════
// BANDWIDTH STRIP — Upload / Download metrics card
// ═══════════════════════════════════════════════════════════

@Composable
private fun BandwidthStrip(
    uploadSpeed: String,
    downloadSpeed: String,
    totalUpload: String,
    totalDownload: String,
    onClick: () -> Unit
) {
    val isActive = uploadSpeed != "0 KB/s" || downloadSpeed != "0 KB/s"

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(PhantomColors.Surface1)
            .border(
                width = 1.dp,
                color = PhantomColors.BorderSubtle,
                shape = RoundedCornerShape(16.dp)
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(16.dp)
            .semantics { contentDescription = "Bandwidth: Upload $uploadSpeed, Download $downloadSpeed" }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Upload column
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "\u2191",
                        color = if (isActive) PhantomColors.Jade else PhantomColors.TextTertiary,
                        fontSize = 15.sp,
                        fontFamily = JetBrainsMonoFontFamily
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = uploadSpeed,
                        style = PhantomTypography.LabelMedium.copy(
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = 15.sp
                        ),
                        color = PhantomColors.TextPrimary
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Total \u2191 $totalUpload",
                    style = PhantomTypography.LabelSmall.copy(
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 12.sp
                    ),
                    color = PhantomColors.TextTertiary
                )
            }

            // Download column
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "\u2193",
                        color = if (isActive) PhantomColors.Jade else PhantomColors.TextTertiary,
                        fontSize = 15.sp,
                        fontFamily = JetBrainsMonoFontFamily
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = downloadSpeed,
                        style = PhantomTypography.LabelMedium.copy(
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = 15.sp
                        ),
                        color = PhantomColors.TextPrimary
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Total \u2193 $totalDownload",
                    style = PhantomTypography.LabelSmall.copy(
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 12.sp
                    ),
                    color = PhantomColors.TextTertiary
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
// QUICK TOGGLE ROW — VPN Mode + Kill Switch chips
// ═══════════════════════════════════════════════════════════

@Composable
private fun QuickToggleRow(
    isVpnMode: Boolean,
    isKillSwitch: Boolean,
    onVpnModeToggle: () -> Unit,
    onKillSwitchToggle: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ToggleChip(
            label = "VPN Mode",
            isActive = isVpnMode,
            onClick = onVpnModeToggle,
            modifier = Modifier.weight(1f)
        )
        ToggleChip(
            label = "Kill Switch",
            isActive = isKillSwitch,
            onClick = onKillSwitchToggle,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ToggleChip(
    label: String,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isActive) PhantomColors.JadeDim else PhantomColors.Surface1)
            .border(
                width = 1.dp,
                color = if (isActive) PhantomColors.Jade else PhantomColors.BorderSubtle,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .semantics { contentDescription = "$label ${if (isActive) "on" else "off"}" }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            // Dot indicator
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (isActive) PhantomColors.Jade else PhantomColors.TextTertiary)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                style = PhantomTypography.BodyMedium.copy(
                    fontFamily = DmSansFontFamily,
                    fontSize = 14.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                ),
                color = if (isActive) PhantomColors.Jade else PhantomColors.TextSecondary
            )
        }
    }
}
