package com.galeria.app.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.galeria.app.glass.Backdrop
import com.galeria.app.glass.liquidGlass
import com.galeria.app.ui.theme.LocalGlass
import com.galeria.app.ui.theme.LocalPalette
import com.galeria.app.ui.theme.Type

/** A Liquid Glass surface. The backdrop is the live content behind it. */
@Composable
fun GlassSurface(
    backdrop: Backdrop?,
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
    tint: Color? = null,
    shadow: Boolean = true,
    elevation: Dp = 12.dp,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit,
) {
    val look = LocalGlass.current
    val density = LocalDensity.current.density
    val shadowColor = Color.Black.copy(alpha = look.shadowAlpha)
    Box(
        modifier
            .then(if (shadow) Modifier.shadow(elevation, shape, clip = false, ambientColor = shadowColor, spotColor = shadowColor) else Modifier)
            .liquidGlass(backdrop, shape, look, density, tint),
        contentAlignment = contentAlignment,
    ) {
        CompositionLocalProvider(LocalContentColor provides look.contentColor) { content() }
    }
}

/** Glass control that swells slightly while pressed, as iOS 26 controls do. */
@Composable
fun GlassButton(
    backdrop: Backdrop?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
    enabled: Boolean = true,
    label: String? = null,
    tint: Color? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && enabled) 1.1f else 1f, spring(dampingRatio = .52f, stiffness = 520f), label = "press")
    GlassSurface(
        backdrop = backdrop,
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier),
        shape = shape,
        tint = tint,
    ) {
        Box(Modifier.graphicsLayer { alpha = if (enabled) 1f else .35f }, contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
fun GlassIconButton(
    backdrop: Backdrop?,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    iconSize: Dp = 21.dp,
    enabled: Boolean = true,
    iconTint: Color? = null,
    tint: Color? = null,
) {
    GlassButton(backdrop, onClick, modifier.size(size), CircleShape, enabled, label, tint) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(iconSize), tint = iconTint ?: LocalContentColor.current)
    }
}

@Composable
fun GlassTextButton(
    backdrop: Backdrop?,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color? = null,
) {
    GlassButton(backdrop, onClick, modifier.height(44.dp), RoundedCornerShape(22.dp)) {
        Text(text, style = Type.bodyMedium, color = color ?: LocalContentColor.current, modifier = Modifier.padding(horizontal = 17.dp))
    }
}

/** A plain pressable area with iOS-style dimming instead of a ripple. */
@Composable
fun Modifier.pressable(enabled: Boolean = true, role: Role = Role.Button, onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val a by animateFloatAsState(if (pressed) .55f else 1f, spring(stiffness = Spring.StiffnessHigh), label = "dim")
    return this
        .graphicsLayer { alpha = a }
        .clickable(interaction, indication = null, enabled = enabled, role = role, onClick = onClick)
}

@Composable
fun secondaryText(): Color = LocalPalette.current.secondaryLabel
