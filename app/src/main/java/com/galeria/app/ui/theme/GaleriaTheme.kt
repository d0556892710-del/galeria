package com.galeria.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ── LUMA Gallery Pro — Liquid Glass Design System ──────────────────────
object GlassTokens {
    // Backgrounds
    val BgPrimary   = Color(0xFF000000)
    val BgSecondary = Color(0xFF0C0C0E)
    val BgTertiary  = Color(0xFF1C1C1E)
    val BgCard      = Color(0xFF2C2C2E)

    // Glass layers
    val GlassUltraClear  = Color(0x14FFFFFF)  // 8%
    val GlassStandard    = Color(0x26FFFFFF)  // 15%
    val GlassTinted      = Color(0x33FFFFFF)  // 20%
    val GlassDark        = Color(0xCC000000)  // 80%
    val GlassFloating    = Color(0x1AFFFFFF)  // 10%
    val GlassInteractive = Color(0x33FFFFFF)
    val GlassNav         = Color(0xB3000000)  // 70% nav pill

    // Borders
    val BorderSubtle  = Color(0x1AFFFFFF)   // 10%
    val BorderRegular = Color(0x33FFFFFF)   // 20%
    val BorderStrong  = Color(0x4DFFFFFF)   // 30%

    // Text
    val TextPrimary    = Color(0xFFFFFFFF)
    val TextSecondary  = Color(0x99FFFFFF)   // 60%
    val TextTertiary   = Color(0x66FFFFFF)   // 40%
    val TextQuaternary = Color(0x33FFFFFF)   // 20%

    // Accent
    val AccentBlue        = Color(0xFF0A84FF)
    val AccentBluePressed = Color(0xFF007AFF)
    val AccentPurple      = Color(0xFFBF5AF2)
    val AccentTeal        = Color(0xFF5AC8FA)

    // Semantic
    val Danger  = Color(0xFFFF3B30)
    val Warning = Color(0xFFFF9F0A)
    val Success = Color(0xFF34C759)

    // Nav
    val NavPillBg     = Color(0xB3000000)
    val NavPillBorder = Color(0x1AFFFFFF)
    val NavActive     = Color(0xFFFFFFFF)
    val NavInactive   = Color(0x66FFFFFF)
}

private val LumaColorScheme = darkColorScheme(
    primary           = GlassTokens.AccentBlue,
    onPrimary         = Color.White,
    background        = GlassTokens.BgPrimary,
    onBackground      = GlassTokens.TextPrimary,
    surface           = GlassTokens.BgSecondary,
    onSurface         = GlassTokens.TextPrimary,
    surfaceVariant    = GlassTokens.GlassStandard,
    onSurfaceVariant  = GlassTokens.TextSecondary,
    error             = GlassTokens.Danger,
    onError           = Color.White
)

val LumaTypography = Typography(
    headlineLarge  = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold,     color = GlassTokens.TextPrimary, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold,     color = GlassTokens.TextPrimary, letterSpacing = (-0.5).sp),
    headlineSmall  = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = GlassTokens.TextPrimary),
    titleLarge     = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = GlassTokens.TextPrimary),
    titleMedium    = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium,   color = GlassTokens.TextPrimary),
    bodyLarge      = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Normal,   color = GlassTokens.TextPrimary),
    bodyMedium     = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal,   color = GlassTokens.TextSecondary),
    bodySmall      = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal,   color = GlassTokens.TextSecondary),
    labelLarge     = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium,   color = GlassTokens.TextPrimary),
    labelMedium    = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium,   color = GlassTokens.TextSecondary),
    labelSmall     = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium,   color = GlassTokens.TextTertiary),
)

@Composable
fun GaleriaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LumaColorScheme,
        typography  = LumaTypography,
        content     = content
    )
}
