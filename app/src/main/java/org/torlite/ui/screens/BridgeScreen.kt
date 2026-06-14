// === BridgeScreen.kt ===
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.torlite.R
import org.torlite.ui.theme.DmSansFontFamily
import org.torlite.ui.theme.JetBrainsMonoFontFamily
import org.torlite.ui.theme.PhantomColors
import org.torlite.ui.theme.PhantomTypography
import org.torlite.ui.theme.SyneFontFamily

// ═══════════════════════════════════════════════════════════
// BRIDGE CONFIGURATION SCREEN
// ═══════════════════════════════════════════════════════════

@Composable
fun BridgeScreen(
    snowflakeBrokerUrl: String,
    snowflakeFrontDomains: String,
    isSnowflakeActive: Boolean,
    obfs4BridgeLines: String,
    customBridgeLines: String,
    onSnowflakeBrokerChange: (String) -> Unit,
    onSnowflakeFrontDomainsChange: (String) -> Unit,
    onObfs4BridgesChange: (String) -> Unit,
    onCustomBridgesChange: (String) -> Unit,
    onSaveBridges: () -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableIntStateOf(0) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PhantomColors.Black)
    ) {
        // ── Top Bar ──────────────────────────────────────
        BridgeTopBar(onBackClick = onBackClick)

        // ── Segmented Control ────────────────────────────
        SegmentedControl(
            tabs = listOf("Snowflake", "obfs4", "Custom"),
            selectedIndex = selectedTab,
            onTabSelect = { selectedTab = it }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // ── Tab Content ──────────────────────────────────
        when (selectedTab) {
            0 -> SnowflakeTab(
                brokerUrl = snowflakeBrokerUrl,
                frontDomains = snowflakeFrontDomains,
                isActive = isSnowflakeActive,
                onBrokerUrlChange = onSnowflakeBrokerChange,
                onFrontDomainsChange = onSnowflakeFrontDomainsChange
            )
            1 -> Obfs4Tab(
                bridgeLines = obfs4BridgeLines,
                onBridgeLinesChange = onObfs4BridgesChange,
                onSave = onSaveBridges
            )
            2 -> CustomTab(
                bridgeLines = customBridgeLines,
                onBridgeLinesChange = onCustomBridgesChange,
                onSave = onSaveBridges
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════
// BRIDGE TOP BAR
// ═══════════════════════════════════════════════════════════

@Composable
private fun BridgeTopBar(
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
            text = "Bridge Configuration",
            style = PhantomTypography.HeadlineMedium.copy(
                fontFamily = SyneFontFamily,
                fontWeight = FontWeight.SemiBold
            ),
            color = PhantomColors.TextPrimary
        )
    }
}

// ═══════════════════════════════════════════════════════════
// SEGMENTED CONTROL — Snowflake / obfs4 / Custom
// ═══════════════════════════════════════════════════════════

@Composable
private fun SegmentedControl(
    tabs: List<String>,
    selectedIndex: Int,
    onTabSelect: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(PhantomColors.Surface1)
            .border(
                width = 1.dp,
                color = PhantomColors.BorderSubtle,
                shape = RoundedCornerShape(8.dp)
            )
            .height(40.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        tabs.forEachIndexed { index, tab ->
            val isSelected = index == selectedIndex
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onTabSelect(index) }
                    )
                    .semantics { contentDescription = "Select $tab tab" }
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = tab,
                        style = PhantomTypography.LabelMedium.copy(
                            fontFamily = DmSansFontFamily,
                            fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                            fontSize = 13.sp
                        ),
                        color = if (isSelected) PhantomColors.Jade else PhantomColors.TextSecondary
                    )

                    // Bottom border indicator for selected tab
                    if (isSelected) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .width(24.dp)
                                .height(2.dp)
                                .clip(RoundedCornerShape(1.dp))
                                .background(PhantomColors.Jade)
                        )
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
// SNOWFLAKE TAB
// ═══════════════════════════════════════════════════════════

@Composable
private fun SnowflakeTab(
    brokerUrl: String,
    frontDomains: String,
    isActive: Boolean,
    onBrokerUrlChange: (String) -> Unit,
    onFrontDomainsChange: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Status indicator
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (isActive) PhantomColors.Jade else PhantomColors.TextTertiary)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isActive) "Snowflake active" else "Snowflake inactive",
                    style = PhantomTypography.BodyMedium.copy(
                        fontFamily = DmSansFontFamily,
                        fontSize = 14.sp
                    ),
                    color = if (isActive) PhantomColors.Jade else PhantomColors.TextSecondary
                )
            }
        }

        // Broker URL field
        item {
            PhantomInputField(
                label = "Broker URL",
                value = brokerUrl,
                onValueChange = onBrokerUrlChange,
                placeholder = "https://snowflake-broker.example.com"
            )
        }

        // Front Domains field
        item {
            PhantomInputField(
                label = "Front Domains",
                value = frontDomains,
                onValueChange = onFrontDomainsChange,
                placeholder = "example.com, cdn.example.com",
                isSingleLine = false
            )
        }

        // Reset to defaults
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {
                            onBrokerUrlChange("https://snowflake-broker.torproject.net.global.prod.fastly.net/")
                            onFrontDomainsChange("cdn.sstatic.net, ajax.googleapis.com")
                        }
                    )
                    .padding(vertical = 8.dp)
                    .semantics { contentDescription = "Reset to default Snowflake settings" }
            ) {
                Text(
                    text = "Reset to defaults",
                    style = PhantomTypography.BodyMedium.copy(
                        fontFamily = DmSansFontFamily,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    color = PhantomColors.Jade
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
// OBFS4 TAB
// ═══════════════════════════════════════════════════════════

@Composable
private fun Obfs4Tab(
    bridgeLines: String,
    onBridgeLinesChange: (String) -> Unit,
    onSave: () -> Unit
) {
    var useDefault by remember { mutableStateOf(true) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Radio chips: default vs custom
        item {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                RadioChip(
                    label = "Use default bridges",
                    isSelected = useDefault,
                    onClick = { useDefault = true }
                )
                Spacer(modifier = Modifier.height(8.dp))
                RadioChip(
                    label = "Use custom bridges",
                    isSelected = !useDefault,
                    onClick = { useDefault = false }
                )
            }
        }

        // Custom bridges input (only when not using defaults)
        if (!useDefault) {
            item {
                PhantomInputField(
                    label = "obfs4 Bridge Lines",
                    value = bridgeLines,
                    onValueChange = onBridgeLinesChange,
                    placeholder = "obfs4 bridge1.example.com:443 FINGERPRINT cert=... iat-mode=0",
                    isSingleLine = false,
                    minLines = 4
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PhantomTextButton(
                        text = "Save",
                        onClick = onSave,
                        modifier = Modifier.weight(1f)
                    )
                    PhantomTextButton(
                        text = "Clear",
                        onClick = { onBridgeLinesChange("") },
                        modifier = Modifier.weight(1f),
                        isDestructive = true
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
// CUSTOM TAB
// ═══════════════════════════════════════════════════════════

@Composable
private fun CustomTab(
    bridgeLines: String,
    onBridgeLinesChange: (String) -> Unit,
    onSave: () -> Unit
) {
    val bridgeCount = if (bridgeLines.isBlank()) 0
        else bridgeLines.lines().count { it.isNotBlank() }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Bridge input
        item {
            PhantomInputField(
                label = "Bridge Lines",
                value = bridgeLines,
                onValueChange = onBridgeLinesChange,
                placeholder = "Enter bridge lines, one per row",
                isSingleLine = false,
                minLines = 6
            )
        }

        // Bridge count
        item {
            Text(
                text = "$bridgeCount bridge${if (bridgeCount != 1) "s" else ""} loaded",
                style = PhantomTypography.LabelMedium.copy(
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = 12.sp
                ),
                color = PhantomColors.TextSecondary
            )
        }

        // Action buttons
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PhantomTextButton(
                    text = "Import from clipboard",
                    onClick = {
                        // Import from clipboard
                    },
                    modifier = Modifier.weight(1f)
                )
                PhantomTextButton(
                    text = "Save",
                    onClick = onSave,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
// SHARED COMPONENTS
// ═══════════════════════════════════════════════════════════

@Composable
private fun PhantomInputField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    isSingleLine: Boolean = true,
    minLines: Int = if (isSingleLine) 1 else 3
) {
    Column {
        Text(
            text = label,
            style = PhantomTypography.BodyMedium.copy(
                fontFamily = DmSansFontFamily,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            ),
            color = PhantomColors.TextSecondary,
            modifier = Modifier.padding(bottom = 6.dp)
        )

        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = {
                Text(
                    text = placeholder,
                    style = PhantomTypography.LabelMedium.copy(
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 13.sp
                    ),
                    color = PhantomColors.TextTertiary
                )
            },
            singleLine = isSingleLine,
            minLines = minLines,
            textStyle = PhantomTypography.LabelMedium.copy(
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = 13.sp,
                color = PhantomColors.TextPrimary
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = PhantomColors.Jade,
                unfocusedBorderColor = PhantomColors.BorderSubtle,
                cursorColor = PhantomColors.Jade,
                focusedContainerColor = PhantomColors.Surface3,
                unfocusedContainerColor = PhantomColors.Surface3
            ),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "$label input field" }
        )
    }
}

@Composable
private fun RadioChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(PhantomColors.Surface1)
            .border(
                width = 1.dp,
                color = if (isSelected) PhantomColors.Jade else PhantomColors.BorderSubtle,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics { contentDescription = "$label ${if (isSelected) "selected" else "not selected"}" }
    ) {
        // Radio dot
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .border(
                    width = 1.5.dp,
                    color = if (isSelected) PhantomColors.Jade else PhantomColors.BorderMid,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(PhantomColors.Jade)
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = label,
            style = PhantomTypography.BodyMedium.copy(
                fontFamily = DmSansFontFamily,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            ),
            color = if (isSelected) PhantomColors.Jade else PhantomColors.TextSecondary
        )
    }
}

@Composable
private fun PhantomTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isDestructive: Boolean = false
) {
    val bgColor = if (isDestructive) Color(0x20FF4757) else PhantomColors.Surface1
    val borderColor = if (isDestructive) PhantomColors.StatusRed.copy(alpha = 0.4f) else PhantomColors.BorderSubtle
    val textColor = if (isDestructive) PhantomColors.StatusRed else PhantomColors.Jade

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .border(
                width = 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .semantics { contentDescription = "$text button" }
    ) {
        Text(
            text = text,
            style = PhantomTypography.BodyMedium.copy(
                fontFamily = DmSansFontFamily,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            ),
            color = textColor
        )
    }
}
