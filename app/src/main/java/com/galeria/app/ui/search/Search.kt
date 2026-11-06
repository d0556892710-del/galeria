package com.galeria.app.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.galeria.app.R
import com.galeria.app.data.Media
import com.galeria.app.ui.components.Fmt
import com.galeria.app.ui.components.Sym
import com.galeria.app.ui.components.plural
import com.galeria.app.ui.components.pressable
import com.galeria.app.ui.components.rememberFmt
import com.galeria.app.ui.library.EmptyState
import com.galeria.app.ui.library.GridHandle
import com.galeria.app.ui.library.PhotoGrid
import com.galeria.app.ui.library.thumbModel
import com.galeria.app.ui.root.Album
import com.galeria.app.ui.theme.LocalPalette
import com.galeria.app.ui.theme.Type
import java.util.Locale

/** Words each item answers to: month names (English + Hebrew), year, album, kind, file name. */
private fun words(m: Media, fmt: Fmt): List<String> {
    val key = fmt.monthKey(m.dateTaken)
    val month = key % 12 + 1
    val en = java.time.Month.of(month).getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH).lowercase()
    val he = java.time.Month.of(month).getDisplayName(java.time.format.TextStyle.FULL, Locale.forLanguageTag("he")).lowercase()
    return buildList {
        add(en); add(en.take(3)); add(he); add(fmt.month(month).lowercase())
        add((key / 12).toString())
        addAll(m.bucketName.lowercase().split(' ', '_', '-'))
        add(m.bucketName.lowercase())
        addAll(m.displayName.lowercase().substringBeforeLast('.').split(' ', '_', '-'))
        if (m.isVideo) addAll(listOf("video", "videos", "סרטון", "סרטונים"))
        else addAll(listOf("photo", "photos", "תמונה", "תמונות"))
        if (m.isScreenshot) addAll(listOf("screenshot", "screenshots", "צילום", "מסך", "צילומי"))
        if (m.isFavorite) addAll(listOf("favourite", "favourites", "favorite", "favorites", "מועדף", "מועדפים"))
        if (m.isPanorama) addAll(listOf("panorama", "פנורמה"))
    }
}

fun searchLibrary(lib: List<Media>, query: String, fmt: Fmt): List<Media> {
    val toks = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (toks.isEmpty()) return emptyList()
    return lib.filter { m ->
        val w = words(m, fmt)
        toks.all { t -> w.any { it.startsWith(t) } }
    }
}

@Composable
fun SearchScreen(
    library: List<Media>,
    albums: List<Album>,
    query: String,
    recent: List<String>,
    viewed: List<Media>,
    handle: GridHandle,
    topPadding: Dp,
    bottomPadding: Dp,
    onQuery: (String) -> Unit,
    onClearRecent: () -> Unit,
    onOpen: (List<Media>, Media, Rect?) -> Unit,
) {
    val p = LocalPalette.current
    val fmt = rememberFmt()
    val results = remember(library, query, fmt) { searchLibrary(library, query, fmt) }
    if (query.isNotBlank()) {
        if (results.isEmpty()) {
            EmptyState(stringResource(R.string.no_results), stringResource(R.string.no_results_sub))
        } else {
            PhotoGrid(
                items = results,
                handle = handle,
                columns = 3,
                aspect = false,
                topPadding = topPadding,
                bottomPadding = bottomPadding,
                selecting = false,
                selected = emptySet(),
                hiddenId = null,
                onOpen = { m, r -> onOpen(results, m, r) },
                onToggle = {},
                header = {
                    Text(
                        plural(R.plurals.results, results.size), style = Type.subheadSemibold, color = p.secondaryLabel,
                        modifier = Modifier.padding(start = 20.dp, bottom = 10.dp),
                    )
                },
            )
        }
        return
    }
    // Suggestions built from what is actually in the library.
    val months = remember(library, fmt) {
        library.groupBy { fmt.monthKey(it.dateTaken) }.entries.sortedByDescending { it.key }.take(4).map { it.key }
    }
    val years = remember(library, fmt) { library.map { fmt.yearOf(it.dateTaken) }.distinct().sortedDescending().take(3) }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = topPadding, bottom = bottomPadding)
    ) {
        if (viewed.isNotEmpty()) {
            SectionTitle(stringResource(R.string.recently_viewed))
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(viewed.take(12), key = { it.id }) { m ->
                    Box(
                        Modifier
                            .size(150.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(p.placeholder)
                            .pressable { onOpen(viewed, m, null) }
                    ) {
                        com.galeria.app.ui.components.ThumbImage(m, 320, Modifier.fillMaxSize())
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(.5f to Color.Transparent, 1f to Color.Black.copy(alpha = .5f))))
                        Text(
                            fmt.medium(m.dateTaken),
                            style = Type.footnoteSemibold.copy(shadow = Shadow(Color.Black.copy(alpha = .4f), androidx.compose.ui.geometry.Offset(0f, 1f), 4f)),
                            color = Color.White,
                            modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
                        )
                    }
                }
            }
            Box(Modifier.height(26.dp))
        }
        if (recent.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { SectionTitle(stringResource(R.string.recent_searches)) }
                Text(stringResource(R.string.clear), style = Type.subhead, color = p.blue, modifier = Modifier.pressable(onClick = onClearRecent).padding(bottom = 10.dp))
            }
            Chips(recent.map { it to it }, onQuery)
            Box(Modifier.height(26.dp))
        }
        SectionTitle(stringResource(R.string.suggestions))
        val chips = buildList {
            months.forEach { k -> add("${fmt.month(k % 12 + 1)} ${k / 12}" to "${fmt.month(k % 12 + 1)} ${k / 12}") }
            years.forEach { add(it.toString() to it.toString()) }
            albums.take(4).forEach { add(it.name to it.name) }
            if (library.any { it.isVideo }) add(stringResource(R.string.videos) to stringResource(R.string.videos))
            if (library.any { it.isFavorite }) add(stringResource(R.string.favourites) to stringResource(R.string.favourites))
        }
        Chips(chips, onQuery)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = Type.title2, color = LocalPalette.current.label, modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 10.dp))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chips(chips: List<Pair<String, String>>, onQuery: (String) -> Unit) {
    val p = LocalPalette.current
    FlowRow(
        Modifier.padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        chips.forEach { (label, q) ->
            Row(
                Modifier
                    .height(38.dp)
                    .background(p.fill, RoundedCornerShape(19.dp))
                    .pressable { onQuery(q) }
                    .padding(horizontal = 15.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Icon(Sym.search, null, Modifier.size(15.dp), tint = p.secondaryLabel)
                Text(label, style = Type.subhead.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), color = p.label)
            }
        }
    }
}
