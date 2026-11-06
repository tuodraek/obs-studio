package com.experimental.gcam100x

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/**
 * Real-ESRGAN-x4plus super-resolution (TFLite, GPU delegate with CPU
 * fallback). The model takes 128x128 RGB tiles in [0,1] and returns
 * 512x512 tiles. Tiles overlap by [PAD] input pixels to hide seams.
 */
class SuperRes(private val module: MainHook) {
    companion object {
        private const val TAG = "GCam100x"
        private const val TILE = 128
        private const val PAD = 8
        private const val CORE = TILE - 2 * PAD
        private const val SCALE = 4
        private const val MODEL = "assets/real_esrgan_x4plus.tflite"
    }

    private var interpreter: Interpreter? = null
    private var gpu: GpuDelegate? = null
    var backend = "none"; private set

    @Synchronized
    private fun ensure(): Interpreter {
        interpreter?.let { return it }
        val apk = module.moduleApplicationInfo.sourceDir
        // The module's class loader may not have a native library path; load
        // the JNI libraries straight from the (uncompressed) module APK.
        for (lib in listOf("libtensorflowlite_jni.so", "libtensorflowlite_gpu_jni.so")) {
            runCatching { System.load("$apk!/lib/arm64-v8a/$lib") }
                .onFailure { module.logMessage(Log.WARN, TAG, "SuperRes: System.load $lib: $it") }
        }
        val buf = ZipFile(File(apk)).use { z ->
            val e = z.getEntry(MODEL) ?: error("model missing from module APK")
            val b = ByteBuffer.allocateDirect(e.size.toInt()).order(ByteOrder.nativeOrder())
            z.getInputStream(e).use { ins ->
                val tmp = ByteArray(1 shl 20)
                while (true) { val n = ins.read(tmp); if (n < 0) break; b.put(tmp, 0, n) }
            }
            b.rewind(); b
        }
        val it = try {
            val d = GpuDelegate(GpuDelegate.Options().setPrecisionLossAllowed(true)
                .setInferencePreference(GpuDelegate.Options.INFERENCE_PREFERENCE_SUSTAINED_SPEED))
            Interpreter(buf, Interpreter.Options().addDelegate(d)).also { gpu = d; backend = "GPU" }
        } catch (t: Throwable) {
            module.logMessage(Log.WARN, TAG, "SuperRes: GPU delegate unavailable ($t), using CPU")
            gpu?.close(); gpu = null
            Interpreter(buf, Interpreter.Options().setNumThreads(3).setUseXNNPACK(true)).also { backend = "CPU" }
        }
        interpreter = it
        module.logMessage(Log.INFO, TAG, "SuperRes: Real-ESRGAN x4 loaded ($backend)")
        return it
    }

    /** Returns a bitmap 4x the size of [src]. */
    fun upscale4x(src: Bitmap, onProgress: (Int, Int) -> Unit = { _, _ -> }): Bitmap {
        val interp = ensure()
        val w = src.width; val h = src.height
        val out = Bitmap.createBitmap(w * SCALE, h * SCALE, Bitmap.Config.ARGB_8888)
        val inBuf = ByteBuffer.allocateDirect(TILE * TILE * 3 * 4).order(ByteOrder.nativeOrder())
        val outBuf = ByteBuffer.allocateDirect(TILE * SCALE * TILE * SCALE * 3 * 4).order(ByteOrder.nativeOrder())
        val px = IntArray(TILE * TILE)
        val opx = IntArray(TILE * SCALE * TILE * SCALE)
        val t0 = System.currentTimeMillis()
        var tiles = 0
        val total = ((w + CORE - 1) / CORE) * ((h + CORE - 1) / CORE)
        var lastPct = -1
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                // Input window = core tile + PAD on each side, clamped to the image.
                val x0 = (x - PAD).coerceIn(0, maxOf(0, w - TILE))
                val y0 = (y - PAD).coerceIn(0, maxOf(0, h - TILE))
                val tw = minOf(TILE, w - x0); val th = minOf(TILE, h - y0)
                java.util.Arrays.fill(px, 0)
                src.getPixels(px, 0, TILE, x0, y0, tw, th)
                inBuf.rewind()
                for (i in 0 until TILE * TILE) {
                    val c = px[i]
                    inBuf.putFloat(((c shr 16) and 0xff) / 255f)
                    inBuf.putFloat(((c shr 8) and 0xff) / 255f)
                    inBuf.putFloat((c and 0xff) / 255f)
                }
                inBuf.rewind(); outBuf.rewind()
                interp.run(inBuf, outBuf)
                outBuf.rewind()
                val fb = outBuf.asFloatBuffer()
                val ow = TILE * SCALE
                for (i in 0 until ow * ow) {
                    val r = (fb.get() * 255f + 0.5f).toInt().coerceIn(0, 255)
                    val g = (fb.get() * 255f + 0.5f).toInt().coerceIn(0, 255)
                    val b = (fb.get() * 255f + 0.5f).toInt().coerceIn(0, 255)
                    opx[i] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
                }
                // Copy only the core region [x, x+CORE) x [y, y+CORE) to the output.
                val cx1 = minOf(x + CORE, w); val cy1 = minOf(y + CORE, h)
                val sx = (x - x0) * SCALE; val sy = (y - y0) * SCALE
                val cw = (cx1 - x) * SCALE; val chh = (cy1 - y) * SCALE
                out.setPixels(opx, sy * ow + sx, ow, x * SCALE, y * SCALE, cw, chh)
                tiles++
                val pct = tiles * 100 / total
                if (pct >= lastPct + 5) { lastPct = pct; onProgress(tiles, total) }
                x += CORE
            }
            y += CORE
        }
        module.logMessage(Log.INFO, TAG, "SuperRes: ${w}x$h -> ${w * SCALE}x${h * SCALE}, $tiles tiles in ${System.currentTimeMillis() - t0} ms ($backend)")
        return out
    }
}
