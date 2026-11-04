package com.galeria.app.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galeria.app.data.MediaItem
import com.galeria.app.ui.library.PhotoCell
import com.galeria.app.ui.theme.GlassTokens

@Composable
fun SearchScreen(
    query: String,
    results: List<MediaItem>,
    onQueryChange: (String) -> Unit,
    onItemClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    var isFocused by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(GlassTokens.BgPrimary)
            .statusBarsPadding()
    ) {
        Spacer(Modifier.height(8.dp))

        Text(
            text = "Search",
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
            color = GlassTokens.TextPrimary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

        // ── Glass search bar ─────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(GlassTokens.GlassStandard)
                .border(0.5.dp, GlassTokens.BorderRegular, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("🔍", fontSize = 16.sp, modifier = Modifier.padding(end = 8.dp))
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .onFocusChanged { isFocused = it.isFocused },
                textStyle = TextStyle(
                    fontSize = 16.sp,
                    color = GlassTokens.TextPrimary
                ),
                cursorBrush = SolidColor(GlassTokens.AccentBlue),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                decorationBox = { inner ->
                    Box {
                        if (query.isEmpty()) {
                            Text("Search Photos", fontSize = 16.sp, color = GlassTokens.TextTertiary)
                        }
                        inner()
                    }
                }
            )
            if (query.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(GlassTokens.TextTertiary)
                        .clickable { onQueryChange("") },
                    contentAlignment = Alignment.Center
                ) {
                    Text("✕", fontSize = 10.sp, color = GlassTokens.BgPrimary, fontWeight = FontWeight.Bold)
                }
            }
        }

        // ── Results or empty state ───────────────────────────────
        if (query.isBlank()) {
            SearchSuggestionsView()
        } else if (results.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🔍", fontSize = 48.sp)
                    Spacer(Modifier.height(12.dp))
                    Text("No Results", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = GlassTokens.TextPrimary)
                    Text("for \"$query\"", fontSize = 15.sp, color = GlassTokens.TextSecondary)
                }
            }
        } else {
            Text(
                text = "${results.size} results",
                fontSize = 13.sp,
                color = GlassTokens.TextSecondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(bottom = 100.dp),
                horizontalArrangement = Arrangement.spacedBy(1.5.dp),
                verticalArrangement = Arrangement.spacedBy(1.5.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(results, key = { it.id }) { item ->
                    PhotoCell(
                        item = item,
                        isSelected = false,
                        onClick = { onItemClick(item) },
                        onLongPress = {}
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchSuggestionsView() {
    val filters = listOf("📅 Date", "📍 Location", "👤 People", "🏷 Type", "⭐ Favorites", "🎥 Videos")
    Column(modifier = Modifier.padding(16.dp)) {
        Text(
            text = "Filter by",
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = GlassTokens.TextPrimary
        )
        Spacer(Modifier.height(12.dp))
        filters.chunked(3).forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(bottom = 10.dp)
            ) {
                row.forEach { label ->
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(GlassTokens.GlassStandard)
                            .border(0.5.dp, GlassTokens.BorderSubtle, CircleShape)
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(label, fontSize = 14.sp, color = GlassTokens.TextPrimary)
                    }
                }
            }
        }
    }
}
