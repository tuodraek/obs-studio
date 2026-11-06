package com.experimental.gcam100x

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

class MainHook : XposedModule() {

    private val installed = AtomicBoolean(false)

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        logMessage(Log.INFO, TAG, "Module loaded in process ${param.processName}")
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        logMessage(Log.INFO, TAG, "onPackageLoaded: ${param.packageName} (first=${param.isFirstPackage})")
        if (param.packageName != TARGET_PACKAGE) return
        // The framework classes we hook are shared by the whole process, so
        // install exactly once even if several packages load into it.
        if (!installed.compareAndSet(false, true)) return
        logMessage(Log.INFO, TAG, "GCam100x ${BuildConfig.VERSION_NAME} starting in ${param.packageName}")
        ManifestHook(this).install(param)
        BadgeHook(this).install(param)
        VersionHook(this).install(param)
        DiagHook(this).install()
        FlagHook(this).install(param)
        ZoomHook(this).install()
        SliderHook(this).install(param)
    }

    fun hookMethod(method: Method) = hook(method)

    /** LSPosed/Vector mirrors module logs to logcat under the same tag. */
    @Volatile private var logFile: java.io.File? = null
    private val pending = ArrayList<String>()
    @Volatile var lastError: String? = null

    /** Called once an Activity exists: log to /sdcard/Android/data/<camera>/files/gcam100x.log. */
    fun attachLogFile(f: java.io.File) {
        synchronized(this) {
            if (logFile != null) return
            try {
                f.parentFile?.mkdirs()
                f.writeText(pending.joinToString(""))
                pending.clear()
                logFile = f
            } catch (t: Throwable) {
                lastError = t.toString()
            }
        }
    }

    fun logMessage(priority: Int, tag: String, msg: String) {
        runCatching { log(priority, tag, msg) }
        // Also keep our own log in the camera's data dir (logcat buffers
        // roll over too fast): /data/data/com.google.android.GoogleCamera/files/gcam100x.log
        if (msg.startsWith("Property read") || msg.startsWith("Feature query")) return
        val line = "${java.text.SimpleDateFormat("HH:mm:ss.SSS").format(java.util.Date())} $msg\n"
        synchronized(this) {
            val f = logFile
            if (f == null) {
                if (pending.size < 5000) pending.add(line)
            } else if (f.length() < 4_000_000) {
                runCatching { f.appendText(line) }.onFailure { lastError = it.toString() }
            }
        }
    }

    companion object {
        const val TAG = "GCam100x"
        const val TARGET_PACKAGE = "com.google.android.GoogleCamera"
    }
}
