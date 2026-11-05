package com.galeria.app.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.unit.dp

/** SF Symbols look-alikes, drawn on a 24-unit grid (the Library glyph uses 26). */
object Sym {
    private fun circle(cx: Float, cy: Float, r: Float) =
        "M${cx - r},${cy}a$r,$r 0 1,0 ${2 * r},0a$r,$r 0 1,0 ${-2 * r},0Z"

    private fun rrect(x: Float, y: Float, w: Float, h: Float, r: Float) =
        "M${x + r},${y}h${w - 2 * r}a$r,$r 0 0 1 $r,$r" + "v${h - 2 * r}a$r,$r 0 0 1 ${-r},$r" +
            "h${-(w - 2 * r)}a$r,$r 0 0 1 ${-r},${-r}" + "v${-(h - 2 * r)}a$r,$r 0 0 1 $r,${-r}Z"

    private fun build(name: String, vw: Float = 24f, mirror: Boolean = false, block: ImageVector.Builder.() -> Unit) =
        ImageVector.Builder(name, vw.dp, 24.dp, vw, 24f, autoMirror = mirror).apply(block).build()

    private fun ImageVector.Builder.s(d: String, w: Float = 2f, alpha: Float = 1f) = addPath(
        pathData = addPathNodes(d), fill = null, stroke = SolidColor(Color.Black), strokeAlpha = alpha,
        strokeLineWidth = w, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
    )

    private fun ImageVector.Builder.f(d: String, evenOdd: Boolean = false, alpha: Float = 1f) = addPath(
        pathData = addPathNodes(d), fill = SolidColor(Color.Black), fillAlpha = alpha,
        pathFillType = if (evenOdd) PathFillType.EvenOdd else PathFillType.NonZero,
    )

    private const val HEART = "M12 20.3s-7.9-4.7-7.9-10.4A4.4 4.4 0 0 1 8.5 5.5c1.5 0 2.8.8 3.5 2 .7-1.2 2-2 3.5-2a4.4 4.4 0 0 1 4.4 4.4c0 5.7-7.9 10.4-7.9 10.4z"

    val library = build("library", 26f) {
        s("M7.6 6.3V5.4A2.4 2.4 0 0 1 10 3h10.6a2.9 2.9 0 0 1 2.9 2.9v8.4a2.3 2.3 0 0 1-2.3 2.3", 1.9f)
        f("M5.1 7.6h12.6a2.8 2.8 0 0 1 2.8 2.8v8.3a2.8 2.8 0 0 1-2.8 2.8H5.1a2.8 2.8 0 0 1-2.8-2.8v-8.3a2.8 2.8 0 0 1 2.8-2.8zm9.5 3a1.65 1.65 0 1 0 0 3.3 1.65 1.65 0 0 0 0-3.3zM4.3 19.4h14.3l-3.5-3.9a.9.9 0 0 0-1.33 0l-1.5 1.6-3.1-3.45a.9.9 0 0 0-1.33 0z", evenOdd = true)
    }
    val collections = build("collections") {
        f(rrect(2.6f, 9.2f, 18.8f, 12.6f, 3f)); f(rrect(4.8f, 5.6f, 14.4f, 2.3f, 1.15f)); f(rrect(7.2f, 2.4f, 9.6f, 2f, 1f))
    }
    val search = build("search") { s(circle(10.4f, 10.4f, 6.6f), 2.3f); s("M15.4 15.4l5.2 5.2", 2.6f) }
    val filter = build("filter") { s("M4 6.5h16M7 12h10M10 17.5h4", 2.1f) }
    val chevronForward = build("chevronForward", mirror = true) { s("M9 4.5l7.5 7.5L9 19.5", 2.7f) }
    val chevronBack = build("chevronBack", mirror = true) { s("M15.5 4.5L8 12l7.5 7.5", 2.6f) }
    val chevronDown = build("chevronDown") { s("M4.8 8.6l7.2 7.2 7.2-7.2", 2.8f) }
    val chevronUp = build("chevronUp") { s("M4.8 15.4l7.2-7.2 7.2 7.2", 2.8f) }
    val ellipsis = build("ellipsis") { f(circle(5.2f, 12f, 2.05f)); f(circle(12f, 12f, 2.05f)); f(circle(18.8f, 12f, 2.05f)) }
    val heart = build("heart") { s(HEART, 2f) }
    val heartFill = build("heartFill") { f(HEART) }
    val info = build("info") {
        s(circle(11f, 13f, 8.2f), 1.9f); s("M11 12.1v5", 2.1f); f(circle(11f, 9.1f, 1.25f))
        f("M19.6 1.6l.65 1.75 1.75.65-1.75.65-.65 1.75-.65-1.75L17.2 4l1.75-.65z")
    }
    val sliders = build("sliders") {
        s("M3.5 6.5h5.5M13 6.5h7.5M3.5 12h10.5M18 12h2.5M3.5 17.5h3.5M11 17.5h9.5", 1.9f)
        s(circle(11f, 6.5f, 2f), 1.9f); s(circle(16f, 12f, 2f), 1.9f); s(circle(9f, 17.5f, 2f), 1.9f)
    }
    val trash = build("trash") {
        s("M4.3 6.4h15.4M9.4 6.4V4.9c0-.8.5-1.3 1.3-1.3h2.6c.8 0 1.3.5 1.3 1.3v1.5M6.3 6.4l.85 12.5c.07 1 .9 1.7 1.9 1.7h5.9c1 0 1.83-.7 1.9-1.7l.85-12.5M10 10.4v6.4M14 10.4v6.4", 1.85f)
    }
    val share = build("share") {
        s("M12 3.4v11M8.2 7L12 3.2 15.8 7", 1.95f)
        s("M8.6 10H7.3A2.3 2.3 0 0 0 5 12.3v6.4A2.3 2.3 0 0 0 7.3 21h9.4a2.3 2.3 0 0 0 2.3-2.3v-6.4a2.3 2.3 0 0 0-2.3-2.3h-1.3", 1.95f)
    }
    val lock = build("lock") { f(rrect(5f, 10.4f, 14f, 10.6f, 2.5f)); s("M8 10.4V7.8a4 4 0 0 1 8 0v2.6", 2.2f) }
    val checkmark = build("checkmark") { s("M4.8 12.6l4.6 4.6 9.8-10", 2.7f) }
    val play = build("play") { f("M7.5 5.1v13.8c0 .85.95 1.35 1.65.87l10.4-6.9c.62-.42.62-1.33 0-1.74L9.15 4.23C8.45 3.75 7.5 4.25 7.5 5.1z") }
    val pause = build("pause") { f(rrect(6.3f, 4.6f, 4.1f, 14.8f, 1.3f)); f(rrect(13.6f, 4.6f, 4.1f, 14.8f, 1.3f)) }
    val xmarkCircle = build("xmarkCircle") { f(circle(12f, 12f, 9.4f)); s("M9 9l6 6M15 9l-6 6", 1.9f, alpha = 0f) }
    val xmark = build("xmark") { s("M6.5 6.5l11 11M17.5 6.5l-11 11", 2.4f) }
    val person = build("person") { f(circle(12f, 8.6f, 4.1f)); f("M4.2 20.6c.8-4.2 3.9-6.3 7.8-6.3s7 2.1 7.8 6.3z") }
    val grid = build("grid") {
        for (r in 0..2) for (c in 0..2) f(rrect(3.6f + c * 6.1f, 3.6f + r * 6.1f, 4.6f, 4.6f, 1.2f))
    }
    val photo = build("photo") {
        s(rrect(3f, 4.8f, 18f, 14.4f, 2.7f), 1.9f); f(circle(15.4f, 9.4f, 1.6f)); s("M3.8 17.6l4.7-5.1 3.7 4 2.1-2.1 5.6 5", 1.9f)
    }
    val video = build("video") {
        s(rrect(2.6f, 6f, 13.6f, 12f, 2.7f), 1.9f); s("M16.2 10.3l4.3-2.6c.5-.3 1 0 1 .6v7.4c0 .6-.5.9-1 .6l-4.3-2.6", 1.9f)
    }
    val viewfinder = build("viewfinder") {
        s("M3.8 8.6V6.5a2.7 2.7 0 0 1 2.7-2.7h2.1M15.4 3.8h2.1a2.7 2.7 0 0 1 2.7 2.7v2.1M20.2 15.4v2.1a2.7 2.7 0 0 1-2.7 2.7h-2.1M8.6 20.2H6.5a2.7 2.7 0 0 1-2.7-2.7v-2.1", 1.9f)
        s(rrect(7.9f, 9.3f, 8.2f, 6.2f, 1.5f), 1.6f); f(circle(12f, 12.4f, 1.5f))
    }
    val record = build("record") { s(circle(12f, 12f, 8.6f), 1.85f); f(circle(12f, 12f, 4.2f)) }
    val zoomIn = build("zoomIn") { s(circle(10.4f, 10.4f, 6.6f), 2f); s("M15.4 15.4l5 5M10.4 7.6v5.6M7.6 10.4h5.6", 2f) }
    val zoomOut = build("zoomOut") { s(circle(10.4f, 10.4f, 6.6f), 2f); s("M15.4 15.4l5 5M7.6 10.4h5.6", 2f) }
    val aspect = build("aspect") {
        s(rrect(3f, 4f, 8f, 10f, 1.8f), 1.8f); s(rrect(13f, 4f, 8f, 6f, 1.8f), 1.8f)
        s(rrect(3f, 16f, 8f, 4.5f, 1.5f), 1.8f); s(rrect(13f, 12f, 8f, 8.5f, 1.8f), 1.8f)
    }
    val panorama = build("panorama") { s("M2.8 7.2c6-2.4 12.4-2.4 18.4 0v9.6c-6-2.4-12.4-2.4-18.4 0z", 1.9f) }
    val duplicate = build("duplicate") {
        s(rrect(8f, 8f, 12.5f, 12.5f, 2.6f), 1.9f)
        s("M16 8V6.1A2.6 2.6 0 0 0 13.4 3.5H6.1A2.6 2.6 0 0 0 3.5 6.1v7.3A2.6 2.6 0 0 0 6.1 16H8", 1.9f)
    }
    val eyeSlash = build("eyeSlash") {
        s("M3 12s3.3-6 9-6 9 6 9 6-3.3 6-9 6-9-6-9-6z", 1.85f); s(circle(12f, 12f, 2.8f), 1.85f); s("M4 4l16 16", 1.85f)
    }
    val eye = build("eye") { s("M2.8 12s3.4-6.2 9.2-6.2 9.2 6.2 9.2 6.2-3.4 6.2-9.2 6.2S2.8 12 2.8 12z", 1.85f); f(circle(12f, 12f, 3f)) }
    val arrowDownCircle = build("arrowDownCircle") { s(circle(12f, 12f, 8.6f), 1.85f); s("M12 7.6v8.2M8.4 12.4l3.6 3.6 3.6-3.6", 1.85f) }
    val clock = build("clock") { s(circle(12f, 12f, 8.6f), 1.85f); s("M12 7.4V12l3.2 2", 1.85f) }
    val docOnDoc = build("docOnDoc") {
        s(rrect(8f, 7f, 11.5f, 14f, 2.4f), 1.85f); s("M16 7V5.4A2.4 2.4 0 0 0 13.6 3H6.9A2.4 2.4 0 0 0 4.5 5.4v10.2A2.4 2.4 0 0 0 6.9 18H8", 1.85f)
    }
    val playRectangle = build("playRectangle") { s(rrect(2.8f, 5f, 18.4f, 14f, 3f), 1.85f); f("M10 9.2v5.6c0 .5.5.8.9.5l4.4-2.8c.4-.3.4-.8 0-1.1l-4.4-2.8c-.4-.2-.9 0-.9.6z") }
    val wallpaper = build("wallpaper") { s(rrect(6f, 2.8f, 12f, 18.4f, 2.8f), 1.85f); s("M8.5 16.5l2.4-2.7 1.8 2 1.3-1.4 1.6 2.1", 1.6f); f(circle(14.5f, 8.5f, 1.3f)) }
    val speaker = build("speaker") { f("M4 9.2h3.2L11.6 5.4c.5-.4 1.2-.1 1.2.6v12c0 .7-.7 1-1.2.6L7.2 14.8H4c-.6 0-1-.4-1-1V10.2c0-.6.4-1 1-1z"); s("M15.6 9a4.2 4.2 0 0 1 0 6M18.2 6.6a7.6 7.6 0 0 1 0 10.8", 1.8f) }
    val speakerSlash = build("speakerSlash") { f("M4 9.2h3.2L11.6 5.4c.5-.4 1.2-.1 1.2.6v12c0 .7-.7 1-1.2.6L7.2 14.8H4c-.6 0-1-.4-1-1V10.2c0-.6.4-1 1-1z"); s("M16 9.5l5 5M21 9.5l-5 5", 1.8f) }
    val map = build("map") { s("M9 4.5L3.5 6.6v13l5.5-2.1 6 2.1 5.5-2.1v-13L15 6.6 9 4.5zM9 4.5v13M15 6.6v13", 1.8f) }
    val gear = build("gear") {
        s(circle(12f, 12f, 3.2f), 1.8f)
        s("M12 2.8l1.5 2.4 2.8-.6.6 2.8 2.4 1.5-1.3 2.5 1.3 2.5-2.4 1.5-.6 2.8-2.8-.6L12 21.2l-1.5-2.4-2.8.6-.6-2.8-2.4-1.5 1.3-2.5-1.3-2.5 2.4-1.5.6-2.8 2.8.6z", 1.7f)
    }
    val upDown = build("upDown") { s("M7 9.5l5-5 5 5M7 14.5l5 5 5-5", 2.4f) }
    val sparkle = build("sparkle") { f("M10 3.5l1.6 4.4 4.4 1.6-4.4 1.6L10 15.5l-1.6-4.4L4 9.5l4.4-1.6zM17.5 13.5l.9 2.3 2.3.9-2.3.9-.9 2.3-.9-2.3-2.3-.9 2.3-.9z") }

    /** The Photos app mark: eight translucent petals. Keeps its own colours. */
    val flower: ImageVector = ImageVector.Builder("flower", 24.dp, 24.dp, 24f, 24f).apply {
        val colors = listOf(0xFFFF9F0A, 0xFFFFCC00, 0xFFA8D930, 0xFF30C454, 0xFF1E9BFF, 0xFF5E5CE6, 0xFFBF5AF2, 0xFFFF375F)
        colors.forEachIndexed { k, c ->
            group(rotate = k * 45f, pivotX = 12f, pivotY = 12f) {
                addPath(pathData = addPathNodes("M8.7,6.4a3.3,5 0 1,0 6.6,0a3.3,5 0 1,0 -6.6,0Z"), fill = SolidColor(Color(c)), fillAlpha = .86f)
            }
        }
    }.build()
}
