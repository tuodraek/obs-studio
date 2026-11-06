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

    fun init(m: MainHook) { module = m }

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
            if (r[2] != done[job.id]) cropId(ctx, job.id, job.p)
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
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        runCatching { done[id] = crop(ctx, uri, p) }
            .onFailure { done[id] = -1; module.logMessage(Log.WARN, TAG, "Post-crop failed for $uri: ${Log.getStackTraceString(it)}") }
    }

    /** Returns the size of the written file. */
    private fun crop(ctx: Context, uri: Uri, p: Pending): Long {
        val cr = ctx.contentResolver
        val original = cr.openInputStream(uri)!!.use { it.readBytes() }
        // Keep EXIF from the original.
        val exifIn = ExifInterface(java.io.ByteArrayInputStream(original))
        @Suppress("DEPRECATION")
        val decoder = BitmapRegionDecoder.newInstance(original, 0, original.size, false)!!
        val w = decoder.width; val h = decoder.height
        val cw = (w * p.scale).toInt().coerceAtLeast(16); val ch = (h * p.scale).toInt().coerceAtLeast(16)
        val left = (w - cw) / 2; val top = (h - ch) / 2
        val region = decoder.decodeRegion(Rect(left, top, left + cw, top + ch), null)
        decoder.recycle()
        val scaled = Bitmap.createScaledBitmap(region, w, h, true)
        region.recycle()
        val out = ByteArrayOutputStream(original.size)
        scaled.compress(Bitmap.CompressFormat.JPEG, 95, out)
        scaled.recycle()
        cr.openOutputStream(uri, "wt")!!.use { it.write(out.toByteArray()) }
        // Restore EXIF (orientation, date, camera, GPS...).
        cr.openFileDescriptor(uri, "rw")?.use { pfd ->
            val exifOut = ExifInterface(pfd.fileDescriptor)
            for (tag in EXIF_TAGS) exifIn.getAttribute(tag)?.let { exifOut.setAttribute(tag, it) }
            exifOut.setAttribute(ExifInterface.TAG_DIGITAL_ZOOM_RATIO, String.format("%.1f", p.zoom))
            exifOut.saveAttributes()
        }
        val size = cr.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
        module.logMessage(Log.INFO, TAG, "Post-crop: $uri ${w}x$h cropped to ${cw}x$ch (zoom ${p.zoom}x) and saved")
        return size
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
