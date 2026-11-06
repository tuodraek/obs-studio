package com.experimental.gcam100x

import android.app.Activity
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import io.github.libxposed.api.XposedModuleInterface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shows that the module is active:
 *  - a toast "GCam100x <version> active" the first time a camera activity resumes;
 *  - Settings > About: wherever Google Camera displays its own version name,
 *    " · GCam100x <version>" is appended (display only, nothing else changes).
 */
class BadgeHook(private val module: MainHook) {
    private val toasted = AtomicBoolean(false)
    @Volatile private var camVersion: String? = null

    fun install(param: XposedModuleInterface.PackageLoadedParam) {
        val badge = "GCam100x ${BuildConfig.VERSION_NAME}"
        try {
            val onResume = Activity::class.java.getDeclaredMethod("onResume")
            module.hookMethod(onResume).intercept { chain ->
                val r = chain.proceed()
                val act = chain.thisObject as? Activity
                if (act != null && toasted.compareAndSet(false, true)) {
                    runCatching { Toast.makeText(act, "$badge active", Toast.LENGTH_SHORT).show() }
                }
                r
            }
        } catch (t: Throwable) {
            module.logMessage(Log.WARN, "GCam100x", "Badge toast hook failed: $t")
        }
        try {
            val setText = TextView::class.java.getDeclaredMethod(
                "setText", CharSequence::class.java, TextView.BufferType::class.java,
                Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType
            )
            module.hookMethod(setText).intercept { chain ->
                val text = chain.getArg(0) as? CharSequence
                val tv = chain.thisObject as? TextView
                val ver = camVersion ?: tv?.let { v ->
                    runCatching { v.context.packageManager.getPackageInfo(v.context.packageName, 0).versionName }
                        .getOrNull()?.also { camVersion = it }
                }
                if (text != null && ver != null && text.toString().contains(ver) && !text.toString().contains("GCam100x")) {
                    chain.proceed(arrayOf("$text · $badge", chain.getArg(1), chain.getArg(2), chain.getArg(3)))
                } else chain.proceed()
            }
        } catch (t: Throwable) {
            module.logMessage(Log.WARN, "GCam100x", "Badge text hook failed: $t")
        }
    }
}
