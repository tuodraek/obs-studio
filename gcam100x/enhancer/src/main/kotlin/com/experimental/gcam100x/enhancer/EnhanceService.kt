package com.experimental.gcam100x.enhancer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.Executors

/**
 * Enhances one photo per start command, in the ":engine" process.
 * Extras: "path" (file) or data = content Uri; "accelerator" ("gpu" default); "name" (output base name).
 * The source photo is never modified; the result is written next to nothing else:
 * Pictures/GCam100x/<name>_AI.jpg. Everything is logged to Download/gcam100x_enhancer.log.
 */
class EnhanceService : Service() {
    companion object {
        val MODEL = File(Environment.getExternalStorageDirectory(), "Download/adcsr_128_fp16.tflite")
        val LOG = File(Environment.getExternalStorageDirectory(), "Download/gcam100x_enhancer.log")
        val OUT_DIR = File(Environment.getExternalStorageDirectory(), "Pictures/GCam100x")
        const val MAX_INPUT_LONG_SIDE = 512      // input pixels fed to the model on the long side (output is 4x that)
        private const val IDLE_EXIT_MS = 4 * 60_000L
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var engine = 0L
    private var engineAcc = ""
    private var pending = 0
    private val idleExit = Runnable { if (pending == 0) { log("idle: releasing the engine"); if (engine != 0L) Native.close(engine); stopSelf(); Process.killProcess(Process.myPid()) } }

    override fun onBind(i: Intent?): IBinder? = null

    private fun log(s: String) = runCatching { LOG.appendText("${SimpleDateFormat("MM-dd HH:mm:ss.SSS").format(Date())} $s\n") }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("enhance", "Enhancing photos", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, "enhance").setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle("GCam100x Enhancer").setContentText(text).setOngoing(true).build()
    }

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        runCatching { startForeground(1, notification("Enhancing…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) }
            .onFailure { log("startForeground failed: $it") }
        if (i == null) return START_NOT_STICKY
        main.removeCallbacks(idleExit); pending++
        worker.execute {
            runCatching { job(i) }.onFailure { log("FAILED: $it") }
            main.post { pending--; if (pending == 0) { stopForeground(STOP_FOREGROUND_REMOVE); main.postDelayed(idleExit, IDLE_EXIT_MS) } }
        }
        return START_NOT_STICKY
    }

    private fun job(i: Intent) {
        val t0 = SystemClock.elapsedRealtime()
        val acc = i.getStringExtra("accelerator") ?: "gpu"
        val path = i.getStringExtra("path"); val uri: Uri? = i.data
        val name = i.getStringExtra("name") ?: path?.let { File(it).nameWithoutExtension } ?: "photo_${SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())}"
        log("=== job $name  source=${path ?: uri}  accelerator=$acc")
        if (!MODEL.exists()) { log("FAILED: model missing: $MODEL"); return }

        val src: Bitmap = (if (path != null) BitmapFactory.decodeFile(path) else contentResolver.openInputStream(uri!!).use { BitmapFactory.decodeStream(it) })
            ?: run { log("FAILED: cannot decode the source image"); return }
        // The model works on real image information: bring the photo down to at most MAX_INPUT_LONG_SIDE
        // (a 120x crop of 1536x2040 becomes 384x510), then restore 4x.
        var b = src
        val target = minOf(MAX_INPUT_LONG_SIDE, maxOf(src.width, src.height) / 4).coerceAtLeast(64)
        while (maxOf(b.width, b.height) / 2 >= target) b = Bitmap.createScaledBitmap(b, b.width / 2, b.height / 2, true)   // exact halving = box filter
        if (maxOf(b.width, b.height) != target) {
            val s = target.toFloat() / maxOf(b.width, b.height)
            b = Bitmap.createScaledBitmap(b, (b.width * s).toInt().coerceAtLeast(1), (b.height * s).toInt().coerceAtLeast(1), true)
        }
        val w = b.width; val h = b.height
        val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h)
        val rgb = ByteArray(w * h * 3)
        for (k in px.indices) { val p = px[k]; rgb[k * 3] = (p shr 16).toByte(); rgb[k * 3 + 1] = (p shr 8).toByte(); rgb[k * 3 + 2] = p.toByte() }
        log("source ${src.width}x${src.height} -> model input ${w}x$h -> output ${w * 4}x${h * 4}")

        if (engine == 0L || engineAcc != acc) {
            if (engine != 0L) Native.close(engine)
            val cache = File(cacheDir, "gpu").apply { mkdirs() }
            log("loading the engine ($acc)… the first start compiles the GPU programs and can take about a minute")
            engine = Native.open(MODEL.path, acc, applicationInfo.nativeLibraryDir, cache.path, LOG.path)
            engineAcc = acc
            if (engine == 0L) { log("FAILED: engine did not start: ${Native.error()}"); return }
            log("engine ready: ${Native.info(engine)}")
        }
        val t1 = SystemClock.elapsedRealtime()
        val out = ByteArray(w * 4 * h * 4 * 3)
        val rc = Native.enhance(engine, rgb, w, h, out, 1.0f)
        if (rc != 0) { log("FAILED: enhance: ${Native.error()}"); return }
        val t2 = SystemClock.elapsedRealtime()
        val W = w * 4; val H = h * 4
        val opx = IntArray(W * H)
        for (k in opx.indices) opx[k] = (0xFF shl 24) or ((out[k * 3].toInt() and 0xFF) shl 16) or ((out[k * 3 + 1].toInt() and 0xFF) shl 8) or (out[k * 3 + 2].toInt() and 0xFF)
        val ob = Bitmap.createBitmap(opx, W, H, Bitmap.Config.ARGB_8888)
        OUT_DIR.mkdirs()
        val file = File(OUT_DIR, "${name}_AI.jpg")
        file.outputStream().use { ob.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        runCatching {
            android.media.ExifInterface(file.path).apply {
                setAttribute(android.media.ExifInterface.TAG_SOFTWARE, "GCam100x Enhancer (AdcSR, generative AI)")
                setAttribute(android.media.ExifInterface.TAG_IMAGE_DESCRIPTION, "AI-enhanced: detail in this image was generated by a diffusion model")
                saveAttributes()
            }
        }
        MediaScannerConnection.scanFile(this, arrayOf(file.path), arrayOf("image/jpeg"), null)
        val tiles = Native.progress() and 0xffff
        log("DONE $file  tiles=$tiles  model ${t2 - t1} ms (${(t2 - t1) / maxOf(tiles, 1)} ms/tile)  total ${SystemClock.elapsedRealtime() - t0} ms  ${Native.info(engine)}")
    }
}
