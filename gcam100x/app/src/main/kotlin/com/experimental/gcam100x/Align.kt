package com.experimental.gcam100x

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 1:1 framing for Pro Zoom shots.
 *
 * Google Camera's viewfinder past 30x is an exact centre crop of the 30x
 * preview stream (shader: texCoord = (t - 0.5) * croppingZoomFactor + 0.5).
 * The 30x still, however, is framed a little differently from that stream
 * (HDR+ frame selection/alignment, ZSL timing, stream FOV). At 30x nobody
 * sees it; at 100x the offset is magnified 3.3x. So instead of assuming a
 * centre crop, the still is registered against the exact viewfinder frame
 * that was on screen at the shutter (normalised cross-correlation over
 * position, scale and the four rotations, coarse to fine).
 */
object Align {

    /** Luminance image, row-major, 0..1. */
    class Gray(val w: Int, val h: Int, val px: FloatArray) {
        operator fun get(x: Int, y: Int) = px[y * w + x]
    }

    /** What the viewfinder showed at the shutter. */
    class View(val gray: Gray, val time: Long)

    /** Result in normalised still coordinates (0..1). */
    class Match(val cx: Float, val cy: Float, val scale: Float, val rot: Int, val score: Float)

    fun gray(b: Bitmap, maxSide: Int): Gray {
        val f = max(1f, max(b.width, b.height) / maxSide.toFloat())
        val w = max(8, (b.width / f).roundToInt()); val h = max(8, (b.height / f).roundToInt())
        // Area-average downscale (no aliasing).
        val src = IntArray(b.width * b.height)
        b.getPixels(src, 0, b.width, 0, 0, b.width, b.height)
        val acc = FloatArray(w * h); val cnt = FloatArray(w * h)
        for (y in 0 until b.height) {
            val ty = min(h - 1, (y * h) / b.height)
            for (x in 0 until b.width) {
                val c = src[y * b.width + x]
                val l = (0.299f * ((c shr 16) and 0xff) + 0.587f * ((c shr 8) and 0xff) + 0.114f * (c and 0xff)) / 255f
                val i = ty * w + min(w - 1, (x * w) / b.width)
                acc[i] += l; cnt[i] += 1f
            }
        }
        for (i in acc.indices) acc[i] /= max(1f, cnt[i])
        return Gray(w, h, acc)
    }

    private fun half(g: Gray): Gray {
        val w = g.w / 2; val h = g.h / 2
        val o = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val i = 2 * y * g.w + 2 * x
            o[y * w + x] = 0.25f * (g.px[i] + g.px[i + 1] + g.px[i + g.w] + g.px[i + g.w + 1])
        }
        return Gray(w, h, o)
    }

    private fun rotate(g: Gray, rot: Int): Gray = when (rot) {
        90 -> Gray(g.h, g.w, FloatArray(g.w * g.h).also { o -> for (y in 0 until g.h) for (x in 0 until g.w) o[x * g.h + (g.h - 1 - y)] = g[x, y] })
        180 -> Gray(g.w, g.h, FloatArray(g.w * g.h).also { o -> for (i in o.indices) o[i] = g.px[g.px.size - 1 - i] })
        270 -> Gray(g.h, g.w, FloatArray(g.w * g.h).also { o -> for (y in 0 until g.h) for (x in 0 until g.w) o[(g.w - 1 - x) * g.h + y] = g[x, y] })
        else -> g
    }

    /** Bilinear resample to [w]x[h], zero-mean, unit-norm (for NCC). */
    private fun template(g: Gray, w: Int, h: Int): FloatArray? {
        val t = FloatArray(w * h)
        val sx = g.w / w.toFloat(); val sy = g.h / h.toFloat()
        for (y in 0 until h) {
            val fy = ((y + 0.5f) * sy - 0.5f).coerceIn(0f, g.h - 1f)
            val y0 = fy.toInt(); val y1 = min(g.h - 1, y0 + 1); val wy = fy - y0
            for (x in 0 until w) {
                val fx = ((x + 0.5f) * sx - 0.5f).coerceIn(0f, g.w - 1f)
                val x0 = fx.toInt(); val x1 = min(g.w - 1, x0 + 1); val wx = fx - x0
                t[y * w + x] = (g[x0, y0] * (1 - wx) + g[x1, y0] * wx) * (1 - wy) + (g[x0, y1] * (1 - wx) + g[x1, y1] * wx) * wy
            }
        }
        val mean = t.average().toFloat()
        var ss = 0.0
        for (i in t.indices) { t[i] -= mean; ss += t[i] * t[i] }
        if (ss < 1e-6 * t.size) return null // flat (sky, wall): nothing to match
        val inv = (1.0 / sqrt(ss)).toFloat()
        for (i in t.indices) t[i] *= inv
        return t
    }

    /** NCC of template [t] (tw x th) at top-left (x, y) of [img]. */
    private fun ncc(img: Gray, t: FloatArray, tw: Int, th: Int, x: Int, y: Int): Float {
        var s = 0f; var si = 0f; var sii = 0f
        for (j in 0 until th) {
            var o = (y + j) * img.w + x; var k = j * tw
            for (i in 0 until tw) {
                val v = img.px[o]; s += v * t[k]; si += v; sii += v * v; o++; k++
            }
        }
        val n = (tw * th).toFloat()
        val varI = sii - si * si / n
        return if (varI <= 1e-9f) 0f else s / sqrt(varI)
    }

    /**
     * Finds the viewfinder frame [view] inside the full 30x still [still]
     * (luminance, any size). [scale] is the Pro Zoom crop (30/zoom).
     */
    fun locate(still: Gray, view: Gray, scale: Float): Match? {
        // Pyramid of the still.
        val pyr = ArrayList<Gray>().apply { add(still) }
        while (pyr.last().w > 64 && pyr.last().h > 48) pyr.add(half(pyr.last()))
        // Coarsest level where the template is still ~1000+ pixels.
        fun tplSize(level: Int, rot: Int, s: Float): Pair<Int, Int> {
            val g = pyr[level]
            val vw = if (rot % 180 == 0) view.w else view.h
            val vh = if (rot % 180 == 0) view.h else view.w
            // Template width follows the crop of the still; height keeps the view's aspect.
            val tw = (g.w * scale * s).roundToInt()
            val th = (tw * vh / vw.toFloat()).roundToInt()
            return tw to th
        }
        var level = pyr.size - 1
        while (level > 0) {
            val (tw, th) = tplSize(level, 0, 1f)
            if (tw * th >= 900) break
            level--
        }
        val rots = intArrayOf(0, 90, 180, 270).filter { r ->
            val va = if (r % 180 == 0) view.w / view.h.toFloat() else view.h / view.w.toFloat()
            val sa = still.w / still.h.toFloat()
            abs(va / sa - 1f) < 0.2f
        }.ifEmpty { listOf(0, 90, 180, 270) }
        val views = rots.associateWith { rotate(view, it) }
        val scales = floatArrayOf(0.88f, 0.91f, 0.94f, 0.97f, 1f, 1.03f, 1.06f, 1.09f, 1.12f)

        // Coarse search: every rotation and scale, +-12% of the frame around the centre.
        var best: FloatArray? = null // x, y, sIdx, rot, score
        for (r in rots) for ((si, s) in scales.withIndex()) {
            val g = pyr[level]
            val (tw, th) = tplSize(level, r, s)
            if (tw < 8 || th < 8 || tw >= g.w || th >= g.h) continue
            val t = template(views[r]!!, tw, th) ?: return null
            val cx = (g.w - tw) / 2; val cy = (g.h - th) / 2
            val rx = max(2, (g.w * 0.12f).toInt()); val ry = max(2, (g.h * 0.12f).toInt())
            for (y in max(0, cy - ry)..min(g.h - th, cy + ry)) for (x in max(0, cx - rx)..min(g.w - tw, cx + rx)) {
                val v = ncc(g, t, tw, th, x, y)
                if (best == null || v > best[4]) best = floatArrayOf(x.toFloat(), y.toFloat(), si.toFloat(), r.toFloat(), v)
            }
        }
        var b = best ?: return null
        val rot = b[3].toInt()
        var s = scales[b[2].toInt()]
        var x = b[0]; var y = b[1]
        // Refine down the pyramid: +-2 px, finer scale steps, until the template is large.
        var lv = level
        var step = 0.015f
        while (lv > 0) {
            val (tw0, th0) = tplSize(lv - 1, rot, s)
            if (tw0 * th0 > 60_000) break
            lv--
            x *= 2f; y *= 2f
            val g = pyr[lv]
            var bb = floatArrayOf(x, y, s, b[4])
            for (ds in floatArrayOf(-step, 0f, step)) {
                val s2 = s + ds
                val (tw, th) = tplSize(lv, rot, s2)
                if (tw >= g.w || th >= g.h) continue
                val t = template(views[rot]!!, tw, th) ?: continue
                // Keep the centre fixed when the size changes.
                val (twc, thc) = tplSize(lv, rot, s)
                val bx = (x + (twc - tw) / 2f).roundToInt(); val by = (y + (thc - th) / 2f).roundToInt()
                for (dy in -2..2) for (dx in -2..2) {
                    val px = bx + dx; val py = by + dy
                    if (px < 0 || py < 0 || px + tw > g.w || py + th > g.h) continue
                    val v = ncc(g, t, tw, th, px, py)
                    if (v > bb[3]) bb = floatArrayOf(px.toFloat(), py.toFloat(), s2, v)
                }
            }
            x = bb[0]; y = bb[1]; s = bb[2]; b = floatArrayOf(x, y, 0f, rot.toFloat(), bb[3])
            step /= 2f
        }
        val g = pyr[lv]
        val (tw, th) = tplSize(lv, rot, s)
        return Match((x + tw / 2f) / g.w, (y + th / 2f) / g.h, s, rot, b[4])
    }

    /** Crop of [g] (normalised centre/size). */
    fun crop(g: Gray, scale: Float): Gray {
        val w = max(4, (g.w * scale).roundToInt()); val h = max(4, (g.h * scale).roundToInt())
        val x0 = (g.w - w) / 2; val y0 = (g.h - h) / 2
        return Gray(w, h, FloatArray(w * h) { g[x0 + it % w, y0 + it / w] })
    }

    /** Best NCC between [a] and [b] (any rotation), both resampled to 32x32. */
    fun similarity(a: Gray, b: Gray): Float {
        val ta = template(a, 32, 32) ?: return 0f
        var best = -1f
        for (r in intArrayOf(0, 90, 180, 270)) {
            val tb = template(rotate(b, r), 32, 32) ?: return 0f
            var d = 0f
            for (k in ta.indices) d += ta[k] * tb[k]
            if (d > best) best = d
        }
        return best
    }
}
