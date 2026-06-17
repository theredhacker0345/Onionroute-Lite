package org.onionroute.lite.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object OnionRouteColors {
    val Black = Color(0xFF000000)
    val Surface1 = Color(0xFF0D0D0D)
    val Surface2 = Color(0xFF141414)
    val Surface3 = Color(0xFF1C1C1C)
    val BorderSubtle = Color(0xFF2A2A2A)
    val BorderMid = Color(0xFF3D3D3D)
    val Jade = Color(0xFF00E5C4)
    val JadeGlow = Color(0x1A00E5C4)
    val JadeDim = Color(0x4000E5C4)
    val StatusRed = Color(0xFFFF4757)
    val StatusAmber = Color(0xFFFFB300)
    val StatusGreen = Color(0xFF00E5C4)
    val White = Color(0xFFFFFFFF)
    val TextPrimary = Color(0xFFE8E8E8)
    val TextSecondary = Color(0xFF888888)
    val TextTertiary = Color(0xFF444444)
    val TextJade = Color(0xFF00E5C4)
}

val SyneFontFamily = FontFamily(
    Font(resId = org.onionroute.lite.R.font.syne_regular, weight = FontWeight.Normal),
    Font(resId = org.onionroute.lite.R.font.syne_medium, weight = FontWeight.Medium),
    Font(resId = org.onionroute.lite.R.font.syne_semibold, weight = FontWeight.SemiBold),
    Font(resId = org.onionroute.lite.R.font.syne_bold, weight = FontWeight.Bold),
)

val JetBrainsMonoFontFamily = FontFamily(
    Font(resId = org.onionroute.lite.R.font.jetbrainsmono_regular, weight = FontWeight.Normal),
    Font(resId = org.onionroute.lite.R.font.jetbrainsmono_medium, weight = FontWeight.Medium),
    Font(resId = org.onionroute.lite.R.font.jetbrainsmono_bold, weight = FontWeight.Bold),
)

val DmSansFontFamily = FontFamily(
    Font(resId = org.onionroute.lite.R.font.dmsans_regular, weight = FontWeight.Normal),
    Font(resId = org.onionroute.lite.R.font.dmsans_medium, weight = FontWeight.Medium),
    Font(resId = org.onionroute.lite.R.font.dmsans_semibold, weight = FontWeight.SemiBold),
    Font(resId = org.onionroute.lite.R.font.dmsans_bold, weight = FontWeight.Bold),
)

object OnionRouteTypography {
    val DisplayLarge = TextStyle(
        fontFamily = SyneFontFamily, fontWeight = FontWeight.Bold, fontSize = 48.sp,
        letterSpacing = (-1.5).sp, color = OnionRouteColors.TextPrimary
    )
    val DisplayMedium = TextStyle(
        fontFamily = SyneFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 32.sp,
        letterSpacing = (-1.0).sp, color = OnionRouteColors.TextPrimary
    )
    val HeadlineMedium = TextStyle(
        fontFamily = SyneFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 22.sp,
        letterSpacing = (-0.5).sp, color = OnionRouteColors.TextPrimary
    )
    val TitleSmall = TextStyle(
        fontFamily = DmSansFontFamily, fontWeight = FontWeight.Medium, fontSize = 13.sp,
        letterSpacing = 0.8.sp, color = OnionRouteColors.TextSecondary
    )
    val BodyMedium = TextStyle(
        fontFamily = DmSansFontFamily, fontWeight = FontWeight.Normal, fontSize = 15.sp,
        letterSpacing = 0.sp, color = OnionRouteColors.TextPrimary
    )
    val BodyLarge = TextStyle(
        fontFamily = DmSansFontFamily, fontWeight = FontWeight.Normal, fontSize = 17.sp,
        letterSpacing = 0.sp, color = OnionRouteColors.TextPrimary
    )
    val LabelMedium = TextStyle(
        fontFamily = JetBrainsMonoFontFamily, fontWeight = FontWeight.Normal, fontSize = 13.sp,
        letterSpacing = 0.sp, color = OnionRouteColors.TextPrimary
    )
    val LabelLarge = TextStyle(
        fontFamily = JetBrainsMonoFontFamily, fontWeight = FontWeight.Bold, fontSize = 28.sp,
        letterSpacing = 0.sp, color = OnionRouteColors.TextPrimary
    )
    val LabelSmall = TextStyle(
        fontFamily = JetBrainsMonoFontFamily, fontWeight = FontWeight.Normal, fontSize = 11.sp,
        letterSpacing = 0.sp, color = OnionRouteColors.TextTertiary
    )
    val SectionHeader = TextStyle(
        fontFamily = DmSansFontFamily, fontWeight = FontWeight.Medium, fontSize = 11.sp,
        letterSpacing = 1.2.sp, color = OnionRouteColors.TextSecondary
    )
}

object OnionRouteSpacing {
    val Spacing4 = 4f
    val Spacing8 = 8f
    val Spacing12 = 12f
    val Spacing16 = 16f
    val Spacing24 = 24f
    val Spacing32 = 32f
    val Spacing48 = 48f
}

sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    /** @param progress Tor bootstrap progress 0..100 (0 = not yet started / unknown) */
    data class Connecting(val progress: Int = 0) : ConnectionState()
    data class Connected(val exitNodeIp: String = "") : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

private val OnionRouteDarkColorScheme = darkColorScheme(
    primary = OnionRouteColors.Jade,
    onPrimary = OnionRouteColors.Black,
    primaryContainer = OnionRouteColors.JadeDim,
    onPrimaryContainer = OnionRouteColors.Jade,
    inversePrimary = OnionRouteColors.Jade,
    secondary = OnionRouteColors.TextSecondary,
    onSecondary = OnionRouteColors.Black,
    secondaryContainer = OnionRouteColors.Surface2,
    onSecondaryContainer = OnionRouteColors.TextPrimary,
    tertiary = OnionRouteColors.StatusAmber,
    onTertiary = OnionRouteColors.Black,
    tertiaryContainer = Color(0x40FFB300),
    onTertiaryContainer = OnionRouteColors.StatusAmber,
    error = OnionRouteColors.StatusRed,
    onError = OnionRouteColors.White,
    errorContainer = Color(0x40FF4757),
    onErrorContainer = OnionRouteColors.StatusRed,
    background = OnionRouteColors.Black,
    onBackground = OnionRouteColors.TextPrimary,
    surface = OnionRouteColors.Surface1,
    onSurface = OnionRouteColors.TextPrimary,
    surfaceVariant = OnionRouteColors.Surface2,
    onSurfaceVariant = OnionRouteColors.TextSecondary,
    inverseSurface = OnionRouteColors.TextPrimary,
    inverseOnSurface = OnionRouteColors.Black,
    outline = OnionRouteColors.BorderSubtle,
    outlineVariant = OnionRouteColors.BorderMid,
    scrim = OnionRouteColors.Black,
)

@Composable
fun OnionRouteTheme(
    content: @Composable () -> Unit
) {
    val colorScheme = OnionRouteDarkColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = androidx.compose.material3.Typography(),
        content = content
    )
}
