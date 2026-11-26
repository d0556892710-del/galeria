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
        val t0 = System.currentTimeMillis()
        val ocr = com.galeria.app.data.HebrewOcr.available(context)
        var hebrew = 0
        val boxes = res.textBlocks.mapNotNull { b ->
            val r = b.boundingBox ?: return@mapNotNull null
            if (b.text.isBlank()) return@mapNotNull null
            // Google's reader only knows Latin letters: each block is also read for Hebrew (within a time budget).
            var text = b.text
            if (ocr && System.currentTimeMillis() - t0 < 9000) runCatching {
                val pad = (r.height() * .12f).toInt() + 4
                val x0 = (r.left - pad).coerceIn(0, bmp.width - 1); val y0 = (r.top - pad).coerceIn(0, bmp.height - 1)
                val x1 = (r.right + pad).coerceIn(x0 + 1, bmp.width); val y1 = (r.bottom + pad).coerceIn(y0 + 1, bmp.height)
                val crop = android.graphics.Bitmap.createBitmap(bmp, x0, y0, x1 - x0, y1 - y0)
                val heb = com.galeria.app.data.HebrewOcr.read(context, crop)
                if (crop !== bmp) crop.recycle()
                if (com.galeria.app.data.HebrewOcr.hebrewShare(heb) >= .3f && heb.count { it in '\u05D0'..'\u05EA' } >= 2) { text = heb; hebrew++ }
            }.onFailure { android.util.Log.w("Photos", "hebrew ocr", it) }
            TextBox(text, r.left / w, r.top / h, r.right / w, r.bottom / h)
        }
        android.util.Log.i("PhotosPerf", "livetext blocks=${boxes.size} hebrew=$hebrew ms=${System.currentTimeMillis() - t0}")
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
