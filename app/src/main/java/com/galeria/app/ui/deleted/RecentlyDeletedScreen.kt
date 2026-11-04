package com.galeria.app.ui.deleted

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galeria.app.data.MediaItem
import com.galeria.app.ui.components.*
import com.galeria.app.ui.theme.GlassTokens

@Composable
fun RecentlyDeletedScreen(
    items: List<MediaItem>,
    onBack: () -> Unit,
    onRestore: (Set<Long>) -> Unit,
    onDeletePermanent: (Set<Long>) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var showConfirm by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize().background(GlassTokens.BgPrimary)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            // Header
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GlassIconButton(icon = Icons.Filled.ArrowBack, onClick = onBack)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Recently Deleted", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = GlassTokens.TextPrimary)
                    Text("Items are deleted after 30 days", fontSize = 12.sp, color = GlassTokens.TextSecondary)
                }
            }

            if (items.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Filled.Delete, null, tint = GlassTokens.TextTertiary, modifier = Modifier.size(64.dp))
                        Text("No Deleted Items", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = GlassTokens.TextSecondary)
                        Text("Items you delete will appear here", fontSize = 14.sp, color = GlassTokens.TextTertiary)
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(2.dp, 2.dp, 2.dp, 100.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(items) { item ->
                        com.galeria.app.ui.library.PhotoCell(
                            item = item,
                            isSelected = item.id in selectedIds,
                            onClick = {
                                selectedIds = selectedIds.toMutableSet().also {
                                    if (item.id in it) it.remove(item.id) else it.add(item.id)
                                }
                            },
                            onLongPress = {}
                        )
                    }
                }
            }

            // Bottom toolbar
            if (selectedIds.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(GlassTokens.GlassDark)
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    GlassButton(
                        text = "Recover",
                        onClick = { onRestore(selectedIds); selectedIds = emptySet() },
                        icon = Icons.Filled.Refresh,
                        tint = GlassTokens.AccentBlue
                    )
                    GlassButton(
                        text = "Delete Forever",
                        onClick = { showConfirm = true },
                        icon = Icons.Filled.Delete,
                        tint = GlassTokens.Danger,
                        bgAlpha = 0.08f
                    )
                }
            }
        }

        // Confirm dialog
        if (showConfirm) {
            AlertDialog(
                onDismissRequest = { showConfirm = false },
                title = { Text("Delete Forever?", color = GlassTokens.TextPrimary) },
                text = { Text("${selectedIds.size} items will be permanently deleted and cannot be recovered.", color = GlassTokens.TextSecondary) },
                confirmButton = {
                    TextButton(onClick = {
                        onDeletePermanent(selectedIds)
                        selectedIds = emptySet()
                        showConfirm = false
                    }) {
                        Text("Delete", color = GlassTokens.Danger, fontWeight = FontWeight.SemiBold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showConfirm = false }) {
                        Text("Cancel", color = GlassTokens.AccentBlue)
                    }
                },
                containerColor = GlassTokens.BgTertiary,
                titleContentColor = GlassTokens.TextPrimary
            )
        }
    }
}
