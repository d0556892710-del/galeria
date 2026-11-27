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

    fun available(context: Context): Boolean = FILES.all { Models.ready(context, it) }

    @Synchronized
    private fun api(context: Context): TessBaseAPI {
        api?.let { return it }
        // Tesseract reads <root>/tessdata/<lang>.traineddata.
        val files = FILES.map { Models.file(context, it) ?: error("$it not downloaded yet") }
        val root = files.first().parentFile!!.parentFile!!
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

    /** Text blocks found anywhere in [page] (Tesseract's own layout analysis), with their boxes in its pixels. */
    @Synchronized
    fun readPage(context: Context, page: Bitmap): List<Pair<String, android.graphics.Rect>> {
        val a = api(context)
        a.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
        a.setImage(page)
        a.getUTF8Text()
        val out = ArrayList<Pair<String, android.graphics.Rect>>()
        val it = a.resultIterator
        if (it != null) {
            val level = TessBaseAPI.PageIteratorLevel.RIL_BLOCK
            it.begin()
            do {
                val t = it.getUTF8Text(level)?.trim().orEmpty()
                val r = it.getBoundingRect(level)
                if (t.isNotEmpty() && r != null && r.width() > 4 && r.height() > 4) out += t to r
            } while (it.next(level))
            it.delete()
        }
        a.clear()
        a.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK)
        return out
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
