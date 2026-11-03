package com.galeria.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ── Liquid Glass Color Tokens ──────────────────────────────────────────
object GlassTokens {
    // Backgrounds
    val BgPrimary   = Color(0xFF000000)
    val BgSecondary = Color(0xFF0C0C0E)   // iOS dark surface
    val BgTertiary  = Color(0xFF1C1C1E)   // iOS grouped background

    // Glass layers (matches iOS Liquid Glass system)
    val GlassUltraClear  = Color(0x14FFFFFF)  // 8%  — ultra thin
    val GlassStandard    = Color(0x26FFFFFF)  // 15% — regular
    val GlassTinted      = Color(0x33FFFFFF)  // 20% — tinted
    val GlassDark        = Color(0xCC000000)  // 80% — dark overlay
    val GlassFloating    = Color(0x1AFFFFFF)  // 10% + elevation shadow
    val GlassInteractive = Color(0x33FFFFFF)  // responds to press

    // Borders
    val BorderSubtle  = Color(0x1AFFFFFF)   // 10%
    val BorderRegular = Color(0x33FFFFFF)   // 20%
    val BorderStrong  = Color(0x4DFFFFFF)   // 30%

    // Text
    val TextPrimary   = Color(0xFFFFFFFF)
    val TextSecondary = Color(0x99FFFFFF)   // 60%
    val TextTertiary  = Color(0x66FFFFFF)   // 40%
    val TextQuaternary= Color(0x33FFFFFF)   // 20%

    // Accent — iOS blue
    val AccentBlue    = Color(0xFF0A84FF)
    val AccentBluePressed = Color(0xFF007AFF)

    // Semantic
    val Danger  = Color(0xFFFF3B30)
    val Warning = Color(0xFFFF9F0A)
    val Success = Color(0xFF34C759)

    // Nav bar pill (from screenshots — frosted pill)
    val NavPillBg     = Color(0x99000000)   // 60% black
    val NavPillBorder = Color(0x1AFFFFFF)
    val NavActive     = Color(0xFFFFFFFF)
    val NavInactive   = Color(0x66FFFFFF)
}

private val ColorScheme = darkColorScheme(
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

private val Typography = Typography(
    // "Library" headline — big bold like in screenshot
    headlineLarge = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold,   color = GlassTokens.TextPrimary),
    headlineMedium= TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold,   color = GlassTokens.TextPrimary),
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold,color = GlassTokens.TextPrimary),
    titleLarge    = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold,color = GlassTokens.TextPrimary),
    titleMedium   = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, color = GlassTokens.TextPrimary),
    bodyLarge     = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Normal,  color = GlassTokens.TextPrimary),
    bodyMedium    = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal,  color = GlassTokens.TextSecondary),
    bodySmall     = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal,  color = GlassTokens.TextSecondary),
    labelLarge    = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = GlassTokens.TextPrimary),
    labelMedium   = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, color = GlassTokens.TextSecondary),
    labelSmall    = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium, color = GlassTokens.TextTertiary),
)

@Composable
fun GaleriaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ColorScheme,
        typography  = Typography,
        content     = content
    )
}
