package com.experimental.gcam100x

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.media.ExifInterface
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Pro Zoom past the 30x hardware limit.
 *
 * The camera captures at 30x (its hardware maximum) on Google Camera's
 * normal HDR+ path. Past 30x the viewfinder is Google Camera's own centre crop
 * of the 30x preview (scale = 30/zoom); the same centre crop is applied to
 * the photo:
 *
 *  - Thumbnail: cropped where Google Camera creates it (rah.ak intermediate
 *    thumbnail, rah.aj capture thumbnail), the same place and the same way
 *    Google Camera's own Pro Zoom crops it (sxh.r). Every consumer (thumbnail
 *    button, gallery, Photos' processing preview) gets the cropped bitmap.
 *  - Photo: Google Camera's JPEG is intercepted as it writes the pending
 *    MediaStore item (vnn.g()), cropped and rebuilt with the upscaler, and
 *    written before the item is published. The gallery never has the 30x
 *    image.
 */
object ProZoom {
    private const val TAG = "GCam100x"
    /** Network input = crop / this. */
    private const val SR_IN_DIV = 4
    /** Longest network input side. */
    private const val SR_IN_MAX = 1024
    /** Share of the network's luminance detail applied on the clean base. */
    private const val DETAIL = 0.85f

    /**
     * Send Pro Zoom shots through Google Camera's own Pro Zoom pipeline
     * (CENTAUR capture + Google's model via tna). Its controller loads the
     * downloaded centaur_p26 model on this G4 (tna.i initialized=true); if the
     * model processes a shot, its output is kept untouched, otherwise the
     * photo falls back to the centre crop + upscaler.
     */
    const val GOOGLE_PRO_ZOOM = true

    class Shot(val scale: Float, val zoom: Float, val time: Long) {
        @Volatile var consumed = false
        /** Google's Pro Zoom processor received both inputs for this shot. */
        @Volatile var googleRan = false
    }

    private lateinit var module: MainHook
    lateinit var sr: SuperRes; private set
    @Volatile private var appContext: Context? = null
    private val worker by lazy { Handler(HandlerThread("gcam100x-prozoom").apply { start() }.looper) }

    private val shots = ConcurrentLinkedQueue<Shot>()
    /** Pro Zoom shot per Google Camera shot-params object (pcl). */
    val pclShots: MutableMap<Any, Shot> = Collections.synchronizedMap(WeakHashMap())
    /** Bitmaps this module produced (already cropped). */
    private val ours: MutableMap<Bitmap, Boolean> = Collections.synchronizedMap(WeakHashMap())
    @Volatile private var last: Shot? = null

    /** Live viewfinder Pro Zoom crop (1 = none). */
    @Volatile var liveScale = 1f
    @Volatile var modelState: String? = null
    @Volatile var lastResult: String? = null

    fun init(m: MainHook) { module = m; sr = SuperRes(m) }

    private fun log(msg: String) = module.logMessage(Log.INFO, TAG, msg)
    private fun warn(msg: String) = module.logMessage(Log.WARN, TAG, msg)

    /** Shutter pressed with Pro Zoom active. */
    fun onShot(scale: Float, zoom: Float): Shot? {
        if (scale <= 0f || scale >= 0.999f) return null
        val s = Shot(scale, zoom, System.currentTimeMillis())
        last = s
        shots.add(s)
        log("ProZoom: shot at ${zoom}x (crop $scale)")
        // Load the upscaler now (first Pro Zoom shot only), in parallel with
        // Google Camera's own HDR+ processing: nothing touches the GPU while
        // the camera is just previewing.
        worker.post { runCatching { sr.warmUp() }.onFailure { warn("ProZoom: upscaler load failed: $it") } }
        return s
    }

    private fun claimShot(): Shot? {
        val now = System.currentTimeMillis()
        while (shots.peek()?.let { now - it.time > 60_000 || it.consumed } == true) shots.poll()
        return shots.poll()?.also { it.consumed = true }
    }

    /** Centre crop, exactly like Google Camera's viewfinder and sxh.r. */
    private fun crop(bmp: Bitmap, scale: Float): Bitmap {
        val w = (bmp.width * scale).toInt().coerceAtLeast(2)
        val h = (bmp.height * scale).toInt().coerceAtLeast(2)
        return Bitmap.createBitmap(bmp, (bmp.width - w) / 2, (bmp.height - h) / 2, w, h).also { ours[it] = true }
    }

    private fun cropThumb(bmp: Bitmap?, shot: Shot?, what: String): Bitmap? {
        if (bmp == null || shot == null || bmp.isRecycled || ours.containsKey(bmp)) return null
        return crop(bmp, shot.scale).also { log("ProZoom: $what thumbnail ${bmp.width}x${bmp.height} -> ${it.width}x${it.height}") }
    }

    fun install(loader: ClassLoader) {
        // 1. Thumbnails, where Google Camera creates them.
        runCatching {
            val rah = loader.loadClass("rah")
            val pclF = rah.declaredFields.first { it.type.name == "pcl" }.apply { isAccessible = true }
            fun shotOf(o: Any?) = runCatching { pclF.get(o) }.getOrNull()?.let { pclShots[it] }
            val pclCls = loader.loadClass("pcl")
            val nF = pclCls.declaredFields.first { it.type.name == "tnm" }.apply { isAccessible = true }
            val kF = pclCls.getDeclaredField("k").apply { isAccessible = true }
            val tbF = loader.loadClass("tnm").getDeclaredField("b").apply { isAccessible = true }
            // Google Camera crops rah.aj itself when the shot has an active Pro
            // Zoom state and is not high-res (sxh.r): do not crop twice.
            fun nativeCrop(o: Any?) = runCatching {
                val pcl = pclF.get(o); tbF.getBoolean(nF.get(pcl)) && !kF.getBoolean(pcl)
            }.getOrDefault(false)
            val aj = rah.declaredMethods.first { it.name == "aj" && it.parameterTypes.size == 2 && it.parameterTypes[0] == Bitmap::class.java }
            module.hookMethod(aj).intercept { chain ->
                if (nativeCrop(chain.thisObject)) return@intercept chain.proceed()
                val out = cropThumb(chain.getArg(0) as? Bitmap, shotOf(chain.thisObject), "capture")
                if (out == null) chain.proceed() else chain.proceed(arrayOf(out, chain.getArg(1)))
            }
            val ak = rah.declaredMethods.first { it.name == "ak" && it.parameterTypes.size == 1 && it.parameterTypes[0] == Bitmap::class.java }
            module.hookMethod(ak).intercept { chain ->
                val out = cropThumb(chain.getArg(0) as? Bitmap, shotOf(chain.thisObject), "intermediate")
                if (out == null) chain.proceed() else chain.proceed(arrayOf(out))
            }
            log("ProZoom: thumbnail hooks on rah.aj/ak")
        }.onFailure { warn("ProZoom: thumbnail hooks failed: $it") }
        // Backstop: the shot thumbnail cache (rrg.c), if a thumbnail reaches
        // it without passing rah (already-cropped bitmaps are left alone).
        runCatching {
            val rrg = loader.loadClass("rrg")
            val c = rrg.declaredMethods.first { it.name == "c" && it.parameterTypes.size == 3 && it.parameterTypes[1] == Bitmap::class.java }
            module.hookMethod(c).intercept { chain ->
                val shot = last?.takeIf { System.currentTimeMillis() - it.time < 15_000 }
                val out = cropThumb(chain.getArg(1) as? Bitmap, shot, "cached")
                if (out == null) chain.proceed() else chain.proceed(arrayOf(chain.getArg(0), out, chain.getArg(2)))
            }
        }.onFailure { warn("ProZoom: thumbnail cache hook failed: $it") }

        // 2. The photo, in Google Camera's save pipeline.
        runCatching {
            val vnn = loader.loadClass("vnn")
            val g = vnn.getDeclaredMethod("g")
            val aF = vnn.getDeclaredField("a").apply { isAccessible = true }
            val bF = vnn.getDeclaredField("b").apply { isAccessible = true }
            val cF = vnn.getDeclaredField("c").apply { isAccessible = true }
            module.hookMethod(g).intercept { chain ->
                val out = chain.proceed() as FileOutputStream
                val vnk = bF.get(chain.thisObject)
                val mime = runCatching { vnk.javaClass.getDeclaredField("e").apply { isAccessible = true }.get(vnk) as String }.getOrNull()
                if (mime != "image/jpeg") return@intercept out
                val shot = claimShot() ?: return@intercept out
                if (shot.googleRan) {
                    log("ProZoom: ${shot.zoom}x photo from Google's Pro Zoom model, saved untouched")
                    lastResult = "${shot.zoom.toInt()}× · Google Pro Zoom model"
                    return@intercept out
                }
                ProZoomStream(out, aF.get(chain.thisObject) as android.content.ContentResolver, cF.get(chain.thisObject) as Uri, shot)
            }
            log("ProZoom: photo hook on vnn.g")
        }.onFailure { warn("ProZoom: photo hook failed: $it") }

        // 3. Google's Pro Zoom pipeline: trace the fusion inputs (myn) and the
        //    processor call (tna.c), mark shots the model processed.
        if (GOOGLE_PRO_ZOOM) runCatching {
            val myn = loader.loadClass("myn")
            val seen = Collections.synchronizedMap(WeakHashMap<Any, String>())
            fun note(o: Any?, what: String) {
                val prev = seen[o] ?: ""
                if (!prev.contains(what)) { seen[o] = "$prev $what"; log("Pro Zoom fusion: $what") }
            }
            for (m in myn.declaredMethods) {
                val name = m.name
                val kind = when {
                    name in setOf("b", "c") && m.parameterTypes.size >= 4 -> "primary in (${m.parameterTypes[0].simpleName})"
                    name in setOf("d", "e", "f") && m.parameterTypes.size >= 3 -> "secondary in (${m.parameterTypes[0].simpleName})"
                    name == "close" && m.parameterTypes.isEmpty() -> "close"
                    else -> null
                } ?: continue
                module.hookMethod(m).intercept { chain ->
                    val arg0 = if (m.parameterTypes.isNotEmpty()) chain.getArg(0) else Unit
                    note(chain.thisObject, if (arg0 == null) "$kind = null" else kind)
                    chain.proceed()
                }
            }
            val tna = loader.loadClass("tna")
            val iF = tna.getDeclaredField("i").apply { isAccessible = true }
            val mys = loader.loadClass("mys")
            val rawF = mys.getDeclaredField("a").apply { isAccessible = true }
            val c = tna.declaredMethods.first { it.name == "c" && it.parameterTypes.size == 7 }
            module.hookMethod(c).intercept { chain ->
                val init = runCatching { iF.getBoolean(chain.thisObject) }.getOrDefault(false)
                // mys.a: (desugared) Optional<RawReadView>
                val raw = runCatching { rawF.get(chain.getArg(3))?.let { it.javaClass.getMethod("isPresent").invoke(it) as Boolean } }.getOrNull()
                log("Pro Zoom fusion: Google processor tna.c called (model initialized=$init, secondary RAW present=$raw)")
                if (init && raw == true) last?.googleRan = true
                val t0 = System.currentTimeMillis()
                val r = chain.proceed()
                log("Pro Zoom fusion: tna.c returned in ${System.currentTimeMillis() - t0} ms (processing continues async)")
                r
            }
            log("ProZoom: Google Pro Zoom pipeline tracing installed")
        }.onFailure { warn("ProZoom: Google pipeline hooks failed: $it") }
    }

    /** Buffers Google Camera's JPEG; on close writes the Pro Zoom photo instead. */
    private class ProZoomStream(
        private val target: FileOutputStream,
        private val cr: android.content.ContentResolver,
        private val uri: Uri,
        private val shot: Shot,
    ) : FileOutputStream(target.fd) {
        private val buf = ByteArrayOutputStream(8 shl 20)
        private var closed = false
        override fun write(b: Int) = buf.write(b)
        override fun write(b: ByteArray) = buf.write(b, 0, b.size)
        override fun write(b: ByteArray, off: Int, len: Int) = buf.write(b, off, len)
        override fun flush() {}
        override fun close() {
            if (closed) return
            closed = true
            var bytes = buf.toByteArray()
            if (bytes.isEmpty()) runCatching { bytes = cr.openInputStream(uri)!!.use { it.readBytes() } }
            val result = try { render(bytes, shot) } catch (t: Throwable) {
                module.logMessage(Log.WARN, TAG, "ProZoom: render failed, keeping the original: ${Log.getStackTraceString(t)}")
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
        }
    }

    // --- Rendering -------------------------------------------------------

    private fun downscale(src: Bitmap, tw: Int, th: Int): Bitmap {
        var b = src
        while (b.width / 2 >= tw && b.height / 2 >= th) {
            val n = Bitmap.createScaledBitmap(b, b.width / 2, b.height / 2, true)
            if (b !== src) b.recycle()
            b = n
        }
        val out = if (b.width == tw && b.height == th) b else Bitmap.createScaledBitmap(b, tw, th, true)
        if (b !== src && b !== out) b.recycle()
        return out
    }

    /** [base] plus [a] of [detail]'s luminance difference (colour stays the base's). */
    private fun lumaBlend(base: Bitmap, detail: Bitmap, a: Float): Bitmap {
        val w = base.width; val h = base.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val bp = IntArray(w); val dp = IntArray(w)
        val k = (a * 256).toInt()
        for (y in 0 until h) {
            base.getPixels(bp, 0, w, 0, y, w, 1); detail.getPixels(dp, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val p = bp[x]; val q = dp[x]
                val yb = (77 * ((p shr 16) and 0xff) + 150 * ((p shr 8) and 0xff) + 29 * (p and 0xff)) shr 8
                val yd = (77 * ((q shr 16) and 0xff) + 150 * ((q shr 8) and 0xff) + 29 * (q and 0xff)) shr 8
                val d = ((yd - yb) * k) shr 8
                bp[x] = (0xff shl 24) or ((((p shr 16) and 0xff) + d).coerceIn(0, 255) shl 16) or
                    ((((p shr 8) and 0xff) + d).coerceIn(0, 255) shl 8) or ((p and 0xff) + d).coerceIn(0, 255)
            }
            out.setPixels(bp, 0, w, 0, y, w, 1)
        }
        return out
    }

    /** Centre crop of [jpeg] to the shot's zoom, rebuilt with the upscaler, original EXIF kept. */
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
        var mode = "AI ${sr.backend}"
        val outBmp: Bitmap = try {
            // The network works at the crop's real detail scale; the base is
            // the crop averaged down and back up, so Google Camera's
            // sharpening and pixel noise (magnified up to 4x) do not show.
            val f = maxOf(SR_IN_DIV.toFloat(), maxOf(cw, ch) / SR_IN_MAX.toFloat())
            val small = downscale(region, maxOf(64, (cw / f).toInt()), maxOf(64, (ch / f).toInt()))
            val up = sr.upscale4x(small)
            region.recycle()
            val clean = Bitmap.createScaledBitmap(small, up.width, up.height, true)
            small.recycle()
            lumaBlend(clean, up, DETAIL).also { up.recycle(); clean.recycle() }
        } catch (t: Throwable) {
            warn("ProZoom: upscaler failed ($t); plain crop")
            mode = "plain crop"
            region
        }
        val enc = ByteArrayOutputStream(4 shl 20)
        outBmp.compress(Bitmap.CompressFormat.JPEG, 95, enc)
        val size = "${outBmp.width}x${outBmp.height}"
        outBmp.recycle()
        val bytes = copyExif(jpeg, enc.toByteArray(), shot.zoom)
        val ms = System.currentTimeMillis() - t0
        lastResult = "${shot.zoom.toInt()}× · $size from ${w}×$h · $ms ms ($mode)"
        log("ProZoom: ${w}x$h at ${shot.zoom}x -> $size in $ms ms ($mode)")
        return bytes
    }

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

    /** First camera activity: keep its context. */
    fun register(ctx: Context) {
        if (appContext == null) appContext = ctx.applicationContext
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
