package com.galeria.app.ui.library

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.galeria.app.data.MediaItem
import com.galeria.app.ui.theme.GlassTokens

@Composable
fun LibraryScreen(
    items: List<MediaItem>,
    selectedIds: Set<Long>,
    totalCount: Int,
    onItemClick: (MediaItem) -> Unit,
    onItemLongPress: (MediaItem) -> Unit,
    onSelectToggle: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val gridState = rememberLazyGridState()
    // Hide header when scrolled past the first item
    val firstVisible by remember { derivedStateOf { gridState.firstVisibleItemIndex } }
    val showLargeTitle = firstVisible == 0

    Box(modifier = modifier.fillMaxSize().background(GlassTokens.BgPrimary)) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            state = gridState,
            contentPadding = PaddingValues(bottom = 100.dp),
            horizontalArrangement = Arrangement.spacedBy(1.5.dp),
            verticalArrangement = Arrangement.spacedBy(1.5.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            // Header item spanning all columns
            item(span = { GridItemSpan(maxLineSpan) }) {
                LibraryHeader(
                    totalCount = totalCount,
                    isSelectMode = selectedIds.isNotEmpty(),
                    selectedCount = selectedIds.size,
                    showLargeTitle = true
                )
            }

            // Photo grid
            items(items, key = { it.id }) { item ->
                MediaGridCell(
                    item = item,
                    isSelected = item.id in selectedIds,
                    isSelectMode = selectedIds.isNotEmpty(),
                    onClick = {
                        if (selectedIds.isNotEmpty()) onSelectToggle(item.id)
                        else onItemClick(item)
                    },
                    onLongPress = { onItemLongPress(item) }
                )
            }
        }
    }
}

@Composable
private fun LibraryHeader(
    totalCount: Int,
    isSelectMode: Boolean,
    selectedCount: Int,
    showLargeTitle: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Column {
                // "Library" — big bold white title
                Text(
                    text = "Library",
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                    color = GlassTokens.TextPrimary,
                    lineHeight = 36.sp
                )
                // "4,627 Items" — subtitle in secondary color
                if (!isSelectMode) {
                    Text(
                        text = "%,d Items".format(totalCount),
                        fontSize = 13.sp,
                        color = GlassTokens.TextSecondary,
                        fontWeight = FontWeight.Normal
                    )
                } else {
                    Text(
                        text = "$selectedCount Selected",
                        fontSize = 13.sp,
                        color = GlassTokens.AccentBlue,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Right side — "Select" pill
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(GlassTokens.GlassStandard)
                    .border(0.5.dp, GlassTokens.BorderSubtle, CircleShape)
                    .clickable { /* TODO: toggle select mode */ }
                    .padding(horizontal = 14.dp, vertical = 7.dp)
            ) {
                Text(
                    text = if (isSelectMode) "Done" else "Select",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = GlassTokens.AccentBlue
                )
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
fun MediaGridCell(
    item: MediaItem,
    isSelected: Boolean,
    isSelectMode: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = { onLongPress() }
                )
            }
    ) {
        // Thumbnail
        AsyncImage(
            model = item.uri,
            contentDescription = item.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // Video badge — duration pill bottom-left
        if (item.isVideo && item.durationFormatted.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0x99000000))
                    .padding(horizontal = 5.dp, vertical = 2.dp)
            ) {
                Text(
                    text = item.durationFormatted,
                    fontSize = 11.sp,
                    color = Color.White,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // GIF badge
        if (item.isGif) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0x99000000))
                    .padding(horizontal = 5.dp, vertical = 2.dp)
            ) {
                Text("GIF", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
            }
        }

        // Favorite star top-right
        if (item.isFavorite) {
            Text(
                text = "★",
                fontSize = 14.sp,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
            )
        }

        // Selection overlay
        AnimatedVisibility(
            visible = isSelectMode,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopEnd
            ) {
                // Dim overlay when select mode active but not selected
                if (!isSelected) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.25f))
                    )
                }
                // Check circle
                Box(
                    modifier = Modifier
                        .padding(5.dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(
                            if (isSelected) GlassTokens.AccentBlue else Color(0x66000000)
                        )
                        .border(
                            1.5.dp,
                            if (isSelected) GlassTokens.AccentBlue else Color.White.copy(alpha = 0.6f),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Text("✓", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
