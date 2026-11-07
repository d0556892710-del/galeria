package com.galeria.app.ui.editor

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galeria.app.R
import com.galeria.app.data.Media
import com.galeria.app.data.MotionStore
import com.galeria.app.glass.backdropSource
import com.galeria.app.glass.rememberBackdrop
import com.galeria.app.ui.components.GlassIconButton
import com.galeria.app.ui.components.GlassSurface
import com.galeria.app.ui.components.GlassTextButton
import com.galeria.app.ui.components.LocalOverlays
import com.galeria.app.ui.components.SheetAction
import com.galeria.app.ui.components.SheetSpec
import com.galeria.app.ui.components.Sym
import com.galeria.app.ui.components.pressable
import com.galeria.app.ui.root.AppViewModel
import com.galeria.app.ui.root.LocalBackdrops
import com.galeria.app.ui.root.LocalBridge
import com.galeria.app.ui.theme.LocalPalette
import com.galeria.app.ui.theme.PhotosTheme
import com.galeria.app.ui.theme.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class EditTab { Live, Adjust, Filters, Crop }
private enum class CropTool { Straighten, Vertical, Horizontal }

@Composable
fun Editor(vm: AppViewModel, m: Media, onClose: () -> Unit) {
    val settings by vm.settings.collectAsState()
    PhotosTheme(true, settings.glass) { EditorContent(vm, m, onClose) }
}

@Composable
private fun EditorContent(vm: AppViewModel, m: Media, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val bridge = LocalBridge.current
    val overlays = LocalOverlays.current
    val backdrops = LocalBackdrops.current
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val repo = vm.repo
    val edits = repo.edits
    val tags by repo.motion.tags.collectAsState()
    val isLive = m.id in tags.live
    val backdrop = rememberBackdrop()
    DisposableEffect(backdrop) {
        backdrops.list.add(backdrop)
        onDispose { backdrops.list.remove(backdrop) }
    }
    LaunchedEffect(Unit) { bridge.setSystemBars(true) }

    val conf = LocalConfiguration.current
    val density = LocalDensity.current
    val maxPreview = remember { with(density) { max(conf.screenWidthDp, conf.screenHeightDp).dp.toPx() }.roundToInt().coerceIn(1280, 2400) }
    val hadEdits = remember(m.id) { edits.hasBackup(m) }
    val stored = remember(m.id) { if (hadEdits) EditParams.fromJson(edits.params(m.id)) else null }
    var params by remember { mutableStateOf(stored ?: EditParams()) }
    var initial by remember { mutableStateOf(params) }
    var original by remember { mutableStateOf<Bitmap?>(null) }
    var keyFrame by remember { mutableStateOf<Bitmap?>(null) }
    var scrubFrame by remember { mutableStateOf<Bitmap?>(null) }
    var small by remember { mutableStateOf<Bitmap?>(null) }
    var tab by remember { mutableStateOf(EditTab.Adjust) }
    var tool by remember { mutableStateOf(Tool.Auto) }
    var cropTool by remember { mutableStateOf(CropTool.Straighten) }
    var aspectOpen by remember { mutableStateOf(false) }
    var comparing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val lastValues = remember { HashMap<Tool, Float>() }

    var live by remember { mutableStateOf<LiveSession?>(null) }
    var liveFile by remember { mutableStateOf<File?>(null) }
    var origKeyUs by remember { mutableLongStateOf(-1L) }
    var frames by remember { mutableStateOf<List<ImageBitmap?>>(List(12) { null }) }
    var scrubUs by remember { mutableLongStateOf(-1L) }
    var scrubbing by remember { mutableStateOf(false) }
    val shader = remember { EditRender.newShader() }

    LaunchedEffect(m.id) {
        val b = edits.decodePreview(m, maxPreview)
        if (b == null) {
            overlays.toast(ctx.getString(R.string.edit_failed)); onClose(); return@LaunchedEffect
        }
        original = b
        val sm = withContext(Dispatchers.Default) { scaleTo(b, 240) }
        small = sm
        val av = withContext(Dispatchers.Default) { runCatching { EditRender.autoVector(sm) }.getOrDefault(List(Tool.entries.size) { 0f }) }
        params = params.copy(autoVec = av)
        initial = initial.copy(autoVec = av)
    }
    LaunchedEffect(isLive) {
        if (!isLive) return@LaunchedEffect
        val (f, info) = repo.motion.video(m) ?: return@LaunchedEffect
        val s = withContext(Dispatchers.IO) { runCatching { LiveSession(f) }.getOrNull() } ?: return@LaunchedEffect
        liveFile = f
        live = s
        origKeyUs = if (info.keyUs in 0..s.durationUs) info.keyUs else s.durationUs / 2
        scrubUs = if (params.keyUs >= 0) params.keyUs else origKeyUs
        if (params.keyUs >= 0) keyFrame = withContext(Dispatchers.IO) { s.frame(params.keyUs, maxPreview) }
        val n = 12
        val list = MutableList<ImageBitmap?>(n) { null }
        for (i in 0 until n) {
            val t = (s.durationUs * (i + .5f) / n).toLong()
            list[i] = withContext(Dispatchers.IO) { s.frame(t, 220, exact = false) }?.asImageBitmap()
            frames = list.toList()
        }
    }
    DisposableEffect(live) { val s = live; onDispose { s?.release() } }
    LaunchedEffect(live) {
        val s = live ?: return@LaunchedEffect
        snapshotFlow { scrubUs to scrubbing }.conflate().collect { (us, active) ->
            if (us < 0) return@collect
            scrubFrame = withContext(Dispatchers.IO) { s.frame(us, if (active) 1280 else maxPreview) }
        }
    }

    val keyNow = if (params.keyUs >= 0) params.keyUs else origKeyUs
    val atKey = abs(scrubUs - keyNow) < 34_000L
    val editBase: Bitmap? = if (params.keyUs >= 0) keyFrame ?: original else original
    val shown: Bitmap? = when {
        comparing -> original
        tab == EditTab.Live && !atKey && scrubFrame != null -> scrubFrame
        else -> editBase
    }
    val changed = params != initial

    fun cancel() {
        if (saving) return
        if (changed) overlays.sheet(SheetSpec(null, listOf(SheetAction(ctx.getString(R.string.discard_changes), destructive = true) { onClose() })))
        else onClose()
    }
    BackHandler { cancel() }

    suspend fun buildBytes(pp: EditParams): ByteArray? {
        if (!edits.ensureBackup(m)) return null
        if (pp.isIdentity) return edits.backup(m).readBytes()
        val session = live
        val src = if (pp.keyUs >= 0 && session != null) withContext(Dispatchers.IO) { session.frame(pp.keyUs, 6144) } else edits.decodeFull(m)
        src ?: return null
        val out = withContext(Dispatchers.Default) { EditRender.render(src, pp) }
        val png = m.mime.equals("image/png", true)
        var bytes = edits.encode(m, out, png)
        if (isLive && !pp.liveOff && !png) {
            val v = withContext(Dispatchers.IO) { edits.originalVideo(m, repo.motion) }
            if (v != null) bytes = MotionStore.buildMotionJpeg(bytes, v, if (pp.keyUs >= 0) pp.keyUs else origKeyUs)
        }
        return bytes
    }

    fun save() {
        if (saving || !changed) return
        saving = true
        val pp = params
        scope.launch {
            val bytes = runCatching { buildBytes(pp) }.getOrNull()
            if (bytes == null) {
                saving = false; overlays.toast(ctx.getString(R.string.edit_failed)); return@launch
            }
            val inPlace = MotionStore.isJpeg(m) || m.mime.equals("image/png", true)
            if (!inPlace) {
                val name = m.displayName.substringBeforeLast('.') + "_edited.jpg"
                val uri = withContext(Dispatchers.IO) { repo.insertCopy({ ByteArrayInputStream(bytes) }, name, "image/jpeg", m.relativePath.ifBlank { "Pictures/" }, m.dateTaken, false) }
                saving = false
                overlays.toast(ctx.getString(if (uri != null) R.string.saved_copy else R.string.edit_failed))
                repo.refresh(200); onClose(); return@launch
            }
            bridge.launch(repo.writeRequest(listOf(m))) { ok ->
                if (!ok) { saving = false; return@launch }
                scope.launch {
                    if (edits.write(m, bytes)) {
                        if (pp.isIdentity) { edits.backup(m).delete(); edits.setParams(m.id, null); vm.setEdited(m.id, false) }
                        else { edits.setParams(m.id, pp.toJson()); vm.setEdited(m.id, true) }
                        repo.refresh(250)
                        onClose()
                    } else {
                        saving = false; overlays.toast(ctx.getString(R.string.edit_failed))
                    }
                }
            }
        }
    }

    fun revert() {
        overlays.sheet(SheetSpec(null, listOf(SheetAction(ctx.getString(R.string.revert_to_original), destructive = true) {
            saving = true
            bridge.launch(repo.writeRequest(listOf(m))) { ok ->
                if (!ok) { saving = false; return@launch }
                scope.launch {
                    if (edits.revert(m)) { vm.setEdited(m.id, false); repo.refresh(250); onClose() }
                    else { saving = false; overlays.toast(ctx.getString(R.string.edit_failed)) }
                }
            }
        })))
    }

    val tabs = if (isLive) listOf(EditTab.Live, EditTab.Adjust, EditTab.Filters, EditTab.Crop) else listOf(EditTab.Adjust, EditTab.Filters, EditTab.Crop)
    val tabLabels = tabs.map {
        stringResource(when (it) { EditTab.Live -> R.string.tab_live; EditTab.Adjust -> R.string.tab_adjust; EditTab.Filters -> R.string.tab_filters; EditTab.Crop -> R.string.tab_crop })
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.windowInsetsPadding(WindowInsets.statusBars).height(60.dp))
            // ------------------------------ photo ------------------------------
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .backdropSource(backdrop)
            ) {
                val b = shown
                if (b != null) {
                    if (tab == EditTab.Crop && !comparing) {
                        CropView(b, shader, params, onChange = { params = it }, modifier = Modifier.fillMaxSize())
                    } else {
                        EditPreview(
                            b, if (comparing) EditParams() else params, shader,
                            Modifier
                                .fillMaxSize()
                                .padding(horizontal = 10.dp, vertical = 10.dp)
                                .pointerInput(Unit) {
                                    detectTapGestures(onPress = {
                                        comparing = true
                                        tryAwaitRelease()
                                        comparing = false
                                    })
                                },
                        )
                    }
                } else {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.align(Alignment.Center).size(28.dp))
                }
                if (comparing) {
                    GlassSurface(backdrop, Modifier.align(Alignment.TopCenter).padding(top = 8.dp).height(30.dp), RoundedCornerShape(15.dp)) {
                        Text(stringResource(R.string.a_original).uppercase(), style = Type.caption1Semibold.copy(letterSpacing = 1.sp), color = Color.White, modifier = Modifier.padding(horizontal = 12.dp))
                    }
                }
            }
            // ------------------------------ panel ------------------------------
            Box(Modifier.fillMaxWidth().height(168.dp), contentAlignment = Alignment.Center) {
                Crossfade(tab, animationSpec = tween(160), label = "panel") { t ->
                    when (t) {
                        EditTab.Adjust -> AdjustPanel(params, tool, onTool = { nt ->
                            if (nt == tool) {
                                val v = params.eff(tool)
                                params = if (tool == Tool.Auto) params.with(Tool.Auto, if (v > 0f) 0f else 1f)
                                else if (abs(v) > .004f) { lastValues[tool] = v; params.with(tool, 0f) }
                                else params.with(tool, lastValues[tool] ?: 0f)
                            } else tool = nt
                        }, onValue = { params = params.with(tool, it) })
                        EditTab.Filters -> FiltersPanel(small, params, onFilter = { f ->
                            params = params.copy(filter = f, filterAmount = if (f != params.filter) 1f else params.filterAmount)
                        }, onAmount = { params = params.copy(filterAmount = it) })
                        EditTab.Crop -> if (aspectOpen) AspectPicker(backdrop, params, original) { params = it } else CropPanel(
                            params, cropTool, aspectOpen, original,
                            onTool = { cropTool = it },
                            onParams = { params = it },
                        )
                        EditTab.Live -> LivePanel(
                            frames = frames,
                            durationUs = live?.durationUs ?: 0L,
                            scrubUs = scrubUs,
                            origKeyUs = origKeyUs,
                            showMake = !atKey && !params.liveOff,
                            params = params,
                            backdrop = backdrop,
                            onScrub = { scrubbing = true; scrubUs = it },
                            onScrubEnd = { scrubbing = false },
                            onTrim = { s, e -> params = params.copy(trimStartUs = s, trimEndUs = if (e >= (live?.durationUs ?: 0L)) -1L else e) },
                            onMakeKey = {
                                val us = scrubUs
                                params = params.copy(keyUs = if (abs(us - origKeyUs) < 34_000L) -1L else us)
                                keyFrame = scrubFrame
                                scope.launch { live?.let { s -> keyFrame = withContext(Dispatchers.IO) { s.frame(us, maxPreview) } ?: keyFrame } }
                            },
                        )
                    }
                }
            }
            // ------------------------------ tabs ------------------------------
            Box(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(top = 4.dp, bottom = 12.dp), contentAlignment = Alignment.Center) {
                ModeTabs(backdrop, tabLabels, tabs.indexOf(tab).coerceAtLeast(0), onSelect = { i ->
                    tab = tabs[i]; aspectOpen = false
                    if (tab == EditTab.Live) scrubUs = keyNow
                })
            }
        }

        // ------------------------------ top bar ------------------------------
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 16.dp)
                .height(60.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassIconButton(backdrop, Sym.xmark, stringResource(R.string.cancel), { cancel() })
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                when (tab) {
                    EditTab.Crop -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        GlassIconButton(backdrop, Sym.flip, stringResource(R.string.flip), {
                            params = params.copy(flip = !params.flip, straighten = -params.straighten, horizontal = -params.horizontal, crop = flipCrop(params.crop))
                        })
                        GlassIconButton(backdrop, Sym.rotate, stringResource(R.string.rotate), {
                            params = params.copy(rot = (params.rot + 1) % 4, crop = rotateCrop(params.crop), portraitAspect = !params.portraitAspect)
                        })
                        GlassIconButton(
                            backdrop, Sym.aspect, stringResource(R.string.aspect_ratio), { aspectOpen = !aspectOpen },
                            tint = if (aspectOpen || params.aspect != Aspect.Freeform) EditYellow.copy(alpha = .9f) else null,
                            iconTint = if (aspectOpen || params.aspect != Aspect.Freeform) Color.Black else null,
                        )
                        if (params.geometryChanged) GlassTextButton(backdrop, stringResource(R.string.reset).uppercase(), { params = params.resetGeometry() }, color = EditYellow)
                    }
                    EditTab.Live -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        GlassSurface(backdrop, Modifier.height(44.dp).pressable { params = params.copy(liveOff = !params.liveOff) }, RoundedCornerShape(22.dp)) {
                            Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                val on = !params.liveOff
                                Icon(if (on) Sym.live else Sym.liveOff, null, tint = if (on) EditYellow else Color.White.copy(alpha = .7f), modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(if (on) R.string.live_label else R.string.live_off), style = Type.subheadSemibold.copy(letterSpacing = 1.sp), color = if (on) EditYellow else Color.White.copy(alpha = .7f))
                            }
                        }
                        GlassIconButton(
                            backdrop, if (params.muted) Sym.speakerSlash else Sym.speaker,
                            stringResource(if (params.muted) R.string.unmute else R.string.mute), { params = params.copy(muted = !params.muted) },
                        )
                    }
                    else -> if (hadEdits) GlassTextButton(backdrop, stringResource(R.string.revert).uppercase(), { revert() }, color = p.red)
                }
            }
            GlassIconButton(
                backdrop, Sym.checkmark, stringResource(R.string.done), { save() },
                enabled = changed && !saving,
                tint = if (changed) EditYellow else null,
                iconTint = if (changed) Color.Black else Color.White.copy(alpha = .4f),
            )
        }

        if (saving) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = .55f)).clickable(remember { MutableInteractionSource() }, null) {},
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.5.dp, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.saving), style = Type.subheadSemibold, color = Color.White)
                }
            }
        }
    }
}

/** The edited photo, fitted to the space. Only redraws when values change. */
@Composable
private fun EditPreview(bmp: Bitmap, params: EditParams, shader: Any?, modifier: Modifier) {
    val paint = remember { Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG) }
    val input = remember(bmp) { BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
    Canvas(modifier) {
        val w = bmp.width.toFloat(); val h = bmp.height.toFloat()
        val (ow, oh) = EditRender.outputSize(params, w, h)
        val (s, o) = EditRender.fit(ow, oh, size.width, size.height)
        drawIntoCanvas { c ->
            val nc = c.nativeCanvas
            nc.save()
            nc.translate(o.x, o.y)
            EditRender.draw(nc, bmp, params, shader, s, paint, input)
            nc.restore()
        }
    }
}

@Composable
private fun AdjustPanel(params: EditParams, tool: Tool, onTool: (Tool) -> Unit, onValue: (Float) -> Unit) {
    val state = rememberLazyListState()
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        ToolLabel(stringResource(tool.label))
        Spacer(Modifier.height(8.dp))
        SnapRow(Tool.entries, tool.ordinal, onSelect = { onTool(Tool.entries[it]) }, state = state, itemWidth = 50.dp, gap = 12.dp) { _, t, sel ->
            ToolCircle(t, params.eff(t), sel)
        }
        Spacer(Modifier.height(4.dp))
        Ruler(params.eff(tool), tool.min, 1f, onValue, Modifier.padding(horizontal = 8.dp))
    }
}

@Composable
private fun FiltersPanel(small: Bitmap?, params: EditParams, onFilter: (Filter) -> Unit, onAmount: (Float) -> Unit) {
    val state = rememberLazyListState()
    val shaders = remember { Filter.entries.map { EditRender.newShader() } }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        ToolLabel(stringResource(params.filter.label))
        Spacer(Modifier.height(8.dp))
        SnapRow(Filter.entries, params.filter.ordinal, onSelect = { onFilter(Filter.entries[it]) }, state = state, itemWidth = 62.dp, gap = 8.dp) { i, f, sel ->
            FilterThumb(small, params, f, shaders[i], sel)
        }
        Spacer(Modifier.height(4.dp))
        if (params.filter != Filter.Original) Ruler(params.filterAmount, 0f, 1f, onAmount, Modifier.padding(horizontal = 8.dp), unitsPerTick = .02f)
        else Spacer(Modifier.height(50.dp))
    }
}

@Composable
private fun FilterThumb(small: Bitmap?, params: EditParams, f: Filter, shader: Any?, selected: Boolean) {
    val paint = remember { Paint(Paint.FILTER_BITMAP_FLAG) }
    val input = remember(small) { small?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) } }
    Box(Modifier.size(if (selected) 62.dp else 54.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = .08f))) {
        val b = small ?: return@Box
        Canvas(Modifier.fillMaxSize()) {
            val w = b.width.toFloat(); val h = b.height.toFloat()
            val s = max(size.width / w, size.height / h)
            drawIntoCanvas { c ->
                val nc = c.nativeCanvas
                nc.save()
                nc.translate((size.width - w * s) / 2f, (size.height - h * s) / 2f)
                nc.scale(s, s)
                if (shader is RuntimeShader && input != null) {
                    EditRender.configure(shader, params, w, h, input, filterOverride = f, colourOnly = true)
                    paint.shader = shader; paint.colorFilter = null
                } else {
                    paint.shader = input; paint.colorFilter = android.graphics.ColorMatrixColorFilter(EditRender.colorMatrix(params, f))
                }
                nc.drawRect(0f, 0f, w, h, paint)
                nc.restore()
            }
        }
    }
}

@Composable
private fun CropPanel(
    params: EditParams, cropTool: CropTool, aspectOpen: Boolean, original: Bitmap?,
    onTool: (CropTool) -> Unit, onParams: (EditParams) -> Unit,
) {
    val labels = listOf(R.string.straighten, R.string.vertical, R.string.horizontal)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        val v = when (cropTool) { CropTool.Straighten -> params.straighten; CropTool.Vertical -> params.vertical * 100f; CropTool.Horizontal -> params.horizontal * 100f }
        ToolLabel(stringResource(labels[cropTool.ordinal]) + "  " + (if (cropTool == CropTool.Straighten) "${v.roundToInt()}°" else "${v.roundToInt()}"))
        Spacer(Modifier.height(10.dp))
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.CenterVertically) {
                CropTool.entries.forEach { t ->
                    val value = when (t) { CropTool.Straighten -> params.straighten / 45f; CropTool.Vertical -> params.vertical; CropTool.Horizontal -> params.horizontal }
                    Box(Modifier.size(52.dp).pressable {
                        if (t == cropTool) {
                            onParams(when (t) { CropTool.Straighten -> params.copy(straighten = 0f); CropTool.Vertical -> params.copy(vertical = 0f); CropTool.Horizontal -> params.copy(horizontal = 0f) })
                        } else onTool(t)
                    }, contentAlignment = Alignment.Center) {
                        CropToolCircle(t, value, t == cropTool)
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        when (cropTool) {
            CropTool.Straighten -> Ruler(params.straighten, -45f, 45f, { onParams(params.copy(straighten = it)) }, Modifier.padding(horizontal = 8.dp), unitsPerTick = 1f)
            CropTool.Vertical -> Ruler(params.vertical, -1f, 1f, { onParams(params.copy(vertical = it)) }, Modifier.padding(horizontal = 8.dp))
            CropTool.Horizontal -> Ruler(params.horizontal, -1f, 1f, { onParams(params.copy(horizontal = it)) }, Modifier.padding(horizontal = 8.dp))
        }
    }
}

@Composable
private fun CropToolCircle(t: CropTool, value: Float, selected: Boolean) {
    Box(Modifier.size(50.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f - 2.dp.toPx()
            drawCircle(Color.White.copy(alpha = if (selected) .9f else .28f), r, style = androidx.compose.ui.graphics.drawscope.Stroke(if (selected) 1.6.dp.toPx() else 1.2.dp.toPx()))
            if (abs(value) > .004f) drawArc(
                EditYellow, -90f, 360f * value, false,
                topLeft = androidx.compose.ui.geometry.Offset(size.width / 2f - r, size.height / 2f - r), size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
                style = androidx.compose.ui.graphics.drawscope.Stroke(2.4.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round),
            )
            val c = Color.White
            val sw = 1.8.dp.toPx()
            val cx = size.width / 2f; val cy = size.height / 2f; val k = size.width * .2f
            when (t) {
                CropTool.Straighten -> {
                    drawLine(c, androidx.compose.ui.geometry.Offset(cx - k, cy + k * .35f), androidx.compose.ui.geometry.Offset(cx + k, cy - k * .35f), sw)
                    drawCircle(c, sw, androidx.compose.ui.geometry.Offset(cx, cy))
                }
                CropTool.Vertical -> {
                    val path = androidx.compose.ui.graphics.Path().apply { moveTo(cx - k * .55f, cy - k); lineTo(cx + k * .55f, cy - k); lineTo(cx + k, cy + k); lineTo(cx - k, cy + k); close() }
                    drawPath(path, c, style = androidx.compose.ui.graphics.drawscope.Stroke(sw))
                }
                CropTool.Horizontal -> {
                    val path = androidx.compose.ui.graphics.Path().apply { moveTo(cx - k, cy - k * .55f); lineTo(cx + k, cy - k); lineTo(cx + k, cy + k); lineTo(cx - k, cy + k * .55f); close() }
                    drawPath(path, c, style = androidx.compose.ui.graphics.drawscope.Stroke(sw))
                }
            }
        }
    }
}

@Composable
private fun AspectPicker(backdrop: com.galeria.app.glass.Backdrop, params: EditParams, original: Bitmap?, onParams: (EditParams) -> Unit) {
    val w = original?.width?.toFloat() ?: 1f
    val h = original?.height?.toFloat() ?: 1f
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val squareish = params.aspect == Aspect.Square || params.aspect == Aspect.Freeform
            GlassIconButton(
                backdrop, Sym.portraitRect, stringResource(R.string.a_portrait), { if (!params.portraitAspect) onParams(applyAspect(params.copy(portraitAspect = true), w, h)) },
                enabled = !squareish, tint = if (params.portraitAspect && !squareish) Color.White.copy(alpha = .9f) else null, iconTint = if (params.portraitAspect && !squareish) Color.Black else null,
            )
            GlassIconButton(
                backdrop, Sym.landscapeRect, stringResource(R.string.a_landscape), { if (params.portraitAspect) onParams(applyAspect(params.copy(portraitAspect = false), w, h)) },
                enabled = !squareish, tint = if (!params.portraitAspect && !squareish) Color.White.copy(alpha = .9f) else null, iconTint = if (!params.portraitAspect && !squareish) Color.Black else null,
            )
        }
        Spacer(Modifier.height(10.dp))
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
            ) {
                items(Aspect.entries) { a ->
                    val on = a == params.aspect
                    GlassSurface(
                        backdrop,
                        Modifier.height(34.dp).pressable {
                            val base = params.copy(aspect = a, portraitAspect = if (a == Aspect.Original) (EditRender.baseSize(params, w, h).let { it.second > it.first }) else params.portraitAspect)
                            onParams(applyAspect(base, w, h))
                        },
                        RoundedCornerShape(17.dp),
                        tint = if (on) Color.White.copy(alpha = .92f) else null,
                    ) {
                        Text(stringResource(a.label).uppercase(), style = Type.caption1Semibold.copy(letterSpacing = .6.sp), color = if (on) Color.Black else Color.White, modifier = Modifier.padding(horizontal = 14.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun LivePanel(
    frames: List<ImageBitmap?>,
    durationUs: Long,
    scrubUs: Long,
    origKeyUs: Long,
    showMake: Boolean,
    params: EditParams,
    backdrop: com.galeria.app.glass.Backdrop,
    onScrub: (Long) -> Unit,
    onScrubEnd: () -> Unit,
    onTrim: (Long, Long) -> Unit,
    onMakeKey: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.height(48.dp), contentAlignment = Alignment.Center) {
            androidx.compose.animation.AnimatedVisibility(showMake, enter = fadeIn(tween(140)), exit = fadeOut(tween(140))) {
                GlassSurface(backdrop, Modifier.height(38.dp).pressable(onClick = onMakeKey), RoundedCornerShape(19.dp)) {
                    Text(stringResource(R.string.make_key_photo), style = Type.subheadSemibold, color = Color.White, modifier = Modifier.padding(horizontal = 18.dp))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        LiveStrip(
            frames = frames,
            durationUs = durationUs,
            scrubUs = if (scrubUs < 0) origKeyUs else scrubUs,
            originalKeyUs = origKeyUs,
            trimStartUs = params.trimStartUs,
            trimEndUs = params.trimEndUs,
            onScrub = onScrub,
            onScrubEnd = onScrubEnd,
            onTrim = onTrim,
        )
    }
}

private fun scaleTo(b: Bitmap, side: Int): Bitmap {
    val s = side.toFloat() / max(b.width, b.height)
    if (s >= 1f) return b
    return Bitmap.createScaledBitmap(b, max(1, (b.width * s).toInt()), max(1, (b.height * s).toInt()), true)
}

@Suppress("unused")
private fun clampUnit(v: Float) = min(1f, max(0f, v))
