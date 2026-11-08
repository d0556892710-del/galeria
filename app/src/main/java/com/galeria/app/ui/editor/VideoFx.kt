package com.galeria.app.ui.editor

import android.content.Context
import android.net.Uri
import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.Crop
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

private const val VERTEX = """
attribute vec4 aFramePosition;
varying vec2 vTexSamplingCoord;
void main() {
  gl_Position = aFramePosition;
  vTexSamplingCoord = vec2(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5);
}
"""

/** The photo colour pipeline, in GLSL for video frames (same look as photos). */
private const val FRAGMENT = """
precision highp float;
uniform sampler2D uTexSampler;
uniform vec2 uTexel;
uniform float uAspect;
uniform float uExposure;
uniform float uBrilliance;
uniform float uHighlights;
uniform float uShadows;
uniform float uContrast;
uniform float uBrightness;
uniform float uBlackPoint;
uniform float uSaturation;
uniform float uVibrance;
uniform float uWarmth;
uniform float uTint;
uniform float uSharpness;
uniform float uDefinition;
uniform float uNoise;
uniform float uVignette;
uniform float uFilterId;
uniform float uFilterAmt;
varying vec2 vTexSamplingCoord;

float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }
vec3 sat(vec3 c, float s) { float l = luma(c); return mix(vec3(l), c, s); }

vec3 applyFilter(vec3 c, float id) {
  float l = luma(c);
  if (id < 3.5) {
    vec3 v = sat(c, 1.38);
    v = (v - 0.5) * 1.12 + 0.5;
    if (id > 1.5 && id < 2.5) v *= vec3(1.07, 1.0, 0.9);
    if (id > 2.5) v *= vec3(0.92, 0.99, 1.08);
    return v;
  }
  if (id < 6.5) {
    vec3 d = sat(c, 0.7);
    d = (d - 0.5) * 1.3 + 0.5;
    d -= 0.07 * smoothstep(0.5, 1.0, l);
    if (id > 4.5 && id < 5.5) d *= vec3(1.09, 1.0, 0.86);
    if (id > 5.5) d *= vec3(0.88, 0.98, 1.12);
    return d;
  }
  if (id < 7.5) { float m = (l - 0.5) * 1.06 + 0.5; return vec3(m); }
  if (id < 8.5) { float s = (l - 0.5) * 1.18 + 0.53; return vec3(s * 0.99, s, s * 1.025); }
  float n = smoothstep(0.07, 0.93, l);
  n = (n - 0.5) * 1.22 + 0.5;
  return vec3(n);
}

void main() {
  vec2 p = vTexSamplingCoord;
  vec4 src = texture2D(uTexSampler, p);
  vec3 c = src.rgb;
  if (uSharpness > 0.0 || uNoise > 0.0 || uDefinition > 0.0) {
    vec2 dx = vec2(uTexel.x * 1.5, 0.0);
    vec2 dy = vec2(0.0, uTexel.y * 1.5);
    vec3 n4 = (texture2D(uTexSampler, p + dx).rgb + texture2D(uTexSampler, p - dx).rgb
             + texture2D(uTexSampler, p + dy).rgb + texture2D(uTexSampler, p - dy).rgb) * 0.25;
    c = mix(c, n4, uNoise * 0.8);
    c += (c - n4) * uSharpness * 2.2;
    if (uDefinition > 0.0) {
      vec2 r2 = uTexel * 10.0;
      vec3 m4 = (texture2D(uTexSampler, p + r2).rgb + texture2D(uTexSampler, p - r2).rgb
               + texture2D(uTexSampler, p + vec2(r2.x, -r2.y)).rgb + texture2D(uTexSampler, p + vec2(-r2.x, r2.y)).rgb) * 0.25;
      float ld = luma(c);
      c += (c - m4) * uDefinition * 0.85 * (1.0 - abs(ld - 0.5) * 1.2);
    }
  }
  c = clamp(c, 0.0, 1.0);
  vec3 lin = c * c * exp2(uExposure * 1.6);
  c = sqrt(max(lin, vec3(0.0)));
  float sh = uShadows + uBrilliance * 0.55;
  float hi = uHighlights - uBrilliance * 0.5;
  float con = uContrast + uBrilliance * 0.08;
  float l = clamp(luma(c), 0.0, 1.0);
  float lo = l;
  float wS = 1.0 - smoothstep(0.0, 0.62, l);
  lo += sh * 0.3 * wS * (0.3 + l);
  float wH = smoothstep(0.38, 1.0, l);
  lo += hi * 0.3 * wH;
  lo = pow(clamp(lo, 0.0, 1.0), exp2(-uBrightness * 0.75));
  lo = clamp((lo - 0.5) * (1.0 + con * 0.65) + 0.5, 0.0, 1.0);
  float bp = uBlackPoint * 0.18;
  lo = clamp((lo - bp) / (1.0 - bp), 0.0, 1.0);
  vec3 ratio = l > 0.0005 ? c * (lo / l) : vec3(lo);
  c = mix(c + (lo - l), ratio, 0.5);
  c = clamp(c, 0.0, 1.0);
  c.r += uWarmth * 0.09; c.g += uWarmth * 0.01; c.b -= uWarmth * 0.11;
  c.g -= uTint * 0.07; c.r += uTint * 0.035; c.b += uTint * 0.035;
  c = sat(c, 1.0 + uSaturation);
  float mx = max(c.r, max(c.g, c.b));
  float mn = min(c.r, min(c.g, c.b));
  c = sat(c, 1.0 + uVibrance * (1.0 - clamp(mx - mn, 0.0, 1.0)) * 1.3);
  c = clamp(c, 0.0, 1.0);
  if (uFilterId > 0.5) c = mix(c, clamp(applyFilter(c, uFilterId), 0.0, 1.0), uFilterAmt);
  if (uVignette != 0.0) {
    vec2 q = (p - 0.5) * vec2(uAspect, 1.0);
    float d = length(q) / (0.5 * length(vec2(uAspect, 1.0)));
    float v = smoothstep(0.42, 1.22, d);
    if (uVignette > 0.0) { c *= 1.0 - uVignette * v * 0.85; }
    else { c = mix(c, vec3(1.0), -uVignette * v * 0.8); }
  }
  gl_FragColor = vec4(clamp(c, 0.0, 1.0), src.a);
}
"""

/** Holds the values the video colour effect reads every frame, so sliders update the preview live. */
class VideoColor(initial: EditParams) {
    @Volatile var params: EditParams = initial
}

@OptIn(UnstableApi::class)
class ColorEffect(private val holder: VideoColor) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = ColorProgram(holder, useHdr)
}

@OptIn(UnstableApi::class)
private class ColorProgram(private val holder: VideoColor, useHdr: Boolean) : BaseGlShaderProgram(useHdr, 1) {
    private val program = GlProgram(VERTEX, FRAGMENT).apply {
        setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
    }
    private var w = 1
    private var h = 1

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        w = inputWidth.coerceAtLeast(1); h = inputHeight.coerceAtLeast(1)
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        val p = holder.params
        program.use()
        program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
        program.setFloatsUniform("uTexel", floatArrayOf(1f / w, 1f / h))
        program.setFloatUniform("uAspect", w.toFloat() / h)
        program.setFloatUniform("uExposure", p.eff(Tool.Exposure))
        program.setFloatUniform("uBrilliance", p.eff(Tool.Brilliance))
        program.setFloatUniform("uHighlights", p.eff(Tool.Highlights))
        program.setFloatUniform("uShadows", p.eff(Tool.Shadows))
        program.setFloatUniform("uContrast", p.eff(Tool.Contrast))
        program.setFloatUniform("uBrightness", p.eff(Tool.Brightness))
        program.setFloatUniform("uBlackPoint", p.eff(Tool.BlackPoint))
        program.setFloatUniform("uSaturation", p.eff(Tool.Saturation))
        program.setFloatUniform("uVibrance", p.eff(Tool.Vibrance))
        program.setFloatUniform("uWarmth", p.eff(Tool.Warmth))
        program.setFloatUniform("uTint", p.eff(Tool.Tint))
        program.setFloatUniform("uSharpness", p.eff(Tool.Sharpness))
        program.setFloatUniform("uDefinition", p.eff(Tool.Definition))
        program.setFloatUniform("uNoise", p.eff(Tool.Noise))
        program.setFloatUniform("uVignette", p.eff(Tool.Vignette))
        program.setFloatUniform("uFilterId", p.filter.ordinal.toFloat())
        program.setFloatUniform("uFilterAmt", p.filterAmount)
        program.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    override fun release() {
        super.release()
        runCatching { program.delete() }
    }
}

object VideoFx {
    /** Rotate, flip and crop as video effects (the order matches the photo editor). */
    @OptIn(UnstableApi::class)
    fun geometry(p: EditParams): List<Effect> = buildList {
        if (p.rot % 4 != 0) add(ScaleAndRotateTransformation.Builder().setRotationDegrees(90f * (p.rot % 4)).build())
        if (p.flip) add(ScaleAndRotateTransformation.Builder().setScale(-1f, 1f).build())
        val c = p.crop
        if (c.left > .001f || c.top > .001f || c.right < .999f || c.bottom < .999f) {
            add(Crop(c.left * 2f - 1f, c.right * 2f - 1f, 1f - c.bottom * 2f, 1f - c.top * 2f))
        }
    }

    fun effects(p: EditParams, color: VideoColor): List<Effect> = geometry(p) + ColorEffect(color)

    /** Writes the edited video to [out]. Must be called on the main thread. */
    @OptIn(UnstableApi::class)
    suspend fun export(context: Context, src: Uri, p: EditParams, out: File, scope: CoroutineScope, onProgress: (Float) -> Unit): Boolean =
        suspendCancellableCoroutine { cont ->
            runCatching { out.delete() }
            val clip = MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs((p.trimStartUs / 1000L).coerceAtLeast(0L))
                .apply { if (p.trimEndUs > p.trimStartUs) setEndPositionMs(p.trimEndUs / 1000L) }
                .build()
            val item = MediaItem.Builder().setUri(src).setClippingConfiguration(clip).build()
            val edited = EditedMediaItem.Builder(item)
                .setRemoveAudio(p.muted)
                .setEffects(Effects(emptyList(), effects(p, VideoColor(p))))
                .build()
            var poll: kotlinx.coroutines.Job? = null
            val transformer = Transformer.Builder(context)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        poll?.cancel()
                        if (cont.isActive) cont.resume(true)
                    }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        poll?.cancel()
                        if (cont.isActive) cont.resume(false)
                    }
                })
                .build()
            runCatching { transformer.start(edited, out.absolutePath) }.onFailure { if (cont.isActive) cont.resume(false) }
            poll = scope.launch(Dispatchers.Main) {
                val holder = ProgressHolder()
                while (isActive) {
                    if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress / 100f)
                    delay(200)
                }
            }
            cont.invokeOnCancellation {
                poll?.cancel()
                scope.launch(Dispatchers.Main) { runCatching { transformer.cancel() } }
            }
        }
}
