package com.experimental.gcam100x

import android.util.Log

/**
 * The native part (libgcam100x_native.so): the guard against the G4 crash in
 * Google's diffusion super-resolver, and the capture of Google's own log
 * messages.
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
    const val ST_LOG = 4
    const val ST_WRONG_BUILD = 8
    const val ST_HOOK_FAILED = 16
    const val ST_LIB_SEEN = 32

    @Volatile var loaded = false; private set
    @Volatile var loadError: String? = null; private set

    @JvmStatic private external fun status(): Int

    fun load(moduleApk: String?, log: (Int, String) -> Unit) {
        if (loaded) return
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

    /** ST_* bits, or 0 when the library is not loaded. */
    fun state(): Int = if (!loaded) 0 else runCatching { status() }.getOrDefault(0)

    /** The crash guard is installed in Google's libgcastartup.so. */
    fun guardActive(): Boolean = state() and ST_GUARD != 0

    fun describe(s: Int = state()): String = when {
        !loaded -> "not loaded${loadError?.let { " ($it)" } ?: ""}"
        s and ST_API == 0 -> "loaded, but the framework did not call native_init (no native hook API)"
        else -> buildList {
            add(if (s and ST_GUARD != 0) "crash guard ACTIVE" else when {
                s and ST_WRONG_BUILD != 0 -> "crash guard off: libgcastartup.so is another build"
                s and ST_HOOK_FAILED != 0 -> "crash guard FAILED to install"
                s and ST_LIB_SEEN == 0 -> "crash guard waiting for libgcastartup.so"
                else -> "crash guard off"
            })
            add(if (s and ST_LOG != 0) "Google log capture on" else "Google log capture off")
        }.joinToString(", ")
    }
}
