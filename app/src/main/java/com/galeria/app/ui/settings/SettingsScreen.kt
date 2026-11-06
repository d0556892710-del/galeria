package com.galeria.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.galeria.app.R
import com.galeria.app.data.GlassMode
import com.galeria.app.data.ThemeMode
import com.galeria.app.glass.Backdrop
import com.galeria.app.glass.backdropSource
import com.galeria.app.ui.components.GlassIconButton
import com.galeria.app.ui.components.GlassSurface
import com.galeria.app.ui.components.Sym
import com.galeria.app.ui.components.pressable
import com.galeria.app.ui.root.AppViewModel
import com.galeria.app.ui.root.LocalBridge
import com.galeria.app.ui.theme.LocalPalette
import com.galeria.app.ui.theme.Type
import com.galeria.app.ui.theme.glassLook

@Composable
fun SettingsScreen(vm: AppViewModel, backdrop: Backdrop, onBack: () -> Unit) {
    val p = LocalPalette.current
    val bridge = LocalBridge.current
    val ctx = LocalContext.current
    val s by vm.settings.collectAsState()
    var lang by remember { mutableStateOf(bridge.currentLanguage()) }
    var resumes by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) resumes++ }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }
    val canManage = remember(resumes) { bridge.canManageMedia() }
    BackHandler(onBack = onBack)
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "" }

    Box(Modifier.fillMaxSize().background(p.groupedBackground)) {
        Column(
            Modifier
                .fillMaxSize()
                .backdropSource(backdrop)
                .background(p.groupedBackground)
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(top = 60.dp)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = 40.dp),
        ) {
            Text(stringResource(R.string.settings), style = Type.largeTitle, color = p.label, modifier = Modifier.padding(start = 20.dp, bottom = 18.dp))

            Header(stringResource(R.string.appearance))
            Group {
                ThemeMode.entries.forEachIndexed { i, t ->
                    if (i > 0) Divider()
                    CheckRow(
                        stringResource(
                            when (t) {
                                ThemeMode.System -> R.string.theme_system
                                ThemeMode.Light -> R.string.theme_light
                                ThemeMode.Dark -> R.string.theme_dark
                            }
                        ),
                        s.theme == t,
                    ) { vm.setTheme(t) }
                }
            }

            Header(stringResource(R.string.photo_order))
            Group {
                CheckRow(stringResource(R.string.newest_first), s.newestFirst) { vm.setNewestFirst(true) }
                Divider()
                CheckRow(stringResource(R.string.oldest_first), !s.newestFirst) { vm.setNewestFirst(false) }
            }
            Footer(stringResource(R.string.photo_order_footer))

            Header(stringResource(R.string.tab_bar_scroll))
            Group {
                CheckRow(stringResource(R.string.scroll_down), s.shrinkOnScrollDown) { vm.setShrinkDown(true) }
                Divider()
                CheckRow(stringResource(R.string.scroll_up), !s.shrinkOnScrollDown) { vm.setShrinkDown(false) }
            }
            Footer(stringResource(R.string.tab_bar_scroll_footer))

            Header(stringResource(R.string.liquid_glass))
            Group {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    GlassMode.entries.forEach { g ->
                        GlassChoice(
                            Modifier.weight(1f), g,
                            stringResource(if (g == GlassMode.Clear) R.string.glass_clear else R.string.glass_tinted),
                            s.glass == g,
                        ) { vm.setGlass(g) }
                    }
                }
            }
            Footer(stringResource(R.string.glass_footer))

            Header(stringResource(R.string.language))
            Group {
                val options = listOf("" to stringResource(R.string.lang_system), "en" to stringResource(R.string.lang_en), "he" to stringResource(R.string.lang_he))
                options.forEachIndexed { i, (tag, label) ->
                    if (i > 0) Divider()
                    val on = when (tag) {
                        "" -> lang.isBlank()
                        "he" -> lang.startsWith("he") || lang.startsWith("iw")
                        else -> lang.startsWith(tag)
                    }
                    CheckRow(label, on) {
                        if (!on) {
                            lang = tag
                            bridge.setLanguage(tag.ifBlank { null })
                        }
                    }
                }
            }

            Header(stringResource(R.string.privacy))
            Group {
                SwitchRow(stringResource(R.string.use_fingerprint), s.lockAlbums) { vm.setLock(it) }
                Divider()
                SwitchRow(stringResource(R.string.show_hidden_album), s.showHidden) { vm.setShowHidden(it) }
            }
            Footer(stringResource(R.string.use_fp_footer))

            Header(stringResource(R.string.default_gallery))
            Group {
                NavRow(stringResource(R.string.set_default), null) { bridge.openDefaultApps() }
                Divider()
                NavRow(stringResource(R.string.manage_media), stringResource(if (canManage) R.string.allowed else R.string.not_allowed)) { bridge.requestManageMedia() }
            }
            Footer(stringResource(R.string.default_footer) + "\n\n" + stringResource(R.string.manage_media_footer))

            Header(stringResource(R.string.about))
            Group { NavRow(stringResource(R.string.version), version, chevron = false) {} }
        }
        Box(Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(start = 16.dp, top = 8.dp)) {
            GlassIconButton(backdrop, Sym.chevronBack, stringResource(R.string.back), onBack)
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(
        text, style = Type.subheadSemibold, color = LocalPalette.current.label,
        modifier = Modifier.padding(start = 36.dp, end = 36.dp, top = 22.dp, bottom = 8.dp),
    )
}

@Composable
private fun Footer(text: String) {
    Text(
        text, style = Type.footnote, color = LocalPalette.current.secondaryLabel,
        modifier = Modifier.padding(start = 36.dp, end = 36.dp, top = 8.dp),
    )
}

@Composable
private fun Group(content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPalette.current
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(p.cell),
        content = content,
    )
}

@Composable
private fun Divider() {
    Box(Modifier.padding(start = 20.dp).fillMaxWidth().height(0.5.dp).background(LocalPalette.current.separator))
}

@Composable
private fun CheckRow(title: String, checked: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .pressable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = Type.body, color = p.label, modifier = Modifier.weight(1f))
        if (checked) Icon(Sym.checkmark, null, Modifier.size(18.dp), tint = p.blue)
    }
}

@Composable
private fun NavRow(title: String, value: String?, chevron: Boolean = true, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .pressable(onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = Type.body, color = p.label, modifier = Modifier.weight(1f))
        if (value != null) Text(value, style = Type.body, color = p.secondaryLabel)
        if (chevron) Icon(Sym.chevronForward, null, Modifier.padding(start = 8.dp).size(13.dp), tint = p.tertiaryLabel)
    }
}

@Composable
private fun SwitchRow(title: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalPalette.current
    val state = stringResource(if (on) R.string.allowed else R.string.not_allowed)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .pressable(role = Role.Switch) { onChange(!on) }
            .semantics { stateDescription = state }
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = Type.body, color = p.label, modifier = Modifier.weight(1f))
        IosSwitch(on)
    }
}

/** UISwitch in iOS 26 proportions: 64 × 28 capsule with a pill knob. */
@Composable
fun IosSwitch(on: Boolean) {
    val p = LocalPalette.current
    val track by animateColorAsState(if (on) p.green else p.fillStrong, label = "track")
    val x by animateDpAsState(if (on) 24.dp else 2.dp, spring(dampingRatio = .68f, stiffness = 520f), label = "knob")
    Box(Modifier.size(width = 64.dp, height = 28.dp).background(track, RoundedCornerShape(14.dp)), contentAlignment = Alignment.CenterStart) {
        Box(
            Modifier
                .offset(x = x)
                .size(width = 38.dp, height = 24.dp)
                .shadow(2.dp, RoundedCornerShape(12.dp))
                .background(Color.White, RoundedCornerShape(12.dp))
        )
    }
}

@Composable
private fun GlassChoice(modifier: Modifier, mode: GlassMode, label: String, selected: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    val look = glassLook(mode, p.dark)
    Column(modifier.pressable(role = Role.RadioButton, onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(.82f)
                .clip(RoundedCornerShape(18.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF3A7BD5), Color(0xFFF7B267), Color(0xFFE85D75)))),
            contentAlignment = Alignment.BottomCenter,
        ) {
            // A miniature tab bar using the same tint as the real glass for this option.
            Box(
                Modifier
                    .padding(bottom = 12.dp)
                    .size(width = 92.dp, height = 30.dp)
                    .shadow(6.dp, RoundedCornerShape(15.dp))
                    .background(look.tint.copy(alpha = if (mode == GlassMode.Clear) .32f else look.tint.alpha), RoundedCornerShape(15.dp))
                    .border(0.8.dp, Color.White.copy(alpha = .6f), RoundedCornerShape(15.dp))
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(label, style = Type.subheadSemibold, color = p.label)
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .size(22.dp)
                .then(if (selected) Modifier.background(p.blue, CircleShape) else Modifier.border(1.5.dp, p.tertiaryLabel, CircleShape))
                .semantics { role = Role.RadioButton },
            contentAlignment = Alignment.Center,
        ) { if (selected) Icon(Sym.checkmark, null, Modifier.size(13.dp), tint = Color.White) }
    }
}
