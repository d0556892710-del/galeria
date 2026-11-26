package com.galeria.app.data

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Search by meaning: "dog on the beach at sunset", "birthday cake", "כלב על החוף" — the words are
 * turned into a point and compared with every photo's point (all on the phone).
 */
object Semantic {
    @Volatile private var translator: Translator? = null
    @Volatile private var translatorReady = false
    private val heToEn: Map<String, String> by lazy { SmartIndex.HE.entries.associate { (en, he) -> he to en.lowercase() } }

    private fun hasHebrew(s: String) = s.any { it in '֐'..'׿' }

    /** The query in English (the model reads English). Hebrew: known words directly, otherwise on-device translation. */
    private fun english(context: Context, query: String): String? {
        val q = query.trim()
        if (!hasHebrew(q)) return q
        val words = q.split(Regex("\\s+"))
        val mapped = words.map { w -> heToEn[w] ?: heToEn[w.removePrefix("ה")] ?: heToEn[w.removePrefix("ו")] }
        if (mapped.all { it != null }) return mapped.joinToString(" ")
        val t = translator ?: Translation.getClient(
            TranslatorOptions.Builder().setSourceLanguage(TranslateLanguage.HEBREW).setTargetLanguage(TranslateLanguage.ENGLISH).build()
        ).also { translator = it }
        if (!translatorReady) {
            // The Hebrew pack is fetched once (then it works offline); meanwhile only known words are used.
            translatorReady = runCatching { Tasks.await(t.downloadModelIfNeeded(), 6, TimeUnit.SECONDS) }.isSuccess
            if (!translatorReady) return mapped.filterNotNull().takeIf { it.isNotEmpty() }?.joinToString(" ")
        }
        return runCatching { Tasks.await(t.translate(q), 4, TimeUnit.SECONDS) }.getOrNull()
    }

    private fun skip(why: String): List<Media> { android.util.Log.i("PhotosPerf", "semantic skip $why"); return emptyList() }

    /** Gets the words model ready (when Search opens), so the first search answers at once. */
    fun warm(context: Context) {
        if (!Clip.available(context)) return
        val t0 = System.currentTimeMillis()
        runCatching { Clip.text(context, "a photo") }
            .onSuccess { android.util.Log.i("PhotosPerf", "semantic warm ms=${System.currentTimeMillis() - t0}") }
            .onFailure { android.util.Log.w("Photos", "semantic warm", it) }
    }

    /** Photos of [lib] that match [query], best first (empty if nothing really matches). Runs on a background thread. */
    fun search(context: Context, query: String, lib: List<Media>, index: Map<Long, FloatArray>): List<Media> {
        if (query.isBlank()) return emptyList()
        if (!Clip.available(context)) return skip("no model")
        if (index.isEmpty()) return skip("nothing indexed yet")
        // Dates and numbers are better served by the normal search.
        if (query.trim().all { it.isDigit() || it.isWhitespace() }) return emptyList()
        val en = english(context, query)?.trim()?.takeIf { it.isNotEmpty() } ?: return skip("no translation")
        val prompt = if (en.split(' ').size <= 3) "a photo of $en" else en
        val t0 = System.currentTimeMillis()
        val q = runCatching { Clip.text(context, prompt) }.onFailure { android.util.Log.w("Photos", "semantic text", it) }.getOrNull()
            ?: return skip("text model failed")
        val scored = ArrayList<Pair<Media, Float>>()
        for (m in lib) {
            val v = index[m.id] ?: continue
            var d = 0f
            for (i in 0 until Clip.DIM) d += q[i] * v[i]
            scored += m to d
        }
        if (scored.isEmpty()) return emptyList()
        val best = scored.maxOf { it.second }
        val cut = max(0.205f, best - 0.075f)
        val hits = scored.filter { it.second >= cut }.sortedByDescending { it.second }.take(600).map { it.first }
        android.util.Log.i("PhotosPerf", "semantic q=\"$prompt\" indexed=${scored.size} best=${"%.3f".format(best)} hits=${hits.size} ms=${System.currentTimeMillis() - t0}")
        return hits
    }
}
