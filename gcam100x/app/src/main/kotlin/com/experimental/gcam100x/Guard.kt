package com.experimental.gcam100x

import java.io.File

/**
 * Crash guard for Google's Pro Zoom pipeline.
 *
 * A marker file is written when a shot goes into Google's Pro Zoom path and
 * removed once its photo is saved. If Google Camera dies in between, the next
 * start finds the marker and turns Google's path off, so the camera keeps
 * working (exact crop) instead of crashing on every Pro Zoom shot.
 * State lives in Google Camera's own files dir.
 */
object Guard {
    private val dir = File("/data/user/0/com.google.android.GoogleCamera/files")
    private val pending = File(dir, "gcam100x_google_shot")
    private val disabledFile = File(dir, "gcam100x_google_disabled")

    @Volatile var googleEnabled = true; private set
    @Volatile var lastCrash: String? = null; private set

    /** At process start, before any hook decides anything. */
    fun load(log: (String) -> Unit) {
        runCatching {
            if (pending.exists()) {
                val what = pending.readText()
                pending.delete()
                disabledFile.writeText("Google Camera closed during a Google Pro Zoom shot ($what)")
                log("Guard: the last Google Pro Zoom shot ($what) closed Google Camera; Google's Pro Zoom path is now off")
            }
            if (disabledFile.exists()) {
                googleEnabled = false
                lastCrash = disabledFile.readText()
            }
        }.onFailure { log("Guard: state unavailable: $it") }
        log("Guard: Google's Pro Zoom path ${if (googleEnabled) "on" else "off"}")
    }

    fun shotStarted(desc: String) {
        if (googleEnabled) runCatching { dir.mkdirs(); pending.writeText(desc) }
    }

    fun shotSaved() { runCatching { pending.delete() } }

    /** Turn Google's path back on (takes effect at the next camera start). */
    fun retry() {
        runCatching { disabledFile.delete(); pending.delete() }
        lastCrash = null
    }
}
