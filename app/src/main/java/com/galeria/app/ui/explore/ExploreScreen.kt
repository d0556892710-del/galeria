package com.galeria.app.ui.explore

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.galeria.app.data.MediaItem
import com.galeria.app.ui.components.LumaTopBar
import com.galeria.app.ui.theme.GlassTokens
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun ExploreScreen(
    allMedia: List<MediaItem>,
    favorites: List<MediaItem>,
    videos: List<MediaItem>,
    recentlyAdded: List<MediaItem>,
    onItemClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val memories = remember(allMedia) {
        // Group into "memories" by month
        val fmt = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
        allMedia.groupBy { fmt.format(Date(it.dateTaken)) }
            .entries.take(5).map { (month, items) -> month to items }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().background(GlassTokens.BgPrimary),
        contentPadding = PaddingValues(bottom = 100.dp)
    ) {
        item {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            LumaTopBar(title = "Explore")
        }

        // Memories
        if (memories.isNotEmpty()) {
            item {
                SectionHeader("Memories")
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(memories) { (month, items) ->
                        MemoryCard(
                            title = month,
                            coverItem = items.first(),
                            count = items.size,
                            onClick = {}
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }

        // Recently Added
        if (recentlyAdded.isNotEmpty()) {
            item {
                SectionHeader("Recently Added")
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(recentlyAdded.take(10)) { item ->
                        SmallPhotoCard(item = item, onClick = { onItemClick(item) })
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }

        // Favorites
        if (favorites.isNotEmpty()) {
            item {
                SectionHeader("Favorites")
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(favorites.take(10)) { item ->
                        SmallPhotoCard(item = item, onClick = { onItemClick(item) })
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }

        // Videos
        if (videos.isNotEmpty()) {
            item {
                SectionHeader("Videos")
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(videos.take(10)) { item ->
                        VideoCard(item = item, onClick = { onItemClick(item) })
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }

        // By Month grid
        item {
            SectionHeader("By Month")
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                memories.take(6).forEach { (month, items) ->
                    MonthRow(month = month, item = items.first(), count = items.size)
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = GlassTokens.TextPrimary)
        Text("See All", fontSize = 14.sp, color = GlassTokens.AccentBlue)
    }
}

@Composable
private fun MemoryCard(
    title: String,
    coverItem: MediaItem,
    count: Int,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .width(200.dp)
            .height(120.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(coverItem.uri).crossfade(true).build(),
            contentDescription = title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(0.7f))
                    )
                )
        )
        Column(
            modifier = Modifier.align(Alignment.BottomStart).padding(10.dp)
        ) {
            Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text("$count photos", color = Color.White.copy(0.7f), fontSize = 11.sp)
        }
    }
}

@Composable
private fun SmallPhotoCard(item: MediaItem, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(80.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(item.uri).crossfade(true).build(),
            contentDescription = item.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun VideoCard(item: MediaItem, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .width(130.dp)
            .height(80.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(item.uri).crossfade(true).build(),
            contentDescription = item.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(0.3f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.PlayCircle, null, tint = Color.White.copy(0.9f), modifier = Modifier.size(28.dp))
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(4.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.Black.copy(0.7f))
                .padding(horizontal = 4.dp, vertical = 2.dp)
        ) {
            Text(item.durationFormatted, color = Color.White, fontSize = 9.sp)
        }
    }
}

@Composable
private fun MonthRow(month: String, item: MediaItem, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(GlassTokens.GlassUltraClear)
            .border(1.dp, GlassTokens.BorderSubtle, RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(item.uri).crossfade(true).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(month, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = GlassTokens.TextPrimary)
            Text("$count items", fontSize = 12.sp, color = GlassTokens.TextSecondary)
        }
        Icon(Icons.Filled.ChevronRight, null, tint = GlassTokens.TextTertiary, modifier = Modifier.size(16.dp))
    }
}
