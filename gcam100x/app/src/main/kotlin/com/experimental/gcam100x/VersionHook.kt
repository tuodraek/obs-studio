package com.experimental.gcam100x

import android.os.Process
import android.util.Log
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections

/**
 * Google Camera 11.1 refuses to start on Pixel 9 ("Invalid version of Google
 * Camera App detected"). DiagHook traced the message to:
 *
 *   CameraContentProvider.onCreate -> CameraApp.d -> CameraApp.i
 *     -> adoc.a  (shows the message via isl.a -> Toast)
 *
 * after which the process dies and Android restarts it in a loop.
 *
 * This hook:
 *  1. Logs what the check looks at: every PackageManager.hasSystemFeature
 *     query and every SystemProperties key the app reads (once each, with the
 *     answer), so we can see which device property makes it "invalid".
 *  2. Logs the signatures of CameraApp, adoc and isl (obfuscated names
 *     from 11.1.040), plus every call into adoc with args/result/exception.
 *  3. Bypass: when adoc's void method is called from CameraApp (the version
 *     check's "show error and quit" path), skips it.
 *  4. Blocks the app killing itself (Process.killProcess(own pid),
 *     System.exit, Runtime.exit/halt) after the check has fired, and logs
 *     who tried.
 *
 * The obfuscated names only match 11.1.040; on other versions the classes
 * are either missing or the CameraApp caller check fails, so nothing is
 * skipped.
 */
class VersionHook(private val module: MainHook) {

    companion object {
        private const val TAG = "GCam100x"
        private const val CAMERA_APP = "com.google.android.apps.camera.app.CameraApp"
        private const val CHECK_CLASS = "adoc"
        private const val TOAST_CLASS = "isl"

        /** Set once the version check has fired (message shown or bypassed). */
        @Volatile
        var tripped = false
    }

    private val loggedFeatures: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private val loggedProps: MutableSet<String> = Collections.synchronizedSet(HashSet())

    private fun info(msg: String) = module.logMessage(Log.INFO, TAG, msg)

    fun install(param: XposedModuleInterface.PackageLoadedParam) {
        val loader = param.defaultClassLoader
        step("signature dump") { dumpClasses(loader) }
        step("adoc hook") { hookCheckClass(loader) }
        step("hasSystemFeature") { hookSystemFeatures() }
        step("SystemProperties") { hookSystemProperties() }
        step("exit guards") { hookExits() }
        info("Version hooks installed")
    }

    private fun step(name: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            module.logMessage(Log.WARN, TAG, "Version: $name failed: $t")
        }
    }

    // --- 1. What the check looks at -------------------------------------------

    private fun hookSystemFeatures() {
        val pm = Class.forName("android.app.ApplicationPackageManager")
        for (m in pm.declaredMethods) {
            if (m.name != "hasSystemFeature") continue
            module.hookMethod(m).intercept { chain ->
                val result = chain.proceed()
                val name = chain.getArg(0) as? String
                if (name != null && loggedFeatures.add(name)) {
                    info("Feature query: $name = $result")
                }
                result
            }
        }
    }

    private fun hookSystemProperties() {
        val sp = Class.forName("android.os.SystemProperties")
        for (m in sp.declaredMethods) {
            if (!Modifier.isStatic(m.modifiers) || !m.name.startsWith("get")) continue
            if (m.parameterTypes.firstOrNull() != String::class.java) continue
            module.hookMethod(m).intercept { chain ->
                val result = chain.proceed()
                val key = chain.getArg(0) as? String
                if (key != null && loggedProps.add(key)) {
                    info("Property read: $key = $result")
                }
                result
            }
        }
    }

    // --- 2. Signatures ---------------------------------------------------------

    private fun dumpClasses(loader: ClassLoader) {
        for (name in listOf(CAMERA_APP, CHECK_CLASS, TOAST_CLASS)) {
            val c = try {
                loader.loadClass(name)
            } catch (_: Throwable) {
                info("Class $name not present")
                continue
            }
            val sb = StringBuilder("Class $name extends ${c.superclass?.name}:")
            for (f in c.declaredFields) sb.append("\n    field ").append(f.toGenericString())
            for (m in c.declaredMethods) sb.append("\n    ").append(m.toGenericString())
            info(sb.toString())
        }
    }

    // --- 3. Bypass ------------------------------------------------------------

    private fun hookCheckClass(loader: ClassLoader) {
        val c = try {
            loader.loadClass(CHECK_CLASS)
        } catch (_: Throwable) {
            return
        }
        for (m in c.declaredMethods) {
            if (Modifier.isAbstract(m.modifiers)) continue
            hookCheckMethod(m)
        }
    }

    private fun hookCheckMethod(m: Method) {
        module.hookMethod(m).intercept { chain ->
            val fromCameraApp = Throwable().stackTrace.take(12).any { it.className == CAMERA_APP }
            val args = (0 until m.parameterTypes.size).joinToString { describe(chain.getArg(it)) }
            if (fromCameraApp && m.returnType == Void.TYPE) {
                tripped = true
                info("Version check: skipped $m from CameraApp (args: $args)")
                return@intercept null
            }
            try {
                val r = chain.proceed()
                if (fromCameraApp) info("Version check: $m($args) returned ${describe(r)}")
                r
            } catch (t: Throwable) {
                info("Version check: $m($args) threw ${Log.getStackTraceString(t)}")
                throw t
            }
        }
    }

    private fun describe(v: Any?): String =
        v?.toString()?.let { if (it.length > 200) it.take(200) + "..." else it } ?: "null"

    // --- 4. Exit guards -------------------------------------------------------

    private fun hookExits() {
        val kill = Process::class.java.getDeclaredMethod("killProcess", Int::class.javaPrimitiveType)
        module.hookMethod(kill).intercept { chain ->
            if (chain.getArg(0) == Process.myPid() && blockExit("Process.killProcess")) null
            else chain.proceed()
        }
        val exit = System::class.java.getDeclaredMethod("exit", Int::class.javaPrimitiveType)
        module.hookMethod(exit).intercept { chain ->
            if (blockExit("System.exit(${chain.getArg(0)})")) null else chain.proceed()
        }
        for (name in listOf("exit", "halt")) {
            val m = Runtime::class.java.getDeclaredMethod(name, Int::class.javaPrimitiveType)
            module.hookMethod(m).intercept { chain ->
                if (blockExit("Runtime.$name(${chain.getArg(0)})")) null else chain.proceed()
            }
        }
    }

    /** Always logs the caller; blocks only once the version check has fired. */
    private fun blockExit(what: String): Boolean {
        val frames = Throwable().stackTrace
            .filterNot { it.className.startsWith("com.experimental.gcam100x") || it.className.startsWith("dalvik.") }
            .take(20)
        info("$what called (blocked=$tripped) from:\n    " + frames.joinToString("\n    "))
        return tripped
    }
}
