package com.experimental.gcam100x

import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/**
 * x4 super-resolution (Real-ESRGAN realesr-general-x4v3) on 256x256 tiles.
 *
 * Backends, fastest first, chosen by a one-tile benchmark at load time:
 *  - Edge TPU through NNAPI ("google-edgetpu"), full-int8 model;
 *  - GPU delegate, float model;
 *  - CPU (XNNPACK), float model.
 */
class SuperRes(private val module: MainHook) {
    companion object {
        private const val TAG = "GCam100x"
        private const val TILE = 256
        private const val PAD = 12
        private const val CORE = TILE - 2 * PAD
        private const val SCALE = 4
        private const val OUT = TILE * SCALE
    }

    private class Backend(val name: String, val interp: Interpreter, val int8: Boolean, val closeable: AutoCloseable?)

    private var be: Backend? = null
    val backend: String get() = be?.name ?: "none"

    private fun loadModel(asset: String): ByteBuffer {
        val apk = module.moduleApplicationInfo.sourceDir
        return ZipFile(File(apk)).use { z ->
            val e = z.getEntry("assets/$asset") ?: error("$asset missing from module APK")
            val b = ByteBuffer.allocateDirect(e.size.toInt()).order(ByteOrder.nativeOrder())
            z.getInputStream(e).use { ins ->
                val tmp = ByteArray(1 shl 20)
                while (true) { val n = ins.read(tmp); if (n < 0) break; b.put(tmp, 0, n) }
            }
            b.rewind(); b
        }
    }

    private fun loadNatives() {
        val apk = module.moduleApplicationInfo.sourceDir
        for (lib in listOf("libtensorflowlite_jni.so", "libtensorflowlite_gpu_jni.so")) {
            runCatching { System.load("$apk!/lib/arm64-v8a/$lib") }
                .onFailure { module.logMessage(Log.WARN, TAG, "SuperRes: System.load $lib: $it") }
        }
    }

    /** Times one tile; returns ms or null if the backend fails. */
    private fun bench(b: Backend): Long? = runCatching {
        val inBuf = ByteBuffer.allocateDirect(TILE * TILE * 3 * (if (b.int8) 1 else 4)).order(ByteOrder.nativeOrder())
        val outBuf = ByteBuffer.allocateDirect(OUT * OUT * 3 * (if (b.int8) 1 else 4)).order(ByteOrder.nativeOrder())
        b.interp.run(inBuf, outBuf) // first run compiles
        inBuf.rewind(); outBuf.rewind()
        val t = System.nanoTime()
        b.interp.run(inBuf, outBuf)
        (System.nanoTime() - t) / 1_000_000
    }.onFailure { module.logMessage(Log.WARN, TAG, "SuperRes: ${b.name} failed: $it") }.getOrNull()

    @Synchronized
    private fun ensure(): Backend {
        be?.let { return it }
        loadNatives()
        val int8 = loadModel("prozoom_x4_int8.tflite")
        val f32 = loadModel("prozoom_x4_f32.tflite")
        val candidates = ArrayList<Pair<Backend, Long>>()
        runCatching {
            val d = NnApiDelegate(NnApiDelegate.Options()
                .setAcceleratorName("google-edgetpu")
                .setUseNnapiCpu(false)
                .setExecutionPreference(NnApiDelegate.Options.EXECUTION_PREFERENCE_SUSTAINED_SPEED))
            Backend("TPU", Interpreter(int8, Interpreter.Options().addDelegate(d)), true, d)
        }.onFailure { module.logMessage(Log.WARN, TAG, "SuperRes: TPU (NNAPI google-edgetpu) unavailable: $it") }
            .getOrNull()?.let { b -> bench(b)?.let { candidates += b to it } ?: close(b) }
        runCatching {
            val d = GpuDelegate(GpuDelegate.Options().setPrecisionLossAllowed(true)
                .setInferencePreference(GpuDelegate.Options.INFERENCE_PREFERENCE_SUSTAINED_SPEED))
            Backend("GPU", Interpreter(f32, Interpreter.Options().addDelegate(d)), false, d)
        }.onFailure { module.logMessage(Log.WARN, TAG, "SuperRes: GPU unavailable: $it") }
            .getOrNull()?.let { b -> bench(b)?.let { candidates += b to it } ?: close(b) }
        if (candidates.isEmpty()) {
            val b = Backend("CPU", Interpreter(f32, Interpreter.Options().setNumThreads(4).setUseXNNPACK(true)), false, null)
            candidates += b to (bench(b) ?: -1)
        }
        module.logMessage(Log.INFO, TAG, "SuperRes: tile benchmark " + candidates.joinToString { "${it.first.name}=${it.second} ms" })
        val best = candidates.minBy { it.second }.first
        candidates.filter { it.first !== best }.forEach { close(it.first) }
        be = best
        module.logMessage(Log.INFO, TAG, "SuperRes: using ${best.name}")
        return best
    }

    private fun close(b: Backend) { runCatching { b.interp.close(); b.closeable?.close() } }

    /** Loads the model and picks the backend ahead of the first photo. */
    fun warmUp() { ensure() }

    /** Returns a bitmap 4x the size of [src]. */
    fun upscale4x(src: Bitmap): Bitmap {
        val b = ensure()
        val w = src.width; val h = src.height
        val out = Bitmap.createBitmap(w * SCALE, h * SCALE, Bitmap.Config.ARGB_8888)
        val bpe = if (b.int8) 1 else 4
        val inBuf = ByteBuffer.allocateDirect(TILE * TILE * 3 * bpe).order(ByteOrder.nativeOrder())
        val outBuf = ByteBuffer.allocateDirect(OUT * OUT * 3 * bpe).order(ByteOrder.nativeOrder())
        val px = IntArray(TILE * TILE)
        val opx = IntArray(OUT * OUT)
        val t0 = System.currentTimeMillis()
        var tiles = 0
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val x0 = (x - PAD).coerceIn(0, maxOf(0, w - TILE))
                val y0 = (y - PAD).coerceIn(0, maxOf(0, h - TILE))
                val tw = minOf(TILE, w - x0); val th = minOf(TILE, h - y0)
                java.util.Arrays.fill(px, 0)
                src.getPixels(px, 0, TILE, x0, y0, tw, th)
                inBuf.rewind()
                if (b.int8) {
                    for (c in px) { inBuf.put((c shr 16).toByte()); inBuf.put((c shr 8).toByte()); inBuf.put(c.toByte()) }
                } else {
                    for (c in px) {
                        inBuf.putFloat(((c shr 16) and 0xff) / 255f)
                        inBuf.putFloat(((c shr 8) and 0xff) / 255f)
                        inBuf.putFloat((c and 0xff) / 255f)
                    }
                }
                inBuf.rewind(); outBuf.rewind()
                b.interp.run(inBuf, outBuf)
                outBuf.rewind()
                if (b.int8) {
                    for (i in 0 until OUT * OUT) {
                        val r = outBuf.get().toInt() and 0xff
                        val g = outBuf.get().toInt() and 0xff
                        val bl = outBuf.get().toInt() and 0xff
                        opx[i] = (0xff shl 24) or (r shl 16) or (g shl 8) or bl
                    }
                } else {
                    val fb = outBuf.asFloatBuffer()
                    for (i in 0 until OUT * OUT) {
                        val r = (fb.get() * 255f + 0.5f).toInt().coerceIn(0, 255)
                        val g = (fb.get() * 255f + 0.5f).toInt().coerceIn(0, 255)
                        val bl = (fb.get() * 255f + 0.5f).toInt().coerceIn(0, 255)
                        opx[i] = (0xff shl 24) or (r shl 16) or (g shl 8) or bl
                    }
                }
                val cx1 = minOf(x + CORE, w); val cy1 = minOf(y + CORE, h)
                val sx = (x - x0) * SCALE; val sy = (y - y0) * SCALE
                out.setPixels(opx, sy * OUT + sx, OUT, x * SCALE, y * SCALE, (cx1 - x) * SCALE, (cy1 - y) * SCALE)
                tiles++
                x += CORE
            }
            y += CORE
        }
        module.logMessage(Log.INFO, TAG, "SuperRes: ${w}x$h -> ${w * SCALE}x${h * SCALE}, $tiles tiles in ${System.currentTimeMillis() - t0} ms (${b.name})")
        return out
    }
}
