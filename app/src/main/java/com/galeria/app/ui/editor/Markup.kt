package com.galeria.app.ui.editor

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.galeria.app.R
import com.galeria.app.glass.Backdrop
import com.galeria.app.ui.components.GlassIconButton
import com.galeria.app.ui.components.Sym
import com.galeria.app.ui.components.pressable
import kotlin.math.hypot

/** Markup tools; [ERASER] removes whole strokes it touches, like iOS's object eraser. */
object MarkTool {
    const val PEN = Stroke.PEN
    const val MARKER = Stroke.MARKER
    const val PENCIL = Stroke.PENCIL
    const val ERASER = 9
}

val MarkColors = listOf(
    Color(0xFF000000), Color(0xFFFFFFFF), Color(0xFFFF3B30), Color(0xFFFFCC00),
    Color(0xFF34C759), Color(0xFF0A84FF), Color(0xFFAF52DE),
)

private fun widthOf(tool: Int) = when (tool) { MarkTool.MARKER -> .032f; MarkTool.PENCIL -> .0045f; else -> .009f }

/**
 * Drawing surface laid exactly over the edited photo. Finished strokes go into the edit
 * (drawn by the renderer); only the stroke in progress is drawn here.
 */
@Composable
fun MarkupLayer(bmp: Bitmap, params: EditParams, tool: Int, color: Color, onParams: (EditParams) -> Unit, modifier: Modifier = Modifier) {
    val cur by rememberUpdatedState(params)
    val cb by rememberUpdatedState(onParams)
    val toolNow by rememberUpdatedState(tool)
    val colorNow by rememberUpdatedState(color)
    var live by remember { mutableStateOf<Stroke?>(null) }
    fun rect(w: Float, h: Float): FloatArray {
        val (ow, oh) = EditRender.outputSize(cur, bmp.width.toFloat(), bmp.height.toFloat())
        val (s, o) = EditRender.fit(ow, oh, w, h)
        return floatArrayOf(o.x, o.y, ow * s, oh * s)
    }
    Canvas(
        modifier
            .fillMaxSize()
            .pointerInput(bmp) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val r = rect(size.width.toFloat(), size.height.toFloat())
                    fun nx(o: Offset) = ((o.x - r[0]) / r[2]).coerceIn(-.05f, 1.05f)
                    fun ny(o: Offset) = ((o.y - r[1]) / r[3]).coerceIn(-.05f, 1.05f)
                    val erasing = toolNow == MarkTool.ERASER
                    val pts = ArrayList<Float>()
                    fun erase(o: Offset) {
                        val x = nx(o); val y = ny(o); val rad = .03f
                        val keep = cur.strokes.filter { st ->
                            var hit = false
                            var i = 0
                            while (i + 1 < st.points.size && !hit) {
                                if (hypot(st.points[i] - x, (st.points[i + 1] - y) * r[3] / r[2]) < rad + st.width / 2f) hit = true
                                i += 2
                            }
                            !hit
                        }
                        if (keep.size != cur.strokes.size) cb(cur.copy(strokes = keep))
                    }
                    if (erasing) erase(down.position) else { pts += nx(down.position); pts += ny(down.position) }
                    var last = down.position
                    do {
                        val ev = awaitPointerEvent()
                        val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (c.pressed && (c.position - last).getDistance() > 1.5f) {
                            last = c.position
                            if (erasing) erase(c.position)
                            else {
                                pts += nx(c.position); pts += ny(c.position)
                                live = Stroke(toolNow, colorNow.toArgb(), widthOf(toolNow), pts.toList())
                            }
                        }
                        c.consume()
                    } while (ev.changes.any { it.pressed })
                    if (!erasing && pts.size >= 2) cb(cur.copy(strokes = cur.strokes + Stroke(toolNow, colorNow.toArgb(), widthOf(toolNow), pts.toList())))
                    live = null
                }
            }
    ) {
        val st = live ?: return@Canvas
        val r = rect(size.width, size.height)
        drawIntoCanvas { c ->
            val nc = c.nativeCanvas
            nc.save()
            nc.translate(r[0], r[1])
            EditRender.drawStrokes(nc, listOf(st), r[2], r[3])
            nc.restore()
        }
    }
}

/** Tools, colours, undo / redo. */
@Composable
fun MarkupPanel(
    backdrop: Backdrop,
    tool: Int, color: Color,
    canUndo: Boolean, canRedo: Boolean,
    onTool: (Int) -> Unit, onColor: (Color) -> Unit, onUndo: () -> Unit, onRedo: () -> Unit,
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton(backdrop, Sym.undo, stringResource(R.string.undo), onUndo, enabled = canUndo)
                listOf(
                    MarkTool.PEN to Sym.pen, MarkTool.MARKER to Sym.marker,
                    MarkTool.PENCIL to Sym.pencil, MarkTool.ERASER to Sym.eraser,
                ).forEach { (t, icon) ->
                    val on = t == tool
                    GlassIconButton(
                        backdrop, icon, "", { onTool(t) },
                        tint = if (on) Color.White.copy(alpha = .9f) else null,
                        iconTint = if (on) Color.Black else null,
                    )
                }
                GlassIconButton(backdrop, Sym.redo, stringResource(R.string.redo), onRedo, enabled = canRedo)
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                MarkColors.forEach { c ->
                    val on = c == color
                    Box(
                        Modifier
                            .size(30.dp)
                            .then(if (on) Modifier.border(2.5.dp, Color.White, CircleShape) else Modifier)
                            .padding(if (on) 4.dp else 0.dp)
                            .clip(CircleShape)
                            .background(c)
                            .border(1.dp, Color.White.copy(alpha = .35f), CircleShape)
                            .pressable { onColor(c) }
                    )
                }
            }
        }
    }
}

@Suppress("unused")
private val unusedGuard = Modifier.padding(0.dp)
