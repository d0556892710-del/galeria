package com.galeria.app.ui.editor

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.galeria.app.R
import com.galeria.app.data.Media
import com.galeria.app.glass.backdropSource
import com.galeria.app.glass.rememberBackdrop
import com.galeria.app.ui.components.GlassIconButton
import com.galeria.app.ui.components.GlassTextButton
import com.galeria.app.ui.components.LocalOverlays
import com.galeria.app.ui.components.SheetAction
import com.galeria.app.ui.components.SheetSpec
import com.galeria.app.ui.components.Sym
import com.galeria.app.ui.root.AppViewModel
import com.galeria.app.ui.root.LocalBackdrops
import com.galeria.app.ui.root.LocalBridge
import com.galeria.app.ui.theme.LocalPalette
import com.galeria.app.ui.theme.PhotosTheme
import com.galeria.app.ui.theme.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Frames of a video (any uri) for the strip, the crop canvas and filter previews. */
class FrameSource(context: Context, uri: Uri) {
    private val r = MediaMetadataRetriever()
    val durationUs: Long
    private val vw: Int
    private val vh: Int

    init {
        r.setDataSource(context, uri)
        durationUs = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000L
        val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        if (rot == 90 || rot == 270) { vw = h; vh = w } else { vw = w; vh = h }
    }

    @Synchronized
    fun frame(us: Long, maxSide: Int, exact: Boolean = false): Bitmap? = runCatching {
        val opt = if (exact) MediaMetadataRetriever.OPTION_CLOSEST else MediaMetadataRetriever.OPTION_CLOSEST_SYNC
        val s = if (vw > 0 && vh > 0) min(1f, maxSide.toFloat() / max(vw, vh)) else 1f
        if (s < 1f) r.getScaledFrameAtTime(us.coerceAtLeast(0L), opt, max(1, (vw * s).roundToInt()), max(1, (vh * s).roundToInt()))
        else r.getFrameAtTime(us.coerceAtLeast(0L), opt)
    }.getOrNull()

    @Synchronized
    fun release() = runCatching { r.release() }
}

private enum class VTab { Video, Adjust, Filters, Crop }

@Composable
fun VideoEditor(vm: AppViewModel, m: Media, onClose: () -> Unit) {
    val settings by vm.settings.collectAsState()
    PhotosTheme(true, settings.glass) { VideoEditorContent(vm, m, onClose) }
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoEditorContent(vm: AppViewModel, m: Media, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val bridge = LocalBridge.current
    val overlays = LocalOverlays.current
    val backdrops = LocalBackdrops.current
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val repo = vm.repo
    val edits = repo.edits
    val backdrop = rememberBackdrop()
    DisposableEffect(backdrop) {
        backdrops.list.add(backdrop)
        onDispose { backdrops.list.remove(backdrop) }
    }
    LaunchedEffect(Unit) { bridge.setSystemBars(true) }

    val hadEdits = remember(m.id) { edits.hasBackup(m) }
    val stored = remember(m.id) { if (hadEdits) EditParams.fromJson(edits.params(m.id)) else null }
    var params by remember { mutableStateOf(stored ?: EditParams()) }
    var initial by remember { mutableStateOf(params) }
    val color = remember { VideoColor(params) }
    color.params = params
    // Edits always start from the untouched original.
    val src = remember(m.id) { edits.sourceFile(m)?.let { Uri.fromFile(it) } ?: m.uri }
    var tab by remember { mutableStateOf(VTab.Video) }
    var tool by remember { mutableStateOf(Tool.Exposure) }
    var saving by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var frames by remember { mutableStateOf<List<ImageBitmap?>>(List(10) { null }) }
    var still by remember { mutableStateOf<Bitmap?>(null) }
    var small by remember { mutableStateOf<Bitmap?>(null) }
    var duration by remember { mutableLongStateOf(0L) }
    var session by remember { mutableStateOf<FrameSource?>(null) }
    val shader = remember { EditRender.newShader() }

    LaunchedEffect(m.id) {
        val s = withContext(Dispatchers.IO) { runCatching { FrameSource(ctx, src) }.getOrNull() }
        if (s == null) { overlays.toast(ctx.getString(R.string.edit_failed)); onClose(); return@LaunchedEffect }
        session = s
        duration = s.durationUs
        val st = withContext(Dispatchers.IO) { s.frame(min(s.durationUs / 3, 1_500_000L), 1600) }
        still = st
        if (st != null) {
            val sm = withContext(Dispatchers.Default) { scaleTo(st, 240) }
            small = sm
            val av = withContext(Dispatchers.Default) { runCatching { EditRender.autoVector(sm) }.getOrDefault(List(Tool.entries.size) { 0f }) }
            params = params.copy(autoVec = av); initial = initial.copy(autoVec = av)
        }
        val n = 10
        val list = MutableList<ImageBitmap?>(n) { null }
        for (i in 0 until n) {
            list[i] = withContext(Dispatchers.IO) { s.frame((s.durationUs * (i + .5f) / n).toLong(), 200) }?.asImageBitmap()
            frames = list.toList()
        }
    }
    DisposableEffect(session) { val s = session; onDispose { s?.release() } }

    // ---------------------------------------------------------------- player with live effects
    val player = remember { ExoPlayer.Builder(ctx).build().apply { repeatMode = Player.REPEAT_MODE_OFF } }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var aspect by remember { mutableFloatStateOf(m.aspect.coerceIn(.2f, 5f)) }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) aspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
            }
        }
        player.addListener(l)
        onDispose { player.removeListener(l); player.release() }
    }
    // The framing (rotate / flip / crop) rebuilds the pipeline; colours update live from [color].
    LaunchedEffect(params.rot, params.flip, params.crop, tab == VTab.Crop) {
        if (tab == VTab.Crop) { player.pause(); return@LaunchedEffect }
        val pos = player.currentPosition
        runCatching {
            player.stop()
            player.setVideoEffects(VideoFx.effects(params, color))
            player.setMediaItem(MediaItem.fromUri(src))
            player.prepare()
            player.seekTo(max(pos, params.trimStartUs / 1000L))
        }
    }
    LaunchedEffect(params.muted) { player.volume = if (params.muted) 0f else 1f }
    LaunchedEffect(Unit) {
        while (true) {
            position = player.currentPosition * 1000L
            val end = if (params.trimEndUs > 0) params.trimEndUs else duration
            if (player.isPlaying && end > 0 && position >= end - 30_000L) { player.pause(); player.seekTo(params.trimStartUs / 1000L) }
            delay(40)
        }
    }

    val changed = params != initial
    fun cancel() {
        if (saving) return
        if (changed) overlays.sheet(SheetSpec(null, listOf(SheetAction(ctx.getString(R.string.discard_changes), destructive = true) { onClose() })))
        else onClose()
    }
    BackHandler { cancel() }

    fun revert() {
        overlays.sheet(SheetSpec(null, listOf(SheetAction(ctx.getString(R.string.revert_to_original), destructive = true) {
            saving = true
            bridge.launch(repo.writeRequest(listOf(m))) { ok ->
                if (!ok) { saving = false; return@launch }
                scope.launch {
                    if (edits.revert(m)) { vm.setEdited(m.id, false); repo.refresh(400); onClose() }
                    else { saving = false; overlays.toast(ctx.getString(R.string.edit_failed)) }
                }
            }
        })))
    }

    fun export(replace: Boolean) {
        saving = true; progress = 0f
        player.pause()
        val pp = params
        scope.launch {
            val out = File(ctx.cacheDir, "edit_${m.id}.mp4")
            val ok = runCatching { VideoFx.export(ctx, src, pp, out, scope) { progress = it } }.getOrDefault(false)
            if (!ok || !out.exists() || out.length() == 0L) {
                saving = false; overlays.toast(ctx.getString(R.string.edit_failed)); return@launch
            }
            if (!replace) {
                val name = m.displayName.substringBeforeLast('.') + "_edited.mp4"
                val uri = withContext(Dispatchers.IO) {
                    repo.insertCopy({ out.inputStream() }, name, "video/mp4", m.relativePath.ifBlank { "Movies/" }, m.dateTaken, true)
                }
                out.delete()
                overlays.toast(ctx.getString(if (uri != null) R.string.saved_copy else R.string.edit_failed))
                repo.refresh(300); onClose(); return@launch
            }
            if (!edits.ensureBackup(m)) { saving = false; overlays.toast(ctx.getString(R.string.edit_failed)); return@launch }
            bridge.launch(repo.writeRequest(listOf(m))) { granted ->
                if (!granted) { saving = false; return@launch }
                scope.launch {
                    if (edits.writeFile(m, out)) {
                        edits.setParams(m.id, pp.toJson()); vm.setEdited(m.id, true)
                        repo.refresh(400); onClose()
                    } else { saving = false; overlays.toast(ctx.getString(R.string.edit_failed)) }
                    out.delete()
                }
            }
        }
    }

    fun done() {
        if (saving || !changed) return
        if (params.isIdentity && hadEdits) { revert(); return }
        overlays.sheet(SheetSpec(null, listOf(
            SheetAction(ctx.getString(R.string.save_video)) { export(replace = true) },
            SheetAction(ctx.getString(R.string.save_new_clip)) { export(replace = false) },
        )))
    }

    val tabs = VTab.entries
    val labels = tabs.map {
        stringResource(when (it) { VTab.Video -> R.string.tab_video; VTab.Adjust -> R.string.tab_adjust; VTab.Filters -> R.string.tab_filters; VTab.Crop -> R.string.tab_crop })
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.windowInsetsPadding(WindowInsets.statusBars).height(60.dp))
            Box(Modifier.weight(1f).fillMaxWidth().backdropSource(backdrop), contentAlignment = Alignment.Center) {
                val st = still
                if (tab == VTab.Crop && st != null) {
                    CropView(st, shader, params.copy(straighten = 0f, vertical = 0f, horizontal = 0f), onChange = { params = it }, modifier = Modifier.fillMaxSize())
                } else {
                    // Output framing: rotate swaps sides, crop takes its share.
                    val rotAspect = if (params.rot % 2 == 1) 1f / aspect else aspect
                    val outAspect = (rotAspect * params.crop.width / params.crop.height.coerceAtLeast(.01f)).coerceIn(.2f, 5f)
                    Box(
                        Modifier
                            .padding(10.dp)
                            .fillMaxWidth()
                            .aspectRatio(outAspect, matchHeightConstraintsFirst = outAspect < 1f)
                            .pointerInput(Unit) { detectTapGestures { if (player.isPlaying) player.pause() else player.play() } }
                    ) {
                        AndroidView(
                            factory = { c -> TextureView(c).also { player.setVideoTextureView(it) } },
                            onRelease = { player.clearVideoTextureView(it) },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(168.dp), contentAlignment = Alignment.Center) {
                Crossfade(tab, animationSpec = tween(160), label = "vpanel") { t ->
                    when (t) {
                        VTab.Video -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            GlassIconButton(backdrop, if (playing) Sym.pause else Sym.play, stringResource(if (playing) R.string.pause else R.string.play), {
                                if (player.isPlaying) player.pause() else {
                                    val end = if (params.trimEndUs > 0) params.trimEndUs else duration
                                    if (position < params.trimStartUs || position >= end - 50_000L) player.seekTo(params.trimStartUs / 1000L)
                                    player.play()
                                }
                            }, size = 48.dp)
                            Spacer(Modifier.height(12.dp))
                            LiveStrip(
                                frames = frames,
                                durationUs = duration,
                                scrubUs = position,
                                originalKeyUs = -1L,
                                trimStartUs = params.trimStartUs,
                                trimEndUs = params.trimEndUs,
                                onScrub = { us -> player.pause(); player.seekTo(us / 1000L); position = us },
                                onScrubEnd = {},
                                onTrim = { s, e -> params = params.copy(trimStartUs = s, trimEndUs = if (e >= duration) -1L else e); player.seekTo(s / 1000L) },
                            )
                        }
                        VTab.Adjust -> AdjustPanel(params, tool, onTool = { tool = it }, onValue = { params = params.with(tool, it) })
                        VTab.Filters -> FiltersPanel(small, params, onFilter = { f ->
                            params = params.copy(filter = f, filterAmount = if (f != params.filter) 1f else params.filterAmount)
                        }, onAmount = { params = params.copy(filterAmount = it) })
                        VTab.Crop -> AspectPicker(backdrop, params, still) { params = it }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(top = 4.dp, bottom = 12.dp), contentAlignment = Alignment.Center) {
                ModeTabs(backdrop, labels, tab.ordinal, onSelect = { tab = tabs[it] })
            }
        }

        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 16.dp).height(60.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassIconButton(backdrop, Sym.xmark, stringResource(R.string.cancel), { cancel() })
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                when (tab) {
                    VTab.Video -> GlassIconButton(
                        backdrop, if (params.muted) Sym.speakerSlash else Sym.speaker,
                        stringResource(if (params.muted) R.string.unmute else R.string.mute), { params = params.copy(muted = !params.muted) },
                        tint = if (params.muted) EditYellow.copy(alpha = .9f) else null, iconTint = if (params.muted) Color.Black else null,
                    )
                    VTab.Crop -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        GlassIconButton(backdrop, Sym.flip, stringResource(R.string.flip), {
                            params = params.copy(flip = !params.flip, crop = flipCrop(params.crop))
                        })
                        GlassIconButton(backdrop, Sym.rotate, stringResource(R.string.rotate), {
                            params = params.copy(rot = (params.rot + 1) % 4, crop = rotateCrop(params.crop), portraitAspect = !params.portraitAspect)
                        })
                        if (params.geometryChanged) GlassTextButton(backdrop, stringResource(R.string.reset).uppercase(), { params = params.resetGeometry() }, color = EditYellow)
                    }
                    else -> if (hadEdits) GlassTextButton(backdrop, stringResource(R.string.revert).uppercase(), { revert() }, color = p.red)
                }
            }
            GlassIconButton(
                backdrop, Sym.checkmark, stringResource(R.string.done), { done() },
                enabled = changed && !saving,
                tint = if (changed) EditYellow else null,
                iconTint = if (changed) Color.Black else Color.White.copy(alpha = .4f),
            )
        }

        if (saving) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = .6f)).clickable(remember { MutableInteractionSource() }, null) {},
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, color = Color.White, strokeWidth = 3.dp, trackColor = Color.White.copy(alpha = .2f), modifier = Modifier.size(44.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.saving) + "  ${(progress * 100).roundToInt()}%", style = Type.subheadSemibold, color = Color.White)
                }
            }
        }
    }
}
