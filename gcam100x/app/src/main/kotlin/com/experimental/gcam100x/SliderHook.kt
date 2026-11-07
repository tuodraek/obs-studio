package com.experimental.gcam100x

import android.util.Log
import io.github.libxposed.api.XposedModuleInterface
import java.util.Collections

/**
 * Google Camera 11.1.040 zoom-slider limit (obfuscated names, 11.1.040 only).
 *
 * kmd.a(List, cont) computes the slider maximum:
 *   max  = (boba UI on && PHOTO && back camera) ? max(tmu.a(), cam.c()) : cam.c()
 *   cap  = min(max of the zoom UI config stops, list[2] if present)
 *   f5   = min(cap, max)
 * where cam.c() (vgq.c) is the camera's CONTROL_ZOOM_RATIO_RANGE upper bound
 * and tmu.a() is the Pro Zoom level (camera.boba_jelly_level = 100).
 *
 * This hook:
 *  - leaves vgq.c() (real max, 30) alone: kkp.f() takes the Pro Zoom branch
 *    only when tmu.a() > vgq.c() and kkp.j() is true,
 *  - raises tmu.a() to at least 100,
 *  - logs kmd's inputs and replaces a ~30 cap in list[2] with 100.
 */
class SliderHook(private val module: MainHook) {
    companion object {
        private const val TAG = "GCam100x"
        private const val TARGET = 100f
    }

    private val logged: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private fun once(key: String, msg: String) {
        if (logged.add(key)) module.logMessage(Log.INFO, TAG, msg)
    }

    fun install(param: XposedModuleInterface.PackageLoadedParam) {
        val loader = param.defaultClassLoader
        hookFloat(loader, "tmu", "a") { v -> maxOf(v, TARGET) }
        hookViewfinderDiag(loader)
        hookProZoomState(loader)
        hookCapture(loader)
        // kkp.a() builds the zoom stops; it only adds the Pro Zoom level when
        // kkp.v (a "Pro Zoom UI active" observable) is true. kmd reads the same
        // observable as ub.i. Wrap both so d() reports true.
        try {
            val kkp = loader.loadClass("kkp")
            // kkp.j() = v.d() && mode == PHOTO && back camera. Hook it directly:
            // kkp.f() (zoom config builder) calls it before kkp.a() runs.
            val jm = kkp.getDeclaredMethod("j")
            // Enum field names are obfuscated; match the constant by its name().
            val tbpPhoto = loader.loadClass("tbp").enumConstants!!.first { (it as Enum<*>).name == "PHOTO" }
            val lF = kkp.getDeclaredField("l").apply { isAccessible = true }
            val mF = kkp.getDeclaredField("m").apply { isAccessible = true }
            val urt = loader.loadClass("urt")
            val dM = urt.getMethod("d")
            val jutB = loader.loadClass("jut").getMethod("b")
            module.hookMethod(jm).intercept { chain ->
                val orig = chain.proceed() as Boolean
                if (orig) return@intercept true
                val forced = try {
                    val mode = dM.invoke(lF.get(chain.thisObject))
                    val back = jutB.invoke(dM.invoke(mF.get(chain.thisObject))) as Boolean
                    mode == tbpPhoto && back
                } catch (t: Throwable) {
                    once("jerr", "kkp.j() eval failed: $t"); false
                }
                if (forced) once("jforced", "Pro Zoom slider gate kkp.j(): false -> true (photo, back camera)")
                forced
            }
            for (m in kkp.declaredMethods) {
                if (m.name != "a" || m.parameterTypes.isNotEmpty() || m.returnType != java.util.List::class.java) continue
                module.hookMethod(m).intercept { chain ->
                    forceTrue(loader, chain.thisObject, "v")
                    val r = chain.proceed()
                    r
                }
            }
        } catch (t: Throwable) {
            module.logMessage(Log.WARN, TAG, "kkp hook failed: $t")
        }
        try {
            val kmd = loader.loadClass("kmd")
            for (m in kmd.declaredMethods) {
                if (m.name != "a" || m.parameterTypes.size != 2 || m.parameterTypes[0] != Any::class.java) continue
                module.hookMethod(m).intercept { chain ->
                    try {
                        val kmo = chain.thisObject.javaClass.getDeclaredField("a").apply { isAccessible = true }.get(chain.thisObject)
                        val ub = kmo?.javaClass?.getDeclaredField("ai")?.apply { isAccessible = true }?.get(kmo)
                        if (ub != null) forceTrue(loader, ub, "i")
                    } catch (t: Throwable) {
                        once("kmdwrap", "kmd ub.i wrap failed: $t")
                    }
                    val list = chain.getArg(0) as? List<*>
                    if (list != null && list.size >= 3) {
                        val cap = (list[2] as? Float)
                        if (cap != null && cap < TARGET && cap >= 25f) {
                            val copy = ArrayList(list)
                            copy[2] = TARGET
                            return@intercept chain.proceed(arrayOf(copy, chain.getArg(1)))
                        }
                    }
                    chain.proceed()
                }
                module.logMessage(Log.INFO, TAG, "Slider hook on $m")
            }
        } catch (t: Throwable) {
            module.logMessage(Log.WARN, TAG, "kmd hook failed: $t")
        }
    }

    /** Replaces obj.<field> (a urt observable) with a proxy whose d() returns true. */
    private fun forceTrue(loader: ClassLoader, obj: Any?, field: String) {
        if (obj == null) return
        val f = obj.javaClass.getDeclaredField(field).apply { isAccessible = true }
        val cur = f.get(obj) ?: return
        if (java.lang.reflect.Proxy.isProxyClass(cur.javaClass)) return
        val urt = loader.loadClass("urt")
        if (!urt.isInstance(cur)) { once("nourt$field", "${obj.javaClass.name}.$field is ${cur.javaClass.name}, not urt"); return }
        val orig = runCatching { urt.getMethod("d").invoke(cur) }.getOrNull()
        val proxy = java.lang.reflect.Proxy.newProxyInstance(loader, arrayOf(urt)) { _, m, args ->
            if (m.name == "d" && (args == null || args.isEmpty())) java.lang.Boolean.TRUE
            else m.invoke(cur, *(args ?: emptyArray()))
        }
        try {
            f.set(obj, proxy)
        } catch (t: Throwable) {
            // final field: use Unsafe-free fallback via accessible Field already set; report.
            once("setfail$field", "Cannot replace ${obj.javaClass.name}.$field: $t"); return
        }
        once("wrap${obj.javaClass.name}.$field", "Forced ${obj.javaClass.name}.$field (Pro Zoom UI gate) true; original=$orig")
    }

    /**
     * tmp.o() decides the Pro Zoom state (tnn): NOT_ALLOWED,
     * NOT_ALLOWED_MODEL_UNAVAILABLE or ALLOWED. It needs: Pro Zoom enabled
     * (o), PHOTO mode (c), AI-zoom state >= INACTIVE_ALLOWED (m), !(i && !p),
     * back camera (e), !k, and model state (g) == UP_TO_DATE for ALLOWED.
     * Only ALLOWED turns on the beyond-30x viewfinder renderer and capture.
     * Log every input, and return ALLOWED when Pro Zoom is on in Photo mode
     * on the back camera.
     */
    private fun hookProZoomState(loader: ClassLoader) {
        runCatching {
            val tmp = loader.loadClass("tmp")
            val o = tmp.getDeclaredMethod("o")
            val urt = loader.loadClass("urt"); val dM = urt.getMethod("d")
            fun f(n: String) = tmp.getDeclaredField(n).apply { isAccessible = true }
            val fc = f("c"); val fe = f("e"); val fg = f("g"); val fo = f("o")
            // tnn is an obfuscated enum-like class: a=NOT_ALLOWED,
            // b=NOT_ALLOWED_MODEL_UNAVAILABLE, c=ALLOWED.
            val allowed = loader.loadClass("tnn").getDeclaredField("c").apply { isAccessible = true }.get(null)
            // vhd is an obfuscated enum-like class: vhd.b == BACK.
            val vhdBack = loader.loadClass("vhd").getDeclaredField("b").apply { isAccessible = true }.get(null)
            module.hookMethod(o).intercept { chain ->
                val r = chain.proceed()
                val t = chain.thisObject
                val mode = runCatching { dM.invoke(fc.get(t)) }.getOrNull()
                val cam = runCatching { dM.invoke(fe.get(t)) }.getOrNull()
                val model = runCatching { dM.invoke(fg.get(t)) }.getOrNull()
                val en = fo.getBoolean(t)
                val back = cam === vhdBack
                model?.let { ProZoom.modelState = it.toString() }
                val isPhoto = (mode as? Enum<*>)?.name == "PHOTO" || mode?.toString() == "PHOTO"
                if (r != allowed && en && isPhoto && back) {
                    once("allowed", "Pro Zoom state: $r -> ALLOWED")
                    allowed
                } else r
            }
            module.logMessage(Log.INFO, TAG, "Pro Zoom state hook on tmp.o()")
        }.onFailure { module.logMessage(Log.WARN, TAG, "tmp.o hook failed: $it") }
    }

    @Volatile private var lastActiveTnm: Any? = null
    /** Set while this module builds its own tnm (not a viewfinder state change). */
    private val building = ThreadLocal.withInitial { false }
    @Volatile private var lastZoom = 0f
    @Volatile private var lastZoomTime = 0L

    /**
     * Capture: mye picks the CENTAUR (Pro Zoom) fusion for a shot only when the
     * shot's pcl.n (a tnm) is active. In this build pcl.n is only ever set to
     * the inactive default, so put the live Pro Zoom state on each shot, and
     * log what the Pro Zoom processor (tna.c) does with it.
     */
    private fun hookCapture(loader: ClassLoader) {
        runCatching {
            val pcl = loader.loadClass("pcl")
            val tnm = loader.loadClass("tnm")
            val ctor = pcl.declaredConstructors.first { it.parameterTypes.size == 18 }
            val idx = ctor.parameterTypes.indexOf(tnm)
            val tnmCtor = tnm.getDeclaredConstructor(java.lang.Boolean.TYPE, java.lang.Float.TYPE, java.lang.Float.TYPE).apply { isAccessible = true }
            module.hookCtor(ctor).intercept { chain ->
                val live = lastActiveTnm
                val cur = chain.getArg(idx)
                module.logMessage(Log.INFO, TAG, "Shot created: Pro Zoom state on shot = $cur (live active = $live)")
                // Queue the beyond-30x crop for the saved photo.
                var proShot: ProZoom.Shot? = null
                (live ?: cur)?.let { t ->
                    runCatching {
                        val b = tnm.getDeclaredField("b").getBoolean(t)
                        val z = tnm.getDeclaredField("c").getFloat(t)
                        val sc = tnm.getDeclaredField("d").getFloat(t)
                        val shot = if (b) ProZoom.onShot(sc, z)
                            else if (lastZoom > 30f && System.currentTimeMillis() - lastZoomTime < 5000) ProZoom.onShot(30f / lastZoom, lastZoom)
                            else null
                        proShot = shot
                    }
                }
                // Keep the still on Google Camera's normal 30x HDR+ / Night Sight
                // path. An active tnm makes it a CENTAUR session, whose output
                // is the raw burst merge meant for Google's Pro Zoom model
                // (which cannot run here): noisy and unfinished. Pro Zoom is
                // applied to the finished photo in the save pipeline instead.
                val shotTnm = live ?: cur
                val active = shotTnm != null && runCatching { tnm.getDeclaredField("b").getBoolean(shotTnm) }.getOrDefault(false)
                if (active && !ProZoom.GOOGLE_PRO_ZOOM) {
                    val z = tnm.getDeclaredField("c").getFloat(shotTnm)
                    val plain = runCatching {
                        building.set(true)
                        try { tnmCtor.newInstance(false, z, 1f) } finally { building.set(false) }
                    }.onFailure { module.logMessage(Log.WARN, TAG, "Shot: cannot build inactive tnm: $it") }.getOrNull()
                    if (plain != null) {
                        val args = Array<Any?>(ctor.parameterTypes.size) { chain.getArg(it) }
                        args[idx] = plain
                        module.logMessage(Log.INFO, TAG, "Shot: normal HDR+ capture at 30x; Pro Zoom ${z}x applied on save")
                        val r = chain.proceed(args)
                        proShot?.let { ProZoom.pclShots[chain.thisObject] = it }
                        return@intercept r
                    }
                }
                if (active) {
                    module.logMessage(Log.INFO, TAG, "Shot: Google Pro Zoom (CENTAUR) capture, ${proShot?.zoom}x")
                    proShot?.googleAttempt = true
                    Guard.shotStarted("${proShot?.zoom}x at ${java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date())}")
                }
                val r = chain.proceed()
                proShot?.let { ProZoom.pclShots[chain.thisObject] = it }
                r
            }
            module.logMessage(Log.INFO, TAG, "Capture hook on pcl(tnm @ $idx)")
        }.onFailure { module.logMessage(Log.WARN, TAG, "pcl hook failed: $it") }
        runCatching {
            // Google's Pro Zoom processor: the shot is a normal HDR+ capture
            // (see above) and its model cannot run here, so it is not attached.
            val tna = loader.loadClass("tna")
            for (m in tna.declaredMethods) {
                if (m.name == "j" && m.parameterTypes.size == 1 && m.returnType == Void.TYPE) {
                    module.hookMethod(m).intercept { chain -> if (ProZoom.GOOGLE_PRO_ZOOM) chain.proceed() else null }
                }
            }
        }.onFailure { module.logMessage(Log.WARN, TAG, "tna hook failed: $it") }
    }

    @Volatile private var lastLoggedActive = false

    /** Live Pro Zoom state from the viewfinder (tnm). */
    private fun hookViewfinderDiag(loader: ClassLoader) {
        // tnm(active, zoom, previewScale): previewScale = threshold/zoom when active.
        runCatching {
            val tnm = loader.loadClass("tnm")
            val ctor = tnm.getDeclaredConstructor(java.lang.Boolean.TYPE, java.lang.Float.TYPE, java.lang.Float.TYPE)
            module.hookCtor(ctor).intercept { chain ->
                if (building.get() == true) return@intercept chain.proceed()
                val r = chain.proceed()
                (chain.getArg(1) as? Float)?.let { z -> if (z > 0f) { lastZoom = z; lastZoomTime = System.currentTimeMillis() } }
                ProZoom.liveScale = if (chain.getArg(0) == true) ((chain.getArg(2) as? Float) ?: 1f) else 1f
                if (chain.getArg(0) == true) lastActiveTnm = chain.thisObject
                else if ((chain.getArg(1) as? Float ?: 0f) > 0f) lastActiveTnm = null
                val active = chain.getArg(0) == true
                if (active != lastLoggedActive) { lastLoggedActive = active; module.logMessage(Log.INFO, TAG, "Pro Zoom ${if (active) "on" else "off"} (${chain.getArg(1)}x)") }
                r
            }
        }.onFailure { module.logMessage(Log.WARN, TAG, "tnm diag failed: $it") }
    }

    private fun hookFloat(loader: ClassLoader, cls: String, name: String, f: (Float) -> Float) {
        try {
            val m = loader.loadClass(cls).getDeclaredMethod(name)
            if (m.returnType != java.lang.Float.TYPE) {
                module.logMessage(Log.WARN, TAG, "$cls.$name is not float; skipped"); return
            }
            module.hookMethod(m).intercept { chain ->
                val v = chain.proceed() as Float
                val r = f(v)
                if (r != v) once("$cls.$name:$v", "$cls.$name(): $v -> $r")
                r
            }
        } catch (t: Throwable) {
            module.logMessage(Log.WARN, TAG, "$cls.$name hook failed: $t")
        }
    }
}
