package com.galeria.app.ui.albums

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.galeria.app.data.MediaItem
import com.galeria.app.ui.components.*
import com.galeria.app.ui.theme.GlassTokens

data class Album(
    val key: String,
    val name: String,
    val count: Int,
    val coverItem: MediaItem?,
    val icon: ImageVector? = null,
    val iconTint: Color = GlassTokens.AccentBlue,
    val isLocked: Boolean = false
)

@Composable
fun AlbumsScreen(
    allMedia: List<MediaItem>,
    favorites: List<MediaItem>,
    videos: List<MediaItem>,
    screenshots: List<MediaItem>,
    downloads: List<MediaItem>,
    whatsapp: List<MediaItem>,
    recentlyAdded: List<MediaItem>,
    hiddenCount: Int,
    showHiddenAlbum: Boolean,
    recentlyDeletedCount: Int,
    onAlbumClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val builtInAlbums = buildList {
        add(Album("all",      "All Photos",          allMedia.size,          allMedia.firstOrNull(), Icons.Filled.Photo))
        add(Album("favorites","Favorites",            favorites.size,         favorites.firstOrNull(), Icons.Filled.Favorite, GlassTokens.Danger))
        add(Album("videos",   "Videos",              videos.size,            videos.firstOrNull(), Icons.Filled.PlayCircle, GlassTokens.AccentBlue))
        add(Album("screenshots","Screenshots",       screenshots.size,       screenshots.firstOrNull(), Icons.Filled.Screenshot, GlassTokens.AccentPurple))
        add(Album("downloads","Downloads",            downloads.size,         downloads.firstOrNull(), Icons.Filled.Download, GlassTokens.Success))
        if (whatsapp.isNotEmpty()) {
            add(Album("whatsapp","WhatsApp",          whatsapp.size,         whatsapp.firstOrNull(), Icons.Filled.Message, GlassTokens.Success))
        }
        add(Album("recent",   "Recently Added",      recentlyAdded.size,     recentlyAdded.firstOrNull(), Icons.Filled.NewReleases, GlassTokens.AccentTeal))
    }

    val utilityAlbums = buildList {
        if (showHiddenAlbum) {
            add(Album("hidden", "Hidden", hiddenCount, null, Icons.Filled.Lock, GlassTokens.TextSecondary, isLocked = true))
        }
        add(Album("deleted", "Recently Deleted", recentlyDeletedCount, null, Icons.Filled.DeleteSweep, GlassTokens.Danger))
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().background(GlassTokens.BgPrimary),
        contentPadding = PaddingValues(bottom = 100.dp)
    ) {
        item {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            LumaTopBar(title = "Albums")
        }

        // Grid of albums
        item {
            Text(
                "My Albums",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = GlassTokens.TextPrimary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
        }
        item {
            AlbumGrid(albums = builtInAlbums, onAlbumClick = onAlbumClick)
        }

        // Utility section
        item {
            Spacer(Modifier.height(24.dp))
            GlassSection(title = "Utilities") {
                utilityAlbums.forEachIndexed { idx, album ->
                    if (idx > 0) HorizontalDivider(color = GlassTokens.BorderSubtle, thickness = 0.5.dp)
                    GlassSettingsRow(
                        icon = album.icon ?: Icons.Filled.Photo,
                        title = album.name,
                        subtitle = "${album.count} items",
                        iconTint = album.iconTint,
                        onClick = { onAlbumClick(album.key) },
                        trailing = if (album.isLocked) {
                            { Icon(Icons.Filled.Lock, null, tint = GlassTokens.TextTertiary, modifier = Modifier.size(14.dp)) }
                        } else null
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun AlbumGrid(albums: List<Album>, onAlbumClick: (String) -> Unit) {
    val rows = albums.chunked(2)
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                row.forEach { album ->
                    AlbumCard(
                        album = album,
                        onClick = { onAlbumClick(album.key) },
                        modifier = Modifier.weight(1f)
                    )
                }
                if (row.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun AlbumCard(
    album: Album,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(GlassTokens.BgTertiary)
        ) {
            if (album.coverItem != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(album.coverItem.uri)
                        .crossfade(true)
                        .build(),
                    contentDescription = album.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else if (album.icon != null) {
                Icon(
                    album.icon,
                    contentDescription = album.name,
                    tint = album.iconTint,
                    modifier = Modifier
                        .size(48.dp)
                        .align(Alignment.Center)
                )
            }

            // Lock overlay for hidden
            if (album.isLocked) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Lock, null, tint = Color.White, modifier = Modifier.size(32.dp))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(album.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = GlassTokens.TextPrimary, maxLines = 1)
        Text("${album.count}", fontSize = 12.sp, color = GlassTokens.TextSecondary)
    }
}
