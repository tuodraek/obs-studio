package com.experimental.gcam100x

import android.os.Process
import android.util.Log
import io.github.libxposed.api.XposedModuleInterface

/**
 * Google Camera 11.1 on a Pixel 9 shows "Invalid version of Google Camera App
 * detected" (isl.a(), called from CameraApp) and then kills itself. This only
 * blocks the self-kill that comes from that check, and logs crashes.
 */
class VersionHook(private val module: MainHook) {

    companion object {
        private const val TAG = "GCam100x"
        private const val CAMERA_APP = "com.google.android.apps.camera.app.CameraApp"
        private const val CHECK_CLASS = "isl"
    }

    private fun info(msg: String) = module.logMessage(Log.INFO, TAG, msg)

    fun install(param: XposedModuleInterface.PackageLoadedParam) {
        step("exit guards") { hookExits() }
        step("crash logger") { hookCrashes() }
        step("check class") {
            val c = param.defaultClassLoader.loadClass(CHECK_CLASS)
            if (c.declaredFields.none { it.type.name == CAMERA_APP }) info("$CHECK_CLASS is not the version check in this build")
        }
        info("Version hooks installed")
    }

    private fun step(name: String, block: () -> Unit) {
        try { block() } catch (t: Throwable) { module.logMessage(Log.WARN, TAG, "Version: $name failed: $t") }
    }

    private fun hookExits() {
        val kill = Process::class.java.getDeclaredMethod("killProcess", Int::class.javaPrimitiveType)
        module.hookMethod(kill).intercept { chain ->
            if (chain.getArg(0) == Process.myPid() && fromCheck("Process.killProcess")) null else chain.proceed()
        }
        val exit = System::class.java.getDeclaredMethod("exit", Int::class.javaPrimitiveType)
        module.hookMethod(exit).intercept { chain ->
            if (fromCheck("System.exit")) null else chain.proceed()
        }
        for (name in listOf("exit", "halt")) {
            val m = Runtime::class.java.getDeclaredMethod(name, Int::class.javaPrimitiveType)
            module.hookMethod(m).intercept { chain -> if (fromCheck("Runtime.$name")) null else chain.proceed() }
        }
    }

    /** True (blocked) only for exits made by the version check itself. */
    private fun fromCheck(what: String): Boolean {
        val blocked = Throwable().stackTrace.any { it.className == CHECK_CLASS }
        info("$what called (blocked=$blocked)")
        return blocked
    }

    private fun hookCrashes() {
        val m = Thread::class.java.getDeclaredMethod("dispatchUncaughtException", Throwable::class.java)
        module.hookMethod(m).intercept { chain ->
            info("CRASH on thread ${(chain.thisObject as? Thread)?.name}: ${Log.getStackTraceString(chain.getArg(0) as? Throwable)}")
            chain.proceed()
        }
    }
}
