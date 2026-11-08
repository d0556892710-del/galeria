package com.galeria.app.ui.memories

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.galeria.app.R
import com.galeria.app.data.Memory
import com.galeria.app.ui.components.GlassIconButton
import com.galeria.app.ui.components.Sym
import com.galeria.app.ui.components.ThumbImage
import com.galeria.app.ui.theme.Type
import kotlinx.coroutines.delay

/** Plays a Memory like a short film: title card, slow zoom and drift, cross-fades. */
@Composable
fun MemoryPlayer(mem: Memory, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val items = remember(mem) { mem.items.filter { !it.isVideo }.ifEmpty { mem.items } }
    if (items.isEmpty()) { LaunchedEffect(Unit) { onClose() }; return }
    var index by remember { mutableIntStateOf(0) }
    var paused by remember { mutableStateOf(false) }
    var chrome by remember { mutableStateOf(false) }
    var titleCard by remember { mutableStateOf(true) }
    BackHandler { onClose() }
    LaunchedEffect(Unit) { delay(2800); titleCard = false }
    LaunchedEffect(index, paused) {
        if (!paused) { delay(3600); index = (index + 1) % items.size }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures(onTap = { chrome = !chrome }) }
    ) {
        Crossfade(index, animationSpec = tween(1000), label = "memory") { i ->
            val m = items[i]
            val drift = remember(i) { Animatable(0f) }
            LaunchedEffect(i) { drift.animateTo(1f, tween(4600, easing = LinearEasing)) }
            val dx = if (i % 2 == 0) 1f else -1f
            val dy = if (i % 3 == 0) 1f else -1f
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val s = 1.05f + .12f * drift.value
                        scaleX = s; scaleY = s
                        translationX = dx * 16.dp.toPx() * (drift.value - .5f)
                        translationY = dy * 12.dp.toPx() * (drift.value - .5f)
                    }
            ) {
                ThumbImage(m, 512, Modifier.fillMaxSize())
                AsyncImage(ImageRequest.Builder(ctx).data(m.uri).size(1800).build(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        AnimatedVisibility(titleCard, enter = fadeIn(tween(400)), exit = fadeOut(tween(700))) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = .15f), 1f to Color.Black.copy(alpha = .6f)))) {
                Column(Modifier.align(Alignment.Center).padding(horizontal = 30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(mem.title, style = Type.largeTitle.copy(fontSize = 38.sp), color = Color.White, textAlign = TextAlign.Center)
                    if (mem.subtitle.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(mem.subtitle, style = Type.headline, color = Color.White.copy(alpha = .9f), textAlign = TextAlign.Center)
                    }
                }
            }
        }
        // Progress, like iOS.
        Box(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 16.dp, vertical = 6.dp).height(3.dp)
                .clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = .25f))
        ) {
            Box(Modifier.fillMaxWidth((index + 1f) / items.size).height(3.dp).background(Color.White.copy(alpha = .9f)))
        }
        AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize()) {
                Row(Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(start = 16.dp, top = 18.dp)) {
                    GlassIconButton(null, Sym.xmark, stringResource(R.string.close), onClose)
                }
                Row(Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 26.dp)) {
                    GlassIconButton(null, if (paused) Sym.play else Sym.pause, stringResource(if (paused) R.string.play else R.string.pause), { paused = !paused }, size = 58.dp, iconSize = 24.dp)
                }
            }
        }
    }
}
