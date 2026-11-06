package com.galeria.app.glass

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.galeria.app.ui.theme.GlassLook
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A live picture of whatever is drawn behind the glass. The content area records itself into
 * a GraphicsLayer every frame; each glass element redraws that layer, offset to its own position,
 * through blur + saturation + an edge-refraction shader. Nothing is a static screenshot.
 */
@Stable
class Backdrop {
    internal var layer: GraphicsLayer? = null
    internal var origin: Offset = Offset.Zero
    private val listeners = ArrayList<() -> Unit>()
    internal fun add(l: () -> Unit) { listeners += l }
    internal fun remove(l: () -> Unit) { listeners -= l }
    /** Redraws every glass element that uses this backdrop. */
    fun invalidate() { for (i in listeners.indices.reversed()) listeners.getOrNull(i)?.invoke() }
}

@Composable
fun rememberBackdrop(): Backdrop = remember { Backdrop() }

fun Modifier.backdropSource(backdrop: Backdrop): Modifier = this.graphicsLayer().then(SourceElement(backdrop))

private data class SourceElement(val backdrop: Backdrop) : ModifierNodeElement<SourceNode>() {
    override fun create() = SourceNode(backdrop)
    override fun update(node: SourceNode) { node.backdrop = backdrop }
    override fun InspectorInfo.inspectableProperties() { name = "backdropSource" }
}

private class SourceNode(var backdrop: Backdrop) : Modifier.Node(), DrawModifierNode, GlobalPositionAwareModifierNode {
    private var layer: GraphicsLayer? = null

    override fun onAttach() {
        val l = requireGraphicsContext().createGraphicsLayer()
        layer = l
        backdrop.layer = l
    }

    override fun onDetach() {
        val l = layer
        if (l != null) {
            if (backdrop.layer === l) backdrop.layer = null
            requireGraphicsContext().releaseGraphicsLayer(l)
        }
        layer = null
    }

    override fun ContentDrawScope.draw() {
        val l = layer
        if (l == null) {
            drawContent(); return
        }
        l.record { this@draw.drawContent() }
        drawLayer(l)
        backdrop.invalidate()
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val p = coordinates.positionInRoot()
        if (p != backdrop.origin) {
            backdrop.origin = p
            backdrop.invalidate()
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Refraction: a rounded-rect signed distance field; inside a bezel band near the edge the
// backdrop is sampled from further inside, so content bends at the rim like a thick lens.
// ---------------------------------------------------------------------------------------------
private const val REFRACT_SKSL = """
uniform shader content;
uniform float2 origin;
uniform float2 size;
uniform float radius;
uniform float bezel;
uniform float strength;

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 c = coord - origin - halfSize;
    float2 q = abs(c) - (halfSize - float2(radius));
    float dist = length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - radius;
    float t = -dist;
    if (t > 0.0 && t < bezel) {
        float k = 1.0 - t / bezel;
        float m = k * k * (1.6 - 0.6 * k);
        float2 s = float2(c.x < 0.0 ? -1.0 : 1.0, c.y < 0.0 ? -1.0 : 1.0);
        float2 n;
        if (q.x > 0.0 && q.y > 0.0) {
            n = normalize(q) * s;
        } else if (q.x > q.y) {
            n = float2(s.x, 0.0);
        } else {
            n = float2(0.0, s.y);
        }
        coord -= n * m * strength;
    }
    return content.eval(coord);
}
"""

internal object GlassFx {
    val supported: Boolean get() = Build.VERSION.SDK_INT >= 31
    val refractionSupported: Boolean get() = Build.VERSION.SDK_INT >= 33 && !shaderFailed
    @Volatile var shaderFailed = false

    @RequiresApi(31)
    fun blurSat(blurPx: Float, saturation: Float, inner: android.graphics.RenderEffect?): android.graphics.RenderEffect {
        val blur = if (inner != null) android.graphics.RenderEffect.createBlurEffect(max(blurPx, .5f), max(blurPx, .5f), inner, Shader.TileMode.CLAMP)
        else android.graphics.RenderEffect.createBlurEffect(max(blurPx, .5f), max(blurPx, .5f), Shader.TileMode.CLAMP)
        if (saturation == 1f) return blur
        val m = ColorMatrix().apply { setSaturation(saturation) }
        return android.graphics.RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(m), blur)
    }

    @RequiresApi(33)
    fun refract(shader: RuntimeShader, pad: Float, w: Float, h: Float, r: Float, bezel: Float, strength: Float): android.graphics.RenderEffect {
        shader.setFloatUniform("origin", pad, pad)
        shader.setFloatUniform("size", w, h)
        shader.setFloatUniform("radius", r)
        shader.setFloatUniform("bezel", bezel)
        shader.setFloatUniform("strength", strength)
        return android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content")
    }

    @RequiresApi(33)
    fun newShader(): RuntimeShader = RuntimeShader(REFRACT_SKSL)
}

/** Paints Liquid Glass inside [shape] behind this element's content. */
fun Modifier.liquidGlass(
    backdrop: Backdrop?,
    shape: Shape,
    look: GlassLook,
    density: Float,
    tint: Color? = null,
    highlight: Float = 1f,
): Modifier = this.graphicsLayer().then(GlassElement(backdrop, shape, look, density, tint, highlight))

private data class GlassElement(
    val backdrop: Backdrop?,
    val shape: Shape,
    val look: GlassLook,
    val density: Float,
    val tint: Color?,
    val highlight: Float,
) : ModifierNodeElement<GlassNode>() {
    override fun create() = GlassNode(backdrop, shape, look, density, tint, highlight)
    override fun update(node: GlassNode) {
        node.update(backdrop, shape, look, density, tint, highlight)
    }
    override fun InspectorInfo.inspectableProperties() { name = "liquidGlass" }
}

private class GlassNode(
    var backdrop: Backdrop?,
    var shape: Shape,
    var look: GlassLook,
    var density: Float,
    var tint: Color?,
    var highlight: Float,
) : Modifier.Node(), DrawModifierNode, GlobalPositionAwareModifierNode {
    private var effectLayer: GraphicsLayer? = null
    private var pos = Offset.Zero
    private var shader: Any? = null
    private var effectKey: Any? = null
    private var effect: androidx.compose.ui.graphics.RenderEffect? = null
    private val listener: () -> Unit = { if (isAttached) invalidateDraw() }

    fun update(b: Backdrop?, s: Shape, l: GlassLook, d: Float, t: Color?, h: Float) {
        if (b !== backdrop) {
            backdrop?.remove(listener); backdrop = b
            if (isAttached) b?.add(listener)
        }
        shape = s; look = l; density = d; tint = t; highlight = h
        invalidateDraw()
    }

    override fun onAttach() {
        effectLayer = requireGraphicsContext().createGraphicsLayer()
        backdrop?.add(listener)
    }

    override fun onDetach() {
        backdrop?.remove(listener)
        effectLayer?.let { requireGraphicsContext().releaseGraphicsLayer(it) }
        effectLayer = null
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val p = coordinates.positionInRoot()
        if (p != pos) {
            pos = p; invalidateDraw()
        }
    }

    override fun ContentDrawScope.draw() {
        if (size.width < 1f || size.height < 1f) {
            drawContent(); return
        }
        val outline = shape.createOutline(size, layoutDirection, this)
        val path = Path().apply { addOutline(outline) }
        val radius = cornerRadius(outline, size)
        val src = backdrop?.layer
        val el = effectLayer
        val l = look
        clipPath(path) {
            if (src != null && el != null && GlassFx.supported) {
                val blurPx = l.blurDp * density
                val pad = max(8f, blurPx * 2.5f).roundToInt()
                val key = listOf(size.width.roundToInt(), size.height.roundToInt(), radius.roundToInt(), l, density)
                if (key != effectKey || effect == null) {
                var inner: android.graphics.RenderEffect? = null
                if (GlassFx.refractionSupported && l.refraction > 0f) {
                    try {
                        val sh = (shader as? RuntimeShader) ?: GlassFx.newShader().also { shader = it }
                        val bezel = min(20f * density, min(size.width, size.height) * .36f)
                        inner = GlassFx.refract(sh, pad.toFloat(), size.width, size.height, radius, bezel, bezel * .85f * l.refraction)
                    } catch (t: Throwable) {
                        // A GPU without AGSL support: keep the frosted glass, drop the edge bending.
                        GlassFx.shaderFailed = true; inner = null
                    }
                }
                effect = GlassFx.blurSat(blurPx, l.saturation, inner).asComposeRenderEffect()
                effectKey = key
                }
                if (el.renderEffect !== effect) el.renderEffect = effect
                el.topLeft = IntOffset(-pad, -pad)
                val b = backdrop!!
                el.record(IntSize(size.width.roundToInt() + pad * 2, size.height.roundToInt() + pad * 2)) {
                    translate(b.origin.x - pos.x + pad, b.origin.y - pos.y + pad) { drawLayer(src) }
                }
                drawLayer(el)
                drawRect(tint ?: l.tint)
            } else {
                // No live backdrop (Android 11): an opaque frosted fill keeps text readable.
                val base = tint ?: l.tint
                drawRect(base.copy(alpha = max(base.alpha, .92f)))
            }
            // Specular rim: bright on the top-leading and bottom-trailing edges, like light on a lens.
            val w = size.width; val h = size.height
            val a = l.rimAlpha * highlight
            drawOutline(
                outline,
                brush = Brush.linearGradient(
                    0f to Color.White.copy(alpha = a), .32f to Color.White.copy(alpha = a * .18f),
                    .68f to Color.White.copy(alpha = a * .14f), 1f to Color.White.copy(alpha = a * .7f),
                    start = Offset.Zero, end = Offset(w, h),
                ),
                style = Stroke(width = 2.2f * density),
            )
            drawOutline(outline, color = Color.White.copy(alpha = .10f * highlight), style = Stroke(width = 9f * density))
        }
        drawContent()
    }
}

private fun cornerRadius(o: Outline, size: Size): Float = when (o) {
    is Outline.Rounded -> o.roundRect.topLeftCornerRadius.x
    is Outline.Rectangle -> 0f
    else -> min(size.width, size.height) / 2f
}

/**
 * The iOS 26 "scroll edge effect": content that scrolls under the top (or bottom) bar is
 * progressively blurred and softened, instead of a hard bar.
 */
fun Modifier.scrollEdge(backdrop: Backdrop?, fromTop: Boolean, tint: Color, blurPx: Float): Modifier =
    this.graphicsLayer().then(EdgeElement(backdrop, fromTop, tint, blurPx))

private data class EdgeElement(val backdrop: Backdrop?, val fromTop: Boolean, val tint: Color, val blurPx: Float) :
    ModifierNodeElement<EdgeNode>() {
    override fun create() = EdgeNode(backdrop, fromTop, tint, blurPx)
    override fun update(node: EdgeNode) {
        if (node.backdrop !== backdrop) {
            node.backdrop?.remove(node.listener); node.backdrop = backdrop
            if (node.isAttached) backdrop?.add(node.listener)
        }
        node.fromTop = fromTop; node.tint = tint; node.blurPx = blurPx
        node.invalidateDraw()
    }
    override fun InspectorInfo.inspectableProperties() { name = "scrollEdge" }
}

private class EdgeNode(var backdrop: Backdrop?, var fromTop: Boolean, var tint: Color, var blurPx: Float) :
    Modifier.Node(), DrawModifierNode, GlobalPositionAwareModifierNode {
    private var blurLayer: GraphicsLayer? = null
    private var maskLayer: GraphicsLayer? = null
    private var pos = Offset.Zero
    private var effectBlur = -1f
    private var effect: androidx.compose.ui.graphics.RenderEffect? = null
    val listener: () -> Unit = { if (isAttached) invalidateDraw() }

    override fun onAttach() {
        val g = requireGraphicsContext()
        blurLayer = g.createGraphicsLayer()
        maskLayer = g.createGraphicsLayer().apply { compositingStrategy = CompositingStrategy.Offscreen }
        backdrop?.add(listener)
    }

    override fun onDetach() {
        backdrop?.remove(listener)
        val g = requireGraphicsContext()
        blurLayer?.let { g.releaseGraphicsLayer(it) }
        maskLayer?.let { g.releaseGraphicsLayer(it) }
        blurLayer = null; maskLayer = null
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val p = coordinates.positionInRoot()
        if (p != pos) {
            pos = p; invalidateDraw()
        }
    }

    override fun ContentDrawScope.draw() {
        val src = backdrop?.layer
        val bl = blurLayer
        val ml = maskLayer
        val fade = if (fromTop) Brush.verticalGradient(0f to Color.Black, .42f to Color.Black.copy(alpha = .9f), 1f to Color.Transparent)
        else Brush.verticalGradient(0f to Color.Transparent, .58f to Color.Black.copy(alpha = .9f), 1f to Color.Black)
        if (src != null && bl != null && ml != null && GlassFx.supported && size.height >= 1f) {
            val pad = max(8f, blurPx * 2.5f).roundToInt()
            if (effect == null || effectBlur != blurPx) {
                effect = GlassFx.blurSat(blurPx, 1.15f, null).asComposeRenderEffect(); effectBlur = blurPx
            }
            if (bl.renderEffect !== effect) bl.renderEffect = effect
            bl.topLeft = IntOffset(-pad, -pad)
            val b = backdrop!!
            bl.record(IntSize(size.width.roundToInt() + pad * 2, size.height.roundToInt() + pad * 2)) {
                translate(b.origin.x - pos.x + pad, b.origin.y - pos.y + pad) { drawLayer(src) }
            }
            ml.record(IntSize(size.width.roundToInt(), size.height.roundToInt())) {
                drawLayer(bl)
                drawRect(fade, blendMode = BlendMode.DstIn)
            }
            drawLayer(ml)
        }
        val wash = if (fromTop) Brush.verticalGradient(0f to tint, .45f to tint.copy(alpha = tint.alpha * .7f), 1f to Color.Transparent)
        else Brush.verticalGradient(0f to Color.Transparent, 1f to tint)
        drawRect(wash)
        drawContent()
    }
}
