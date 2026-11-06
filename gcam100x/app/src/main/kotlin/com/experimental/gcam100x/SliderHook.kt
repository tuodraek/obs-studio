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
 *  - raises vgq.c() results of ~30 to 100 (rear logical camera max),
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
        hookFloat(loader, "vgq", "c") { v -> if (v in 29.5f..30.5f) TARGET else v }
        hookFloat(loader, "tmu", "a") { v -> maxOf(v, TARGET) }
        try {
            val kmd = loader.loadClass("kmd")
            for (m in kmd.declaredMethods) {
                if (m.name != "a" || m.parameterTypes.size != 2 || m.parameterTypes[0] != Any::class.java) continue
                module.hookMethod(m).intercept { chain ->
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
