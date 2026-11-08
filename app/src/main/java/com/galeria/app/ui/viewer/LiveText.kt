package com.galeria.app.ui.viewer

import android.content.Context
import android.graphics.ImageDecoder
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.galeria.app.data.Media
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/** A block of text found in a photo, in 0…1 of the photo. */
data class TextBox(val text: String, val l: Float, val t: Float, val r: Float, val b: Float)

/** Live Text: reads text in the photo on the device. */
object LiveTextEngine {
    private val reader by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val cache = LruCache<String, List<TextBox>>(40)

    suspend fun read(context: Context, m: Media): List<TextBox> = withContext(Dispatchers.IO) {
        val key = "${m.hiddenFile ?: m.uri}#${m.dateModified}"
        cache.get(key)?.let { return@withContext it }
        val src = m.hiddenFile?.let { ImageDecoder.createSource(File(it)) } ?: ImageDecoder.createSource(context.contentResolver, m.uri)
        val bmp = ImageDecoder.decodeBitmap(src) { d, info, _ ->
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val s = 2048f / max(info.size.width, info.size.height)
            if (s < 1f) d.setTargetSize(max(1, (info.size.width * s).toInt()), max(1, (info.size.height * s).toInt()))
        }
        val w = bmp.width.toFloat(); val h = bmp.height.toFloat()
        val res = Tasks.await(reader.process(InputImage.fromBitmap(bmp, 0)))
        val boxes = res.textBlocks.mapNotNull { b ->
            val r = b.boundingBox ?: return@mapNotNull null
            if (b.text.isBlank()) null else TextBox(b.text, r.left / w, r.top / h, r.right / w, r.bottom / h)
        }
        bmp.recycle()
        cache.put(key, boxes)
        boxes
    }
}

/** Highlights the text in the photo; tap a block to copy it. Lives inside the zoomable page. */
@Composable
fun LiveTextOverlay(boxes: List<TextBox>, fit: Rect, onCopy: (String) -> Unit) {
    Canvas(
        Modifier
            .fillMaxSize()
            .pointerInput(boxes, fit) {
                detectTapGestures { pos ->
                    boxes.firstOrNull { b ->
                        pos.x in (fit.left + b.l * fit.width)..(fit.left + b.r * fit.width) &&
                            pos.y in (fit.top + b.t * fit.height)..(fit.top + b.b * fit.height)
                    }?.let { onCopy(it.text) }
                }
            }
    ) {
        drawRect(Color.Black.copy(alpha = .38f), Offset(fit.left, fit.top), Size(fit.width, fit.height))
        val pad = 3.dp.toPx()
        for (b in boxes) {
            val tl = Offset(fit.left + b.l * fit.width - pad, fit.top + b.t * fit.height - pad)
            val sz = Size((b.r - b.l) * fit.width + pad * 2, (b.b - b.t) * fit.height + pad * 2)
            drawRoundRect(Color.White.copy(alpha = .3f), tl, sz, CornerRadius(6.dp.toPx()))
            drawRoundRect(Color.White.copy(alpha = .95f), tl, sz, CornerRadius(6.dp.toPx()), style = Stroke(1.5.dp.toPx()))
        }
    }
}
