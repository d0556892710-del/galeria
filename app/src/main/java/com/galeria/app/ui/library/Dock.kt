package com.galeria.app.ui.library

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import com.galeria.app.R
import com.galeria.app.glass.Backdrop
import com.galeria.app.ui.components.GlassSurface
import com.galeria.app.ui.components.Sym
import com.galeria.app.ui.components.pressable
import com.galeria.app.ui.root.Tab
import com.galeria.app.ui.root.Zoom
import com.galeria.app.ui.theme.LocalPalette
import com.galeria.app.ui.theme.Type
import kotlinx.coroutines.delay

enum class DockMode { Expanded, Compact, Search }

/**
 * The iOS 26 tab bar. Expanded: Library | Collections + Search. Compact (after scrolling up in the
 * library): the current tab shrinks into a circle and Years / Months / All slides in. Search: the
 * search circle grows into a field.
 */
@Composable
fun Dock(
    backdrop: Backdrop?,
    mode: DockMode,
    tab: Tab,
    zoom: Zoom,
    query: String,
    onQuery: (String) -> Unit,
    onSubmit: () -> Unit,
    onTab: (Tab) -> Unit,
    onExpand: () -> Unit,
    onZoom: (Zoom) -> Unit,
    onSearchOpen: () -> Unit,
    onSearchClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    val spec = spring<Float>(dampingRatio = .8f, stiffness = 420f)
    val compactP by animateFloatAsState(if (mode == DockMode.Compact) 1f else 0f, spec, label = "compact")
    val searchP by animateFloatAsState(if (mode == DockMode.Search) 1f else 0f, spec, label = "search")
    val shrink = maxOf(compactP, searchP)
    BoxWithConstraints(modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        val w = maxWidth
        val gap = 10.dp
        val tabsW = lerp(182.dp, 46.dp, shrink)
        val tabsH = lerp(58.dp, 46.dp, shrink)
        val circle = lerp(58.dp, 46.dp, shrink)
        val gap2 = lerp(gap, 0.dp, searchP)
        val searchW = lerp(circle, w - tabsW - gap, searchP)
        val accW = (w - tabsW - searchW - gap - gap2).coerceAtLeast(0.dp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            // ---- tabs ----
            GlassSurface(backdrop, Modifier.width(tabsW).height(tabsH), RoundedCornerShape(50)) {
                if (shrink < .99f) {
                    val selX by animateDpAsState(if (tab == Tab.Library) 0.dp else 87.dp, spring(dampingRatio = .72f, stiffness = 480f), label = "tabsel")
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(4.dp)
                            .graphicsLayer { alpha = 1f - shrink }
                    ) {
                        Box(
                            Modifier
                                .offset(x = selX)
                                .width(87.dp)
                                .fillMaxHeight()
                                .background(if (p.dark) Color.White.copy(alpha = .14f) else Color.Black.copy(alpha = .068f), RoundedCornerShape(50))
                        )
                        Row {
                            TabItem(Sym.library, stringResource(R.string.tab_library), tab == Tab.Library) { onTab(Tab.Library) }
                            TabItem(Sym.collections, stringResource(R.string.tab_collections), tab == Tab.Collections) { onTab(Tab.Collections) }
                        }
                    }
                }
                if (shrink > .01f) {
                    val label = stringResource(R.string.show_tab_bar)
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = shrink }
                            .pressable(role = Role.Button) { if (mode == DockMode.Search) onSearchClose() else onExpand() }
                            .semantics { contentDescription = label },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(if (tab == Tab.Library) Sym.library else Sym.collections, null, Modifier.size(width = 26.dp, height = 23.dp), tint = p.blue)
                    }
                }
            }
            Spacer(Modifier.width(gap))
            // ---- Years / Months / All ----
            Box(Modifier.width(accW).height(46.dp)) {
                val a = compactP * (1f - searchP)
                if (a > .01f && accW > 40.dp) {
                    GlassSurface(
                        backdrop,
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = a; val s = .6f + .4f * a; scaleX = s; scaleY = s },
                        RoundedCornerShape(50),
                    ) { ZoomSegments(zoom, onZoom) }
                }
            }
            Spacer(Modifier.width(gap2))
            // ---- search ----
            GlassSurface(backdrop, Modifier.width(searchW).height(circle), RoundedCornerShape(50), contentAlignment = Alignment.CenterStart) {
                if (searchP < .99f) {
                    val label = stringResource(R.string.search)
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = 1f - searchP }
                            .pressable(role = Role.Button, onClick = onSearchOpen)
                            .semantics { contentDescription = label },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Sym.search, null, Modifier.size(lerp(23.dp, 21.dp, shrink))) }
                }
                if (searchP > .01f) SearchField(query, onQuery, onSubmit, mode == DockMode.Search, Modifier.graphicsLayer { alpha = searchP })
            }
        }
    }
}

@Composable
private fun TabItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    val c = if (selected) p.blue else p.label
    Column(
        Modifier
            .width(87.dp)
            .fillMaxHeight()
            .pressable(role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, Modifier.size(width = 27.dp, height = 24.dp), tint = c)
        Text(label, style = Type.tabLabel, color = c, maxLines = 1, modifier = Modifier.padding(top = 1.dp))
    }
}

@Composable
private fun ZoomSegments(zoom: Zoom, onZoom: (Zoom) -> Unit) {
    val p = LocalPalette.current
    val order = listOf(Zoom.Years, Zoom.Months, Zoom.All)
    BoxWithConstraints(Modifier.fillMaxSize().padding(3.dp)) {
        val segW = maxWidth / 3
        val idx = order.indexOf(zoom)
        val x by animateDpAsState(segW * idx, spring(dampingRatio = .72f, stiffness = 480f), label = "seg")
        Box(
            Modifier
                .offset(x = x)
                .width(segW)
                .fillMaxHeight()
                .background(if (p.dark) Color.White.copy(alpha = .14f) else Color.Black.copy(alpha = .07f), RoundedCornerShape(50))
        )
        Row(Modifier.fillMaxSize()) {
            order.forEach { z ->
                val label = stringResource(
                    when (z) {
                        Zoom.Years -> R.string.years
                        Zoom.Months -> R.string.months
                        Zoom.All -> R.string.all
                    }
                )
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .pressable(role = Role.Tab) { onZoom(z) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, style = if (z == zoom) Type.subheadSemibold else Type.subhead.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), color = p.label, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit, onSubmit: () -> Unit, active: Boolean, modifier: Modifier) {
    val p = LocalPalette.current
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(active) {
        if (active) {
            delay(260); runCatching { focus.requestFocus() }; keyboard?.show()
        } else keyboard?.hide()
    }
    Row(modifier.fillMaxSize().padding(start = 15.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Sym.search, null, Modifier.size(19.dp), tint = p.secondaryLabel)
        Box(Modifier.weight(1f).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) Text(stringResource(R.string.search_hint), style = Type.body, color = p.secondaryLabel, maxLines = 1)
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = Type.body.copy(color = p.label),
                cursorBrush = SolidColor(p.blue),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit(); keyboard?.hide() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        if (query.isNotEmpty()) {
            val label = stringResource(R.string.clear_search)
            Box(
                Modifier
                    .size(30.dp)
                    .pressable { onQuery("") }
                    .semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(19.dp).background(p.tertiaryLabel, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Sym.xmark, null, Modifier.size(11.dp), tint = if (p.dark) Color.Black else Color.White)
                }
            }
        }
    }
}
