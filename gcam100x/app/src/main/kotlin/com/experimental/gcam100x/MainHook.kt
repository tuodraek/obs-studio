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
        FlagHook(this).install(param)
        ZoomHook(this).install()
    }

    fun hookMethod(method: Method) = hook(method)

    /** LSPosed/Vector mirrors module logs to logcat under the same tag. */
    fun logMessage(priority: Int, tag: String, msg: String) {
        runCatching { log(priority, tag, msg) }
    }

    companion object {
        const val TAG = "GCam100x"
        const val TARGET_PACKAGE = "com.google.android.GoogleCamera"
    }
}
