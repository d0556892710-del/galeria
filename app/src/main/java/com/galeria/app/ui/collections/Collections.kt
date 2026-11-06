package com.galeria.app.ui.collections

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.galeria.app.R
import androidx.compose.foundation.combinedClickable
import com.galeria.app.ui.components.onRootRect
import androidx.compose.runtime.setValue
import com.galeria.app.data.AppSettings
import com.galeria.app.data.Media
import com.galeria.app.ui.components.Sym
import com.galeria.app.ui.components.pressable
import com.galeria.app.ui.library.thumbModel
import com.galeria.app.ui.root.Album
import com.galeria.app.ui.root.Page
import com.galeria.app.ui.theme.LocalPalette
import com.galeria.app.ui.theme.Type

@Composable
fun CollectionsScreen(
    state: LazyListState,
    library: List<Media>,
    albums: List<Album>,
    tags: com.galeria.app.data.MediaTags,
    effects: Map<Long, com.galeria.app.data.LiveEffect>,
    settings: AppSettings,
    onAlbumMenu: (Album, androidx.compose.ui.geometry.Rect) -> Unit,
    vm: com.galeria.app.ui.root.AppViewModel,
    collapsed: Set<String>,
    onToggle: (String) -> Unit,
    onOpen: (Page) -> Unit,
    topPadding: Dp,
    bottomPadding: Dp,
) {
    val p = LocalPalette.current
    val pinnable = pinnableEntries(vm, library, albums, tags, effects, settings)
    val pinned = settings.pinned.mapNotNull { pinnable[it] }
    val types = mediaTypeEntries(library, tags, effects)
    val utils = utilityEntries(vm, library, settings)
    LazyColumn(
        state = state,
        contentPadding = PaddingValues(top = topPadding, bottom = bottomPadding),
        modifier = Modifier.fillMaxSize().background(p.background),
    ) {
        item(key = "pinned") {
            Section("pinned", stringResource(R.string.pinned), collapsed, onToggle, onTitle = { onOpen(Page.PinnedList) }) {
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(pinned, key = { it.key }) { e ->
                        CoverCard(e.cover, e.title, if (e.key == "fav") Sym.heartFill else e.icon) { onOpen(e.page) }
                    }
                    item(key = "+") {
                        Box(
                            Modifier.size(116.dp).clip(RoundedCornerShape(20.dp)).background(p.fill).pressable { onOpen(Page.PinnedList) },
                            contentAlignment = Alignment.Center,
                        ) { Icon(Sym.plus, stringResource(R.string.add_to_pinned), Modifier.size(28.dp), tint = p.secondaryLabel) }
                    }
                }
            }
        }
        if (albums.isNotEmpty()) item(key = "albums") {
            Section("albums", stringResource(R.string.albums), collapsed, onToggle, onTitle = { onOpen(Page.AllAlbums) }) {
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(albums, key = { it.id }) { a ->
                        CoverCard(a.cover, a.name, Sym.photo, count = a.count, onLongPress = { r -> onAlbumMenu(a, r) }) { onOpen(Page.Bucket(a.id, a.name)) }
                    }
                }
            }
        }
        item(key = "media") {
            Section("media", stringResource(R.string.media_types), collapsed, onToggle, onTitle = { onOpen(Page.MediaTypesList) }) {
                Tiles(
                    types.map { Triple(it.icon, it.title, it.page) },
                    locked = emptySet(),
                    onOpen = onOpen,
                )
            }
        }
        item(key = "utilities") {
            Section("utilities", stringResource(R.string.utilities), collapsed, onToggle, onTitle = { onOpen(Page.UtilitiesList) }) {
                Tiles(
                    utils.map { Triple(it.icon, it.title, it.page) },
                    locked = if (settings.lockAlbums) setOf<Page>(Page.Hidden, Page.Deleted) else emptySet(),
                    onOpen = onOpen,
                )
            }
        }
    }
}

@Composable
private fun Section(id: String, title: String, collapsed: Set<String>, onToggle: (String) -> Unit, onTitle: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val p = LocalPalette.current
    val shut = id in collapsed
    val rot by animateFloatAsState(if (shut) -90f else 0f, spring(dampingRatio = .7f, stiffness = 500f), label = "rot")
    Column(Modifier.fillMaxWidth().padding(bottom = 26.dp).animateContentSize()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 32.dp).padding(start = 20.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.then(if (onTitle != null) Modifier.pressable(onClick = onTitle) else Modifier),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                Text(title, style = Type.title2, color = p.label, maxLines = 1)
                Icon(Sym.chevronForward, null, Modifier.padding(start = 4.dp, top = 1.dp).size(14.dp), tint = p.secondaryLabel.copy(alpha = .7f))
                }
            }
            val label = stringResource(if (shut) R.string.expand else R.string.collapse)
            Box(
                Modifier
                    .size(30.dp)
                    .background(p.fill, CircleShape)
                    .pressable(role = Role.Button) { onToggle(id) }
                    .semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Sym.chevronDown, null, Modifier.size(14.dp).graphicsLayer { rotationZ = rot }, tint = p.blue)
            }
        }
        Spacer(Modifier.height(10.dp))
        if (!shut) content()
    }
}

private val labelShadow = Shadow(Color.Black.copy(alpha = .35f), androidx.compose.ui.geometry.Offset(0f, 1f), 4f)

@Composable
private fun CoverCard(
    cover: Media?, title: String, icon: ImageVector, count: Int? = null,
    onLongPress: ((androidx.compose.ui.geometry.Rect) -> Unit)? = null, onClick: () -> Unit,
) {
    val p = LocalPalette.current
    var rect by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    Box(
        Modifier
            .size(116.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(p.fill)
            .then(
                if (onLongPress == null) Modifier.pressable(onClick = onClick)
                else Modifier
                    .onRootRect { rect = it }
                    .combinedClickable(androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, null, onLongClick = { onLongPress(rect) }, onClick = onClick)
            )
            .semantics { contentDescription = title }
    ) {
        if (cover != null) {
            com.galeria.app.ui.components.ThumbImage(cover, 320, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(.45f to Color.Transparent, 1f to Color.Black.copy(alpha = .5f))))
        } else {
            Icon(icon, null, Modifier.align(Alignment.Center).size(34.dp), tint = p.secondaryLabel)
        }
        Column(Modifier.align(Alignment.BottomStart).padding(start = 11.dp, end = 10.dp, bottom = 9.dp)) {
            Text(
                title, style = Type.footnoteSemibold.copy(shadow = if (cover != null) labelShadow else null),
                color = if (cover != null) Color.White else p.label, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            if (count != null) Text(count.toString(), style = Type.caption1.copy(shadow = labelShadow), color = Color.White.copy(alpha = .85f))
        }
    }
}

@Composable
private fun Tiles(tiles: List<Triple<ImageVector, String, Page>>, locked: Set<Page>, onOpen: (Page) -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (icon, title, page) ->
                    Row(
                        Modifier
                            .weight(1f)
                            .height(46.dp)
                            .background(p.fill.copy(alpha = p.fill.alpha * .75f), RoundedCornerShape(13.dp))
                            .pressable { onOpen(page) }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(icon, null, Modifier.size(20.dp), tint = p.label.copy(alpha = .8f))
                        Text(title, style = Type.subhead.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), color = p.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (page in locked) Icon(Sym.lock, null, Modifier.size(13.dp), tint = p.secondaryLabel)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}
