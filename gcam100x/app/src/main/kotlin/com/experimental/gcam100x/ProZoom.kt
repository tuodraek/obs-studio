package com.experimental.gcam100x

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.media.ExifInterface
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Pro Zoom output for zoom levels above the 30x hardware limit.
 *
 * Primary path (in Google Camera's own save pipeline): when Google Camera
 * opens the pending MediaStore item for a past-30x shot (vnn.g()), the JPEG it
 * writes is buffered; on close it is center-cropped to the chosen zoom
 * (scale = 30/zoom), rebuilt with Real-ESRGAN (realesr-general-x4v3, GPU)
 * and written before Google Camera publishes the item. The gallery therefore
 * only ever sees the finished photo, and Google Photos shows Google Camera's
 * own "Processing ..." chip while it runs.
 *
 * Fallback (only if a shot never went through vnn): fix the photo in
 * MediaStore after it is saved.
 */
object ProZoom {
    private const val TAG = "GCam100x"
    /** At or above this zoom the crop is rebuilt with Real-ESRGAN; below it a high-quality resize. */
    private const val SR_MIN_ZOOM = 40f

    class Shot(val scale: Float, val zoom: Float, val time: Long) {
        @Volatile var consumed = false
        /** Google Camera's shot object (rcb) this Pro Zoom shot's thumbnail belongs to. */
        @Volatile var thumbKey: Any? = null
    }

    private val shots = ConcurrentLinkedQueue<Shot>()
    private val fixedIds: MutableSet<Long> = Collections.synchronizedSet(HashSet())
    private lateinit var module: MainHook
    lateinit var sr: SuperRes; private set
    @Volatile private var appContext: Context? = null
    private val worker by lazy { Handler(HandlerThread("gcam100x-prozoom").apply { start() }.looper) }

    fun init(m: MainHook) { module = m; sr = SuperRes(m) }

    private fun log(msg: String) = module.logMessage(Log.INFO, TAG, msg)

    /** A shot was taken with Pro Zoom state (active, zoom, scale). */
    fun onShot(scale: Float, zoom: Float) {
        if (scale <= 0f || scale >= 0.999f) return
        val s = Shot(scale, zoom, System.currentTimeMillis())
        last = s
        shots.add(s)
        log("ProZoom: shot at ${zoom}x (scale $scale)")
        worker.postDelayed({ fallbackScan() }, 20_000)
    }

    @Volatile private var last: Shot? = null
    /** Current viewfinder Pro Zoom crop (1 = none), from the live tnm state. */
    @Volatile var liveScale = 1f
    @Volatile var modelState: String? = null
    @Volatile var lastResult: String? = null

    /** The most recent shot, if taken in the last 10 s. */
    fun latestShot(): Shot? = last?.takeIf { System.currentTimeMillis() - it.time < 10_000 }

    /** Next unconsumed shot taken in the last 60 s. */
    fun claimShot(): Shot? {
        val now = System.currentTimeMillis()
        while (shots.peek()?.let { now - it.time > 60_000 || it.consumed } == true) shots.poll()
        return shots.poll()?.also { it.consumed = true }
    }

    // --- Primary path: vnn.g() --------------------------------------------

    fun install(loader: ClassLoader) {
        runCatching {
            val vnn = loader.loadClass("vnn")
            val g = vnn.getDeclaredMethod("g")
            val bF = vnn.getDeclaredField("b").apply { isAccessible = true }
            val cF = vnn.getDeclaredField("c").apply { isAccessible = true }
            val aF = vnn.getDeclaredField("a").apply { isAccessible = true }
            module.hookMethod(g).intercept { chain ->
                val out = chain.proceed() as FileOutputStream
                val vnk = bF.get(chain.thisObject)
                val mime = runCatching { vnk.javaClass.getDeclaredField("e").apply { isAccessible = true }.get(vnk) as String }.getOrNull()
                if (mime != "image/jpeg") return@intercept out
                val shot = claimShot() ?: return@intercept out
                val uri = cF.get(chain.thisObject) as Uri
                val cr = aF.get(chain.thisObject) as android.content.ContentResolver
                log("ProZoom: processing ${shot.zoom}x photo in the save pipeline ($uri)")
                ProZoomStream(out, cr, uri, shot)
            }
            log("ProZoom: save-pipeline hook on vnn.g()")
        }.onFailure { module.logMessage(Log.WARN, TAG, "ProZoom: vnn hook failed: $it") }
        // Shot thumbnail (camera thumbnail button + Google Photos processing
        // preview): rrg.c(shot, bitmap, rotation). Crop it to the zoom too.
        runCatching {
            val rrg = loader.loadClass("rrg")
            val c = rrg.declaredMethods.first { it.name == "c" && it.parameterTypes.size == 3 && it.parameterTypes[1] == Bitmap::class.java }
            module.hookMethod(c).intercept { chain ->
                val bmp = chain.getArg(1) as? Bitmap
                val key = chain.getArg(0)
                var shot = last?.takeIf { it.thumbKey === key }
                if (shot == null) shot = latestShot()?.takeIf { it.thumbKey == null && System.currentTimeMillis() - it.time < 3_000 }?.also { it.thumbKey = key }
                if (bmp == null || shot == null || bmp.isRecycled) return@intercept chain.proceed()
                val w = bmp.width; val h = bmp.height
                val cw = (w * shot.scale).toInt().coerceAtLeast(2); val ch = (h * shot.scale).toInt().coerceAtLeast(2)
                val crop = Bitmap.createBitmap(bmp, (w - cw) / 2, (h - ch) / 2, cw, ch)
                val scaled = Bitmap.createScaledBitmap(crop, w, h, true)
                if (crop !== bmp && crop !== scaled) crop.recycle()
                log("ProZoom: thumbnail ${w}x$h cropped to ${shot.zoom}x")
                chain.proceed(arrayOf(chain.getArg(0), scaled, chain.getArg(2)))
            }
            log("ProZoom: thumbnail hook on rrg.c()")
            // Viewfinder screenshots (shutter animation, placeholder thumbnail)
            // come from the raw preview stream, which is not Pro Zoom cropped
            // (the crop is applied by the on-screen renderer). Crop them to
            // the live zoom so they match what is shown.
            val swj = loader.loadClass("swj")
            val yia = loader.loadClass("yia")
            val ctor = swj.getDeclaredConstructor(Bitmap::class.java, Int::class.javaPrimitiveType, yia, Boolean::class.javaPrimitiveType)
            module.hookCtor(ctor).intercept { ch ->
                val s = liveScale
                val bmp = ch.getArg(0) as? Bitmap
                if (bmp == null || bmp.isRecycled || s >= 0.999f || s <= 0f) return@intercept ch.proceed()
                val w = bmp.width; val h = bmp.height
                val cw = (w * s).toInt().coerceAtLeast(2); val chh = (h * s).toInt().coerceAtLeast(2)
                val crop = Bitmap.createBitmap(bmp, (w - cw) / 2, (h - chh) / 2, cw, chh)
                val scaled = Bitmap.createScaledBitmap(crop, w, h, true)
                if (crop !== scaled) crop.recycle()
                ch.proceed(arrayOf(scaled, ch.getArg(1), ch.getArg(2), ch.getArg(3)))
            }
            log("ProZoom: viewfinder screenshot hook on swj")
        }.onFailure { module.logMessage(Log.WARN, TAG, "ProZoom: thumbnail hook failed: $it") }
    }

    /** Buffers Google Camera's JPEG and writes the Pro Zoom result on close. */
    private class ProZoomStream(
        private val target: FileOutputStream,
        private val cr: android.content.ContentResolver,
        private val uri: Uri,
        private val shot: Shot,
    ) : FileOutputStream(target.fd) {
        private val buf = ByteArrayOutputStream(16 shl 20)
        private var closed = false
        override fun write(b: Int) = buf.write(b)
        override fun write(b: ByteArray) = buf.write(b, 0, b.size)
        override fun write(b: ByteArray, off: Int, len: Int) = buf.write(b, off, len)
        override fun flush() {}
        override fun close() {
            if (closed) return
            closed = true
            var bytes = buf.toByteArray()
            if (bytes.isEmpty()) {
                // Written through the fd/channel directly: read it back.
                runCatching { bytes = cr.openInputStream(uri)!!.use { it.readBytes() } }
            }
            val result = try {
                render(bytes, shot)
            } catch (t: Throwable) {
                module.logMessage(Log.WARN, TAG, "ProZoom: render failed, keeping original: ${Log.getStackTraceString(t)}")
                bytes
            }
            try {
                target.channel.truncate(0)
                target.channel.position(0)
                target.write(result)
                target.flush()
            } finally {
                target.close()
            }
            fixedIds.add(runCatching { ContentUris.parseId(uri) }.getOrDefault(-1))
        }
    }

    // --- Rendering -------------------------------------------------------

    /** Crop [jpeg] to the shot's zoom, enhance, re-encode with the original EXIF. */
    fun render(jpeg: ByteArray, shot: Shot): ByteArray {
        val t0 = System.currentTimeMillis()
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, o)
        val w = o.outWidth; val h = o.outHeight
        val cw = (w * shot.scale).toInt().coerceAtLeast(16); val ch = (h * shot.scale).toInt().coerceAtLeast(16)
        val left = (w - cw) / 2; val top = (h - ch) / 2
        @Suppress("DEPRECATION")
        val dec = BitmapRegionDecoder.newInstance(jpeg, 0, jpeg.size, false)!!
        val region = dec.decodeRegion(Rect(left, top, left + cw, top + ch), null)
        dec.recycle()
        val ctx = appContext
        val enhance = ctx != null && Prefs.aiEnhance(ctx) && shot.zoom >= SR_MIN_ZOOM
        val full: Bitmap = if (enhance) {
            // Network input = the crop at 1/4 of the output size (exact at 120x).
            val small = if (region.width == w / 4 && region.height == h / 4) region
                else Bitmap.createScaledBitmap(region, w / 4, h / 4, true).also { region.recycle() }
            val up = try { sr.upscale4x(small) } catch (t: Throwable) {
                module.logMessage(Log.WARN, TAG, "ProZoom: enhancement unavailable (${t}); plain crop")
                Bitmap.createScaledBitmap(small, w, h, true)
            } finally { small.recycle() }
            if (up.width == w && up.height == h) up else Bitmap.createScaledBitmap(up, w, h, true).also { up.recycle() }
        } else {
            // Default: the exact crop at its native resolution. No resampling,
            // no invented detail, minimal work.
            region
        }
        val enc = ByteArrayOutputStream(jpeg.size)
        full.compress(Bitmap.CompressFormat.JPEG, 95, enc)
        full.recycle()
        val outBytes = copyExif(jpeg, enc.toByteArray(), shot.zoom)
        val ms = System.currentTimeMillis() - t0
        val mode = if (enhance) "AI ${sr.backend}" else "native crop"
        lastResult = "${shot.zoom.toInt()}× · ${cw}×$ch from ${w}×$h · $ms ms ($mode)"
        log("ProZoom: ${w}x$h at ${shot.zoom}x -> ${cw}x$ch in $ms ms ($mode)")
        return outBytes
    }

    /** Copies EXIF (orientation, date, camera, GPS...) from [src] onto [dst]. */
    private fun copyExif(src: ByteArray, dst: ByteArray, zoom: Float): ByteArray {
        val dir = appContext?.cacheDir ?: return dst
        val tmp = File.createTempFile("prozoom", ".jpg", dir)
        try {
            tmp.writeBytes(dst)
            val exIn = ExifInterface(java.io.ByteArrayInputStream(src))
            val exOut = ExifInterface(tmp.absolutePath)
            for (tag in EXIF_TAGS) exIn.getAttribute(tag)?.let { exOut.setAttribute(tag, it) }
            exOut.setAttribute(ExifInterface.TAG_DIGITAL_ZOOM_RATIO, String.format("%.1f", zoom))
            exOut.saveAttributes()
            return tmp.readBytes()
        } finally { tmp.delete() }
    }

    // --- Fallback: fix in MediaStore after save ---------------------------

    fun register(ctx: Context) {
        if (appContext != null) return
        appContext = ctx.applicationContext
        // Pick the TPU/GPU backend while the camera is idle.
        worker.post { runCatching { if (Prefs.aiEnhance(ctx.applicationContext)) sr.warmUp() } }
        val cr = ctx.applicationContext.contentResolver
        cr.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, object : ContentObserver(worker) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {}
        })
    }

    private fun fallbackScan() {
        val ctx = appContext ?: return
        val now = System.currentTimeMillis()
        val shot = shots.firstOrNull { !it.consumed && now - it.time in 15_000..60_000 } ?: return
        val cr = ctx.contentResolver
        val since = (shot.time / 1000 - 5).toString()
        val ids = ArrayList<Long>()
        runCatching {
            cr.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID),
                "${MediaStore.Images.Media.OWNER_PACKAGE_NAME}=? AND ${MediaStore.Images.Media.MIME_TYPE}='image/jpeg' AND ${MediaStore.Images.Media.DATE_ADDED}>=? AND ${MediaStore.Images.Media.IS_PENDING}=0",
                arrayOf(ctx.packageName, since), "${MediaStore.Images.Media.DATE_ADDED} ASC")?.use { c ->
                while (c.moveToNext()) ids += c.getLong(0)
            }
        }
        val id = ids.firstOrNull { it !in fixedIds } ?: return
        shot.consumed = true
        fixedIds.add(id)
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        runCatching {
            val bytes = cr.openInputStream(uri)!!.use { it.readBytes() }
            val out = render(bytes, shot)
            cr.openOutputStream(uri, "wt")!!.use { it.write(out) }
            log("ProZoom: fallback applied to $uri")
        }.onFailure { module.logMessage(Log.WARN, TAG, "ProZoom: fallback failed: $it") }
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
