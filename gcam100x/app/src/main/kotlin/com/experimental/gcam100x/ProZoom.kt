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
    /** Network input = crop / this (the crop's real detail scale at 30x+ zoom). */
    private const val SR_IN_DIV = 4
    /** Longest network input side (bounds the time at low Pro Zoom levels). */
    private const val SR_IN_MAX = 1024
    /** How much of the network's luminance detail is applied. */
    private const val DETAIL = 0.85f
    /** Minimum NCC score to trust the viewfinder match. */
    private const val MIN_MATCH = 0.5f

    class Shot(val scale: Float, val zoom: Float, val time: Long) {
        @Volatile var consumed = false
        /** Google Camera's shot object (rcb) this Pro Zoom shot's thumbnail belongs to. */
        @Volatile var thumbKey: Any? = null
        /** Viewfinder frame on screen at the shutter. */
        @Volatile var view: Align.View? = null
        @Volatile var thumbIn: Bitmap? = null
        @Volatile var thumbOut: Bitmap? = null
        /** The finished photo has been written. */
        @Volatile var done = false
        /** Small grey copies of the shot thumbnail before / after framing. */
        @Volatile var sigIn: Align.Gray? = null
        @Volatile var sigOut: Align.Gray? = null
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
        s.view = lastView?.takeIf { kotlin.math.abs(it.time - s.time) < 400 }
        last = s
        if (s.view == null) grabViewfinder(s)
        shots.add(s)
        log("ProZoom: shot at ${zoom}x (scale $scale), viewfinder frame ${s.view?.let { "${it.gray.w}x${it.gray.h} captured" } ?: "not yet"}")
        worker.postDelayed({ fallbackScan() }, 20_000)
    }

    @Volatile var activity: java.lang.ref.WeakReference<android.app.Activity>? = null
    private val main by lazy { Handler(android.os.Looper.getMainLooper()) }

    private fun findPreview(v: android.view.View, best: Array<android.view.View?>) {
        if (v.visibility != android.view.View.VISIBLE || v.width <= 0 || v.height <= 0) return
        if (v is android.view.SurfaceView || v is android.view.TextureView) {
            val b = best[0]
            if (b == null || v.width.toLong() * v.height > b.width.toLong() * b.height) best[0] = v
        }
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) findPreview(v.getChildAt(i), best)
    }

    /**
     * Copies the on-screen viewfinder (Google Camera's Pro Zoom cropped
     * preview, exactly what the user sees) at the shutter: the reference for
     * 1:1 framing and for checking thumbnails.
     */
    private fun grabViewfinder(shot: Shot) {
        val act = activity?.get() ?: run { log("ProZoom: viewfinder grab: no activity"); return }
        main.post {
            runCatching {
                val holder = arrayOfNulls<android.view.View>(1)
                findPreview(act.window.decorView, holder)
                val v = holder[0] ?: run { log("ProZoom: viewfinder grab: no preview surface"); return@post }
                val w = maxOf(8, v.width / 3); val h = maxOf(8, v.height / 3)
                val t0 = System.currentTimeMillis()
                fun done(bmp: Bitmap) {
                    val view = Align.View(Align.gray(bmp, 384), System.currentTimeMillis())
                    bmp.recycle()
                    if (shot.view == null) shot.view = view
                    lastView = view
                    log("ProZoom: viewfinder grabbed from ${v.javaClass.simpleName} ${v.width}x${v.height} in ${System.currentTimeMillis() - t0} ms")
                }
                if (v is android.view.TextureView) {
                    v.getBitmap(w, h)?.let { done(it) }
                } else {
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    android.view.PixelCopy.request(v as android.view.SurfaceView, bmp, { r ->
                        if (r == android.view.PixelCopy.SUCCESS) worker.post { done(bmp) }
                        else log("ProZoom: viewfinder grab failed (PixelCopy $r)")
                    }, main)
                }
            }.onFailure { log("ProZoom: viewfinder grab failed: $it") }
        }
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
            hookThumbnailView(loader)
        }.onFailure { module.logMessage(Log.WARN, TAG, "ProZoom: vnn hook failed: $it") }
        // Shot thumbnail. Google Camera hands it out in two places:
        //  - rbl.g(shot, bitmap, rotation): dispatched straight to every shot
        //    listener (thumbnail button, the gallery's first image, shutter
        //    animation), and
        //  - rrg.c(shot, bitmap, rotation): the cache Google Photos'
        //    "processing" preview (CameraContentProvider) reads from.
        // Both get the same Pro Zoom framed bitmap.
        runCatching {
            val rbl = loader.loadClass("rbl")
            val g = rbl.declaredMethods.first { it.name == "g" && it.parameterTypes.size == 3 && it.parameterTypes[1] == Bitmap::class.java }
            module.hookMethod(g).intercept { chain ->
                val out = thumbFor(chain.getArg(0), chain.getArg(1) as? Bitmap, "listeners")
                if (out == null) chain.proceed() else chain.proceed(arrayOf(chain.getArg(0), out, chain.getArg(2)))
            }
            log("ProZoom: thumbnail hook on rbl.g()")
        }.onFailure { module.logMessage(Log.WARN, TAG, "ProZoom: rbl hook failed: $it") }
        runCatching {
            val rrg = loader.loadClass("rrg")
            val c = rrg.declaredMethods.first { it.name == "c" && it.parameterTypes.size == 3 && it.parameterTypes[1] == Bitmap::class.java }
            module.hookMethod(c).intercept { chain ->
                val out = thumbFor(chain.getArg(0), chain.getArg(1) as? Bitmap, "cache")
                if (out == null) chain.proceed() else chain.proceed(arrayOf(chain.getArg(0), out, chain.getArg(2)))
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
                // Exactly what the viewfinder shows: the reference for 1:1 framing.
                runCatching {
                    val v = Align.View(Align.gray(crop, 384), System.currentTimeMillis())
                    lastView = v
                    last?.takeIf { it.view == null && kotlin.math.abs(v.time - it.time) < 2_500 }?.view = v
                }
                val scaled = Bitmap.createScaledBitmap(crop, w, h, true)
                if (crop !== scaled) crop.recycle()
                ours[scaled] = true
                ch.proceed(arrayOf(scaled, ch.getArg(1), ch.getArg(2), ch.getArg(3)))
            }
            log("ProZoom: viewfinder screenshot hook on swj")
        }.onFailure { module.logMessage(Log.WARN, TAG, "ProZoom: thumbnail hook failed: $it") }
    }

    /**
     * True if [bmp] shows the full 30x frame rather than the Pro Zoom view
     * (its centre crop matches the viewfinder better than the whole image).
     * Null when there is nothing to compare against.
     */
    private fun looksUnzoomed(bmp: Bitmap, shot: Shot): Boolean {
        val g = Align.gray(bmp, 96)
        // Compare with the shot thumbnail before/after framing, if seen.
        val sIn = shot.sigIn; val sOut = shot.sigOut
        if (sIn != null && sOut != null) return Align.similarity(g, sIn) > Align.similarity(g, sOut)
        // Else with the viewfinder.
        val v = viewFor(shot)
        if (v != null) return Align.similarity(Align.crop(g, shot.scale), v.gray) > Align.similarity(g, v.gray) + 0.05f
        // Nothing framed has been produced for this shot yet: it is the 30x image.
        return true
    }

    /** The last place a thumbnail is drawn: bottom-bar ThumbnailView.b(bitmap, ...). */
    private fun hookThumbnailView(loader: ClassLoader) {
        runCatching {
            val tv = loader.loadClass("com.google.android.apps.camera.bottombar.ThumbnailView")
            val b = tv.declaredMethods.first { it.name == "b" && it.parameterTypes.size == 4 && it.parameterTypes[0] == Bitmap::class.java }
            module.hookMethod(b).intercept { chain ->
                val bmp = chain.getArg(0) as? Bitmap
                val shot = last?.takeIf { !it.done && System.currentTimeMillis() - it.time < 30_000 }
                if (bmp == null || bmp.isRecycled || shot == null || ours.containsKey(bmp)) return@intercept chain.proceed()
                val unzoomed = runCatching { looksUnzoomed(bmp, shot) }.getOrDefault(true)
                if (unzoomed != true) {
                    log("ProZoom: thumbnail view ${bmp.width}x${bmp.height} already Pro Zoom (check=$unzoomed)")
                    return@intercept chain.proceed()
                }
                val w = bmp.width; val h = bmp.height
                val (r, how) = frame(w, h, shot, bmp)
                val crop = Bitmap.createBitmap(bmp, r.left, r.top, r.width().coerceAtLeast(2), r.height().coerceAtLeast(2))
                val scaled = Bitmap.createScaledBitmap(crop, w, h, true)
                if (crop !== bmp && crop !== scaled) crop.recycle()
                ours[scaled] = true
                log("ProZoom: thumbnail view got the 30x image ${w}x$h; framed to ${shot.zoom}x ($how)")
                chain.proceed(arrayOf(scaled, chain.getArg(1), chain.getArg(2), chain.getArg(3)))
            }
            log("ProZoom: thumbnail view hook on ThumbnailView.b()")
        }.onFailure { module.logMessage(Log.WARN, TAG, "ProZoom: ThumbnailView hook failed: $it") }
    }

    /** Bitmaps this module produced (already Pro Zoom framed). */
    private val ours: MutableMap<Bitmap, Boolean> = Collections.synchronizedMap(java.util.WeakHashMap())

    /**
     * Pro Zoom framed version of a shot thumbnail, or null to leave it as is.
     * The same input bitmap always maps to the same output.
     */
    private fun thumbFor(key: Any?, bmp: Bitmap?, where: String): Bitmap? {
        if (key == null || bmp == null || bmp.isRecycled || ours.containsKey(bmp)) return null
        var shot = last?.takeIf { it.thumbKey === key }
        if (shot == null) shot = latestShot()?.takeIf { it.thumbKey == null && System.currentTimeMillis() - it.time < 5_000 }?.also { it.thumbKey = key }
        if (shot == null) return null
        synchronized(shot) {
            if (shot.thumbIn === bmp) shot.thumbOut?.let { if (!it.isRecycled) return it }
            val w = bmp.width; val h = bmp.height
            val (r, how) = frame(w, h, shot, bmp)
            val crop = Bitmap.createBitmap(bmp, r.left, r.top, r.width().coerceAtLeast(2), r.height().coerceAtLeast(2))
            val scaled = Bitmap.createScaledBitmap(crop, w, h, true)
            if (crop !== bmp && crop !== scaled) crop.recycle()
            ours[scaled] = true
            shot.thumbIn = bmp; shot.thumbOut = scaled
            runCatching { shot.sigIn = Align.gray(bmp, 96); shot.sigOut = Align.gray(scaled, 96) }
            log("ProZoom: thumbnail ($where) ${w}x$h framed to ${shot.zoom}x; framing: $how")
            return scaled
        }
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
            shot.done = true
            fixedIds.add(runCatching { ContentUris.parseId(uri) }.getOrDefault(-1))
        }
    }

    // --- Rendering -------------------------------------------------------

    /** Viewfinder frame shown at the most recent shutter (for 1:1 framing). */
    @Volatile var lastView: Align.View? = null

    private fun viewFor(shot: Shot): Align.View? =
        shot.view ?: lastView?.takeIf { kotlin.math.abs(it.time - shot.time) < 2_500 }

    /** Crop rectangle of [w]x[h] that matches what the viewfinder showed for [shot]. */
    private fun frame(w: Int, h: Int, shot: Shot, coarse: Bitmap?): Pair<Rect, String> {
        val cw0 = w * shot.scale; val ch0 = h * shot.scale
        var cx = w / 2f; var cy = h / 2f; var s = 1f
        var how = "centre (no viewfinder frame)"
        val view = viewFor(shot)
        if (view != null && coarse != null) {
            val m = runCatching { Align.locate(Align.gray(coarse, 1024), view.gray, shot.scale) }
                .onFailure { module.logMessage(Log.WARN, TAG, "ProZoom: align failed: $it") }.getOrNull()
            if (m != null && m.score >= MIN_MATCH) {
                cx = m.cx * w; cy = m.cy * h; s = m.scale
                how = "matched to viewfinder (score %.2f, offset %+.1f%%, %+.1f%% of frame, size x%.3f, rot %d)"
                    .format(m.score, (m.cx - 0.5f) * 100, (m.cy - 0.5f) * 100, m.scale, m.rot)
            } else if (m != null) {
                how = "centre (viewfinder match too weak: %.2f)".format(m.score)
            } else how = "centre (scene too flat to match)"
        }
        val cw = (cw0 * s).toInt().coerceIn(16, w); val ch = (ch0 * s).toInt().coerceIn(16, h)
        val left = (cx - cw / 2f).toInt().coerceIn(0, w - cw); val top = (cy - ch / 2f).toInt().coerceIn(0, h - ch)
        return Rect(left, top, left + cw, top + ch) to how
    }

    /** Area-correct downscale by repeated halving (bilinear alone aliases past 2x). */
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

    /**
     * Adds the network's luminance detail onto [real] (the clean base):
     * colour and tone stay the photo's own; structure comes from the model.
     */
    private fun lumaBlend(real: Bitmap, ai: Bitmap, a: Float): Bitmap {
        val w = real.width; val h = real.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val rp = IntArray(w); val ap = IntArray(w)
        val ai256 = (a * 256).toInt()
        for (y in 0 until h) {
            real.getPixels(rp, 0, w, 0, y, w, 1); ai.getPixels(ap, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val r = rp[x]; val q = ap[x]
                val yr = (77 * ((r shr 16) and 0xff) + 150 * ((r shr 8) and 0xff) + 29 * (r and 0xff)) shr 8
                val yq = (77 * ((q shr 16) and 0xff) + 150 * ((q shr 8) and 0xff) + 29 * (q and 0xff)) shr 8
                val d = ((yq - yr) * ai256) shr 8
                val rr = (((r shr 16) and 0xff) + d).coerceIn(0, 255)
                val gg = (((r shr 8) and 0xff) + d).coerceIn(0, 255)
                val bb = ((r and 0xff) + d).coerceIn(0, 255)
                rp[x] = (0xff shl 24) or (rr shl 16) or (gg shl 8) or bb
            }
            out.setPixels(rp, 0, w, 0, y, w, 1)
        }
        return out
    }

    /** Pro Zoom: frame [jpeg] 1:1 to the viewfinder, rebuild detail, re-encode with the original EXIF. */
    fun render(jpeg: ByteArray, shot: Shot): ByteArray {
        val t0 = System.currentTimeMillis()
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, o)
        val w = o.outWidth; val h = o.outHeight
        // 1. Framing: register the still against the on-screen frame.
        var sample = 1
        while (w / (sample * 2) >= 1000) sample *= 2
        val coarse = if (viewFor(shot) != null)
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = sample }) else null
        val (rect, how) = frame(w, h, shot, coarse)
        coarse?.recycle()
        val t1 = System.currentTimeMillis()
        @Suppress("DEPRECATION")
        val dec = BitmapRegionDecoder.newInstance(jpeg, 0, jpeg.size, false)!!
        val region = dec.decodeRegion(rect, null)
        dec.recycle()
        val cw = region.width; val ch = region.height
        // 2. Detail: the network runs at the crop's real detail scale (1/4),
        //    output at the crop's native resolution, luminance only.
        var mode = "AI ${sr.backend}"
        val full: Bitmap = try {
            val f = maxOf(SR_IN_DIV.toFloat(), maxOf(cw, ch) / SR_IN_MAX.toFloat())
            val small = downscale(region, maxOf(64, (cw / f).toInt()), maxOf(64, (ch / f).toInt()))
            val up = sr.upscale4x(small)
            mode = "AI ${sr.backend}"
            val upN = if (up.width == cw && up.height == ch) up else Bitmap.createScaledBitmap(up, cw, ch, true).also { up.recycle() }
            // Base = the crop averaged down to its real detail scale and back
            // up: Google Camera's sharpening halos and pixel noise (magnified
            // 4x at 120x) are averaged out, colour stays the photo's own.
            val clean = Bitmap.createScaledBitmap(small, cw, ch, true)
            if (small !== region) small.recycle()
            region.recycle()
            lumaBlend(clean, upN, DETAIL).also { upN.recycle(); clean.recycle() }
        } catch (t: Throwable) {
            module.logMessage(Log.WARN, TAG, "ProZoom: upscaler failed ($t); exact crop")
            mode = "exact crop (upscaler failed)"
            region
        }
        val t2 = System.currentTimeMillis()
        val enc = ByteArrayOutputStream(jpeg.size)
        full.compress(Bitmap.CompressFormat.JPEG, 95, enc)
        full.recycle()
        val outBytes = copyExif(jpeg, enc.toByteArray(), shot.zoom)
        val ms = System.currentTimeMillis() - t0
        lastResult = "${shot.zoom.toInt()}× · ${cw}×$ch from ${w}×$h · $ms ms ($mode) · framing: $how"
        log("ProZoom: ${w}x$h at ${shot.zoom}x -> ${cw}x$ch at (${rect.left},${rect.top}) in $ms ms " +
            "[frame ${t1 - t0} ms, detail ${t2 - t1} ms, encode ${System.currentTimeMillis() - t2} ms] ($mode); framing: $how")
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
        // Load the upscaler on the GPU while the camera is idle.
        worker.post { runCatching { sr.warmUp() } }
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
