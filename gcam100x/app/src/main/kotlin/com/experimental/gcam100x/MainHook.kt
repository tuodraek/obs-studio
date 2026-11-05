package com.experimental.gcam100x

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

class MainHook : XposedModule() {

    private val installed = AtomicBoolean(false)

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        if (param.packageName != TARGET_PACKAGE) return
        // The framework classes we hook are shared by the whole process, so
        // install exactly once even if several packages load into it.
        if (!installed.compareAndSet(false, true)) return
        ZoomHook(this).install()
    }

    fun hookMethod(method: Method) = hook(method)

    fun logMessage(priority: Int, tag: String, msg: String) = log(priority, tag, msg)

    companion object {
        const val TARGET_PACKAGE = "com.google.android.GoogleCamera"
    }
}
