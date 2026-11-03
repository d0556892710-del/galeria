package com.galeria.app.ui.viewer

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.galeria.app.data.MediaItem
import com.galeria.app.ui.theme.GlassTokens
import kotlinx.coroutines.launch

@Composable
fun PhotoViewerScreen(
    items: List<MediaItem>,
    startIndex: Int,
    onClose: () -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onShare: (MediaItem) -> Unit,
    onDelete: (MediaItem) -> Unit,
    modifier: Modifier = Modifier
) {
    var currentIndex by remember { mutableIntStateOf(startIndex.coerceIn(0, items.lastIndex)) }
    var showChrome by remember { mutableStateOf(true) }
    val current = items.getOrNull(currentIndex) ?: run { onClose(); return }

    // Swipe-down-to-dismiss state
    var offsetY by remember { mutableFloatStateOf(0f) }
    val dismissThreshold = 200f
    val scope = rememberCoroutineScope()

    val backgroundAlpha = (1f - (offsetY / 600f).coerceIn(0f, 1f))

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = backgroundAlpha))
    ) {
        // ── Main photo pager ─────────────────────────────────────
        PhotoPager(
            items = items,
            currentIndex = currentIndex,
            offsetY = offsetY,
            onDrag = { dy -> offsetY += dy },
            onDragEnd = {
                if (offsetY > dismissThreshold) {
                    onClose()
                } else {
                    scope.launch { offsetY = 0f }
                }
            },
            onPageChange = { currentIndex = it },
            onTap = { showChrome = !showChrome }
        )

        // ── Top bar ──────────────────────────────────────────────
        AnimatedVisibility(
            visible = showChrome,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Close / back
                GlassCircleButton(icon = "✕", onClick = onClose)

                // Date
                Text(
                    text = formatDate(current.dateTaken),
                    fontSize = 15.sp,
                    color = GlassTokens.TextPrimary,
                    fontWeight = FontWeight.Medium
                )

                // More options
                GlassCircleButton(icon = "⋯", onClick = {})
            }
        }

        // ── Bottom chrome — thumbnail strip + actions ─────────────
        AnimatedVisibility(
            visible = showChrome,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 8.dp)
            ) {
                // Thumbnail strip
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    itemsIndexed(items) { idx, item ->
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                                .border(
                                    if (idx == currentIndex) 2.dp else 0.dp,
                                    if (idx == currentIndex) Color.White else Color.Transparent,
                                    androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
                                )
                                .clickable { currentIndex = idx }
                        ) {
                            AsyncImage(
                                model = item.uri,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Action bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    ActionButton("⬆", "Share") { onShare(current) }
                    ActionButton(if (current.isFavorite) "♥" else "♡", "Favorite") { onToggleFavorite(current.id) }
                    ActionButton("✏", "Edit") { /* TODO: editor */ }
                    ActionButton("🗑", "Delete") { onDelete(current) }
                }
            }
        }
    }
}

@Composable
private fun PhotoPager(
    items: List<MediaItem>,
    currentIndex: Int,
    offsetY: Float,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onPageChange: (Int) -> Unit,
    onTap: () -> Unit
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(currentIndex) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 5f)
                    if (scale > 1f) {
                        panOffset += pan
                    }
                }
            }
            .pointerInput(currentIndex) {
                detectTapGestures(onTap = { onTap() })
            },
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = items.getOrNull(currentIndex)?.uri,
            contentDescription = null,
            contentScale = if (scale > 1f) ContentScale.Fit else ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .scale(scale)
                .offset(
                    x = if (scale > 1f) panOffset.x.dp else 0.dp,
                    y = (offsetY + if (scale > 1f) panOffset.y else 0f).dp
                )
        )
    }
}

@Composable
private fun GlassCircleButton(icon: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Color(0x66000000))
            .border(0.5.dp, GlassTokens.BorderSubtle, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(icon, fontSize = 16.sp, color = Color.White)
    }
}

@Composable
private fun ActionButton(icon: String, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(icon, fontSize = 24.sp, color = Color.White)
        Text(label, fontSize = 11.sp, color = GlassTokens.TextSecondary)
    }
}

private fun formatDate(ms: Long): String {
    val date = java.util.Date(ms)
    val sdf = java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault())
    return sdf.format(date)
}
