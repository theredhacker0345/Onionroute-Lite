package org.onionroute.lite.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.onionroute.lite.ui.OnionRouteAppState
import org.onionroute.lite.ui.Screen
import org.onionroute.lite.ui.theme.*
import org.onionroute.lite.util.IconSwitcher

@Composable
fun SettingsScreen(appState: OnionRouteAppState) {
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
                text = "SETTINGS",
                style = OnionRouteTypography.HeadlineMedium.copy(color = OnionRouteColors.TextPrimary)
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        // CONNECTION section
        SectionLabel("CONNECTION")
        Spacer(modifier = Modifier.height(12.dp))
        SettingToggle("VPN Mode", "Route all traffic through Tor", appState.isVpnMode) { appState.updateVpnMode(it) }
        SettingToggle("Kill Switch", "Block traffic if Tor disconnects", appState.isKillSwitchEnabled) { appState.updateKillSwitch(it) }
        SettingToggle("Auto-start", "Connect on device boot", appState.isAutoStart) { appState.updateAutoStart(it) }

        Spacer(modifier = Modifier.height(8.dp))

        // App Routing navigation
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(OnionRouteColors.Surface2)
                .border(1.dp, OnionRouteColors.BorderSubtle, RoundedCornerShape(10.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { appState.navigateTo(Screen.AppSelection) }
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "App Routing",
                        style = OnionRouteTypography.BodyMedium.copy(color = OnionRouteColors.TextPrimary)
                    )
                    Text(
                        text = "Choose which apps route through Tor",
                        style = OnionRouteTypography.LabelSmall.copy(color = OnionRouteColors.TextSecondary)
                    )
                }
                Text(
                    text = "→",
                    color = OnionRouteColors.Jade,
                    fontSize = 16.sp,
                    fontFamily = SyneFontFamily
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // BRIDGES section
        SectionLabel("BRIDGES")
        Spacer(modifier = Modifier.height(12.dp))
        SettingToggle("Snowflake", "WebRTC-based censorship circumvention", appState.isSnowflakeEnabled) { appState.updateSnowflake(it) }
        SettingToggle("obfs4", "Disguise Tor traffic as random data", appState.isObfs4Enabled) { appState.updateObfs4(it) }
        SettingToggle("WebTunnel", "Mimic HTTPS traffic to evade DPI", appState.isWebTunnelEnabled) { appState.updateWebTunnel(it) }

        Spacer(modifier = Modifier.height(16.dp))

        // Bridge config button
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(OnionRouteColors.Surface2)
                .border(1.dp, OnionRouteColors.BorderSubtle, RoundedCornerShape(10.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { appState.navigateTo(Screen.Bridges) }
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Text(
                text = "Configure Bridges →",
                style = OnionRouteTypography.BodyMedium.copy(color = OnionRouteColors.Jade)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // KINDNESS section
        SectionLabel("KINDNESS MODE")
        Spacer(modifier = Modifier.height(12.dp))

        // Clickable kindness card that navigates to stats screen
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(if (appState.isKindnessEnabled) OnionRouteColors.JadeGlow else OnionRouteColors.Surface2)
                .border(1.dp, if (appState.isKindnessEnabled) OnionRouteColors.Jade.copy(alpha = 0.4f) else OnionRouteColors.BorderSubtle, RoundedCornerShape(10.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { appState.navigateTo(Screen.Kindness) }
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Kindness Mode",
                        style = OnionRouteTypography.BodyMedium.copy(
                            color = if (appState.isKindnessEnabled) OnionRouteColors.Jade else OnionRouteColors.TextPrimary
                        )
                    )
                    Text(
                        text = if (appState.isKindnessEnabled) "Tap to view stats" else "Run a Snowflake proxy to help censored users",
                        style = OnionRouteTypography.LabelSmall.copy(
                            color = if (appState.isKindnessEnabled) OnionRouteColors.Jade.copy(alpha = 0.7f) else OnionRouteColors.TextSecondary
                        )
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (appState.isKindnessEnabled) OnionRouteColors.Jade else OnionRouteColors.TextTertiary)
                    )
                    Text(
                        text = "\u2192",
                        color = OnionRouteColors.Jade,
                        fontSize = 16.sp,
                        fontFamily = SyneFontFamily
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // CAMOUFLAGE section
        SectionLabel("CAMOUFLAGE")
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Disguise OnionRoute Lite as another app",
            style = OnionRouteTypography.LabelSmall.copy(color = OnionRouteColors.TextSecondary)
        )
        Spacer(modifier = Modifier.height(16.dp))

        // Icon picker row
        CamouflageIconPicker(appState = appState)

        Spacer(modifier = Modifier.height(24.dp))

        // ABOUT section
        SectionLabel("ABOUT")
        Spacer(modifier = Modifier.height(12.dp))
        SettingInfo("Version", org.onionroute.lite.BuildConfig.VERSION_NAME)
        SettingInfo("License", "GPL-3.0-or-later")
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = OnionRouteTypography.SectionHeader
    )
}

@Composable
private fun SettingToggle(label: String, subtitle: String, isActive: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (isActive) OnionRouteColors.JadeGlow else OnionRouteColors.Surface2)
            .border(1.dp, if (isActive) OnionRouteColors.Jade.copy(alpha = 0.4f) else OnionRouteColors.BorderSubtle, RoundedCornerShape(10.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onToggle(!isActive) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = label,
                style = OnionRouteTypography.BodyMedium.copy(
                    color = if (isActive) OnionRouteColors.Jade else OnionRouteColors.TextPrimary
                )
            )
            Text(
                text = subtitle,
                style = OnionRouteTypography.LabelSmall.copy(color = OnionRouteColors.TextSecondary)
            )
        }
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(if (isActive) OnionRouteColors.Jade else OnionRouteColors.TextTertiary)
        )
    }
    Spacer(modifier = Modifier.height(8.dp))
}

@Composable
private fun SettingInfo(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = OnionRouteTypography.BodyMedium.copy(color = OnionRouteColors.TextSecondary))
        Text(text = value, style = OnionRouteTypography.LabelMedium.copy(color = OnionRouteColors.TextTertiary))
    }
}

// ── Camouflage Icon Data ───────────────────────────────────────

private data class CamouflageOption(
    val key: String,
    val label: String,
    val iconEmoji: String,
    val appIcon: IconSwitcher.AppIcon
)

private val camouflageOptions = listOf(
    CamouflageOption("default", "Default",  "🧅", IconSwitcher.AppIcon.DEFAULT),
    CamouflageOption("note",    "Notes",    "📝", IconSwitcher.AppIcon.NOTE),
    CamouflageOption("weather", "Weather",  "🌤", IconSwitcher.AppIcon.WEATHER),
    CamouflageOption("music",   "Music",    "🎵", IconSwitcher.AppIcon.MUSIC),
    CamouflageOption("calc",    "Calc",     "🔢", IconSwitcher.AppIcon.CALC),
)

// ── Camouflage Icon Picker ─────────────────────────────────────

@Composable
private fun CamouflageIconPicker(appState: OnionRouteAppState) {
    val context = LocalContext.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        camouflageOptions.forEach { option ->
            val isSelected = appState.currentIcon == option.key

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        val switcher = IconSwitcher(context)
                        switcher.switchTo(option.appIcon)
                        appState.updateCurrentIcon(option.key)
                    }
                    .padding(vertical = 4.dp)
            ) {
                // Icon circle with optional jade border/glow
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(if (isSelected) OnionRouteColors.JadeGlow else OnionRouteColors.Surface2)
                        .border(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) OnionRouteColors.Jade else OnionRouteColors.BorderSubtle,
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = option.iconEmoji,
                        fontSize = 22.sp
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Label below icon
                Text(
                    text = option.label,
                    style = OnionRouteTypography.LabelSmall.copy(
                        color = if (isSelected) OnionRouteColors.Jade else OnionRouteColors.TextSecondary
                    )
                )
            }
        }
    }
}
