package com.galeria.app.ui.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import com.galeria.app.data.Spatial
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

// The scene is drawn by the GPU: each layer is moved by its own depth, so near things slide more than
// far ones, and the subject layer is drawn over the background (whose hidden part was painted in).
private const val SPATIAL_SKSL = """
uniform shader layer;
uniform shader depth;
uniform float2 shift;
uniform float focus;
half4 main(float2 p) {
    float2 q = p;
    for (int i = 0; i < 6; i++) {
        float d = depth.eval(q).r;
        q = 0.5 * q + 0.5 * (p + shift * (d - focus));
    }
    return layer.eval(q);
}
"""

/** How far the phone is tilted from where it was held (−1…1 each way), smoothed; with an opening sweep. */
@Composable
private fun rememberTilt(active: Boolean): androidx.compose.runtime.State<Offset> {
    val ctx = LocalContext.current
    val state = remember { mutableStateOf(Offset.Zero) }
    val raw = remember { floatArrayOf(0f, 0f, 0f) } // pitch, roll, have
    DisposableEffect(active) {
        if (!active) return@DisposableEffect onDispose { }
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) ?: sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val rot = FloatArray(9); val ori = FloatArray(3)
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rot, e.values)
                SensorManager.getOrientation(rot, ori)
                raw[0] = ori[1]; raw[1] = ori[2]; raw[2] = 1f
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        if (sensor != null) sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sm.unregisterListener(l); raw[2] = 0f }
    }
    LaunchedEffect(active) {
        if (!active) { state.value = Offset.Zero; return@LaunchedEffect }
        var base: FloatArray? = null
        var cur = Offset.Zero
        var last = 0L; val start = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 1f / 60f else ((now - last) / 1e9f).coerceIn(0f, .1f)
                last = now
                val t = (now - start) / 1e9f
                // Where the phone is now, relative to how it was held (that slowly becomes the new rest).
                var target = Offset.Zero
                if (raw[2] > 0f) {
                    val b = base ?: floatArrayOf(raw[0], raw[1]).also { base = it }
                    val k = 1f - exp(-dt / 2.5f)
                    b[0] += (raw[0] - b[0]) * k; b[1] += (raw[1] - b[1]) * k
                    target = Offset(((raw[1] - b[1]) / .26f).coerceIn(-1f, 1f), ((raw[0] - b[0]) / .26f).coerceIn(-1f, 1f))
                }
                // The opening: the scene sweeps round once, so it is clear it is in 3D.
                if (t < 2.4f) {
                    val a = sin(PI.toFloat() * t / 2.4f) * .95f
                    val ang = 2f * PI.toFloat() * t / 2.4f
                    target += Offset(cos(ang) * a, sin(ang) * a * .55f)
                }
                cur += (target - cur) * (1f - exp(-dt / .09f))
                state.value = cur
            }
        }
    }
    return state
}

/** Draws [scene] (with background [bg]) filling [fit], the photo's place on screen, moving with the phone. */
@RequiresApi(33)
@Composable
fun SpatialLayer(scene: Spatial.Scene, bg: Bitmap, fit: Rect, appear: () -> Float) {
    val tilt = rememberTilt(true)
    val r = remember(scene) {
        object {
            val fgShader = RuntimeShader(SPATIAL_SKSL)
            val bgShader = RuntimeShader(SPATIAL_SKSL)
            val fgPaint = Paint(Paint.FILTER_BITMAP_FLAG)
            val bgPaint = Paint(Paint.FILTER_BITMAP_FLAG)
            var bgSet: Bitmap? = null
            init {
                fgShader.setInputShader("layer", com.galeria.app.data.Portrait.linear(scene.fg))
                fgShader.setInputShader("depth", com.galeria.app.data.Portrait.linear(scene.fgDepth))
                bgShader.setInputShader("depth", com.galeria.app.data.Portrait.linear(scene.bgDepth))
                fgShader.setFloatUniform("focus", scene.focus); bgShader.setFloatUniform("focus", scene.focus)
                fgPaint.shader = fgShader; bgPaint.shader = bgShader
            }
        }
    }
    androidx.compose.foundation.layout.Box(
        Modifier.fillMaxSize().drawBehind {
            if (r.bgSet !== bg) { r.bgShader.setInputShader("layer", com.galeria.app.data.Portrait.linear(bg)); r.bgSet = bg }
            val w = scene.fg.width.toFloat(); val h = scene.fg.height.toFloat()
            val k = appear()
            // Up to 6.5 % of the width between nearest and farthest; enlarged a little so no edge shows.
            val t = tilt.value * k
            val sx = t.x * w * .065f; val sy = t.y * w * .065f
            r.fgShader.setFloatUniform("shift", sx, sy); r.bgShader.setFloatUniform("shift", sx, sy)
            val zoom = 1f + .08f * k
            drawIntoCanvas { c ->
                val nc = c.nativeCanvas
                nc.save()
                nc.clipRect(fit.left, fit.top, fit.right, fit.bottom)
                nc.translate(fit.center.x, fit.center.y)
                nc.scale(fit.width / w * zoom, fit.height / h * zoom)
                nc.translate(-w / 2f, -h / 2f)
                nc.drawRect(0f, 0f, w, h, r.bgPaint)
                nc.drawRect(0f, 0f, w, h, r.fgPaint)
                nc.restore()
            }
        }
    )
}
