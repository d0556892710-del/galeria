package com.galeria.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galeria.app.ui.theme.GlassTokens

// ═══════════════════════════════════════════════════════════════
//  GLASS CARD  — standard frosted card with gradient border
// ═══════════════════════════════════════════════════════════════
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    radius: Dp = 16.dp,
    bgColor: Color = GlassTokens.GlassStandard,
    borderColor: Color = GlassTokens.BorderRegular,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(radius))
            .background(
                Brush.verticalGradient(
                    listOf(bgColor.copy(alpha = bgColor.alpha * 1.3f), bgColor)
                )
            )
            .border(0.5.dp, borderColor, RoundedCornerShape(radius)),
        content = content
    )
}

// ═══════════════════════════════════════════════════════════════
//  GLASS BUTTON  — interactive, scales on press
// ═══════════════════════════════════════════════════════════════
@Composable
fun GlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    radius: Dp = 12.dp,
    content: @Composable BoxScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) 0.94f else 1f, spring(stiffness = Spring.StiffnessMediumLow), label = "scale")

    Box(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(radius))
            .background(
                Brush.verticalGradient(
                    listOf(
                        GlassTokens.GlassTinted,
                        GlassTokens.GlassStandard
                    )
                )
            )
            .border(0.5.dp, GlassTokens.BorderRegular, RoundedCornerShape(radius))
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
        content = content
    )
}

// ═══════════════════════════════════════════════════════════════
//  GLASS PILL  — rounded pill, used for nav labels + small buttons
// ═══════════════════════════════════════════════════════════════
@Composable
fun GlassPill(
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    bgColor: Color = GlassTokens.GlassStandard,
    content: @Composable BoxScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed && onClick != null) 0.95f else 1f, label = "pill")

    val base = modifier
        .scale(scale)
        .clip(CircleShape)
        .background(
            Brush.verticalGradient(
                listOf(bgColor.copy(alpha = (bgColor.alpha * 1.4f).coerceIn(0f, 1f)), bgColor)
            )
        )
        .border(0.5.dp, GlassTokens.BorderSubtle, CircleShape)

    Box(
        modifier = if (onClick != null)
            base.clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
        else base,
        contentAlignment = Alignment.Center,
        content = content
    )
}

// ═══════════════════════════════════════════════════════════════
//  BOTTOM NAV BAR  — frosted pill exactly like iOS 27 screenshot
//  Library | Collections   [Search icon]
// ═══════════════════════════════════════════════════════════════
@Composable
fun GaleriaBottomBar(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    onSearchClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 20.dp)
            .navigationBarsPadding(),
        contentAlignment = Alignment.BottomCenter
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Left pill — Library + Collections tabs
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(GlassTokens.NavPillBg)
                    .border(0.5.dp, GlassTokens.NavPillBorder, CircleShape)
            ) {
                Row(modifier = Modifier.padding(4.dp)) {
                    NavTab(
                        label = "Library",
                        isSelected = selectedTab == 0,
                        onClick = { onTabSelected(0) }
                    )
                    NavTab(
                        label = "Collections",
                        isSelected = selectedTab == 1,
                        onClick = { onTabSelected(1) }
                    )
                }
            }

            // Right — Search button
            GlassPill(
                onClick = onSearchClick,
                bgColor = GlassTokens.NavPillBg
            ) {
                Box(
                    modifier = Modifier.size(44.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("🔍", fontSize = 18.sp)
                }
            }
        }
    }
}

@Composable
private fun NavTab(label: String, isSelected: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .then(
                if (isSelected) Modifier.background(GlassTokens.GlassStandard)
                else Modifier
            )
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = if (isSelected) GlassTokens.NavActive else GlassTokens.NavInactive,
            fontWeight = if (isSelected) androidx.compose.ui.text.font.FontWeight.SemiBold
                         else            androidx.compose.ui.text.font.FontWeight.Normal
        )
    }
}

// ═══════════════════════════════════════════════════════════════
//  ACTION SHEET  — slides from bottom, blur background
// ═══════════════════════════════════════════════════════════════
@Composable
fun GlassActionSheet(
    onDismiss: () -> Unit,
    items: List<ActionSheetItem>
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            ),
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp)
                .navigationBarsPadding()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
        ) {
            // Items list
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                radius = 14.dp,
                bgColor = Color(0xE6000000)
            ) {
                Column {
                    items.forEachIndexed { index, item ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    item.action()
                                    onDismiss()
                                }
                                .padding(horizontal = 20.dp, vertical = 16.dp)
                        ) {
                            Text(
                                text = item.label,
                                fontSize = 17.sp,
                                color = if (item.isDestructive) GlassTokens.Danger
                                        else GlassTokens.TextPrimary
                            )
                        }
                        if (index < items.size - 1) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(0.5.dp)
                                    .background(GlassTokens.BorderSubtle)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Cancel button
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                radius = 14.dp,
                bgColor = Color(0xE6000000)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("ביטול", fontSize = 17.sp, color = GlassTokens.AccentBlue,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                }
            }
        }
    }
}

data class ActionSheetItem(val label: String, val isDestructive: Boolean = false, val action: () -> Unit)
