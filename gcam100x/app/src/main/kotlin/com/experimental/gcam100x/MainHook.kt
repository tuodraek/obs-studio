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
        // As early as possible: the native crash guard must be in place before
        // Google Camera loads libgcastartup.so.
        Native.load(runCatching { moduleApplicationInfo.sourceDir }.getOrNull()) { pr, msg -> logMessage(pr, TAG, msg) }
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        logMessage(Log.INFO, TAG, "onPackageLoaded: ${param.packageName} (first=${param.isFirstPackage})")
        if (param.packageName != TARGET_PACKAGE) return
        // The framework classes we hook are shared by the whole process, so
        // install exactly once even if several packages load into it.
        if (!installed.compareAndSet(false, true)) return
        logMessage(Log.INFO, TAG, "GCam100x ${BuildConfig.VERSION_NAME} starting in ${param.packageName}")
        Guard.load { logMessage(Log.INFO, TAG, it) }
        ManifestHook(this).install(param)
        ProZoom.init(this)
        ProZoom.install(param.defaultClassLoader)
        BadgeHook(this).install(param)
        VersionHook(this).install(param)
        FlagHook(this).install(param)
        ZoomHook(this).install()
        SliderHook(this).install(param)
        SettingsHook(this).install(param)
        logMessage(Log.INFO, TAG, "Startup complete: ${hookCount()} hooks installed")
        logMessage(Log.INFO, TAG, "Hooks: " + synchronized(hooks) { hooks.groupingBy { it }.eachCount().entries.joinToString { (k, v) -> if (v > 1) "$k x$v" else k } })
    }

    // --- Hooks: every installed hook is counted and listed in the log -------

    private val hooks = java.util.Collections.synchronizedList(ArrayList<String>())
    fun hookMethod(method: Method) = hook(method).also { hooks.add("${method.declaringClass.name}.${method.name}") }
    fun hookCtor(c: java.lang.reflect.Constructor<*>) = hook(c).also { hooks.add("${c.declaringClass.name}.<init>") }
    fun hookCount() = hooks.size

    // --- Log -----------------------------------------------------------------
    //
    // One file in Google Camera's own data dir, written synchronously and
    // flushed per line, so the last lines before a native crash are on disk.
    // Previous sessions are kept (rolled at 3 MB, keeping the newest 1 MB).
    // Also mirrored to logcat (tag GCam100x) by LSPosed/Vector.

    @Volatile private var logOut: java.io.FileOutputStream? = null
    @Volatile private var logFile: java.io.File? = null
    fun logFilePath(): String? = logFile?.absolutePath
    private val pending = ArrayList<String>()
    @Volatile var lastError: String? = null
    private val stamp = java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US)

    private fun open(f: java.io.File): Boolean = try {
        f.parentFile?.mkdirs()
        if (f.exists() && f.length() > 3_000_000) {
            val t = f.readBytes()
            f.writeBytes(t.copyOfRange(t.size - 1_000_000, t.size))
        }
        val out = java.io.FileOutputStream(f, true)
        val head = StringBuilder("\n===== camera session ${stamp.format(java.util.Date())} pid ${android.os.Process.myPid()} · GCam100x ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · " +
            "${android.os.Build.MODEL} (${android.os.Build.DEVICE}) Android ${android.os.Build.VERSION.RELEASE} ${android.os.Build.ID} =====\n")
        for (l in pending) head.append(l)
        pending.clear()
        out.write(head.toString().toByteArray()); out.flush()
        logOut = out; logFile = f
        true
    } catch (t: Throwable) { lastError = t.toString(); false }

    /** Called once an Activity exists, if the data-dir log could not be opened at start. */
    fun attachLogFile(f: java.io.File) {
        synchronized(this) { if (logOut == null) open(f) }
    }

    fun logMessage(priority: Int, tag: String, msg: String) {
        runCatching { log(priority, tag, msg) }
        val level = when (priority) { Log.ERROR -> "E"; Log.WARN -> "W"; else -> "I" }
        val line = "${stamp.format(java.util.Date())} $level [${Thread.currentThread().name}] $msg\n"
        synchronized(this) {
            if (logOut == null && logFile == null && !triedDataDir) {
                triedDataDir = true
                open(java.io.File("/data/user/0/$TARGET_PACKAGE/files/gcam100x.log"))
            }
            val out = logOut
            if (out == null) { if (pending.size < 5000) pending.add(line); return }
            try { out.write(line.toByteArray()); out.flush() } catch (t: Throwable) { lastError = t.toString() }
        }
    }
    private var triedDataDir = false

    companion object {
        const val TAG = "GCam100x"
        const val TARGET_PACKAGE = "com.google.android.GoogleCamera"
    }
}
