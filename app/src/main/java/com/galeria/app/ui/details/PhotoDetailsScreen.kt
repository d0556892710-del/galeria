package com.galeria.app.ui.details

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.galeria.app.data.MediaItem
import com.galeria.app.ui.components.GlassIconButton
import com.galeria.app.ui.components.GlassSection
import com.galeria.app.ui.theme.GlassTokens
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun PhotoDetailsScreen(
    item: MediaItem,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dateStr = remember(item.dateTaken) {
        SimpleDateFormat("EEEE, MMMM d, yyyy · h:mm a", Locale.getDefault())
            .format(Date(item.dateTaken))
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(GlassTokens.BgPrimary)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))

        // Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GlassIconButton(icon = Icons.Filled.ArrowBack, onClick = onBack)
            Spacer(Modifier.width(12.dp))
            Text("Info", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = GlassTokens.TextPrimary)
        }

        // Thumbnail
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(16.dp))
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(item.uri).crossfade(true).build(),
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        Spacer(Modifier.height(20.dp))

        // Date
        GlassSection(title = "Date & Time") {
            DetailRow(icon = Icons.Filled.DateRange, label = "Date", value = dateStr)
        }

        Spacer(Modifier.height(16.dp))

        // File info
        GlassSection(title = "File") {
            DetailRow(icon = Icons.Filled.Article, label = "Name", value = item.name)
            HorizontalDivider(color = GlassTokens.BorderSubtle, thickness = 0.5.dp)
            DetailRow(icon = Icons.Filled.SaveAlt, label = "Size", value = item.sizeFormatted)
            HorizontalDivider(color = GlassTokens.BorderSubtle, thickness = 0.5.dp)
            DetailRow(icon = Icons.Filled.CropFree, label = "Dimensions",
                value = if (item.width > 0 && item.height > 0) "${item.width} × ${item.height}" else "—"
            )
            if (item.isVideo) {
                HorizontalDivider(color = GlassTokens.BorderSubtle, thickness = 0.5.dp)
                DetailRow(icon = Icons.Filled.Schedule, label = "Duration", value = item.durationFormatted)
            }
            HorizontalDivider(color = GlassTokens.BorderSubtle, thickness = 0.5.dp)
            DetailRow(icon = Icons.Filled.Folder, label = "Path", value = item.path.ifEmpty { "—" })
        }

        Spacer(Modifier.height(16.dp))

        // Type
        GlassSection(title = "Type") {
            DetailRow(
                icon = if (item.isVideo) Icons.Filled.PlayArrow else Icons.Filled.Image,
                label = "Kind",
                value = item.mimeType.ifEmpty { "—" }
            )
        }

        Spacer(Modifier.height(100.dp))
    }
}

@Composable
private fun DetailRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = GlassTokens.AccentBlue, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, fontSize = 14.sp, color = GlassTokens.TextSecondary, modifier = Modifier.width(90.dp))
        Text(value, fontSize = 14.sp, color = GlassTokens.TextPrimary, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
    }
}
