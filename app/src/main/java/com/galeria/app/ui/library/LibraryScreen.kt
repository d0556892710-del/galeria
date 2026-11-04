package com.galeria.app.ui.library

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.galeria.app.data.MediaItem
import com.galeria.app.ui.components.GlassIconButton
import com.galeria.app.ui.components.LumaTopBar
import com.galeria.app.ui.theme.GlassTokens

@Composable
fun LibraryScreen(
    items: List<MediaItem>,
    selectedIds: Set<Long>,
    totalCount: Int,
    groupByDate: Boolean = true,
    gridColumns: Int = 3,
    onItemClick: (MediaItem) -> Unit,
    onItemLongPress: (MediaItem) -> Unit,
    onSelectToggle: (Long) -> Unit,
    onSearchClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val grouped = remember(items) {
        val fmt = java.text.SimpleDateFormat("MMMM yyyy", java.util.Locale.getDefault())
        items.groupBy { fmt.format(java.util.Date(it.dateTaken)) }
    }
    val isSelectMode = selectedIds.isNotEmpty()

    Box(modifier = modifier.fillMaxSize().background(GlassTokens.BgPrimary)) {
        LazyColumn(
            contentPadding = PaddingValues(bottom = 100.dp)
        ) {
            // Top bar
            item {
                Column {
                    Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
                    LumaTopBar(
                        title = "LUMA",
                        subtitle = "$totalCount items",
                        onSearchClick = onSearchClick
                    )
                }
            }

            if (groupByDate) {
                grouped.forEach { (month, monthItems) ->
                    item(key = "header_$month") {
                        Text(
                            text = month,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = GlassTokens.TextPrimary,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                        )
                    }
                    item(key = "grid_$month") {
                        PhotoGrid(
                            items = monthItems,
                            columns = gridColumns,
                            selectedIds = selectedIds,
                            onItemClick = onItemClick,
                            onItemLongPress = onItemLongPress
                        )
                    }
                }
            } else {
                item {
                    PhotoGrid(
                        items = items,
                        columns = gridColumns,
                        selectedIds = selectedIds,
                        onItemClick = onItemClick,
                        onItemLongPress = onItemLongPress
                    )
                }
            }
        }

        // Select mode toolbar
        AnimatedVisibility(
            visible = isSelectMode,
            enter = slideInVertically { -it } + fadeIn(),
            exit  = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            SelectionToolbar(
                count = selectedIds.size,
                onShare = {},
                onHide  = { /* handled by parent */ },
                onDelete = {},
                onCancel = { /* handled by parent */ }
            )
        }
    }
}

@Composable
private fun PhotoGrid(
    items: List<MediaItem>,
    columns: Int,
    selectedIds: Set<Long>,
    onItemClick: (MediaItem) -> Unit,
    onItemLongPress: (MediaItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val rows = items.chunked(columns)
    Column(modifier = modifier) {
        rows.forEach { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                row.forEach { item ->
                    PhotoCell(
                        item = item,
                        isSelected = item.id in selectedIds,
                        onClick = { onItemClick(item) },
                        onLongPress = { onItemLongPress(item) },
                        modifier = Modifier.weight(1f)
                    )
                }
                // Fill empty cells
                repeat(columns - row.size) {
                    Box(modifier = Modifier.weight(1f).aspectRatio(1f))
                }
            }
        }
    }
}

@Composable
fun PhotoCell(
    item: MediaItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .padding(1.dp)
            .pointerInput(item.id) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = { onLongPress() }
                )
            }
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(item.uri)
                .crossfade(true)
                .size(300)
                .build(),
            contentDescription = item.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // Gradient overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.3f)),
                        startY = Float.MAX_VALUE / 2
                    )
                )
        )

        // Video badge
        if (item.isVideo) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 5.dp, vertical = 2.dp)
            ) {
                Text(
                    text = item.durationFormatted,
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Favorite star
        if (item.isFavorite) {
            Icon(
                Icons.Filled.Star,
                contentDescription = null,
                tint = Color.Yellow,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .size(14.dp)
            )
        }

        // Selected overlay
        if (isSelected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(GlassTokens.AccentBlue.copy(alpha = 0.4f))
            )
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = GlassTokens.AccentBlue,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(20.dp)
            )
        }
    }
}

@Composable
private fun SelectionToolbar(
    count: Int,
    onShare: () -> Unit,
    onHide: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(GlassTokens.GlassDark)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onCancel) {
            Text("Cancel", color = GlassTokens.AccentBlue)
        }
        Text("$count selected", color = GlassTokens.TextPrimary, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassIconButton(icon = Icons.Filled.Share, onClick = onShare, size = 32.dp)
            GlassIconButton(icon = Icons.Filled.Lock, onClick = onHide, size = 32.dp)
            GlassIconButton(icon = Icons.Filled.Delete, onClick = onDelete, tint = GlassTokens.Danger, size = 32.dp)
        }
    }
}
