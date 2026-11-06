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
                    once("kkp.a:$r", "Zoom stops: $r")
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
                        once("kmd:${list.drop(1)}", "Slider input: f=${list.getOrNull(1)} cap=${list.getOrNull(2)} config=${list[0]?.toString()?.take(300)}")
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
            val fc = f("c"); val fe = f("e"); val fg = f("g"); val fi = f("i"); val fk = f("k"); val fm = f("m")
            val fo = f("o"); val fp = f("p")
            // tnn is an obfuscated enum-like class: a=NOT_ALLOWED,
            // b=NOT_ALLOWED_MODEL_UNAVAILABLE, c=ALLOWED.
            val allowed = loader.loadClass("tnn").getDeclaredField("c").apply { isAccessible = true }.get(null)
            // vhd is an obfuscated enum-like class: vhd.b == BACK.
            val vhdBack = loader.loadClass("vhd").getDeclaredField("b").apply { isAccessible = true }.get(null)
            var lastDesc = ""
            module.hookMethod(o).intercept { chain ->
                val r = chain.proceed()
                val t = chain.thisObject
                val mode = runCatching { dM.invoke(fc.get(t)) }.getOrNull()
                val cam = runCatching { dM.invoke(fe.get(t)) }.getOrNull()
                val model = runCatching { dM.invoke(fg.get(t)) }.getOrNull()
                val m = runCatching { dM.invoke(fm.get(t)) }.getOrNull()
                val i = runCatching { dM.invoke(fi.get(t)) }.getOrNull()
                val k = runCatching { dM.invoke(fk.get(t)) }.getOrNull()
                val en = fo.getBoolean(t); val p = fp.getBoolean(t)
                val back = cam === vhdBack
                model?.let { ProZoom.modelState = it.toString() }
                val desc = "result=$r enabled=$en mode=$mode camera=$cam model=$model aiZoomOk(m)=$m i=$i p=$p k=$k"
                if (desc != lastDesc) { lastDesc = desc; module.logMessage(Log.INFO, TAG, "Pro Zoom state inputs: $desc") }
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
                (live ?: cur)?.let { t ->
                    runCatching {
                        val b = tnm.getDeclaredField("b").getBoolean(t)
                        val z = tnm.getDeclaredField("c").getFloat(t)
                        val sc = tnm.getDeclaredField("d").getFloat(t)
                        if (b) ProZoom.onShot(sc, z)
                        else if (lastZoom > 30f && System.currentTimeMillis() - lastZoomTime < 5000) ProZoom.onShot(30f / lastZoom, lastZoom)
                    }
                }
                // Keep the still on Google Camera's normal 30x HDR+ / Night Sight
                // path. An active tnm makes it a CENTAUR session, whose output
                // is the raw burst merge meant for Google's Pro Zoom model
                // (which cannot run here): noisy and unfinished. Pro Zoom is
                // applied to the finished photo in the save pipeline instead.
                val shotTnm = live ?: cur
                val active = shotTnm != null && runCatching { tnm.getDeclaredField("b").getBoolean(shotTnm) }.getOrDefault(false)
                if (active) {
                    val z = tnm.getDeclaredField("c").getFloat(shotTnm)
                    val plain = runCatching {
                        building.set(true)
                        try { tnmCtor.newInstance(false, z, 1f) } finally { building.set(false) }
                    }.onFailure { module.logMessage(Log.WARN, TAG, "Shot: cannot build inactive tnm: $it") }.getOrNull()
                    if (plain != null) {
                        val args = Array<Any?>(ctor.parameterTypes.size) { chain.getArg(it) }
                        args[idx] = plain
                        module.logMessage(Log.INFO, TAG, "Shot: normal HDR+ capture at 30x; Pro Zoom ${z}x applied on save")
                        return@intercept chain.proceed(args)
                    }
                }
                chain.proceed()
            }
            module.logMessage(Log.INFO, TAG, "Capture hook on pcl(tnm @ $idx)")
        }.onFailure { module.logMessage(Log.WARN, TAG, "pcl hook failed: $it") }
        runCatching {
            val tna = loader.loadClass("tna")
            val iF = tna.getDeclaredField("i").apply { isAccessible = true }
            for (m in tna.declaredMethods) {
                if (m.name == "c" && m.parameterTypes.size == 7) {
                    module.hookMethod(m).intercept { chain ->
                        module.logMessage(Log.INFO, TAG, "Pro Zoom processor tna.c: shot=${chain.getArg(0)} initialized=${iF.get(chain.thisObject)} primary=${chain.getArg(2)} secondary=${chain.getArg(3)}")
                        try { chain.proceed() } catch (t: Throwable) {
                            module.logMessage(Log.WARN, TAG, "tna.c threw ${Log.getStackTraceString(t)}"); throw t
                        }
                    }
                }
                if (m.name == "j" && m.parameterTypes.size == 1) {
                    module.hookMethod(m).intercept { chain ->
                        // The shot is a normal HDR+ capture now (see pcl hook);
                        // Google's Pro Zoom processor has no model to run here.
                        if (m.returnType == Void.TYPE) {
                            module.logMessage(Log.INFO, TAG, "Pro Zoom processor tna.j skipped (normal HDR+ shot)")
                            return@intercept null
                        }
                        try { chain.proceed() } catch (t: Throwable) {
                            module.logMessage(Log.WARN, TAG, "tna.j threw ${Log.getStackTraceString(t)}"); throw t
                        }
                    }
                }
                if (m.name == "a" && m.parameterTypes.size == 2 && m.parameterTypes[0] == java.lang.Long.TYPE) {
                    module.hookMethod(m).intercept { chain ->
                        module.logMessage(Log.INFO, TAG, "tna.a(shot=${chain.getArg(0)}) result")
                        chain.proceed()
                    }
                }
                if (m.name == "i" && m.parameterTypes.isEmpty()) {
                    module.hookMethod(m).intercept { chain ->
                        module.logMessage(Log.INFO, TAG, "Pro Zoom processor init (tna.i)")
                        try { chain.proceed().also { module.logMessage(Log.INFO, TAG, "tna.i done, initialized=${iF.get(chain.thisObject)}") } } catch (t: Throwable) {
                            module.logMessage(Log.WARN, TAG, "tna.i threw ${Log.getStackTraceString(t)}"); throw t
                        }
                    }
                }
            }
        }.onFailure { module.logMessage(Log.WARN, TAG, "tna hook failed: $it") }
        runCatching {
            // Photo capture session type (rce): CENTAUR for Pro Zoom shots.
            val rbb = loader.loadClass("rbb")
            val rce = loader.loadClass("rce")
            for (c in rbb.declaredConstructors) {
                val ri = c.parameterTypes.indexOf(rce)
                if (ri < 0) continue
                module.hookCtor(c).intercept { chain ->
                    module.logMessage(Log.INFO, TAG, "Capture session type: ${chain.getArg(ri)}")
                    chain.proceed()
                }
            }
        }.onFailure { module.logMessage(Log.WARN, TAG, "rbb hook failed: $it") }
        runCatching {
            // Fusion mode selection result per shot.
            val myh = loader.loadClass("myh")
            val apply = myh.getDeclaredMethod("apply", Any::class.java)
            val dF = myh.getDeclaredField("d").apply { isAccessible = true }
            val eF = myh.getDeclaredField("e").apply { isAccessible = true }
            module.hookMethod(apply).intercept { chain ->
                val r = chain.proceed()
                if (runCatching { eF.getInt(chain.thisObject) }.getOrDefault(1) == 0) {
                    val mode = runCatching { dF.get(chain.thisObject)?.let { it.javaClass.getMethod("a").invoke(it) } }.getOrNull()
                    module.logMessage(Log.INFO, TAG, "Shot fusion mode: $mode")
                }
                r
            }
        }.onFailure { module.logMessage(Log.WARN, TAG, "myh hook failed: $it") }
    }

    /** Diagnostics for the beyond-30x viewfinder path. */
    private fun hookViewfinderDiag(loader: ClassLoader) {
        // tnm(active, zoom, previewScale): previewScale = threshold/zoom when active.
        runCatching {
            val tnm = loader.loadClass("tnm")
            val ctor = tnm.getDeclaredConstructor(java.lang.Boolean.TYPE, java.lang.Float.TYPE, java.lang.Float.TYPE)
            var last = ""
            module.hookCtor(ctor).intercept { chain ->
                if (building.get() == true) return@intercept chain.proceed()
                val r = chain.proceed()
                (chain.getArg(1) as? Float)?.let { z -> if (z > 0f) { lastZoom = z; lastZoomTime = System.currentTimeMillis() } }
                ProZoom.liveScale = if (chain.getArg(0) == true) ((chain.getArg(2) as? Float) ?: 1f) else 1f
                if (chain.getArg(0) == true) lastActiveTnm = chain.thisObject
                else if ((chain.getArg(1) as? Float ?: 0f) > 0f) lastActiveTnm = null
                val desc = "active=${chain.getArg(0)} zoom=${chain.getArg(1)} scale=${chain.getArg(2)}"
                val zoom = (chain.getArg(1) as? Float) ?: 0f
                if (zoom >= 20f && desc != last) { last = desc; module.logMessage(Log.INFO, TAG, "Pro Zoom state: $desc") }
                r
            }
        }.onFailure { module.logMessage(Log.WARN, TAG, "tnm diag failed: $it") }
        // thc = viewfinder-extension component (created only if Pro Zoom + VFE enabled).
        runCatching {
            val thc = loader.loadClass("thc")
            for (c in thc.declaredConstructors) {
                module.hookCtor(c).intercept { chain ->
                    once("thc", "Viewfinder extension (thc) created")
                    chain.proceed()
                }
            }
        }.onFailure { module.logMessage(Log.WARN, TAG, "thc diag failed: $it") }
        // tmr.apply: case 1 = (zoom >= threshold), case 0 builds tnm.
        runCatching {
            val tmr = loader.loadClass("tmr")
            val apply = tmr.getDeclaredMethod("apply", Any::class.java)
            val bF = tmr.getDeclaredField("b").apply { isAccessible = true }
            val aF = tmr.getDeclaredField("a").apply { isAccessible = true }
            val tmuCls = loader.loadClass("tmu")
            val thrF = tmuCls.getDeclaredField("b").apply { isAccessible = true }
            module.hookMethod(apply).intercept { chain ->
                val kind0 = runCatching { bF.getInt(chain.thisObject) }.getOrDefault(-1)
                val r = chain.proceed()
                val kind = kind0
                if (kind == 0 || kind == 1) {
                    val inp = chain.getArg(0).toString()
                    if (inp.contains(Regex("\\b(2[5-9]|[3-9][0-9]|100)\\."))) once("tmr$kind:$inp:$r", "tmr[$kind]($inp) = $r")
                }
                r
            }
        }.onFailure { module.logMessage(Log.WARN, TAG, "tmr diag failed: $it") }
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
