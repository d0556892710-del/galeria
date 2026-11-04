package com.galeria.app.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galeria.app.ui.theme.GlassTokens

// ── Glass Card ────────────────────────────────────────────────────────────
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 16.dp,
    alpha: Float = 0.15f,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val bg = Color.White.copy(alpha = alpha)
    val shape = RoundedCornerShape(cornerRadius)
    val baseModifier = modifier
        .clip(shape)
        .background(bg)
        .border(1.dp, GlassTokens.BorderSubtle, shape)

    if (onClick != null) {
        Column(
            modifier = baseModifier.clickable(onClick = onClick),
            content = content
        )
    } else {
        Column(
            modifier = baseModifier,
            content = content
        )
    }
}

// ── Glass Button ──────────────────────────────────────────────────────────
@Composable
fun GlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tint: Color = GlassTokens.TextPrimary,
    bgAlpha: Float = 0.15f
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .background(Color.White.copy(alpha = bgAlpha))
            .border(1.dp, GlassTokens.BorderSubtle, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = tint, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

// ── LUMA Bottom Navigation Bar ────────────────────────────────────────────
data class LumaNavItem(val icon: ImageVector, val label: String)

val lumaNavItems = listOf(
    LumaNavItem(Icons.Filled.Photo,      "Photos"),
    LumaNavItem(Icons.Filled.PhotoAlbum, "Albums"),
    LumaNavItem(Icons.Filled.Explore,    "Explore"),
    LumaNavItem(Icons.Filled.Settings,   "Settings")
)

@Composable
fun GaleriaBottomBar(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    onSearchClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        // frosted pill
        Row(
            modifier = Modifier
                .padding(bottom = 24.dp)
                .clip(RoundedCornerShape(50.dp))
                .background(GlassTokens.NavPillBg)
                .border(1.dp, GlassTokens.NavPillBorder, RoundedCornerShape(50.dp))
                .padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            lumaNavItems.forEachIndexed { idx, item ->
                val isActive = selectedTab == idx
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(40.dp))
                        .background(if (isActive) Color.White.copy(alpha = 0.12f) else Color.Transparent)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onTabSelected(idx) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Icon(
                            item.icon,
                            contentDescription = item.label,
                            tint = if (isActive) GlassTokens.NavActive else GlassTokens.NavInactive,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            item.label,
                            fontSize = 10.sp,
                            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isActive) GlassTokens.NavActive else GlassTokens.NavInactive
                        )
                    }
                }
            }
        }
    }
}

// ── Glass Top Bar ─────────────────────────────────────────────────────────
@Composable
fun LumaTopBar(
    title: String,
    onSearchClick: (() -> Unit)? = null,
    onMoreClick: (() -> Unit)? = null,
    subtitle: String? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = title,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                color = GlassTokens.TextPrimary,
                letterSpacing = (-0.5).sp
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    fontSize = 13.sp,
                    color = GlassTokens.TextSecondary
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onSearchClick != null) {
                GlassIconButton(icon = Icons.Filled.Search, onClick = onSearchClick)
            }
            if (onMoreClick != null) {
                GlassIconButton(icon = Icons.Filled.MoreVert, onClick = onMoreClick)
            }
        }
    }
}

@Composable
fun GlassIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    tint: Color = GlassTokens.TextPrimary,
    size: Dp = 36.dp,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(GlassTokens.GlassStandard)
            .border(1.dp, GlassTokens.BorderSubtle, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
    }
}

// ── Glass Settings Row ────────────────────────────────────────────────────
@Composable
fun GlassSettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    iconTint: Color = GlassTokens.AccentBlue,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(iconTint.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = iconTint, modifier = Modifier.size(18.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = GlassTokens.TextPrimary)
            if (subtitle != null) {
                Text(subtitle, fontSize = 12.sp, color = GlassTokens.TextSecondary)
            }
        }
        if (trailing != null) trailing()
        else if (onClick != null) {
            Icon(Icons.Filled.ChevronRight, null, tint = GlassTokens.TextTertiary, modifier = Modifier.size(16.dp))
        }
    }
}

// ── Glass Section Container ───────────────────────────────────────────────
@Composable
fun GlassSection(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = modifier) {
        if (title != null) {
            Text(
                text = title.uppercase(),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = GlassTokens.TextTertiary,
                letterSpacing = 0.8.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(GlassTokens.GlassUltraClear)
                .border(1.dp, GlassTokens.BorderSubtle, RoundedCornerShape(14.dp)),
            content = content
        )
    }
}
