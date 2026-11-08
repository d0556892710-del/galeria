package com.galeria.app.ui.viewer

import android.content.Context
import android.net.Uri
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import coil.compose.AsyncImage
import com.galeria.app.R
import com.galeria.app.data.Media
import com.galeria.app.glass.Backdrop
import com.galeria.app.ui.components.GlassSurface
import com.galeria.app.ui.components.Sym
import com.galeria.app.ui.components.duration
import com.galeria.app.ui.components.pressable
import com.galeria.app.ui.library.thumbModel
import kotlinx.coroutines.delay
import kotlin.math.max

/** One player for the viewer; it follows whichever video page is current. */
@Stable
class VideoController(context: Context) {
    val player: ExoPlayer = ExoPlayer.Builder(context).build()
    var playing by mutableStateOf(false)
    var position by mutableLongStateOf(0L)
    var length by mutableLongStateOf(0L)
    var muted by mutableStateOf(false)
    var firstFrame by mutableStateOf(false)
    var aspect by mutableFloatStateOf(0f)
    var ended by mutableStateOf(false)
    var current: Uri? = null
        private set

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onRenderedFirstFrame() { firstFrame = true }
            override fun onPlaybackStateChanged(state: Int) { ended = state == Player.STATE_ENDED }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.height > 0) aspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
            }
        })
    }

    fun load(uri: Uri) {
        if (current == uri) return
        current = uri
        firstFrame = false
        ended = false
        aspect = 0f
        position = 0L
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.volume = if (muted) 0f else 1f
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.playWhenReady = true
    }

    fun stop() {
        player.pause()
    }

    /** Plays a Live Photo's motion (once, or looping), optionally trimmed. */
    fun playLive(uri: Uri, loop: Boolean, mute: Boolean, startMs: Long, endMs: Long) {
        current = uri
        firstFrame = false
        ended = false
        val clip = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(startMs.coerceAtLeast(0L))
            .apply { if (endMs > startMs) setEndPositionMs(endMs) }
            .build()
        player.setMediaItem(MediaItem.Builder().setUri(uri).setClippingConfiguration(clip).build())
        player.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        player.volume = if (mute) 0f else 1f
        player.prepare()
        player.playWhenReady = true
    }

    fun stopLive() {
        player.pause()
        player.repeatMode = Player.REPEAT_MODE_OFF
        current = null
        firstFrame = false
    }

    fun toggle() {
        if (player.isPlaying) player.pause() else {
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
            player.play()
        }
    }

    fun seek(fraction: Float) {
        if (length > 0) player.seekTo((length * fraction.coerceIn(0f, 1f)).toLong())
        position = player.currentPosition
    }

    fun toggleMute() {
        muted = !muted
        player.volume = if (muted) 0f else 1f
    }

    fun release() = player.release()
}

@Composable
fun rememberVideoController(): VideoController {
    val ctx = LocalContext.current
    val c = remember { VideoController(ctx.applicationContext) }
    DisposableEffect(c) { onDispose { c.release() } }
    LaunchedEffect(c) {
        while (true) {
            if (c.current != null) {
                c.position = c.player.currentPosition
                c.length = max(0L, c.player.duration)
            }
            // Only a playing video needs a fast clock; otherwise wake rarely (saves battery).
            delay(if (c.playing) 100 else if (c.current != null) 400 else 1500)
        }
    }
    return c
}

@Composable
fun VideoSurface(m: Media, video: VideoController, active: Boolean) {
    LaunchedEffect(active, m.uri) {
        if (active) video.load(m.uri) else if (video.current == m.uri) video.stop()
    }
    val ratio = (if (active && video.current == m.uri && video.aspect > 0f) video.aspect else m.aspect).coerceIn(.2f, 5f)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxWidth().aspectRatio(ratio, matchHeightConstraintsFirst = ratio < 1f)) {
            if (active) {
                AndroidView(
                    factory = { ctx -> TextureView(ctx) },
                    update = { tv -> video.player.setVideoTextureView(tv) },
                    onRelease = { tv -> video.player.clearVideoTextureView(tv) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (!active || !video.firstFrame || video.current != m.uri) {
                com.galeria.app.ui.components.ThumbImage(m, 768, Modifier.fillMaxSize(), fit = true)
            }
        }
    }
}

/** iOS 26.1 video controls: play/pause, the timeline and mute in one glass capsule. */
@Composable
fun VideoBar(backdrop: Backdrop, video: VideoController) {
    val content = LocalContentColor.current
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
        GlassSurface(backdrop, Modifier.fillMaxWidth().widthIn(max = 380.dp).height(44.dp), RoundedCornerShape(22.dp)) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(Modifier.fillMaxSize().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val ppLabel = stringResource(if (video.playing) R.string.pause else R.string.play)
                    Box(Modifier.size(36.dp).pressable { video.toggle() }.semantics { contentDescription = ppLabel }, contentAlignment = Alignment.Center) {
                        Icon(if (video.playing) Sym.pause else Sym.play, null, Modifier.size(18.dp))
                    }
                    val frac = if (video.length > 0) (video.position.toFloat() / video.length).coerceIn(0f, 1f) else 0f
                    BoxWithConstraints(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .pointerInput(Unit) {
                                detectTapGestures { o -> video.seek(o.x / size.width) }
                            }
                            .pointerInput(Unit) {
                                detectHorizontalDragGestures { change, _ -> change.consume(); video.seek(change.position.x / size.width) }
                            },
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Box(Modifier.fillMaxWidth().height(4.dp).background(LocalContentColor.current.copy(alpha = .18f), RoundedCornerShape(2.dp)))
                        Box(Modifier.width(maxWidth * frac).height(4.dp).background(content, RoundedCornerShape(2.dp)))
                    }
                    Text(duration(video.position), style = com.galeria.app.ui.theme.Type.footnoteSemibold, color = content.copy(alpha = .62f), modifier = Modifier.widthIn(min = 34.dp))
                    val muteLabel = stringResource(if (video.muted) R.string.unmute else R.string.mute)
                    Box(Modifier.size(34.dp).pressable { video.toggleMute() }.semantics { contentDescription = muteLabel }, contentAlignment = Alignment.Center) {
                        Icon(if (video.muted) Sym.speakerSlash else Sym.speaker, null, Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}
