package com.galeria.app.data

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import kotlin.math.roundToInt

/**
 * Reading Hebrew (and English) in photos: Tesseract's best LSTM models, on the phone. Google's text
 * reader finds where the text is; this reads the Hebrew it can't.
 */
object HebrewOcr {
    private var api: TessBaseAPI? = null
    private val FILES = listOf("heb.traineddata", "eng.traineddata")

    fun available(context: Context): Boolean = FILES.all { f -> runCatching { context.assets.open("models/$f").use { true } }.getOrDefault(false) }

    @Synchronized
    private fun api(context: Context): TessBaseAPI {
        api?.let { return it }
        val root = File(context.filesDir, "ocr")
        val data = File(root, "tessdata").apply { mkdirs() }
        for (name in FILES) {
            val f = File(data, name)
            if (!f.exists() || f.length() == 0L) {
                val tmp = File(data, "$name.tmp")
                context.assets.open("models/$name").use { i -> tmp.outputStream().use { o -> i.copyTo(o, 1 shl 20) } }
                tmp.renameTo(f)
            }
        }
        val a = TessBaseAPI()
        check(a.init(root.absolutePath, "heb+eng", TessBaseAPI.OEM_LSTM_ONLY)) { "tesseract init" }
        a.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK)
        api = a
        return a
    }

    /** The text in [block] (a crop around one block of text). */
    @Synchronized
    fun read(context: Context, block: Bitmap): String {
        val a = api(context)
        // Tesseract reads best with letters about 30–40 px tall.
        val scaled = if (block.height < 60) Bitmap.createScaledBitmap(block, (block.width * 60f / block.height).roundToInt().coerceAtLeast(1), 60, true) else block
        a.setImage(scaled)
        val t = a.getUTF8Text().orEmpty()
        a.clear()
        if (scaled !== block) scaled.recycle()
        return t.trim()
    }

    @Synchronized
    fun release() { runCatching { api?.recycle() }; api = null }

    /** How much of [s] is Hebrew letters (0…1 of its letters). */
    fun hebrewShare(s: String): Float {
        var heb = 0; var letters = 0
        for (c in s) if (c.isLetter()) { letters++; if (c in 'א'..'ת') heb++ }
        return if (letters == 0) 0f else heb.toFloat() / letters
    }
}
