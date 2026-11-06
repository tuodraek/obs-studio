package com.experimental.gcam100x

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

class AlignTest {
    /** Continuous synthetic scene on [0,1]^2: blobs, edges and fine texture. */
    private class Scene(seed: Int) {
        val r = Random(seed)
        val blobs = List(400) { doubleArrayOf(r.nextDouble(), r.nextDouble(), 0.002 + r.nextDouble() * 0.02, r.nextDouble() - 0.5) }
        fun at(x: Double, y: Double): Double {
            var v = 0.5 + 0.05 * sin(40 * x) * cos(33 * y)
            for (b in blobs) {
                val dx = x - b[0]; val dy = y - b[1]
                v += b[3] * exp(-(dx * dx + dy * dy) / (b[2] * b[2]))
            }
            return v.coerceIn(0.0, 1.0)
        }
    }

    private fun render(s: Scene, w: Int, h: Int, x0: Double, y0: Double, sw: Double, sh: Double, ss: Int, tone: (Double) -> Double): Align.Gray {
        val px = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var acc = 0.0 // ss x ss supersampling = optical blur of a lower-res source
            for (j in 0 until ss) for (i in 0 until ss) acc += s.at(x0 + (x + (i + 0.5) / ss) / w * sw, y0 + (y + (j + 0.5) / ss) / h * sh)
            px[y * w + x] = tone(acc / (ss * ss)).toFloat()
        }
        return Align.Gray(w, h, px)
    }

    private fun rot90(g: Align.Gray) = Align.Gray(g.h, g.w, FloatArray(g.w * g.h).also { o ->
        for (y in 0 until g.h) for (x in 0 until g.w) o[x * g.h + (g.h - 1 - y)] = g[x, y]
    })

    private fun check(zoom: Float, dx: Double, dy: Double, size: Double, rotate: Boolean) {
        val scene = Scene(7)
        val scale = 30f / zoom
        // Still: the full 30x frame, 1000x750 (= 1/8 of 8160x6120).
        val still = render(scene, 1000, 750, 0.0, 0.0, 1.0, 1.0, 1) { it }
        // Viewfinder: the Pro Zoom crop of a slightly differently framed,
        // softer, differently toned preview stream.
        val vw = scale * size; val vh = vw
        val cx = 0.5 + dx; val cy = 0.5 + dy
        var view = render(scene, 384, 288, cx - vw / 2, cy - vh / 2, vw, vh, 3) { 0.1 + 0.8 * it.pow(0.8) }
        if (rotate) view = rot90(view)
        val m = Align.locate(still, view, scale)
        assertNotNull(m); m!!
        println("zoom $zoom dx $dx dy $dy size $size rot=$rotate -> cx ${m.cx} cy ${m.cy} s ${m.scale} rot ${m.rot} score ${m.score}")
        // Within 0.5% of the viewfinder width.
        val tol = 0.005 * scale
        assertEquals(cx, m.cx.toDouble(), tol)
        assertEquals(cy, m.cy.toDouble(), tol * 4 / 3)
        assertEquals(size, m.scale.toDouble(), 0.02)
    }

    @Test fun centred100x() = check(100f, 0.0, 0.0, 1.0, false)
    @Test fun offset100x() = check(100f, 0.031, -0.022, 1.0, false)
    @Test fun offsetScaled120x() = check(120f, -0.04, 0.03, 1.05, false)
    @Test fun offset50x() = check(50f, 0.05, 0.035, 0.96, false)
    @Test fun rotatedView80x() = check(80f, 0.02, 0.02, 1.0, true)

    @Test fun flatSceneFallsBack() {
        val still = Align.Gray(1000, 750, FloatArray(750_000) { 0.5f })
        val view = Align.Gray(384, 288, FloatArray(384 * 288) { 0.5f })
        assertNull(Align.locate(still, view, 0.3f))
    }

    @Test fun tellsZoomedFromUnzoomedThumbnail() {
        val scene = Scene(11)
        val scale = 0.3f
        val view = render(scene, 384, 288, 0.35, 0.35, 0.3, 0.3, 3) { it }
        val unzoomed = render(scene, 160, 120, 0.0, 0.0, 1.0, 1.0, 2) { 0.2 + 0.7 * it }
        val zoomed = render(scene, 160, 120, 0.35, 0.35, 0.3, 0.3, 2) { 0.2 + 0.7 * it }
        fun check(g: Align.Gray) = Align.similarity(Align.crop(g, scale), view) > Align.similarity(g, view) + 0.05f
        org.junit.Assert.assertTrue(check(unzoomed))
        org.junit.Assert.assertFalse(check(zoomed))
    }
}
