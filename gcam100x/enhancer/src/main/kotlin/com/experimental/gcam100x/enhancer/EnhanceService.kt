package com.experimental.gcam100x.enhancer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * The enhancer, in the ":engine" process.
 *
 * It stays up as a small foreground service and watches the folder where the GCam100x
 * camera module drops a copy of every photo taken above 30x. Each photo is restored by
 * AdcSR on the GPU and saved as <name>_AI.jpg next to the camera's own photo in
 * DCIM/Camera; the camera's photo is never modified. The model is loaded for the first
 * photo and released after a few idle minutes. Log: Download/gcam100x_enhancer.log.
 */
class EnhanceService : Service() {
    companion object {
        const val ACTION_WATCH = "watch"
        private val SD = Environment.getExternalStorageDirectory()
        val MODEL = File(SD, "Download/adcsr_128_fp16.tflite")
        val LOG = File(SD, "Download/gcam100x_enhancer.log")
        /** Written by the camera module (inside Google Camera, which may write here without any permission). */
        val INBOX = File(SD, "Android/media/com.google.android.GoogleCamera/gcam100x")
        val CAMERA_DIR = File(SD, "DCIM/Camera")
        val PICKED_DIR = File(SD, "Pictures/GCam100x")
        private const val TILE = 128
        private const val OVERLAP = 8            // must match OV in engine.c
        private const val MAX_INPUT = 512        // model input pixels on the long side
        private const val IDLE_RELEASE_MS = 4 * 60_000L
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var engine = 0L
    @Volatile private var watching = false
    @Volatile private var busy = 0
    private var lastLine = "Ready: waiting for photos above 30x"
    private val release = Runnable { worker.execute { if (busy == 0 && engine != 0L) { Native.close(engine); engine = 0L; log("idle: model released") } } }

    override fun onBind(i: Intent?): IBinder? = null

    private fun log(s: String) { runCatching { LOG.appendText("${SimpleDateFormat("MM-dd HH:mm:ss.SSS").format(Date())} $s\n") } }

    private fun notify(text: String) {
        lastLine = text
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("enhance", "Photo enhancer", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "enhance").setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle("GCam100x Enhancer").setContentText(text).setOngoing(true).build()
        runCatching { startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) }.onFailure { log("startForeground failed: $it") }
    }

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        notify(lastLine)
        if (!watching) { watching = true; Thread(::watch, "inbox").start(); log("watcher started (inbox $INBOX)") }
        val uri = i?.data
        if (uri != null) submit(i.getStringExtra("name") ?: "photo_${System.currentTimeMillis()}", PICKED_DIR) {
            contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        }
        return START_STICKY
    }

    /** Polls the inbox; cheap (one directory listing a second) and independent of inotify on shared storage. */
    private fun watch() {
        val seen = HashSet<String>()
        while (true) {
            runCatching {
                INBOX.listFiles { f -> f.name.endsWith(".jpg") }?.sortedBy { it.lastModified() }?.forEach { f ->
                    if (seen.add(f.name)) submit(f.nameWithoutExtension, CAMERA_DIR) { f.readBytes().also { f.delete(); seen.remove(f.name) } }
                }
            }
            Thread.sleep(1000)
        }
    }

    private fun submit(name: String, outDir: File, read: () -> ByteArray) {
        busy++; main.removeCallbacks(release)
        worker.execute {
            runCatching { job(name, outDir, read()) }.onFailure { log("FAILED $name: $it"); notify("Failed: $name (see log)") }
            busy--; if (busy == 0) main.postDelayed(release, IDLE_RELEASE_MS)
        }
    }

    /** Input length that tiles exactly: n tiles of 128 with an 8-pixel overlap. */
    private fun tiled(len: Int): Int { val n = maxOf(1, ((len - OVERLAP).toFloat() / (TILE - OVERLAP)).roundToInt()); return n * TILE - (n - 1) * OVERLAP }

    private fun job(name: String, outDir: File, jpeg: ByteArray) {
        val t0 = SystemClock.elapsedRealtime()
        log("=== $name (${jpeg.size / 1024} KB)")
        if (!MODEL.exists()) { log("FAILED: model missing: $MODEL"); notify("Model file missing (Download/adcsr_128_fp16.tflite)"); return }
        var src = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: run { log("FAILED: cannot decode"); return }
        val tmp = File(cacheDir, "in.jpg").apply { writeBytes(jpeg) }
        val exifIn = runCatching { ExifInterface(tmp.path) }.getOrNull()
        val rot = when (exifIn?.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) { 6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f }
        if (rot != 0f) src = Bitmap.createBitmap(src, 0, 0, src.width, src.height, Matrix().apply { postRotate(rot) }, true)

        // The real information in a high-zoom crop is about a quarter of its pixel size:
        // reduce to that (at most MAX_INPUT), snapped to a whole number of tiles, then restore 4x.
        val scale = minOf(0.25f, MAX_INPUT.toFloat() / maxOf(src.width, src.height))
        val w = tiled((src.width * scale).roundToInt()); val h = tiled((src.height * scale).roundToInt())
        var b = src
        while (b.width / 2 >= w && b.height / 2 >= h) b = Bitmap.createScaledBitmap(b, b.width / 2, b.height / 2, true)   // halving = box filter
        if (b.width != w || b.height != h) b = Bitmap.createScaledBitmap(b, w, h, true)
        val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h)
        val rgb = ByteArray(w * h * 3)
        for (k in px.indices) { val p = px[k]; rgb[k * 3] = (p shr 16).toByte(); rgb[k * 3 + 1] = (p shr 8).toByte(); rgb[k * 3 + 2] = p.toByte() }

        if (engine == 0L) {
            notify("Loading the AI model…")
            log("loading the model (GPU)… the very first start compiles the GPU programs and can take about a minute")
            engine = Native.open(MODEL.path, "gpu", applicationInfo.nativeLibraryDir, File(cacheDir, "gpu").apply { mkdirs() }.path, LOG.path)
            if (engine == 0L) { log("FAILED: engine did not start: ${Native.error()}"); notify("AI model failed to start (see log)"); return }
            log("engine ready: ${Native.info(engine)}")
        }
        notify("Enhancing $name…")
        val t1 = SystemClock.elapsedRealtime()
        val out = ByteArray(w * 4 * h * 4 * 3)
        if (Native.enhance(engine, rgb, w, h, out, 1.0f) != 0) { log("FAILED: enhance: ${Native.error()}"); notify("Failed: $name (see log)"); return }
        val t2 = SystemClock.elapsedRealtime()
        val W = w * 4; val H = h * 4
        val opx = IntArray(W * H)
        for (k in opx.indices) opx[k] = (0xFF shl 24) or ((out[k * 3].toInt() and 0xFF) shl 16) or ((out[k * 3 + 1].toInt() and 0xFF) shl 8) or (out[k * 3 + 2].toInt() and 0xFF)
        var ob = Bitmap.createBitmap(opx, W, H, Bitmap.Config.ARGB_8888)
        if (W != src.width || H != src.height) ob = Bitmap.createScaledBitmap(ob, src.width, src.height, true)   // same size as the camera's photo

        outDir.mkdirs()
        val file = File(outDir, "${name}_AI.jpg")
        file.outputStream().use { ob.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        runCatching {
            ExifInterface(file.path).apply {
                for (tag in arrayOf(ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
                    ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
                    ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_DIGITAL_ZOOM_RATIO,
                    ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF))
                    exifIn?.getAttribute(tag)?.let { setAttribute(tag, it) }
                setAttribute(ExifInterface.TAG_SOFTWARE, "GCam100x Enhancer (AdcSR, generative AI)")
                setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, "AI-enhanced: detail in this image was generated by a diffusion model")
                saveAttributes()
            }
        }
        MediaScannerConnection.scanFile(this, arrayOf(file.path), arrayOf("image/jpeg"), null)
        val tiles = Native.progress() and 0xffff
        val total = SystemClock.elapsedRealtime() - t0
        log("DONE $file  ${src.width}x${src.height} via ${w}x$h, $tiles tiles, model ${t2 - t1} ms (${(t2 - t1) / maxOf(tiles, 1)} ms/tile), total $total ms; ${Native.info(engine)}")
        notify("Saved ${file.name} (${total / 1000} s)")
    }
}
