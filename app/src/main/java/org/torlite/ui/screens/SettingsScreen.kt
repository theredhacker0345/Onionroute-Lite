// === SettingsScreen.kt ===
package org.torlite.ui.screens

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
// SETTINGS SCREEN
// ═══════════════════════════════════════════════════════════

@Composable
fun SettingsScreen(
    isVpnMode: Boolean,
    isKillSwitch: Boolean,
    isAutoStart: Boolean,
    bridgeType: String,
    onVpnModeChange: (Boolean) -> Unit,
    onKillSwitchChange: (Boolean) -> Unit,
    onAutoStartChange: (Boolean) -> Unit,
    onBridgeTypeChange: (String) -> Unit,
    onBridgeConfigClick: () -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PhantomColors.Black)
    ) {
        // ── Top Bar ──────────────────────────────────────
        SettingsTopBar(onBackClick = onBackClick)

        // ── Scrollable Content ───────────────────────────
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            // ── CONNECTION Section ───────────────────────
            item {
                SectionHeader(title = "CONNECTION")
            }

            item {
                SettingsToggleRow(
                    title = "VPN Mode",
                    subtitle = "Route all traffic through Tor",
                    isChecked = isVpnMode,
                    onCheckedChange = onVpnModeChange
                )
            }

            item {
                SettingsDivider()
            }

            item {
                SettingsToggleRow(
                    title = "Kill Switch",
                    subtitle = "Block traffic if Tor disconnects",
                    isChecked = isKillSwitch,
                    onCheckedChange = onKillSwitchChange
                )
            }

            item {
                SettingsDivider()
            }

            item {
                SettingsToggleRow(
                    title = "Auto-start on Boot",
                    subtitle = "Connect when device starts",
                    isChecked = isAutoStart,
                    onCheckedChange = onAutoStartChange
                )
            }

            // ── BRIDGES Section ──────────────────────────
            item {
                Spacer(modifier = Modifier.height(24.dp))
                SectionHeader(title = "BRIDGES")
            }

            item {
                BridgeTypeSelector(
                    currentType = bridgeType,
                    onTypeChange = onBridgeTypeChange
                )
            }

            item {
                SettingsDivider()
            }

            item {
                SettingsNavigationRow(
                    title = "Configure Bridges",
                    onClick = onBridgeConfigClick
                )
            }

            // ── PROXY Section ────────────────────────────
            item {
                Spacer(modifier = Modifier.height(24.dp))
                SectionHeader(title = "PROXY")
            }

            item {
                ProxyAddressRow(
                    label = "SOCKS5 Proxy",
                    address = "127.0.0.1:9050"
                )
            }

            item {
                SettingsDivider()
            }

            item {
                ProxyAddressRow(
                    label = "HTTP Proxy",
                    address = "127.0.0.1:8118"
                )
            }

            // ── ABOUT Section ────────────────────────────
            item {
                Spacer(modifier = Modifier.height(24.dp))
                SectionHeader(title = "ABOUT")
            }

            item {
                SettingsInfoRow(
                    title = "Version",
                    value = "1.0.0"
                )
            }

            item {
                SettingsDivider()
            }

            item {
                SettingsNavigationRow(
                    title = "Licenses",
                    onClick = { /* Open licenses */ }
                )
            }

            // Bottom padding
            item {
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
// SETTINGS TOP BAR
// ═══════════════════════════════════════════════════════════

@Composable
private fun SettingsTopBar(
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
            text = "Settings",
            style = PhantomTypography.HeadlineMedium.copy(
                fontFamily = SyneFontFamily,
                fontWeight = FontWeight.SemiBold
            ),
            color = PhantomColors.TextPrimary
        )
    }
}

// ═══════════════════════════════════════════════════════════
// SECTION HEADER — ALL CAPS, DM Sans Medium
// ═══════════════════════════════════════════════════════════

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = PhantomTypography.SectionHeader.copy(
            fontFamily = DmSansFontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 11.sp,
            letterSpacing = 1.2.sp
        ),
        color = PhantomColors.TextSecondary,
        modifier = Modifier.padding(top = 20.dp, bottom = 12.dp)
    )
}

// ═══════════════════════════════════════════════════════════
// SETTINGS TOGGLE ROW
// ═══════════════════════════════════════════════════════════

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String?,
    isChecked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .semantics { contentDescription = "$title ${if (isChecked) "on" else "off"}" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = PhantomTypography.BodyMedium.copy(
                    fontFamily = DmSansFontFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp
                ),
                color = PhantomColors.TextPrimary
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = PhantomTypography.BodyMedium.copy(
                        fontFamily = DmSansFontFamily,
                        fontWeight = FontWeight.Normal,
                        fontSize = 13.sp
                    ),
                    color = PhantomColors.TextSecondary
                )
            }
        }

        Switch(
            checked = isChecked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = PhantomColors.Jade,
                checkedThumbColor = PhantomColors.White,
                uncheckedTrackColor = PhantomColors.Surface3,
                uncheckedThumbColor = PhantomColors.White,
                uncheckedBorderColor = PhantomColors.BorderMid
            ),
            modifier = Modifier.padding(start = 16.dp)
        )
    }
}

// ═══════════════════════════════════════════════════════════
// SETTINGS DIVIDER — Subtle line between items
// ═══════════════════════════════════════════════════════════

@Composable
private fun SettingsDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(0.5.dp)
            .background(PhantomColors.BorderSubtle)
    )
}

// ═══════════════════════════════════════════════════════════
// SETTINGS NAVIGATION ROW — Chevron + click
// ═══════════════════════════════════════════════════════════

@Composable
private fun SettingsNavigationRow(
    title: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            style = PhantomTypography.BodyMedium.copy(
                fontFamily = DmSansFontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp
            ),
            color = PhantomColors.TextPrimary
        )

        Icon(
            imageVector = ImageVector.vectorResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = PhantomColors.TextTertiary,
            modifier = Modifier.size(20.dp)
        )
    }
}

// ═══════════════════════════════════════════════════════════
// BRIDGE TYPE SELECTOR — Dropdown-style chips
// ═══════════════════════════════════════════════════════════

@Composable
private fun BridgeTypeSelector(
    currentType: String,
    onTypeChange: (String) -> Unit
) {
    val bridgeOptions = listOf("None", "Snowflake", "obfs4", "Custom")

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Bridge Type",
                style = PhantomTypography.BodyMedium.copy(
                    fontFamily = DmSansFontFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp
                ),
                color = PhantomColors.TextPrimary
            )
        }

        // Chip row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            bridgeOptions.forEach { option ->
                val isSelected = currentType == option
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) PhantomColors.JadeDim else PhantomColors.Surface1)
                        .border(
                            width = 1.dp,
                            color = if (isSelected) PhantomColors.Jade else PhantomColors.BorderSubtle,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onTypeChange(option) }
                        )
                        .semantics { contentDescription = "Bridge type $option" }
                ) {
                    Text(
                        text = option,
                        style = PhantomTypography.LabelMedium.copy(
                            fontFamily = DmSansFontFamily,
                            fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                            fontSize = 12.sp
                        ),
                        color = if (isSelected) PhantomColors.Jade else PhantomColors.TextSecondary
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
// PROXY ADDRESS ROW — With copy button
// ═══════════════════════════════════════════════════════════

@Composable
private fun ProxyAddressRow(
    label: String,
    address: String
) {
    var showCopied by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = PhantomTypography.BodyMedium.copy(
                    fontFamily = DmSansFontFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp
                ),
                color = PhantomColors.TextPrimary
            )
            Text(
                text = address,
                style = PhantomTypography.LabelMedium.copy(
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = 13.sp
                ),
                color = PhantomColors.TextSecondary
            )
        }

        // Copy button
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {
                        // Copy to clipboard
                        showCopied = true
                    }
                )
                .semantics { contentDescription = "Copy $label address" }
        ) {
            if (showCopied) {
                Text(
                    text = "Copied!",
                    style = PhantomTypography.LabelSmall.copy(
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 10.sp
                    ),
                    color = PhantomColors.Jade
                )
                // Auto-reset after 1200ms
                androidx.compose.runtime.LaunchedEffect(showCopied) {
                    kotlinx.coroutines.delay(1200)
                    showCopied = false
                }
            } else {
                Icon(
                    imageVector = ImageVector.vectorResource(R.drawable.ic_copy),
                    contentDescription = null,
                    tint = PhantomColors.TextTertiary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
// SETTINGS INFO ROW — Static key-value display
// ═══════════════════════════════════════════════════════════

@Composable
private fun SettingsInfoRow(
    title: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            style = PhantomTypography.BodyMedium.copy(
                fontFamily = DmSansFontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp
            ),
            color = PhantomColors.TextPrimary
        )

        Text(
            text = value,
            style = PhantomTypography.LabelMedium.copy(
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = 13.sp
            ),
            color = PhantomColors.TextSecondary
        )
    }
}
