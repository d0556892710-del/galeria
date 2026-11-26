package com.galeria.app.ui.collections

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.galeria.app.R
import com.galeria.app.data.AppSettings
import com.galeria.app.ui.components.Sym
import com.galeria.app.ui.components.pressable
import com.galeria.app.ui.root.AppViewModel
import com.galeria.app.ui.theme.LocalPalette
import com.galeria.app.ui.theme.Type
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun sectionTitle(id: String): Int = when (id) {
    "pinned" -> R.string.pinned
    "memories" -> R.string.memories
    "albums" -> R.string.albums
    "media" -> R.string.media_types
    else -> R.string.utilities
}

/**
 * iOS "Customize & Reorder": tick the sections to show in Collections and drag ≡ to change their order.
 * Changes apply at once (Collections updates behind the sheet) and are saved.
 */
@Composable
fun CustomizeSheet(vm: AppViewModel, settings: AppSettings, onClose: () -> Unit) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    fun close() {
        if (!shown) return
        shown = false
        scope.launch { delay(280); onClose() }
    }
    BackHandler { close() }

    var order by remember { mutableStateOf(settings.sections) }
    val hidden = settings.sectionsHidden
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val rowH = with(LocalDensity.current) { 52.dp.toPx() }

    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(shown, enter = fadeIn(tween(220)), exit = fadeOut(tween(220))) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .32f)).clickable(remember { MutableInteractionSource() }, null) { close() })
        }
        AnimatedVisibility(
            shown,
            enter = slideInVertically(spring(dampingRatio = .86f, stiffness = 420f)) { it },
            exit = slideOutVertically(tween(260)) { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 38.dp, topEnd = 38.dp))
                    .background(p.groupedBackground)
                    .clickable(remember { MutableInteractionSource() }, null) {}
                    .navigationBarsPadding()
                    .padding(bottom = 22.dp),
            ) {
                Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp).size(36.dp, 5.dp).background(p.tertiaryLabel, RoundedCornerShape(3.dp)))
                // Title and the ✓ button (iOS 26 sheet header).
                Box(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 16.dp)) {
                    Text(stringResource(R.string.customize_reorder), style = Type.headline, color = p.label, modifier = Modifier.align(Alignment.Center))
                    val done = stringResource(R.string.done)
                    Box(
                        Modifier.align(Alignment.CenterEnd).size(44.dp).clip(CircleShape).background(p.blue)
                            .pressable { close() }.semantics { contentDescription = done },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Sym.checkmark, null, Modifier.size(20.dp), tint = Color.White) }
                }
                Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(26.dp)).background(p.cell)) {
                    order.forEachIndexed { index, id ->
                        key(id) {
                            val isDragging = dragging == id
                            val on = id !in hidden
                            Box(
                                Modifier
                                    .zIndex(if (isDragging) 1f else 0f)
                                    .graphicsLayer {
                                        translationY = if (isDragging) dragOffset else 0f
                                        val s = if (isDragging) 1.03f else 1f
                                        scaleX = s; scaleY = s
                                    }
                                    .then(if (isDragging) Modifier.shadow(14.dp, RoundedCornerShape(14.dp), clip = false) else Modifier)
                                    .background(p.cell, if (isDragging) RoundedCornerShape(14.dp) else RoundedCornerShape(0.dp))
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().height(52.dp).pressable { vm.setSectionHidden(id, on) }.padding(start = 16.dp, end = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    // Tick circle, like iOS.
                                    Box(
                                        Modifier.size(24.dp).clip(CircleShape)
                                            .then(if (on) Modifier.background(p.blue) else Modifier.border(1.6.dp, p.tertiaryLabel, CircleShape)),
                                        contentAlignment = Alignment.Center,
                                    ) { if (on) Icon(Sym.checkmark, null, Modifier.size(14.dp), tint = Color.White) }
                                    Spacer(Modifier.width(14.dp))
                                    Text(stringResource(sectionTitle(id)), style = Type.body, color = p.label, modifier = Modifier.weight(1f))
                                    // Drag handle ≡
                                    Box(
                                        Modifier
                                            .size(44.dp)
                                            .pointerInput(id) {
                                                detectDragGestures(
                                                    onDragStart = {
                                                        dragging = id; dragOffset = 0f
                                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    },
                                                    onDrag = { change, amount ->
                                                        change.consume()
                                                        dragOffset += amount.y
                                                        val i = order.indexOf(id)
                                                        if (dragOffset > rowH / 2 && i < order.lastIndex) {
                                                            order = order.toMutableList().also { it.add(i + 1, it.removeAt(i)) }
                                                            dragOffset -= rowH
                                                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                        } else if (dragOffset < -rowH / 2 && i > 0) {
                                                            order = order.toMutableList().also { it.add(i - 1, it.removeAt(i)) }
                                                            dragOffset += rowH
                                                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                        }
                                                    },
                                                    onDragEnd = { dragging = null; dragOffset = 0f; vm.setSections(order) },
                                                    onDragCancel = { dragging = null; dragOffset = 0f; vm.setSections(order) },
                                                )
                                            },
                                        contentAlignment = Alignment.Center,
                                    ) { Icon(Sym.reorder, stringResource(R.string.reorder), Modifier.size(22.dp), tint = p.tertiaryLabel) }
                                }
                                if (index < order.lastIndex && !isDragging) {
                                    Box(Modifier.align(Alignment.BottomStart).padding(start = 54.dp).fillMaxWidth().height(0.5.dp).background(p.separator))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
