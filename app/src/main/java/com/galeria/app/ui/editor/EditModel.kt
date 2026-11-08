package com.galeria.app.ui.editor

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Rect
import com.galeria.app.R
import org.json.JSONArray
import org.json.JSONObject

/** The Adjust tools, in iOS order. Values are -1…1 (or 0…1 where [min] is 0). */
enum class Tool(val label: Int, val min: Float = -1f) {
    Auto(R.string.t_auto, 0f),
    Exposure(R.string.t_exposure),
    Brilliance(R.string.t_brilliance),
    Highlights(R.string.t_highlights),
    Shadows(R.string.t_shadows),
    Contrast(R.string.t_contrast),
    Brightness(R.string.t_brightness),
    BlackPoint(R.string.t_black_point),
    Saturation(R.string.t_saturation),
    Vibrance(R.string.t_vibrance),
    Warmth(R.string.t_warmth),
    Tint(R.string.t_tint),
    Sharpness(R.string.t_sharpness, 0f),
    Definition(R.string.t_definition, 0f),
    Noise(R.string.t_noise, 0f),
    Vignette(R.string.t_vignette),
}

/** iOS filters, in order. */
enum class Filter(val label: Int) {
    Original(R.string.fl_original),
    Vivid(R.string.fl_vivid),
    VividWarm(R.string.fl_vivid_warm),
    VividCool(R.string.fl_vivid_cool),
    Dramatic(R.string.fl_dramatic),
    DramaticWarm(R.string.fl_dramatic_warm),
    DramaticCool(R.string.fl_dramatic_cool),
    Mono(R.string.fl_mono),
    Silvertone(R.string.fl_silvertone),
    Noir(R.string.fl_noir),
}

/** Crop presets: ratio is width / height of the crop in its own orientation; null = free. */
enum class Aspect(val label: Int, val w: Int, val h: Int) {
    Freeform(R.string.a_freeform, 0, 0),
    Original(R.string.a_original, -1, -1),
    Square(R.string.a_square, 1, 1),
    Wide(R.string.a_16_9, 16, 9),
    R10x8(R.string.a_10_8, 10, 8),
    R7x5(R.string.a_7_5, 7, 5),
    R4x3(R.string.a_4_3, 4, 3),
    R5x3(R.string.a_5_3, 5, 3),
    R3x2(R.string.a_3_2, 3, 2),
}

@Immutable
data class EditParams(
    /** Manual tool values by [Tool.ordinal]. */
    val values: List<Float> = List(Tool.entries.size) { 0f },
    /** What Auto would do at full strength for this photo (filled by the editor). */
    val autoVec: List<Float> = List(Tool.entries.size) { 0f },
    val filter: Filter = Filter.Original,
    val filterAmount: Float = 1f,
    /** Quarter turns counter-clockwise. */
    val rot: Int = 0,
    val flip: Boolean = false,
    /** Degrees, -45…45. */
    val straighten: Float = 0f,
    /** Keystone, -1…1. */
    val vertical: Float = 0f,
    val horizontal: Float = 0f,
    /** Crop in the rotated/straightened frame, normalised 0…1. */
    val crop: Rect = Rect(0f, 0f, 1f, 1f),
    val aspect: Aspect = Aspect.Freeform,
    val portraitAspect: Boolean = false,
    /** Live Photo: chosen key photo time (-1 = the original key photo). */
    val keyUs: Long = -1L,
    val liveOff: Boolean = false,
    val muted: Boolean = false,
    val trimStartUs: Long = 0L,
    val trimEndUs: Long = -1L,
    /** Markup drawn on the photo, in the edited picture's own 0…1 coordinates. */
    val strokes: List<Stroke> = emptyList(),
) {
    val auto: Float get() = values[Tool.Auto.ordinal]

    /** The value actually applied: manual + Auto's share. */
    fun eff(t: Tool): Float {
        if (t == Tool.Auto) return auto
        return (values[t.ordinal] + auto * autoVec[t.ordinal]).coerceIn(t.min, 1f)
    }

    fun with(t: Tool, v: Float): EditParams {
        val nv = values.toMutableList()
        if (t == Tool.Auto) nv[t.ordinal] = v.coerceIn(0f, 1f)
        else nv[t.ordinal] = v.coerceIn(t.min, 1f) - auto * autoVec[t.ordinal]
        return copy(values = nv)
    }

    val adjusted: Boolean get() = Tool.entries.any { kotlin.math.abs(eff(it)) > .004f }
    val geometryChanged: Boolean
        get() = rot != 0 || flip || kotlin.math.abs(straighten) > .05f || kotlin.math.abs(vertical) > .004f || kotlin.math.abs(horizontal) > .004f ||
            crop.left > .001f || crop.top > .001f || crop.right < .999f || crop.bottom < .999f
    val liveChanged: Boolean get() = keyUs >= 0 || liveOff || trimStartUs > 0 || trimEndUs >= 0
    val isIdentity: Boolean get() = !adjusted && filter == Filter.Original && !geometryChanged && !liveChanged && strokes.isEmpty()

    fun resetGeometry() = copy(rot = 0, flip = false, straighten = 0f, vertical = 0f, horizontal = 0f, crop = Rect(0f, 0f, 1f, 1f), aspect = Aspect.Freeform)

    fun toJson(): String = JSONObject().apply {
        put("v", JSONArray(values.map { it.toDouble() }))
        put("filter", filter.name); put("fa", filterAmount.toDouble())
        put("rot", rot); put("flip", flip); put("st", straighten.toDouble())
        put("ve", vertical.toDouble()); put("ho", horizontal.toDouble())
        put("crop", JSONArray(listOf(crop.left, crop.top, crop.right, crop.bottom).map { it.toDouble() }))
        put("aspect", aspect.name); put("pa", portraitAspect)
        put("key", keyUs); put("loff", liveOff); put("mute", muted); put("ts", trimStartUs); put("te", trimEndUs)
        put("strokes", JSONArray(strokes.map { st ->
            JSONObject().put("t", st.tool).put("c", st.color).put("w", st.width.toDouble()).put("p", JSONArray(st.points.map { it.toDouble() }))
        }))
    }.toString()

    companion object {
        fun fromJson(s: String?): EditParams? = runCatching {
            if (s.isNullOrBlank()) return null
            val o = JSONObject(s)
            val va = o.getJSONArray("v")
            val c = o.getJSONArray("crop")
            EditParams(
                values = List(Tool.entries.size) { i -> if (i < va.length()) va.getDouble(i).toFloat() else 0f },
                filter = runCatching { Filter.valueOf(o.getString("filter")) }.getOrDefault(Filter.Original),
                filterAmount = o.optDouble("fa", 1.0).toFloat(),
                rot = o.optInt("rot"), flip = o.optBoolean("flip"), straighten = o.optDouble("st").toFloat(),
                vertical = o.optDouble("ve", 0.0).toFloat(), horizontal = o.optDouble("ho", 0.0).toFloat(),
                crop = Rect(c.getDouble(0).toFloat(), c.getDouble(1).toFloat(), c.getDouble(2).toFloat(), c.getDouble(3).toFloat()),
                aspect = runCatching { Aspect.valueOf(o.getString("aspect")) }.getOrDefault(Aspect.Freeform),
                portraitAspect = o.optBoolean("pa"),
                keyUs = o.optLong("key", -1L), liveOff = o.optBoolean("loff"), muted = o.optBoolean("mute"),
                trimStartUs = o.optLong("ts", 0L), trimEndUs = o.optLong("te", -1L),
                strokes = o.optJSONArray("strokes")?.let { arr ->
                    (0 until arr.length()).mapNotNull { i ->
                        runCatching {
                            val so = arr.getJSONObject(i); val pa = so.getJSONArray("p")
                            Stroke(so.getInt("t"), so.getInt("c"), so.getDouble("w").toFloat(), List(pa.length()) { k -> pa.getDouble(k).toFloat() })
                        }.getOrNull()
                    }
                } ?: emptyList(),
            )
        }.getOrNull()
    }
}


/** One Markup stroke. [points] are x,y pairs in 0…1 of the edited picture; [width] is a fraction of its width. */
@Immutable
data class Stroke(val tool: Int, val color: Int, val width: Float, val points: List<Float>) {
    companion object {
        const val PEN = 0
        const val MARKER = 1
        const val PENCIL = 2
    }
}
