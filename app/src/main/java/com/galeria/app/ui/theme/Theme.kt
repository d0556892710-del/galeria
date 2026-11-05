package com.galeria.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.galeria.app.R
import com.galeria.app.data.GlassMode
import com.galeria.app.data.ThemeMode

/** iOS 26 system colours (UIKit semantic colours), light and dark. */
@Immutable
data class Palette(
    val dark: Boolean,
    val background: Color,          // systemBackground
    val groupedBackground: Color,   // systemGroupedBackground
    val cell: Color,                // secondarySystemGroupedBackground
    val label: Color,
    val secondaryLabel: Color,
    val tertiaryLabel: Color,
    val separator: Color,
    val fill: Color,                // tertiarySystemFill
    val fillStrong: Color,          // secondarySystemFill
    val blue: Color,
    val red: Color,
    val green: Color,
    val placeholder: Color,
)

val LightPalette = Palette(
    dark = false,
    background = Color(0xFFFFFFFF),
    groupedBackground = Color(0xFFF2F2F7),
    cell = Color(0xFFFFFFFF),
    label = Color(0xFF000000),
    secondaryLabel = Color(0x993C3C43),
    tertiaryLabel = Color(0x4D3C3C43),
    separator = Color(0x2E3C3C43),
    fill = Color(0x1F767680),
    fillStrong = Color(0x29787880),
    blue = Color(0xFF0088FF),
    red = Color(0xFFFF383C),
    green = Color(0xFF34C759),
    placeholder = Color(0xFFEDEDF0),
)

val DarkPalette = Palette(
    dark = true,
    background = Color(0xFF000000),
    groupedBackground = Color(0xFF000000),
    cell = Color(0xFF1C1C1E),
    label = Color(0xFFFFFFFF),
    secondaryLabel = Color(0x99EBEBF5),
    tertiaryLabel = Color(0x4DEBEBF5),
    separator = Color(0x40545458),
    fill = Color(0x3D767680),
    fillStrong = Color(0x52787880),
    blue = Color(0xFF0091FF),
    red = Color(0xFFFF4245),
    green = Color(0xFF30D158),
    placeholder = Color(0xFF1C1C1E),
)

/** Liquid Glass material parameters. Clear = the transparent look, Tinted = more opaque (iOS 26.1 setting). */
@Immutable
data class GlassLook(
    val tint: Color,
    val blurDp: Float,
    val saturation: Float,
    val refraction: Float,
    val rimAlpha: Float,
    val contentColor: Color,
    val shadowAlpha: Float,
)

fun glassLook(mode: GlassMode, dark: Boolean): GlassLook = when {
    mode == GlassMode.Clear && !dark -> GlassLook(Color.White.copy(alpha = .30f), 4f, 1.85f, 1f, .95f, Color.Black, .10f)
    mode == GlassMode.Clear && dark -> GlassLook(Color(0xFF1C1C1E).copy(alpha = .30f), 4f, 1.7f, 1f, .45f, Color.White, .30f)
    !dark -> GlassLook(Color(0xFFF7F7F9).copy(alpha = .78f), 14f, 1.6f, .55f, .9f, Color.Black, .12f)
    else -> GlassLook(Color(0xFF242426).copy(alpha = .80f), 14f, 1.5f, .55f, .35f, Color.White, .35f)
}

val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)
val InterDisplay = FontFamily(Font(R.font.inter_display_bold, FontWeight.Bold))

/** iOS Dynamic Type sizes at the default "Large" setting (points == dp). */
object Type {
    val largeTitle = TextStyle(fontFamily = InterDisplay, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 41.sp, letterSpacing = (-0.012).em)
    val title1 = TextStyle(fontFamily = InterDisplay, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.01).em)
    val title2 = TextStyle(fontFamily = InterDisplay, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.012).em)
    val title3 = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 25.sp, letterSpacing = (-0.02).em)
    val headline = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = (-0.025).em)
    val body = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = (-0.025).em)
    val bodyMedium = body.copy(fontWeight = FontWeight.Medium)
    val callout = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 21.sp, letterSpacing = (-0.02).em)
    val subhead = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = (-0.015).em)
    val subheadSemibold = subhead.copy(fontWeight = FontWeight.SemiBold)
    val footnote = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = (-0.005).em)
    val footnoteSemibold = footnote.copy(fontWeight = FontWeight.SemiBold)
    val caption1 = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp)
    val caption1Semibold = caption1.copy(fontWeight = FontWeight.SemiBold)
    val caption2 = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 13.sp, letterSpacing = 0.006.em)
    val tabLabel = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, lineHeight = 12.sp, letterSpacing = 0.01.em)
}

val LocalPalette = staticCompositionLocalOf { LightPalette }
val LocalGlass = staticCompositionLocalOf { glassLook(GlassMode.Clear, false) }

@Composable
fun isDark(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.System -> isSystemInDarkTheme()
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
}

@Composable
fun PhotosTheme(dark: Boolean, glass: GlassMode, content: @Composable () -> Unit) {
    val p = if (dark) DarkPalette else LightPalette
    val scheme = if (dark) darkColorScheme(primary = p.blue, background = p.background, surface = p.background, onBackground = p.label, onSurface = p.label)
    else lightColorScheme(primary = p.blue, background = p.background, surface = p.background, onBackground = p.label, onSurface = p.label)
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(
            LocalPalette provides p,
            LocalGlass provides glassLook(glass, dark),
            LocalTextStyle provides Type.body.copy(color = p.label),
            content = content,
        )
    }
}

val TextUnit.half get() = this / 2
