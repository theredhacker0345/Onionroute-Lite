// === Theme.kt ===
package org.torlite.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ═══════════════════════════════════════════════════════════
// PHANTOM DESIGN SYSTEM — Color Tokens
// Law 1: OLED or die. Law 2: One accent. Law 3: Motion is medication.
// ═══════════════════════════════════════════════════════════

object PhantomColors {
    // Background hierarchy — OLED stack
    val Black = Color(0xFF000000)
    val Surface1 = Color(0xFF0D0D0D)
    val Surface2 = Color(0xFF141414)
    val Surface3 = Color(0xFF1C1C1C)

    // Borders — barely there
    val BorderSubtle = Color(0xFF2A2A2A)
    val BorderMid = Color(0xFF3D3D3D)

    // Jade accent — the ONE color
    val Jade = Color(0xFF00E5C4)
    val JadeGlow = Color(0x1A00E5C4)    // 10% opacity — ambient ring
    val JadeDim = Color(0x4000E5C4)      // 25% opacity — container backgrounds

    // Status colors — functional only, never decorative
    val StatusRed = Color(0xFFFF4757)
    val StatusAmber = Color(0xFFFFB300)
    val StatusGreen = Color(0xFF00E5C4)  // Same as Jade — one truth

    // Pure white — functional only (thumb, contrast elements)
    val White = Color(0xFFFFFFFF)

    // Text hierarchy
    val TextPrimary = Color(0xFFE8E8E8)
    val TextSecondary = Color(0xFF888888)
    val TextTertiary = Color(0xFF444444)
    val TextJade = Color(0xFF00E5C4)
}

// ═══════════════════════════════════════════════════════════
// FONT FAMILIES
// Syne → Display + Heading
// JetBrains Mono → Data + Metrics + Code
// DM Sans → Body + UI labels
// Fallbacks for environments without Google Fonts Provider
// ═══════════════════════════════════════════════════════════

val SyneFontFamily = FontFamily(
    Font(resId = org.torlite.R.font.syne_regular, weight = FontWeight.Normal),
    Font(resId = org.torlite.R.font.syne_medium, weight = FontWeight.Medium),
    Font(resId = org.torlite.R.font.syne_semibold, weight = FontWeight.SemiBold),
    Font(resId = org.torlite.R.font.syne_bold, weight = FontWeight.Bold),
)

val JetBrainsMonoFontFamily = FontFamily(
    Font(resId = org.torlite.R.font.jetbrainsmono_regular, weight = FontWeight.Normal),
    Font(resId = org.torlite.R.font.jetbrainsmono_medium, weight = FontWeight.Medium),
    Font(resId = org.torlite.R.font.jetbrainsmono_bold, weight = FontWeight.Bold),
)

val DmSansFontFamily = FontFamily(
    Font(resId = org.torlite.R.font.dmsans_regular, weight = FontWeight.Normal),
    Font(resId = org.torlite.R.font.dmsans_medium, weight = FontWeight.Medium),
    Font(resId = org.torlite.R.font.dmsans_semibold, weight = FontWeight.SemiBold),
    Font(resId = org.torlite.R.font.dmsans_bold, weight = FontWeight.Bold),
)

// ═══════════════════════════════════════════════════════════
// TYPOGRAPHY — Phantom Design Tokens
// ═══════════════════════════════════════════════════════════

object PhantomTypography {
    // Hero — connection status text
    val DisplayLarge = TextStyle(
        fontFamily = SyneFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 48.sp,
        letterSpacing = (-1.5).sp,
        color = PhantomColors.TextPrimary
    )

    // Large status (connecting/disconnected)
    val DisplayMedium = TextStyle(
        fontFamily = SyneFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        letterSpacing = (-1.0).sp,
        color = PhantomColors.TextPrimary
    )

    // Screen titles
    val HeadlineMedium = TextStyle(
        fontFamily = SyneFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        letterSpacing = (-0.5).sp,
        color = PhantomColors.TextPrimary
    )

    // Section labels
    val TitleSmall = TextStyle(
        fontFamily = DmSansFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        letterSpacing = 0.8.sp,
        color = PhantomColors.TextSecondary
    )

    // Body text
    val BodyMedium = TextStyle(
        fontFamily = DmSansFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        letterSpacing = 0.sp,
        color = PhantomColors.TextPrimary
    )

    // Body large
    val BodyLarge = TextStyle(
        fontFamily = DmSansFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        letterSpacing = 0.sp,
        color = PhantomColors.TextPrimary
    )

    // Bandwidth numbers, IDs, technical data
    val LabelMedium = TextStyle(
        fontFamily = JetBrainsMonoFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        letterSpacing = 0.sp,
        color = PhantomColors.TextPrimary
    )

    // Large metrics
    val LabelLarge = TextStyle(
        fontFamily = JetBrainsMonoFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        letterSpacing = 0.sp,
        color = PhantomColors.TextPrimary
    )

    // Small technical text
    val LabelSmall = TextStyle(
        fontFamily = JetBrainsMonoFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        letterSpacing = 0.sp,
        color = PhantomColors.TextTertiary
    )

    // Section header ALL CAPS
    val SectionHeader = TextStyle(
        fontFamily = DmSansFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 1.2.sp,
        color = PhantomColors.TextSecondary
    )
}

// ═══════════════════════════════════════════════════════════
// SPACING — 8dp base grid
// ═══════════════════════════════════════════════════════════

object PhantomSpacing {
    val Spacing4 = 4f
    val Spacing8 = 8f
    val Spacing12 = 12f
    val Spacing16 = 16f
    val Spacing24 = 24f
    val Spacing32 = 32f
    val Spacing48 = 48f
}

// ═══════════════════════════════════════════════════════════
// CONNECTION STATE — Typed sealed class
// ═══════════════════════════════════════════════════════════

sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Connecting : ConnectionState()
    data class Connected(val exitNodeIp: String = "") : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

// ═══════════════════════════════════════════════════════════
// MATERIAL 3 DARK COLOR SCHEME — Phantom Palette
// ═══════════════════════════════════════════════════════════

private val PhantomDarkColorScheme = darkColorScheme(
    primary = PhantomColors.Jade,
    onPrimary = PhantomColors.Black,
    primaryContainer = PhantomColors.JadeDim,
    onPrimaryContainer = PhantomColors.Jade,
    inversePrimary = PhantomColors.Jade,

    secondary = PhantomColors.TextSecondary,
    onSecondary = PhantomColors.Black,
    secondaryContainer = PhantomColors.Surface2,
    onSecondaryContainer = PhantomColors.TextPrimary,

    tertiary = PhantomColors.StatusAmber,
    onTertiary = PhantomColors.Black,
    tertiaryContainer = Color(0x40FFB300),
    onTertiaryContainer = PhantomColors.StatusAmber,

    error = PhantomColors.StatusRed,
    onError = PhantomColors.White,
    errorContainer = Color(0x40FF4757),
    onErrorContainer = PhantomColors.StatusRed,

    background = PhantomColors.Black,
    onBackground = PhantomColors.TextPrimary,
    surface = PhantomColors.Surface1,
    onSurface = PhantomColors.TextPrimary,
    surfaceVariant = PhantomColors.Surface2,
    onSurfaceVariant = PhantomColors.TextSecondary,
    inverseSurface = PhantomColors.TextPrimary,
    inverseOnSurface = PhantomColors.Black,

    outline = PhantomColors.BorderSubtle,
    outlineVariant = PhantomColors.BorderMid,
    scrim = PhantomColors.Black,
)

// ═══════════════════════════════════════════════════════════
// THEME COMPOSABLE
// ═══════════════════════════════════════════════════════════

@Composable
fun TorLiteTheme(
    content: @Composable () -> Unit
) {
    // Phantom is always dark — OLED is the only mode
    val colorScheme = PhantomDarkColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = androidx.compose.material3.Typography(),
        content = content
    )
}
