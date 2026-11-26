package com.galeria.app.data

import android.content.Context
import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Spatial Scenes: a photo split by depth into a subject layer and a background layer (with what was
 * behind the subject filled in), so it can move in 3D as the phone tilts. All on the phone.
 */
object Spatial {
    /** What the depth says about a photo: whether it has a clear subject, and where it separates. */
    class Analysis(val eligible: Boolean, val cut: Float, val focus: Float, val score: Float)

    /**
     * The two layers. [fg] has the subject with soft edges (transparent elsewhere); [bg] is the whole
     * photo with the subject filled in. Depths are grey pictures (white = near), the same size as the layers.
     */
    class Scene(
        val id: Long,
        val fg: Bitmap, val bg: Bitmap,
        val fgDepth: Bitmap, val bgDepth: Bitmap,
        val focus: Float,
        /** Where the subject was removed from [bg] (true = filled in), for the sharper fill later. */
        val hole: BooleanArray,
    )

    // ---------------------------------------------------------------- analysis

    /**
     * Finds the depth cut whose outline runs along real depth edges (a person against a wall, a cup
     * against the table) rather than across smooth surfaces. Photos without one have no clear subject.
     */
    fun analyse(d: Depth.Map): Analysis {
        // Search on a small copy (fast), then use the cut on the full map.
        val k = max(1, max(d.w, d.h) / 200)
        val w = d.w / k; val h = d.h / k
        val v = FloatArray(w * h) { i -> d.v[(i / w) * k * d.w + (i % w) * k] }
        var bestScore = -1f; var bestT = .5f; var bestSharp = 0f; var bestFrac = 0f
        val keep = BooleanArray(w * h)
        var t = .1f
        while (t < .92f) {
            val frac = subject(v, w, h, t, .01f, keep)
            if (frac in .03f..(.75f)) {
                val step = max(1, 4 / k)
                var edges = 0; var sharp = 0
                for (y in 0 until h) for (x in 0 until w) {
                    val i = y * w + x
                    if (!keep[i]) continue
                    // Each subject pixel next to background: how much nearer is it than the background just outside?
                    for (dir in 0 until 4) {
                        val nx = x + when (dir) { 0 -> step; 1 -> -step; else -> 0 }
                        val ny = y + when (dir) { 2 -> step; 3 -> -step; else -> 0 }
                        if (nx !in 0 until w || ny !in 0 until h) continue
                        val j = ny * w + nx
                        if (keep[j]) continue
                        edges++
                        if (v[i] - v[j] > .07f) sharp++
                    }
                }
                if (edges >= 20) {
                    val s = sharp.toFloat() / edges
                    val score = s + .2f * min(frac, .4f)
                    if (score > bestScore) { bestScore = score; bestT = t; bestSharp = s; bestFrac = frac }
                }
            }
            t += .02f
        }
        if (bestScore < 0f) return Analysis(false, .5f, .5f, 0f)
        // The subject stays put; everything else moves around it.
        val full = BooleanArray(d.w * d.h)
        subject(d.v, d.w, d.h, bestT, .01f, full)
        val inSub = FloatArray(full.count { it })
        var c = 0
        for (i in full.indices) if (full[i]) inSub[c++] = d.v[i]
        inSub.sort()
        val focus = if (inSub.isEmpty()) bestT else inSub[inSub.size / 2]
        return Analysis(bestSharp >= .5f && bestFrac >= .03f, bestT, focus, bestSharp)
    }

    /** Pixels nearer than [t], without specks smaller than [minArea] of the picture. Returns the share kept. */
    private fun subject(v: FloatArray, w: Int, h: Int, t: Float, minArea: Float, out: BooleanArray): Float {
        val n = w * h
        val label = IntArray(n)
        val queue = IntArray(n)
        val sizes = ArrayList<Int>(); sizes += 0
        var next = 0
        for (s in 0 until n) {
            if (v[s] <= t || label[s] != 0) continue
            next++
            var head = 0; var tail = 0
            queue[tail++] = s; label[s] = next
            while (head < tail) {
                val i = queue[head++]
                val x = i % w; val y = i / w
                if (x > 0) { val j = i - 1; if (label[j] == 0 && v[j] > t) { label[j] = next; queue[tail++] = j } }
                if (x < w - 1) { val j = i + 1; if (label[j] == 0 && v[j] > t) { label[j] = next; queue[tail++] = j } }
                if (y > 0) { val j = i - w; if (label[j] == 0 && v[j] > t) { label[j] = next; queue[tail++] = j } }
                if (y < h - 1) { val j = i + w; if (label[j] == 0 && v[j] > t) { label[j] = next; queue[tail++] = j } }
            }
            sizes += tail
        }
        val min = (n * minArea).toInt()
        var kept = 0
        for (i in 0 until n) {
            val l = label[i]
            val k = l != 0 && sizes[l] >= min
            out[i] = k
            if (k) kept++
        }
        return kept.toFloat() / n
    }

    // ---------------------------------------------------------------- layers

    /**
     * Builds the two layers from [photo] (software bitmap, long side about 1000–1600 px). The background
     * behind the subject is filled quickly here; [sharpen] later fills it properly with LaMa.
     */
    fun build(id: Long, photo: Bitmap, d: Depth.Map, a: Analysis): Scene {
        val w = photo.width; val h = photo.height; val n = w * h
        val px = IntArray(n).also { photo.getPixels(it, 0, w, 0, 0, w, h) }
        // Depth at the photo's size.
        val dep = FloatArray(n)
        for (y in 0 until h) for (x in 0 until w) dep[y * w + x] = d.at((x + .5f) / w, (y + .5f) / h)
        // Subject mask: specks removed on the depth map's own grid, then lifted to the photo's size.
        val small = BooleanArray(d.w * d.h)
        subject(d.v, d.w, d.h, a.cut, .01f, small)
        val smallMap = Depth.Map(d.w, d.h, FloatArray(small.size) { if (small[it]) 1f else 0f })
        val rough = FloatArray(n)
        for (y in 0 until h) for (x in 0 until w) rough[y * w + x] = smallMap.at((x + .5f) / w, (y + .5f) / h)
        // Edges snapped to the photo (guided filter): hair, ears and fingers follow the picture, not the depth.
        val grey = FloatArray(n) { val c = px[it]; (((c shr 16) and 0xFF) * .299f + ((c shr 8) and 0xFF) * .587f + (c and 0xFF) * .114f) / 255f }
        val r = max(2, max(w, h) / 160)
        val soft = guided(grey, rough, w, h, r, 1e-3f)
        // A firmer edge for the subject layer (no rim of background carried along with it).
        val alpha = FloatArray(n) { ((soft[it] - .25f) / .6f).coerceIn(0f, 1f) }
        // Edge colours without the background mixed in: F = (C − (1−α)·B) / α, with B the background
        // just outside; where α is small, the subject's own colour spread outward.
        val chans = Array(3) { c -> FloatArray(n) { ((px[it] shr (16 - 8 * c)) and 0xFF) / 255f } }
        val outside = BooleanArray(n) { soft[it] > .02f }
        val inside = BooleanArray(n) { alpha[it] < .97f }
        val fgCh = Array(3) { c ->
            val bk = fillPushPull(chans[c], outside, w, h)
            val fin = fillPushPull(chans[c], inside, w, h)
            FloatArray(n) { i ->
                val al = alpha[i]
                if (al >= .97f) chans[c][i]
                else {
                    val f = ((chans[c][i] - (1 - al) * bk[i]) / max(al, .35f)).coerceIn(0f, 1f)
                    val k = ((al - .35f) / .3f).coerceIn(0f, 1f)
                    f * k + fin[i] * (1 - k)
                }
            }
        }
        val fgPx = IntArray(n) { i ->
            val ai = (alpha[i] * 255f).roundToInt()
            if (ai == 0) 0 else (ai shl 24) or (to8(fgCh[0][i]) shl 16) or (to8(fgCh[1][i]) shl 8) or to8(fgCh[2][i])
        }
        val fg = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.setPixels(fgPx, 0, w, 0, 0, w, h) }
        // The subject layer's depth, carried outward past its edge (so its outline moves as one piece).
        val fgDep = fillPushPull(dep, BooleanArray(n) { alpha[it] < .5f }, w, h)
        // What to fill behind it: the subject, a little wider (so no outline of it is left behind).
        val grow = max(3, (max(w, h) * .012f).roundToInt())
        val hole = dilate(BooleanArray(n) { soft[it] > .08f }, w, h, grow)
        val bgCh = Array(3) { c -> fillPushPull(chans[c], hole, w, h) }
        val bg = Bitmap.createBitmap(IntArray(n) { i -> (0xFF shl 24) or (to8(bgCh[0][i]) shl 16) or (to8(bgCh[1][i]) shl 8) or to8(bgCh[2][i]) }, w, h, Bitmap.Config.ARGB_8888)
        // The background's depth behind the subject: spread in from around it.
        val bgDep = fillPushPull(dep, hole, w, h)
        return Scene(id, fg, bg, greyBitmap(fgDep, w, h), greyBitmap(bgDep, w, h), a.focus, hole)
    }

    private fun to8(v: Float) = (v * 255f).roundToInt().coerceIn(0, 255)

    /** The sharp background: LaMa paints what was behind the subject. Returns null if it can't. */
    fun sharpen(context: Context, photo: Bitmap, s: Scene, timeoutMs: Long = 60_000): Bitmap? {
        if (512 !in LaMa.available(context)) return null
        return runCatching { fill(context, photo, s, timeoutMs) }.onFailure { android.util.Log.w("Photos", "spatial fill", it) }.getOrNull()
    }

    private fun fill(context: Context, photo: Bitmap, s: Scene, timeoutMs: Long): Bitmap {
        val w = photo.width; val h = photo.height; val size = 512
        val sq = Bitmap.createScaledBitmap(photo, size, size, true)
        val holeSq = BooleanArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            // A pixel of the square is a hole if any photo pixel it covers is.
            val x0 = x * w / size; val x1 = max(x0 + 1, (x + 1) * w / size); val y0 = y * h / size; val y1 = max(y0 + 1, (y + 1) * h / size)
            var any = false
            var yy = y0
            while (yy < y1 && !any) { var xx = x0; while (xx < x1) { if (s.hole[yy * w + xx]) { any = true; break }; xx++ }; yy++ }
            holeSq[y * size + x] = any
        }
        val out = LaMa.inpaint(context, size, sq, holeSq, timeoutMs)
        if (sq !== photo) sq.recycle()
        val up = Bitmap.createScaledBitmap(out, w, h, true)
        if (up !== out) out.recycle()
        // Only the filled part comes from LaMa (feathered); the rest stays the original photo.
        val n = w * h
        val src = IntArray(n).also { photo.getPixels(it, 0, w, 0, 0, w, h) }
        val fill = IntArray(n).also { up.getPixels(it, 0, w, 0, 0, w, h) }
        up.recycle()
        val soft = box(FloatArray(n) { if (s.hole[it]) 1f else 0f }, w, h, max(2, max(w, h) / 300))
        val res = IntArray(n) { i ->
            val k = soft[i]
            if (k <= 0f) src[i] else if (k >= 1f) fill[i] else mix(src[i], fill[i], k)
        }
        return Bitmap.createBitmap(res, w, h, Bitmap.Config.ARGB_8888)
    }

    // ---------------------------------------------------------------- helpers

    private fun mix(a: Int, b: Int, k: Float): Int {
        val r = (((a shr 16) and 0xFF) * (1 - k) + ((b shr 16) and 0xFF) * k).roundToInt()
        val g = (((a shr 8) and 0xFF) * (1 - k) + ((b shr 8) and 0xFF) * k).roundToInt()
        val bl = ((a and 0xFF) * (1 - k) + (b and 0xFF) * k).roundToInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }

    private fun greyBitmap(v: FloatArray, w: Int, h: Int): Bitmap {
        val px = IntArray(w * h) { val g = (v[it] * 255f).roundToInt().coerceIn(0, 255); (0xFF shl 24) or (g shl 16) or (g shl 8) or g }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Mean over a (2r+1)² square, O(1) per pixel. */
    fun box(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(w * h); val out = FloatArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            var acc = 0f; var cnt = 0
            for (x in 0..min(r, w - 1)) { acc += src[row + x]; cnt++ }
            for (x in 0 until w) {
                tmp[row + x] = acc / cnt
                val add = x + r + 1; val sub = x - r
                if (add < w) { acc += src[row + add]; cnt++ }
                if (sub >= 0) { acc -= src[row + sub]; cnt-- }
            }
        }
        for (x in 0 until w) {
            var acc = 0f; var cnt = 0
            for (y in 0..min(r, h - 1)) { acc += tmp[y * w + x]; cnt++ }
            for (y in 0 until h) {
                out[y * w + x] = acc / cnt
                val add = y + r + 1; val sub = y - r
                if (add < h) { acc += tmp[add * w + x]; cnt++ }
                if (sub >= 0) { acc -= tmp[sub * w + x]; cnt-- }
            }
        }
        return out
    }

    /** He et al.'s guided filter: smooths [p] while keeping the edges of [guide]. */
    fun guided(guide: FloatArray, p: FloatArray, w: Int, h: Int, r: Int, eps: Float): FloatArray {
        val n = w * h
        val mI = box(guide, w, h, r); val mP = box(p, w, h, r)
        val ii = FloatArray(n) { guide[it] * guide[it] }; val ip = FloatArray(n) { guide[it] * p[it] }
        val mII = box(ii, w, h, r); val mIP = box(ip, w, h, r)
        val a = FloatArray(n); val b = FloatArray(n)
        for (i in 0 until n) {
            val varI = mII[i] - mI[i] * mI[i]; val cov = mIP[i] - mI[i] * mP[i]
            a[i] = cov / (varI + eps); b[i] = mP[i] - a[i] * mI[i]
        }
        val ma = box(a, w, h, r); val mb = box(b, w, h, r)
        return FloatArray(n) { (ma[it] * guide[it] + mb[it]).coerceIn(0f, 1f) }
    }

    fun dilate(m: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        // Separable square dilation via running counts.
        val tmp = BooleanArray(w * h); val out = BooleanArray(w * h)
        for (y in 0 until h) {
            var cnt = 0
            for (x in 0..min(r, w - 1)) if (m[y * w + x]) cnt++
            for (x in 0 until w) {
                tmp[y * w + x] = cnt > 0
                val add = x + r + 1; val sub = x - r
                if (add < w && m[y * w + add]) cnt++
                if (sub >= 0 && m[y * w + sub]) cnt--
            }
        }
        for (x in 0 until w) {
            var cnt = 0
            for (y in 0..min(r, h - 1)) if (tmp[y * w + x]) cnt++
            for (y in 0 until h) {
                out[y * w + x] = cnt > 0
                val add = y + r + 1; val sub = y - r
                if (add < h && tmp[add * w + x]) cnt++
                if (sub >= 0 && tmp[sub * w + x]) cnt--
            }
        }
        return out
    }

    /** Push–pull fill of one channel. */
    private fun fillPushPull(v: FloatArray, hole: BooleanArray, w: Int, h: Int): FloatArray {
        // Pyramid of (value × weight, weight).
        val levels = ArrayList<Triple<FloatArray, FloatArray, IntArray>>()
        var cv = FloatArray(w * h) { if (hole[it]) 0f else v[it] }
        var cw = FloatArray(w * h) { if (hole[it]) 0f else 1f }
        var lw = w; var lh = h
        levels += Triple(cv, cw, intArrayOf(lw, lh))
        while (lw > 1 || lh > 1) {
            val nw = max(1, (lw + 1) / 2); val nh = max(1, (lh + 1) / 2)
            val nv = FloatArray(nw * nh); val nwt = FloatArray(nw * nh)
            for (y in 0 until lh) for (x in 0 until lw) {
                val i = y * lw + x; val j = (y / 2) * nw + (x / 2)
                nv[j] += cv[i] * cw[i]; nwt[j] += cw[i]
            }
            for (j in nv.indices) { if (nwt[j] > 0f) nv[j] /= nwt[j]; nwt[j] = min(1f, nwt[j]) }
            cv = nv; cw = nwt; lw = nw; lh = nh
            levels += Triple(cv, cw, intArrayOf(lw, lh))
        }
        // Pull: fill each level's gaps from the coarser one (bilinear), blending by weight.
        for (l in levels.size - 2 downTo 0) {
            val (fv, fwt, fs) = levels[l]; val (pv, _, ps) = levels[l + 1]
            val fw = fs[0]; val fh = fs[1]; val pw = ps[0]; val ph = ps[1]
            for (y in 0 until fh) for (x in 0 until fw) {
                val i = y * fw + x
                if (fwt[i] >= 1f) continue
                val sx = ((x + .5f) / 2f - .5f).coerceIn(0f, pw - 1f); val sy = ((y + .5f) / 2f - .5f).coerceIn(0f, ph - 1f)
                val x0 = sx.toInt(); val y0 = sy.toInt(); val x1 = min(x0 + 1, pw - 1); val y1 = min(y0 + 1, ph - 1)
                val ax = sx - x0; val ay = sy - y0
                val up = (pv[y0 * pw + x0] * (1 - ax) + pv[y0 * pw + x1] * ax) * (1 - ay) + (pv[y1 * pw + x0] * (1 - ax) + pv[y1 * pw + x1] * ax) * ay
                fv[i] = fv[i] * fwt[i] + up * (1 - fwt[i])
                fwt[i] = 1f
            }
        }
        return levels[0].first
    }
}
