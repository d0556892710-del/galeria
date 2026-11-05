package com.galeria.app.ui.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.galeria.app.R
import com.galeria.app.data.Media
import com.galeria.app.glass.Backdrop
import com.galeria.app.ui.components.GlassIconButton
import com.galeria.app.ui.components.GlassTextButton
import com.galeria.app.ui.components.Sym
import com.galeria.app.ui.components.plural
import com.galeria.app.ui.theme.LocalPalette
import com.galeria.app.ui.theme.Type

@Composable
fun selectionLabel(chosen: List<Media>): String {
    if (chosen.isEmpty()) return stringResource(R.string.select_items)
    val v = chosen.count { it.isVideo }
    return when {
        v == 0 -> plural(R.plurals.photos_selected, chosen.size)
        v == chosen.size -> plural(R.plurals.videos_selected, v)
        else -> plural(R.plurals.items_selected, chosen.size)
    }
}

/** Bottom bar while selecting: Share · "3 Photos Selected" · Delete. */
@Composable
fun SelectionBar(
    backdrop: Backdrop?,
    chosen: List<Media>,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    extraLabel: String? = null,
    onExtra: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    Row(
        modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassIconButton(backdrop, Sym.share, stringResource(R.string.share), onShare, size = 50.dp, iconSize = 23.dp, enabled = chosen.isNotEmpty())
        Box(Modifier.weight(1f).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
            Text(selectionLabel(chosen), style = Type.subheadSemibold, color = p.label, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
        if (extraLabel != null && onExtra != null) {
            GlassTextButton(backdrop, extraLabel, { if (chosen.isNotEmpty()) onExtra() })
            Spacer(Modifier.width(10.dp))
        }
        GlassIconButton(backdrop, Sym.trash, stringResource(R.string.delete), onDelete, size = 50.dp, iconSize = 23.dp, enabled = chosen.isNotEmpty())
    }
}
