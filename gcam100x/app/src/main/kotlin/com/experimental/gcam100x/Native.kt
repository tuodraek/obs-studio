package com.experimental.gcam100x

import android.util.Log

/**
 * The native part (libgcam100x_native.so): the guard against the G4 crash in
 * Google's diffusion super-resolver (ModelRunner::Run only).
 *
 * LSPosed / Vector call a module library's native_init (which hands it the
 * native hook API) only when the module itself loads that library, so it is
 * loaded here, at module start, before Google Camera loads libgcastartup.so.
 * Google's Pro Zoom model only runs while [guardActive] is true.
 */
object Native {
    private const val LIB = "gcam100x_native"
    const val ST_API = 1
    const val ST_GUARD = 2
    const val ST_WRONG_BUILD = 8
    const val ST_HOOK_FAILED = 16
    const val ST_LIB_SEEN = 32

    private val dir = java.io.File("/data/user/0/com.google.android.GoogleCamera/files")
    /** Written before loading, removed once the camera has been running a few seconds. */
    private val startMarker = java.io.File(dir, "gcam100x_native_starting")
    /** The camera died during startup with the native part: it is not loaded until Retry. */
    private val offFile = java.io.File(dir, "gcam100x_native_off")

    @Volatile var loaded = false; private set
    @Volatile var disabledReason: String? = null; private set
    @Volatile var loadError: String? = null; private set

    @JvmStatic private external fun status(): Int

    fun load(moduleApk: String?, log: (Int, String) -> Unit) {
        if (loaded) return
        // Self-protection: if the previous camera process crashed during
        // startup with the native part loaded, start without it.
        runCatching {
            if (startMarker.exists()) {
                startMarker.delete()
                val exit = Guard.lastExit()
                if (exit == null || exit.second) offFile.writeText("the camera crashed during startup with it loaded (${exit?.first ?: "exit reason unknown"})")
            }
        }
        if (offFile.exists()) {
            disabledReason = runCatching { offFile.readText() }.getOrNull() ?: "switched off"
            log(Log.WARN, "Native part not loaded: $disabledReason. Google's Pro Zoom model will not run; Settings → Retry turns it back on")
            return
        }
        runCatching { dir.mkdirs(); startMarker.writeText(System.currentTimeMillis().toString()) }
        val tries = ArrayList<String>()
        try {
            System.loadLibrary(LIB)
            loaded = true
        } catch (t: Throwable) {
            tries.add("loadLibrary: $t")
        }
        if (!loaded && moduleApk != null) {
            // Directly from the module APK (the library is stored uncompressed).
            val path = "$moduleApk!/lib/arm64-v8a/lib$LIB.so"
            try {
                System.load(path)
                loaded = true
            } catch (t: Throwable) {
                tries.add("load($path): $t")
            }
        }
        if (!loaded) loadError = tries.joinToString("; ")
        log(if (loaded) Log.INFO else Log.ERROR,
            if (loaded) "Native part loaded: ${describe()}" else "Native part NOT loaded ($loadError); Google's Pro Zoom model will not run")
    }

    /** The camera has been up for a while: startup with the native part is fine. */
    fun startupOk() { runCatching { startMarker.delete() } }

    /** Settings → Retry: load the native part again at the next camera start. */
    fun retry() { runCatching { offFile.delete(); startMarker.delete() } }

    /** ST_* bits, or 0 when the library is not loaded. */
    fun state(): Int = if (!loaded) 0 else runCatching { status() }.getOrDefault(0)

    /** The crash guard is installed in Google's libgcastartup.so. */
    fun guardActive(): Boolean = state() and ST_GUARD != 0

    fun describe(s: Int = state()): String = when {
        disabledReason != null -> "off: $disabledReason"
        !loaded -> "not loaded${loadError?.let { " ($it)" } ?: ""}"
        s and ST_API == 0 -> "loaded, but the framework did not call native_init (no native hook API)"
        else -> buildList {
            add(if (s and ST_GUARD != 0) "crash guard ACTIVE" else when {
                s and ST_WRONG_BUILD != 0 -> "crash guard off: libgcastartup.so is another build"
                s and ST_HOOK_FAILED != 0 -> "crash guard FAILED to install"
                s and ST_LIB_SEEN == 0 -> "crash guard waiting for libgcastartup.so"
                else -> "crash guard off"
            })
        }.joinToString(", ")
    }
}
