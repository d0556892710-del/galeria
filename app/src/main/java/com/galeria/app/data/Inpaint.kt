package com.galeria.app.data

import java.util.Random
import kotlin.math.max
import kotlin.math.min

/**
 * Fills a masked area of a picture with texture from the rest of it (multi-scale PatchMatch,
 * the classic "content-aware fill"): coarse levels decide the structure, finer levels the detail.
 */
object Inpaint {
    private const val R = 3 // 7×7 patches

    private class Level(val w: Int, val h: Int, val r: IntArray, val g: IntArray, val b: IntArray, val mask: BooleanArray) {
        fun down(): Level {
            val w2 = (w + 1) / 2; val h2 = (h + 1) / 2
            val r2 = IntArray(w2 * h2); val g2 = IntArray(w2 * h2); val b2 = IntArray(w2 * h2); val m2 = BooleanArray(w2 * h2)
            for (y in 0 until h2) for (x in 0 until w2) {
                var sr = 0; var sg = 0; var sb = 0; var n = 0; var masked = false
                for (dy in 0..1) for (dx in 0..1) {
                    val xx = min(w - 1, x * 2 + dx); val yy = min(h - 1, y * 2 + dy)
                    val i = yy * w + xx
                    if (mask[i]) masked = true else { sr += r[i]; sg += g[i]; sb += b[i]; n++ }
                }
                val o = y * w2 + x
                m2[o] = masked
                if (n > 0) { r2[o] = sr / n; g2[o] = sg / n; b2[o] = sb / n }
            }
            return Level(w2, h2, r2, g2, b2, m2)
        }
    }

    fun fill(px: IntArray, w: Int, h: Int, mask: BooleanArray): IntArray {
        val r = IntArray(w * h) { (px[it] shr 16) and 255 }
        val g = IntArray(w * h) { (px[it] shr 8) and 255 }
        val b = IntArray(w * h) { px[it] and 255 }
        val levels = ArrayList<Level>()
        var cur = Level(w, h, r, g, b, mask.copyOf())
        levels += cur
        while (min(cur.w, cur.h) > 40 && levels.size < 7) { cur = cur.down(); levels += cur }
        diffuse(levels.last())
        val rnd = Random(7)
        var nnf: IntArray? = null
        for (li in levels.indices.reversed()) {
            val lv = levels[li]
            if (li != levels.lastIndex) {
                val pv = levels[li + 1]
                for (y in 0 until lv.h) for (x in 0 until lv.w) {
                    val i = y * lv.w + x
                    if (!lv.mask[i]) continue
                    val c = min(pv.h - 1, y / 2) * pv.w + min(pv.w - 1, x / 2)
                    lv.r[i] = pv.r[c]; lv.g[i] = pv.g[c]; lv.b[i] = pv.b[c]
                }
            }
            val valid = validCentres(lv)
            if (valid.isEmpty()) { diffuse(lv); nnf = null; continue }
            val validSet = BooleanArray(lv.w * lv.h).also { s -> valid.forEach { s[it] = true } }
            val prev = if (li != levels.lastIndex) levels[li + 1] else null
            nnf = patchMatch(lv, valid, validSet, nnf, prev, rnd, if (li == levels.lastIndex) 7 else if (li == 0) 3 else 4)
        }
        val fin = levels[0]
        val out = px.copyOf()
        for (i in out.indices) if (mask[i]) out[i] = (0xFF shl 24) or (fin.r[i].coerceIn(0, 255) shl 16) or (fin.g[i].coerceIn(0, 255) shl 8) or fin.b[i].coerceIn(0, 255)
        return out
    }

    /** Patch centres whose whole patch is known and inside the picture. */
    private fun validCentres(lv: Level): IntArray {
        val w = lv.w; val h = lv.h
        val sat = IntArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var row = 0
            for (x in 0 until w) {
                if (lv.mask[y * w + x]) row++
                sat[(y + 1) * (w + 1) + x + 1] = sat[y * (w + 1) + x + 1] + row
            }
        }
        val out = ArrayList<Int>()
        for (y in R until h - R) for (x in R until w - R) {
            val x0 = x - R; val y0 = y - R; val x1 = x + R + 1; val y1 = y + R + 1
            val n = sat[y1 * (w + 1) + x1] - sat[y0 * (w + 1) + x1] - sat[y1 * (w + 1) + x0] + sat[y0 * (w + 1) + x0]
            if (n == 0) out += y * w + x
        }
        return out.toIntArray()
    }

    private fun dist(lv: Level, t: Int, s: Int, cap: Long): Long {
        val w = lv.w; val h = lv.h
        val tx = t % w; val ty = t / w; val sx = s % w; val sy = s / w
        var d = 0L
        for (dy in -R..R) {
            val y = ty + dy
            if (y < 0 || y >= h) continue
            val trow = y * w; val srow = (sy + dy) * w
            for (dx in -R..R) {
                val x = tx + dx
                if (x < 0 || x >= w) continue
                val a = trow + x; val c = srow + sx + dx
                val dr = lv.r[a] - lv.r[c]; val dg = lv.g[a] - lv.g[c]; val db = lv.b[a] - lv.b[c]
                d += (dr * dr + dg * dg + db * db).toLong()
            }
            if (d >= cap) return d
        }
        return d
    }

    private fun patchMatch(lv: Level, valid: IntArray, validSet: BooleanArray, coarse: IntArray?, pv: Level?, rnd: Random, iters: Int): IntArray {
        val w = lv.w; val h = lv.h
        val targets = (0 until w * h).filter { lv.mask[it] }.toIntArray()
        val nnf = IntArray(w * h) { -1 }
        val best = LongArray(w * h)
        fun clampValid(x: Int, y: Int): Int {
            val cx = x.coerceIn(R, w - 1 - R); val cy = y.coerceIn(R, h - 1 - R)
            val i = cy * w + cx
            return if (validSet[i]) i else -1
        }
        for (t in targets) {
            var s = -1
            if (coarse != null && pv != null) {
                val tx = t % w; val ty = t / w
                val c = min(pv.h - 1, ty / 2) * pv.w + min(pv.w - 1, tx / 2)
                val cs = coarse.getOrElse(c) { -1 }
                if (cs >= 0) s = clampValid((cs % pv.w) * 2 + tx % 2, (cs / pv.w) * 2 + ty % 2)
            }
            if (s < 0) s = valid[rnd.nextInt(valid.size)]
            nnf[t] = s; best[t] = dist(lv, t, s, Long.MAX_VALUE)
        }
        for (it in 0 until iters) {
            val fwd = it % 2 == 0
            val dir = if (fwd) 1 else -1
            val range = if (fwd) targets.indices else targets.indices.reversed()
            for (k in range) {
                val t = targets[k]
                val tx = t % w; val ty = t / w
                var bs = nnf[t]; var bd = best[t]
                fun tryS(s: Int) {
                    if (s < 0 || s == bs) return
                    val d = dist(lv, t, s, bd)
                    if (d < bd) { bd = d; bs = s }
                }
                val nx = tx - dir
                if (nx in 0 until w) { val n = ty * w + nx; val ns = nnf[n]; if (ns >= 0) tryS(clampValid(ns % w + dir, ns / w)) }
                val ny = ty - dir
                if (ny in 0 until h) { val n = ny * w + tx; val ns = nnf[n]; if (ns >= 0) tryS(clampValid(ns % w, ns / w + dir)) }
                var rad = max(w, h)
                while (rad >= 1) {
                    val sx = bs % w + rnd.nextInt(rad * 2 + 1) - rad
                    val sy = bs / w + rnd.nextInt(rad * 2 + 1) - rad
                    tryS(clampValid(sx, sy))
                    rad /= 2
                }
                nnf[t] = bs; best[t] = bd
            }
            vote(lv, targets, nnf)
        }
        return nnf
    }

    /** Each missing pixel becomes the average of what the overlapping matched patches say it is. */
    private fun vote(lv: Level, targets: IntArray, nnf: IntArray) {
        val w = lv.w; val h = lv.h
        val nr = IntArray(targets.size); val ng = IntArray(targets.size); val nb = IntArray(targets.size)
        for ((k, t) in targets.withIndex()) {
            val tx = t % w; val ty = t / w
            var sr = 0L; var sg = 0L; var sb = 0L; var n = 0
            for (dy in -R..R) {
                val y = ty + dy
                if (y < 0 || y >= h) continue
                for (dx in -R..R) {
                    val x = tx + dx
                    if (x < 0 || x >= w) continue
                    val p = y * w + x
                    val s = nnf[p]
                    if (s < 0) continue
                    val c = (s / w - dy) * w + (s % w - dx)
                    sr += lv.r[c]; sg += lv.g[c]; sb += lv.b[c]; n++
                }
            }
            if (n > 0) { nr[k] = (sr / n).toInt(); ng[k] = (sg / n).toInt(); nb[k] = (sb / n).toInt() }
            else { nr[k] = lv.r[t]; ng[k] = lv.g[t]; nb[k] = lv.b[t] }
        }
        for ((k, t) in targets.withIndex()) { lv.r[t] = nr[k]; lv.g[t] = ng[k]; lv.b[t] = nb[k] }
    }

    /** Coarsest level: grow the edges inwards (onion peel) as a starting guess. */
    private fun diffuse(lv: Level) {
        val w = lv.w; val h = lv.h
        val filled = BooleanArray(w * h) { !lv.mask[it] }
        if (filled.none { it }) return
        var remaining = filled.count { !it }
        var guard = 0
        while (remaining > 0 && guard++ < w + h) {
            val add = ArrayList<Int>()
            for (y in 0 until h) for (x in 0 until w) {
                val i = y * w + x
                if (filled[i]) continue
                var sr = 0; var sg = 0; var sb = 0; var n = 0
                for (dy in -1..1) for (dx in -1..1) {
                    val xx = x + dx; val yy = y + dy
                    if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue
                    val j = yy * w + xx
                    if (filled[j]) { sr += lv.r[j]; sg += lv.g[j]; sb += lv.b[j]; n++ }
                }
                if (n > 0) { lv.r[i] = sr / n; lv.g[i] = sg / n; lv.b[i] = sb / n; add += i }
            }
            add.forEach { filled[it] = true }
            remaining -= add.size
            if (add.isEmpty()) break
        }
    }
}
