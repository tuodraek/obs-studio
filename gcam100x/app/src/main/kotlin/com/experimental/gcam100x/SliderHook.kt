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
