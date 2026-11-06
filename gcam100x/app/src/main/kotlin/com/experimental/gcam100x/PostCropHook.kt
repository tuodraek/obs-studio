package com.experimental.gcam100x

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.media.ExifInterface
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Applies the beyond-30x zoom to saved photos.
 *
 * The camera hardware stops at 30x. Above that, Google Camera's Pro Zoom path
 * crops the 30x frame by scale = 30/zoom. On this phone the saved photo keeps
 * the uncropped 30x frame, so: when a shot is taken with Pro Zoom active
 * (scale < 1), the next photo Google Camera saves is center-cropped by that
 * scale and resized back to its original resolution (EXIF kept).
 */
object PostCropHook {
    private const val TAG = "GCam100x"
    private data class Pending(val scale: Float, val zoom: Float, val time: Long)

    private val pending = ConcurrentLinkedQueue<Pending>()
    /** id -> size of the file we wrote (re-crop if Google Camera rewrites it). */
    private val done: MutableMap<Long, Long> = Collections.synchronizedMap(HashMap())
    private data class Job(val id: Long, val p: Pending, val until: Long)
    private val active = ConcurrentLinkedQueue<Job>()
    @Volatile private var registered = false
    private lateinit var module: MainHook
    private lateinit var handler: Handler

    private lateinit var sr: SuperRes
    /** At or above this zoom the crop is rebuilt with Real-ESRGAN; below it a plain resize. */
    private const val SR_MIN_ZOOM = 45f

    fun init(m: MainHook) { module = m; sr = SuperRes(m) }

    /** Called when a shot is created; scale/zoom from its Pro Zoom state. */
    fun onShot(scale: Float, zoom: Float) {
        if (scale <= 0f || scale >= 0.999f) return
        pending.add(Pending(scale, zoom, System.currentTimeMillis()))
        module.logMessage(Log.INFO, TAG, "Post-crop: queued shot at ${zoom}x (scale $scale)")
    }

    fun register(ctx: Context) {
        if (registered) return
        registered = true
        val t = HandlerThread("gcam100x-postcrop").apply { start() }
        handler = Handler(t.looper)
        val cr = ctx.applicationContext.contentResolver
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                handler.removeCallbacksAndMessages(null)
                handler.postDelayed({ scan(ctx.applicationContext) }, 1500)
            }
        }
        cr.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
        module.logMessage(Log.INFO, TAG, "Post-crop: watching saved photos")
    }

    private fun query(ctx: Context, sel: String, args: Array<String>): List<LongArray> {
        val out = ArrayList<LongArray>()
        val proj = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.IS_PENDING, MediaStore.Images.Media.SIZE)
        runCatching {
            ctx.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj,
                "$sel AND ${MediaStore.Images.Media.MIME_TYPE}='image/jpeg'", args,
                "${MediaStore.Images.Media.DATE_ADDED} ASC")?.use { c ->
                while (c.moveToNext()) out += longArrayOf(c.getLong(0), c.getLong(1), c.getLong(2))
            }
        }.onFailure { module.logMessage(Log.WARN, TAG, "Post-crop query failed: $it") }
        return out
    }

    private fun scan(ctx: Context) {
        val now = System.currentTimeMillis()
        while (pending.peek()?.let { now - it.time > 120_000 } == true) pending.poll()
        active.removeIf { now > it.until }
        var retry = false
        // 1) Re-crop photos Google Camera rewrote after we cropped them.
        for (job in active) {
            val r = query(ctx, "${MediaStore.Images.Media._ID}=?", arrayOf(job.id.toString())).firstOrNull() ?: continue
            if (r[1] != 0L) { retry = true; continue }
            if (job.id !in writing && r[2] != done[job.id]) cropId(ctx, job.id, job.p)
        }
        // 2) New photos for queued shots.
        val p = pending.peek()
        if (p != null) {
            val since = ((p.time / 1000) - 5).toString()
            var rows = query(ctx, "${MediaStore.Images.Media.OWNER_PACKAGE_NAME}=? AND ${MediaStore.Images.Media.DATE_ADDED}>=?", arrayOf(ctx.packageName, since))
            if (rows.isEmpty()) rows = query(ctx, "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Images.Media.DATE_ADDED}>=?", arrayOf("DCIM/Camera%", since))
            for (r in rows) {
                val id = r[0]
                if (done.containsKey(id)) continue
                if (r[1] != 0L) { retry = true; break }
                pending.poll()
                active.add(Job(id, p, now + 120_000))
                cropId(ctx, id, p)
                break
            }
        }
        if (retry || active.isNotEmpty()) handler.postDelayed({ scan(ctx) }, 2000)
    }

    private fun cropId(ctx: Context, id: Long, p: Pending) {
        if (id in writing) return
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        runCatching { done[id] = crop(ctx, uri, p) }
            .onFailure { done[id] = -1; module.logMessage(Log.WARN, TAG, "Post-crop failed for $uri: ${Log.getStackTraceString(it)}") }
    }

    /** Photos we are writing right now (so our own writes don't trigger a re-crop). */
    private val writing: MutableSet<Long> = Collections.synchronizedSet(HashSet())
    private val srThread by lazy {
        HandlerThread("gcam100x-superres", android.os.Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
    }

    /**
     * Stage 1 (immediately): plain center crop at the chosen zoom.
     * Stage 2 (background, >= SR_MIN_ZOOM): Real-ESRGAN rebuild of the same
     * crop from the original pixels, with a progress notification.
     * Returns the size of the stage-1 file.
     */
    private fun crop(ctx: Context, uri: Uri, p: Pending): Long {
        val cr = ctx.contentResolver
        val id = ContentUris.parseId(uri)
        val original = cr.openInputStream(uri)!!.use { it.readBytes() }
        val exifIn = ExifInterface(java.io.ByteArrayInputStream(original))
        val region = decodeCrop(original, p)
        val (w, h) = imageSize(original)
        val fast = Bitmap.createScaledBitmap(region, w, h, true)
        region.recycle()
        val size = write(ctx, uri, id, fast, exifIn, p)
        fast.recycle()
        module.logMessage(Log.INFO, TAG, "Post-crop: $uri cropped to ${p.zoom}x instantly")
        if (p.zoom >= SR_MIN_ZOOM) {
            Handler(srThread.looper).post { enhance(ctx, uri, id, original, exifIn, p, w, h) }
        }
        return size
    }

    private fun imageSize(bytes: ByteArray): Pair<Int, Int> {
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        return o.outWidth to o.outHeight
    }

    private fun decodeCrop(bytes: ByteArray, p: Pending): Bitmap {
        @Suppress("DEPRECATION")
        val decoder = BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)!!
        val w = decoder.width; val h = decoder.height
        val cw = (w * p.scale).toInt().coerceAtLeast(16); val ch = (h * p.scale).toInt().coerceAtLeast(16)
        val left = (w - cw) / 2; val top = (h - ch) / 2
        return decoder.decodeRegion(Rect(left, top, left + cw, top + ch), null).also { decoder.recycle() }
    }

    private fun write(ctx: Context, uri: Uri, id: Long, bmp: Bitmap, exifIn: ExifInterface, p: Pending): Long {
        val cr = ctx.contentResolver
        val out = ByteArrayOutputStream(8 shl 20)
        bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
        writing.add(id)
        try {
            cr.openOutputStream(uri, "wt")!!.use { it.write(out.toByteArray()) }
            cr.openFileDescriptor(uri, "rw")?.use { pfd ->
                val exifOut = ExifInterface(pfd.fileDescriptor)
                for (tag in EXIF_TAGS) exifIn.getAttribute(tag)?.let { exifOut.setAttribute(tag, it) }
                exifOut.setAttribute(ExifInterface.TAG_DIGITAL_ZOOM_RATIO, String.format("%.1f", p.zoom))
                exifOut.saveAttributes()
            }
            val size = cr.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
            done[id] = size
            return size
        } finally {
            writing.remove(id)
        }
    }

    private fun enhance(ctx: Context, uri: Uri, id: Long, original: ByteArray, exifIn: ExifInterface, p: Pending, w: Int, h: Int) {
        val notif = ProgressNotification(ctx, id.toInt())
        notif.show("Enhancing ${p.zoom.toInt()}× photo", 0)
        try {
            val region = decodeCrop(original, p)
            // Model input: the crop at 1/4 of the output size (exact for 120x).
            val small = Bitmap.createScaledBitmap(region, w / 4, h / 4, true)
            if (small !== region) region.recycle()
            val up = try {
                sr.upscale4x(small) { doneTiles, total -> notif.show("Enhancing ${p.zoom.toInt()}× photo", doneTiles * 100 / total) }
            } finally { small.recycle() }
            val full = if (up.width != w || up.height != h) Bitmap.createScaledBitmap(up, w, h, true).also { up.recycle() } else up
            write(ctx, uri, id, full, exifIn, p)
            full.recycle()
            notif.done("${p.zoom.toInt()}× photo enhanced")
            module.logMessage(Log.INFO, TAG, "Post-crop: $uri enhanced (${sr.backend})")
        } catch (t: Throwable) {
            notif.done("Enhancement failed; zoomed photo kept")
            module.logMessage(Log.WARN, TAG, "Enhance failed for $uri: ${Log.getStackTraceString(t)}")
        }
    }

    private fun toast(ctx: Context, msg: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runCatching { android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show() }
        }
    }

    private val EXIF_TAGS = arrayOf(
        ExifInterface.TAG_ORIENTATION, ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED, ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_SOFTWARE,
        ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_ISO_SPEED_RATINGS,
        ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_FLASH,
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF, ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
    )
}
