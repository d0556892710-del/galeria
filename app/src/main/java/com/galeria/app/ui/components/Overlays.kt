package com.galeria.app.ui.components

import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.focus.focusRequester
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.galeria.app.R
import com.galeria.app.glass.Backdrop
import com.galeria.app.ui.theme.LocalPalette
import com.galeria.app.ui.theme.Type
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

data class MenuItem(
    val title: String,
    val icon: ImageVector? = null,
    val checked: Boolean = false,
    val destructive: Boolean = false,
    val enabled: Boolean = true,
    val submenu: (() -> MenuSpec)? = null,
    val onClick: () -> Unit = {},
)

data class MenuSpec(val title: String? = null, val groups: List<List<MenuItem>>)

data class SheetAction(val title: String, val destructive: Boolean = false, val onClick: () -> Unit)
data class SheetSpec(val message: String?, val actions: List<SheetAction>)

/** A small iOS alert with a text field (New Album, Rename…). */
data class PromptSpec(
    val title: String,
    val message: String? = null,
    val initial: String = "",
    val placeholder: String = "",
    val confirm: String,
    val onConfirm: (String) -> Unit,
)

/** Menus, action sheets and toasts, drawn above everything on Liquid Glass. */
class Overlays {
    var prompt by mutableStateOf<PromptSpec?>(null)
        internal set
    fun prompt(spec: PromptSpec) { closeMenus(); prompt = spec }
    fun closePrompt() { prompt = null }
    internal val menus = mutableStateListOf<Pair<MenuSpec, Rect>>()
    var sheet by mutableStateOf<SheetSpec?>(null)
        internal set
    var toastText by mutableStateOf<String?>(null)
        internal set
    var toastId by mutableIntStateOf(0)
        internal set

    fun menu(anchor: Rect, spec: MenuSpec) {
        menus.clear(); menus.add(spec to anchor)
    }
    fun closeMenus() = menus.clear()
    fun sheet(spec: SheetSpec) { sheet = spec }
    fun closeSheet() { sheet = null }
    fun toast(text: String) {
        toastText = text; toastId++
    }
    val anyOpen: Boolean get() = menus.isNotEmpty() || sheet != null || prompt != null
}

val LocalOverlays = staticCompositionLocalOf { Overlays() }

private val MenuWidth = 254.dp

@Composable
fun OverlayHost(backdrop: Backdrop?, overlays: Overlays) {
    OverlayHostContent(backdrop, overlays)
    overlays.prompt?.let { spec -> PromptCard(backdrop, spec) { overlays.closePrompt() } }
}

@Composable
private fun PromptCard(backdrop: Backdrop?, spec: PromptSpec, onDismiss: () -> Unit) {
    val p = com.galeria.app.ui.theme.LocalPalette.current
    var text by remember(spec) {
        mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(spec.initial, androidx.compose.ui.text.TextRange(0, spec.initial.length)))
    }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(spec) { kotlinx.coroutines.delay(120); runCatching { focus.requestFocus() } }
    fun confirm() {
        val t = text.text.trim()
        if (t.isEmpty()) return
        onDismiss(); spec.onConfirm(t)
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = .3f))
            .clickable(remember { MutableInteractionSource() }, null) { onDismiss() }
            .imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        GlassSurface(
            backdrop,
            Modifier.width(300.dp).clickable(remember { MutableInteractionSource() }, null) {},
            androidx.compose.foundation.shape.RoundedCornerShape(30.dp),
            tint = p.background.copy(alpha = .86f),
        ) {
            androidx.compose.foundation.layout.Column(
                Modifier.padding(top = 20.dp, bottom = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(spec.title, style = com.galeria.app.ui.theme.Type.headline, color = p.label, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(horizontal = 20.dp))
                spec.message?.let {
                    Text(it, style = com.galeria.app.ui.theme.Type.footnote, color = p.label, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp))
                }
                androidx.compose.foundation.layout.Spacer(Modifier.height(14.dp))
                androidx.compose.foundation.text.BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    textStyle = com.galeria.app.ui.theme.Type.body.copy(color = p.label),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(p.blue),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { confirm() }),
                    modifier = Modifier
                        .padding(horizontal = 18.dp)
                        .fillMaxWidth()
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                        .background(p.fill)
                        .padding(horizontal = 12.dp, vertical = 11.dp)
                        .focusRequester(focus),
                    decorationBox = { inner ->
                        Box {
                            if (text.text.isEmpty()) Text(spec.placeholder, style = com.galeria.app.ui.theme.Type.body, color = p.tertiaryLabel)
                            inner()
                        }
                    },
                )
                androidx.compose.foundation.layout.Spacer(Modifier.height(16.dp))
                androidx.compose.foundation.layout.Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        Modifier.weight(1f).height(48.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(24.dp)).background(p.fill).pressable(onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) { Text(androidx.compose.ui.res.stringResource(com.galeria.app.R.string.cancel), style = com.galeria.app.ui.theme.Type.body, color = p.label) }
                    val ok = text.text.isNotBlank()
                    Box(
                        Modifier.weight(1f).height(48.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
                            .background(if (ok) p.blue else p.fill).pressable(enabled = ok) { confirm() },
                        contentAlignment = Alignment.Center,
                    ) { Text(spec.confirm, style = com.galeria.app.ui.theme.Type.headline, color = if (ok) androidx.compose.ui.graphics.Color.White else p.tertiaryLabel) }
                }
            }
        }
    }
}

@Composable
private fun OverlayHostContent(backdrop: Backdrop?, overlays: Overlays) {
    val density = LocalDensity.current
    val dir = LocalLayoutDirection.current
    val screenW = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    val screenH = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }

    // ---- menus ----
    if (overlays.menus.isNotEmpty()) {
        Box(
            Modifier
                .fillMaxSize()
                .clickable(remember { MutableInteractionSource() }, null) { overlays.closeMenus() }
        )
        overlays.menus.forEachIndexed { level, (spec, anchor) ->
            val top = level == overlays.menus.lastIndex
            // Menus are positioned in absolute screen pixels, so their container must start at the
            // physical left edge in Hebrew too (TopStart would be the right edge there).
            Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.AbsoluteAlignment.TopLeft) {
            MenuPanel(
                backdrop = backdrop,
                spec = spec,
                anchor = anchor,
                screenW = screenW,
                screenH = screenH,
                rtl = dir == LayoutDirection.Rtl,
                sub = level > 0,
                dimmed = !top,
                onBack = { if (overlays.menus.size > 1) overlays.menus.removeAt(overlays.menus.lastIndex) else overlays.closeMenus() },
                onItem = { item, rowRect ->
                    val sub = item.submenu
                    if (sub != null) overlays.menus.add(sub() to rowRect)
                    else {
                        overlays.closeMenus(); item.onClick()
                    }
                },
            )
            }
        }
    }

    // ---- action sheet ----
    val sheet = overlays.sheet
    val dimAlpha = remember { Animatable(0f) }
    LaunchedEffect(sheet != null) { dimAlpha.animateTo(if (sheet != null) 1f else 0f, tween(260)) }
    if (dimAlpha.value > 0.01f) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = dimAlpha.value }
                .background(Color.Black.copy(alpha = .26f))
                .clickable(remember { MutableInteractionSource() }, null) { overlays.closeSheet() }
        )
    }
    var lastSheet by remember { mutableStateOf<SheetSpec?>(null) }
    if (sheet != null) lastSheet = sheet
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = sheet != null,
            enter = slideInVertically(spring(dampingRatio = .82f, stiffness = 380f)) { it } + fadeIn(),
            exit = slideOutVertically(tween(220)) { it } + fadeOut(tween(220)),
        ) {
            lastSheet?.let { s -> ActionSheet(backdrop, s) { overlays.closeSheet() } }
        }
    }

    // ---- toast ----
    val text = overlays.toastText
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(overlays.toastId) {
        if (text != null) {
            visible = true; delay(1800); visible = false
        }
    }
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(spring(dampingRatio = .7f, stiffness = 420f)) { -it } + fadeIn(),
            exit = slideOutVertically(tween(200)) { -it } + fadeOut(tween(200)),
            modifier = Modifier.padding(top = 92.dp),
        ) {
            GlassSurface(backdrop, Modifier.heightIn(min = 42.dp), RoundedCornerShape(21.dp)) {
                Text(text ?: "", style = Type.subheadSemibold, modifier = Modifier.padding(horizontal = 18.dp, vertical = 11.dp))
            }
        }
    }
}

@Composable
private fun MenuPanel(
    backdrop: Backdrop?,
    spec: MenuSpec,
    anchor: Rect,
    screenW: Float,
    screenH: Float,
    rtl: Boolean,
    sub: Boolean,
    dimmed: Boolean,
    onBack: () -> Unit,
    onItem: (MenuItem, Rect) -> Unit,
) {
    val density = LocalDensity.current
    val p = LocalPalette.current
    val margin = with(density) { 10.dp.toPx() }
    val rawY = anchor.top - if (sub) with(density) { 6.dp.toPx() } else 0f
    // Open towards the middle of the screen from whichever side the button is on (as iOS does),
    // using the real size of the window and of the menu, so it can never run off the edge.
    val openRight = anchor.center.x < screenW / 2f
    val scale = remember { Animatable(.3f) }
    val fade = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { scale.animateTo(1f, spring(dampingRatio = .72f, stiffness = 520f)) }
        launch { fade.animateTo(1f, tween(140)) }
    }
    val dimScale by androidx.compose.animation.core.animateFloatAsState(if (dimmed) .965f else 1f, spring(stiffness = Spring.StiffnessMediumLow), label = "dim")
    GlassSurface(
        backdrop = backdrop,
        modifier = Modifier
            .layout { measurable, c ->
                val pl = measurable.measure(c.copy(minWidth = 0, minHeight = 0))
                val fw = c.maxWidth.toFloat(); val fh = c.maxHeight.toFloat()
                val wantX = if (openRight) anchor.left else anchor.right - pl.width
                val hiX = fw - pl.width - margin
                val px = if (hiX < margin) (fw - pl.width) / 2f else wantX.coerceIn(margin, hiX)
                val hiY = fh - pl.height - margin * 4
                val py = if (hiY < margin * 4) margin * 4 else rawY.coerceIn(margin * 4, hiY)
                layout(c.maxWidth, c.maxHeight) { pl.place(px.roundToInt(), py.roundToInt()) }
            }
            .width(MenuWidth)
            .graphicsLayer {
                val s = scale.value * dimScale
                scaleX = s; scaleY = s
                transformOrigin = TransformOrigin(if (openRight) 0f else 1f, 0f)
            },
        shape = RoundedCornerShape(26.dp),
        elevation = 26.dp,
        contentAlignment = Alignment.TopStart,
    ) {
        Column(Modifier.padding(vertical = 7.dp).graphicsLayer { alpha = fade.value * if (dimmed) .32f else 1f }) {
            if (spec.title != null) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 44.dp).pressable(onClick = onBack).padding(start = 44.dp, end = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(spec.title, style = Type.headline, modifier = Modifier.weight(1f))
                    Icon(Sym.chevronDown, null, Modifier.size(14.dp), tint = LocalContentColor.current.copy(alpha = .55f))
                }
                MenuSeparator()
            }
            spec.groups.forEachIndexed { gi, group ->
                if (gi > 0) MenuSeparator()
                group.forEach { item -> MenuRow(item, if (dimmed) ({ _ -> onBack() }) else ({ r -> onItem(item, r) }), p.red) }
            }
        }
    }
}

@Composable
private fun MenuSeparator() {
    Box(
        Modifier
            .padding(horizontal = 18.dp, vertical = 6.dp)
            .fillMaxWidth()
            .height(1.dp)
            .background(LocalContentColor.current.copy(alpha = .12f))
    )
}

@Composable
private fun MenuRow(item: MenuItem, onClick: (Rect) -> Unit, red: Color) {
    var rect by remember { mutableStateOf(Rect.Zero) }
    val color = if (item.destructive) red else LocalContentColor.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp)
            .onRootRect { rect = it }
            .pressable(enabled = item.enabled) { onClick(rect) }
            .graphicsLayer { alpha = if (item.enabled) 1f else .32f }
            .padding(start = 15.dp, end = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Box(Modifier.size(17.dp)) { if (item.checked) Icon(Sym.checkmark, null, Modifier.size(17.dp), tint = color) }
        if (item.icon != null) Icon(item.icon, null, Modifier.size(21.dp), tint = color)
        Text(item.title, style = Type.body, color = color, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (item.submenu != null) Icon(Sym.chevronForward, null, Modifier.size(13.dp), tint = color.copy(alpha = .55f))
    }
}

@Composable
private fun ActionSheet(backdrop: Backdrop?, spec: SheetSpec, close: () -> Unit) {
    val p = LocalPalette.current
    GlassSurface(
        backdrop = backdrop,
        modifier = Modifier
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(10.dp)
            .fillMaxWidth()
            .clickable(remember { MutableInteractionSource() }, null) {},
        shape = RoundedCornerShape(36.dp),
        elevation = 30.dp,
        tint = if (p.dark) Color(0xFF262628).copy(alpha = .86f) else Color.White.copy(alpha = .84f),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            if (spec.message != null) {
                Text(
                    spec.message, style = Type.footnote, color = p.secondaryLabel, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 6.dp),
                )
            }
            spec.actions.forEach { a ->
                SheetButton(a.title, if (a.destructive) p.red else p.label) { close(); a.onClick() }
            }
            SheetButton(stringResource(R.string.cancel), p.label, bold = true) { close() }
        }
    }
}

@Composable
private fun SheetButton(text: String, color: Color, bold: Boolean = false, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(p.fill, RoundedCornerShape(26.dp))
            .pressable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = if (bold) Type.headline else Type.headline.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), color = color)
    }
}

/** Reports this element's bounds in root coordinates. */
fun Modifier.onRootRect(onRect: (Rect) -> Unit): Modifier = this.onGloballyPositioned { c ->
    val pos = c.positionInRoot()
    onRect(Rect(pos.x, pos.y, pos.x + c.size.width, pos.y + c.size.height))
}

@Composable
fun Spacer8() = Spacer(Modifier.size(8.dp))
