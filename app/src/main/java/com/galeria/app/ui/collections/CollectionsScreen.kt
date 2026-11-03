package com.galeria.app.ui.collections

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.galeria.app.data.MediaItem
import com.galeria.app.ui.theme.GlassTokens

@Composable
fun CollectionsScreen(
    allMedia: List<MediaItem>,
    favorites: List<MediaItem>,
    videos: List<MediaItem>,
    screenshots: List<MediaItem>,
    hiddenCount: Int,
    recentlyDeletedCount: Int,
    onAlbumClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(GlassTokens.BgPrimary)
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
    ) {
        Spacer(Modifier.height(8.dp))

        // Page title
        Text(
            text = "Collections",
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
            color = GlassTokens.TextPrimary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        // ── Featured Photos ──────────────────────────────────────
        if (allMedia.isNotEmpty()) {
            SectionTitle("Featured Photos")
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(allMedia.take(8)) { item ->
                    FeaturedPhotoCard(item, onClick = { onAlbumClick("featured") })
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        // ── Albums grid 2×N ─────────────────────────────────────
        SectionTitle("Albums")
        val albums = listOf(
            AlbumEntry("Recents",     allMedia.firstOrNull(), allMedia.size),
            AlbumEntry("Favorites",   favorites.firstOrNull(), favorites.size),
            AlbumEntry("Videos",      videos.firstOrNull(), videos.size),
            AlbumEntry("Screenshots", screenshots.firstOrNull(), screenshots.size),
        ).filter { it.count > 0 }

        AlbumsGrid(albums, onAlbumClick)
        Spacer(Modifier.height(24.dp))

        // ── Utility section — Hidden & Recently Deleted ──────────
        SectionTitle("Utilities")
        UtilityRow(
            label = "Hidden",
            count = hiddenCount,
            icon = "🔒",
            onClick = { onAlbumClick("hidden") }
        )
        UtilityRow(
            label = "Recently Deleted",
            count = recentlyDeletedCount,
            icon = "🗑",
            onClick = { onAlbumClick("deleted") }
        )
        Spacer(Modifier.height(100.dp))
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        fontSize = 20.sp,
        fontWeight = FontWeight.SemiBold,
        color = GlassTokens.TextPrimary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun FeaturedPhotoCard(item: MediaItem, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(width = 240.dp, height = 300.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
    ) {
        AsyncImage(
            model = item.uri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    }
}

data class AlbumEntry(val name: String, val coverItem: MediaItem?, val count: Int)

@Composable
private fun AlbumsGrid(albums: List<AlbumEntry>, onAlbumClick: (String) -> Unit) {
    // 2-column grid using nested rows
    val rows = albums.chunked(2)
    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { album ->
                    AlbumTile(
                        album = album,
                        onClick = { onAlbumClick(album.name) },
                        modifier = Modifier.weight(1f)
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun AlbumTile(
    album: AlbumEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(GlassTokens.GlassStandard)
        ) {
            if (album.coverItem != null) {
                AsyncImage(
                    model = album.coverItem.uri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = album.name,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = GlassTokens.TextPrimary
        )
        Text(
            text = "%,d".format(album.count),
            fontSize = 13.sp,
            color = GlassTokens.TextSecondary
        )
    }
}

@Composable
private fun UtilityRow(label: String, count: Int, icon: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(GlassTokens.GlassStandard),
            contentAlignment = Alignment.Center
        ) {
            Text(icon, fontSize = 20.sp)
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontSize = 16.sp, color = GlassTokens.TextPrimary, fontWeight = FontWeight.Medium)
            if (count > 0) {
                Text("%,d items".format(count), fontSize = 13.sp, color = GlassTokens.TextSecondary)
            }
        }
        Text("›", fontSize = 20.sp, color = GlassTokens.TextTertiary)
    }
    Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).padding(start = 74.dp).background(GlassTokens.BorderSubtle))
}
