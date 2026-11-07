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
 *    MediaStore item (vnn.g()) and cropped (unless Google's model made it), and
 *    written before the item is published. The gallery never has the 30x
 *    image.
 */
object ProZoom {
    private const val TAG = "GCam100x"

    /**
     * Send Pro Zoom shots through Google Camera's own Pro Zoom pipeline
     * (CENTAUR capture + Google's model via tna). Its controller loads the
     * downloaded centaur_p26 model on this G4 (tna.i initialized=true); if the
     * model processes a shot, its output is kept untouched, otherwise the
     * photo is the exact centre crop.
     */
    val GOOGLE_PRO_ZOOM get() = Guard.googleEnabled

    class Shot(val scale: Float, val zoom: Float, val time: Long) {
        val id = ++counter
        @Volatile var consumed = false
        /** Went through Google's Pro Zoom (CENTAUR) pipeline. */
        @Volatile var googleAttempt = false
        /** Google's processor (tna.c) got the HDR+ image and the RAW frame. */
        @Volatile var processorCalled = false
        /** Google's native model returned an output image (delivered as final). */
        @Volatile var modelDelivered = false
        /** Google's native model returned without an image (original promoted). */
        @Volatile var modelNoImage = false
        @Volatile var modelMs = -1L
        @Volatile var modelStatus = ""
        /** Size of the first JPEG (Google's 30x original). */
        @Volatile var originalSize: String? = null
        @Volatile var finalDone = false
        @Volatile var originalUri: Uri? = null
        /** When Google's processor reported its result (model output or none). */
        @Volatile var resultTime = 0L
        companion object { @Volatile var counter = 0 }
    }

    /** Google Pro Zoom shots whose original was saved and whose final photo may still come. */
    private val awaitingFinal = ConcurrentLinkedQueue<Shot>()

    private lateinit var module: MainHook
    @Volatile private var appContext: Context? = null
    private val worker by lazy { Handler(HandlerThread("gcam100x-prozoom").apply { start() }.looper) }

    private val shots = ConcurrentLinkedQueue<Shot>()
    /** Pro Zoom shot per Google Camera shot-params object (pcl). */
    val pclShots: MutableMap<Any, Shot> = Collections.synchronizedMap(WeakHashMap())
    /** Bitmaps this module produced (already cropped). */
    private val ours: MutableMap<Bitmap, Boolean> = Collections.synchronizedMap(WeakHashMap())
    @Volatile private var last: Shot? = null
    /** The shot Google's processor is working on (results are attributed to it). */
    @Volatile private var inProcessor: Shot? = null

    // --- Per-shot history (kept across restarts) -----------------------------

    private val historyFile = File("/data/user/0/com.google.android.GoogleCamera/files/gcam100x_history")

    /** One line per photo: what Google's pipeline did and what was saved. */
    private fun record(shot: Shot, saved: String) {
        val line = "${java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(shot.time))} " +
            "#${shot.id} ${shot.zoom.toInt()}×: " +
            (if (!shot.googleAttempt) "normal capture" else
                "Google Pro Zoom capture, processor ${if (shot.processorCalled) "called" else "NOT called"}, model " +
                    when { shot.modelDelivered -> "OUTPUT (${shot.modelMs} ms)"; shot.modelNoImage -> "no output (${shot.modelMs} ms, ${shot.modelStatus})"; shot.modelMs >= 0 -> "returned (${shot.modelStatus})"; else -> "not run yet" }) +
            " → saved $saved"
        log("Shot summary: $line")
        runCatching {
            val old = if (historyFile.exists()) historyFile.readLines().takeLast(49) else emptyList()
            historyFile.writeText((old + line).joinToString("\n") + "\n")
        }
    }

    fun history(n: Int): List<String> = runCatching { historyFile.readLines().takeLast(n) }.getOrDefault(emptyList())

    /** Live viewfinder Pro Zoom crop (1 = none). */
    @Volatile var liveScale = 1f
    @Volatile var modelState: String? = null
    @Volatile var lastResult: String? = null

    fun init(m: MainHook) { module = m }

    private fun log(msg: String) = module.logMessage(Log.INFO, TAG, msg)
    private fun warn(msg: String) = module.logMessage(Log.WARN, TAG, msg)

    /** Shutter pressed with Pro Zoom active. */
    fun lastShot(): Shot? = last

    fun onShot(scale: Float, zoom: Float): Shot? {
        if (scale <= 0f || scale >= 0.999f) return null
        val s = Shot(scale, zoom, System.currentTimeMillis())
        last = s
        shots.add(s)
        log("ProZoom: shot #${s.id} at ${zoom}x (crop $scale)")
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
                val cr = aF.get(chain.thisObject) as android.content.ContentResolver
                val uri = cF.get(chain.thisObject) as Uri
                val shot = claimShot()
                if (shot != null) {
                    // First JPEG of a Pro Zoom shot. With Google's pipeline this is
                    // the 30x original, saved before the model runs: crop it now so
                    // the gallery shows the zoomed photo immediately.
                    shot.originalUri = uri
                    if (shot.googleAttempt) awaitingFinal.add(shot) else Guard.shotDone()
                    log("ProZoom: shot #${shot.id}: ${if (shot.googleAttempt) "Google's original (saved before the model runs)" else "photo"} -> exact crop ($uri)")
                    return@intercept ProZoomStream(out, cr, uri, shot, Kind.ORIGINAL)
                }
                // A later JPEG of a Google Pro Zoom shot: its final photo. Only if it
                // goes to the original's gallery item, or arrives right after
                // Google's processor reported its result (never an unrelated photo).
                val now = System.currentTimeMillis()
                while (awaitingFinal.peek()?.let { it.finalDone || now - it.time > 300_000 } == true) awaitingFinal.poll()
                val fin = awaitingFinal.firstOrNull { !it.finalDone && (it.originalUri == uri || (it.resultTime > 0 && now - it.resultTime < 15_000)) }
                if (fin == null) {
                    if (awaitingFinal.isNotEmpty()) log("ProZoom: JPEG $uri is not a Pro Zoom shot's final photo; left untouched")
                    return@intercept out
                }
                awaitingFinal.remove(fin)
                fin.finalDone = true
                Guard.shotDone()
                if (fin.modelDelivered) {
                    log("ProZoom: shot #${fin.id}: final photo = Google's Pro Zoom model output ($uri)")
                    ProZoomStream(out, cr, uri, fin, Kind.MODEL_OUTPUT)
                } else {
                    log("ProZoom: shot #${fin.id}: final photo = Google's promoted original (model gave no image) -> exact crop ($uri)")
                    ProZoomStream(out, cr, uri, fin, Kind.ORIGINAL)
                }
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
            val dF = tna.getDeclaredField("d").apply { isAccessible = true }
            val mys = loader.loadClass("mys")
            val rawF = mys.getDeclaredField("a").apply { isAccessible = true }
            val tnz = loader.loadClass("tnz")
            val zA = tnz.getDeclaredField("a").apply { isAccessible = true }
            val zB = tnz.getDeclaredField("b").apply { isAccessible = true }
            val zC = tnz.getDeclaredField("c").apply { isAccessible = true }
            val zD = tnz.getDeclaredField("d").apply { isAccessible = true }
            val zE = tnz.getDeclaredField("e").apply { isAccessible = true }
            val hookedModel = Collections.synchronizedSet(HashSet<Class<*>>())
            // Google's native model call: the real tnr implementation behind
            // tna.d (tnq is only a stub). Hooked once its class is known.
            fun hookModel(impl: Any) {
                val cls = impl.javaClass
                if (!hookedModel.add(cls)) return
                val m = cls.declaredMethods.firstOrNull { it.name == "c" && it.parameterTypes.size == 4 && it.returnType == tnz }
                if (m == null) { warn("Pro Zoom model: no model call on ${cls.name}"); return }
                module.hookMethod(m).intercept { chain ->
                    val shot = inProcessor ?: last
                    val tag = "shot #${shot?.id} ${shot?.zoom}x"
                    log("Pro Zoom model: START native model run ($tag) on ${cls.name}; ${Forensics.memory(appContext)}")
                    Guard.modelStarted("$tag at ${java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date())}")
                    val t0 = System.currentTimeMillis()
                    val r = try { chain.proceed() } catch (t: Throwable) {
                        Guard.modelReturned(false, System.currentTimeMillis() - t0)
                        warn("Pro Zoom model: native model run THREW after ${System.currentTimeMillis() - t0} ms: ${Log.getStackTraceString(t)}")
                        throw t
                    }
                    val ms = System.currentTimeMillis() - t0
                    val status = runCatching { zA.getInt(r) }.getOrDefault(-1)
                    val handle = runCatching { zB.getLong(r) }.getOrDefault(0L)
                    val c = runCatching { zC.getFloat(r) }.getOrDefault(Float.NaN)
                    val d = runCatching { zD.getInt(r) }.getOrDefault(-1)
                    val e = runCatching { zE.getInt(r) }.getOrDefault(-1)
                    val got = handle != 0L
                    Guard.modelReturned(got, ms)
                    shot?.modelMs = ms
                    shot?.modelStatus = "status=$status image=${if (got) "yes" else "no"} score=$c d=$d e=$e"
                    log("Pro Zoom model: END after $ms ms ($tag): status=$status, output image=${if (got) "YES (handle $handle)" else "NO"}, " +
                        "value=$c, d=$d, e=$e; ${Forensics.memory(appContext)}")
                    r
                }
                log("Pro Zoom model: hooked Google's native model call ${cls.name}.c")
            }
            val c = tna.declaredMethods.first { it.name == "c" && it.parameterTypes.size == 7 }
            module.hookMethod(c).intercept { chain ->
                val init = runCatching { iF.getBoolean(chain.thisObject) }.getOrDefault(false)
                // mys.a: (desugared) Optional<RawReadView>
                val raw = runCatching { rawF.get(chain.getArg(3))?.let { it.javaClass.getMethod("isPresent").invoke(it) as Boolean } }.getOrNull()
                runCatching { dF.get(chain.thisObject)?.let { hookModel(it) } }.onFailure { warn("Pro Zoom model: hook failed: $it") }
                log("Pro Zoom fusion: Google processor tna.c called (model initialized=$init, secondary RAW present=$raw)")
                inProcessor = last
                if (init && raw == true) last?.processorCalled = true
                else log("Pro Zoom fusion: the model will NOT run for this shot (${if (!init) "model not initialized" else "no RAW frame"}); Google promotes the original")
                chain.proceed()
            }
            // What Google's processor delivers (myl = its result callback):
            //  d = original saved before the model runs, g(.., 1) = model output
            //  as the final photo, e = no output, original promoted.
            val myl = loader.loadClass("myl")
            for (m in myl.declaredMethods) {
                val p = m.parameterTypes
                when {
                    m.name == "d" && p.size == 3 -> module.hookMethod(m).intercept { chain ->
                        log("Pro Zoom result: original saved before the model runs (shot #${(inProcessor ?: last)?.id})"); chain.proceed()
                    }
                    m.name == "e" && p.size == 1 -> module.hookMethod(m).intercept { chain ->
                        val sh = inProcessor ?: last
                        sh?.modelNoImage = true; sh?.resultTime = System.currentTimeMillis()
                        log("Pro Zoom result: NO model output; Google promotes the original (shot #${sh?.id})"); chain.proceed()
                    }
                    m.name == "g" && p.size == 4 -> module.hookMethod(m).intercept { chain ->
                        val sh = inProcessor ?: last
                        val fromModel = chain.getArg(3) == 1
                        if (fromModel) sh?.modelDelivered = true else sh?.modelNoImage = true
                        sh?.resultTime = System.currentTimeMillis()
                        log("Pro Zoom result: final image delivered (${if (fromModel) "Google's model output" else "fallback ${chain.getArg(3)}"}, shot #${sh?.id})")
                        chain.proceed()
                    }
                }
            }
            // Per-shot fusion mode decision: CENTAUR (myu.h) feeds Google's model.
            val mye = loader.loadClass("mye")
            val myvCls = loader.loadClass("myv")
            val dec = mye.declaredMethods.first { it.name == "a" && it.returnType == myvCls && it.parameterTypes.size == 4 }
            val modeM = myvCls.getDeclaredMethod("a")
            // myu is an obfuscated enum-like class: name the mode by its static field.
            val myu = loader.loadClass("myu")
            val modes = myu.declaredFields.filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == myu }
                .onEach { it.isAccessible = true }
            module.hookMethod(dec).intercept { chain ->
                val r = chain.proceed()
                val mode = runCatching { modeM.invoke(r) }.getOrNull()
                val name = modes.firstOrNull { runCatching { it.get(null) === mode }.getOrDefault(false) }?.name ?: "?"
                log("Pro Zoom fusion: mode decision = myu.$name (CENTAUR = myu.h)")
                r
            }
            // Post-processing pipeline start for a shot, with its fusion mode.
            val mre = loader.loadClass("mre")
            for (m in mre.declaredMethods.filter { it.name == "b" && it.parameterTypes.size == 6 && it.parameterTypes[5] == myvCls }) {
                module.hookMethod(m).intercept { chain ->
                    val mode = runCatching { modeM.invoke(chain.getArg(5)) }.getOrNull()
                    val name = modes.firstOrNull { runCatching { it.get(null) === mode }.getOrDefault(false) }?.name ?: "?"
                    log("Pro Zoom fusion: post-processing started with mode myu.$name")
                    chain.proceed()
                }
            }
            val myo = loader.loadClass("myo")
            for (m in myo.declaredMethods.filter { it.name == "d" && it.parameterTypes.size == 2 }) {
                module.hookMethod(m).intercept { chain -> log("Pro Zoom fusion: fusion session created (myo.d)"); chain.proceed() }
            }
            log("ProZoom: Google Pro Zoom pipeline tracing installed")
        }.onFailure { warn("ProZoom: Google pipeline hooks failed: $it") }
    }

    private enum class Kind { ORIGINAL, MODEL_OUTPUT }

    /** Buffers Google Camera's JPEG; on close writes the Pro Zoom photo instead. */
    private class ProZoomStream(
        private val target: FileOutputStream,
        private val cr: android.content.ContentResolver,
        private val uri: Uri,
        private val shot: Shot,
        private val kind: Kind,
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
            val result = try { finish(bytes, shot, kind) } catch (t: Throwable) {
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

    @Volatile private var lastSize = ""

    private fun dims(jpeg: ByteArray): String {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, o)
        return "${o.outWidth}x${o.outHeight}"
    }

    private fun finish(jpeg: ByteArray, shot: Shot, kind: Kind): ByteArray {
        if (kind == Kind.ORIGINAL) {
            val first = shot.originalSize == null
            if (first) shot.originalSize = dims(jpeg)
            val out = render(jpeg, shot)
            if (!shot.googleAttempt) record(shot, "exact crop $lastSize")
            else if (!first || shot.finalDone) record(shot, "exact crop of Google's promoted original $lastSize")
            return out
        }
        // Google's model output. It is the Pro Zoom result; if it has exactly
        // the size of the 30x original it is not zoomed yet: crop it.
        val d = dims(jpeg)
        return if (d == shot.originalSize) {
            log("ProZoom: shot #${shot.id}: model output is $d, the same size as the 30x original: cropping it to ${shot.zoom}x")
            render(jpeg, shot, "Google's model output, cropped").also { record(shot, "Google's model output, cropped to zoom $lastSize") }
        } else {
            lastResult = "${shot.zoom.toInt()}× · ENHANCED by Google's Pro Zoom model · $d · model ${shot.modelMs} ms"
            log("ProZoom: shot #${shot.id}: saved Google's Pro Zoom model output untouched ($d, ${jpeg.size / 1024} KB, model ${shot.modelMs} ms)")
            record(shot, "ENHANCED by Google's Pro Zoom model $d")
            jpeg
        }
    }

    /** Centre crop of [jpeg] to the shot's zoom at native resolution, original EXIF kept. */
    fun render(jpeg: ByteArray, shot: Shot, what: String? = null): ByteArray {
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
        // The exact crop at its native resolution (no upscaler).
        val outBmp = region
        val mode = what ?: when {
            !shot.googleAttempt -> "exact crop (Google's Pro Zoom path off)"
            shot.modelNoImage -> "exact crop; Google's model returned no image"
            shot.modelDelivered -> "exact crop of the original; Google's output follows"
            else -> "exact crop of Google's original; model still running"
        }
        val enc = ByteArrayOutputStream(4 shl 20)
        outBmp.compress(Bitmap.CompressFormat.JPEG, 95, enc)
        val size = "${outBmp.width}x${outBmp.height}"
        lastSize = size
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
        if (appContext != null) return
        appContext = ctx.applicationContext
        worker.post { Forensics.report(ctx.applicationContext) { pr, msg -> module.logMessage(pr, TAG, msg) } }
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
