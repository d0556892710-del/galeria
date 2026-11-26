package com.galeria.app.data

import android.content.Context
import android.graphics.Bitmap
import android.util.JsonReader
import kotlin.math.exp

/**
 * What kind of photo this is (night, sunset, snow, food, portrait…), from the same MobileCLIP2 points
 * that power search: Auto treats each kind the way a photo editor would. On the phone.
 */
object AutoScene {
    @Volatile private var scenes: Map<String, FloatArray>? = null

    private fun scenes(context: Context): Map<String, FloatArray>? = scenes ?: runCatching {
        val out = HashMap<String, FloatArray>()
        context.assets.open("models/auto_scenes.json").use { input ->
            JsonReader(input.bufferedReader()).use { r ->
                r.beginObject()
                while (r.hasNext()) {
                    val k = r.nextName(); val v = ArrayList<Float>(Clip.DIM)
                    r.beginArray(); while (r.hasNext()) v += r.nextDouble().toFloat(); r.endArray()
                    out[k] = v.toFloatArray()
                }
                r.endObject()
            }
        }
        out.takeIf { it.isNotEmpty() }
    }.getOrNull().also { scenes = it }

    /**
     * How much the photo is each kind (0…1; "photo" is the share that is none in particular). [known] is the
     * photo's point if search already worked it out. Background thread.
     */
    fun probs(context: Context, small: Bitmap, known: FloatArray?): Map<String, Float>? = runCatching {
        val sc = scenes(context) ?: return null
        val v = known ?: if (Clip.available(context)) Clip.image(context, small) else return null
        val logits = sc.mapValues { (_, t) -> var d = 0f; for (i in 0 until Clip.DIM) d += t[i] * v[i]; d * 100f }
        val mx = logits.values.max()
        val e = logits.mapValues { exp((it.value - mx).toDouble()).toFloat() }
        val sum = e.values.sum()
        e.mapValues { it.value / sum }
    }.onFailure { android.util.Log.w("Photos", "auto scene", it) }.getOrNull()
}
