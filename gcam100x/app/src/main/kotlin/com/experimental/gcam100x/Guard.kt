package com.experimental.gcam100x

import java.io.File

/**
 * Crash guard and statistics for Google's Pro Zoom pipeline.
 *
 * Two markers in Google Camera's own files dir:
 *  - "model": written right before Google's native model call (tnr.c) and
 *    removed when it returns;
 *  - "shot": written when a shot enters Google's Pro Zoom path and removed
 *    when its final photo is saved.
 * If Google Camera dies while either exists, the next start counts a crash
 * (and records in which phase). After [MAX_CRASHES] crashes in a row Google's
 * path is switched off (normal HDR+ capture + exact crop) so the camera keeps
 * working; any completed model run resets the count. Settings can re-arm it.
 */
object Guard {
    private const val MAX_CRASHES = 2
    private val dir = File("/data/user/0/com.google.android.GoogleCamera/files")
    private val modelMarker = File(dir, "gcam100x_model_running")
    private val shotMarker = File(dir, "gcam100x_google_shot")
    private val disabledFile = File(dir, "gcam100x_google_disabled")
    private val statsFile = File(dir, "gcam100x_stats")

    @Volatile var googleEnabled = true; private set
    @Volatile var disabledReason: String? = null; private set
    /** Set at start when the previous process died inside Google's pipeline. */
    @Volatile var crashedLastTime: String? = null; private set

    // Persistent counters.
    @Volatile var modelOk = 0; private set
    @Volatile var modelNoImage = 0; private set
    @Volatile var crashesInModel = 0; private set
    @Volatile var crashesInShot = 0; private set
    @Volatile var consecutiveCrashes = 0; private set
    @Volatile var lastModelMs = -1L; private set

    private fun readStats() = runCatching {
        statsFile.readText().split(',').map { it.trim().toLong() }.let {
            modelOk = it[0].toInt(); modelNoImage = it[1].toInt(); crashesInModel = it[2].toInt()
            crashesInShot = it[3].toInt(); consecutiveCrashes = it[4].toInt(); lastModelMs = it[5]
        }
    }

    private fun writeStats() = runCatching {
        dir.mkdirs()
        statsFile.writeText("$modelOk,$modelNoImage,$crashesInModel,$crashesInShot,$consecutiveCrashes,$lastModelMs")
    }

    /** At process start, before any hook decides anything. */
    fun load(log: (String) -> Unit) {
        readStats()
        runCatching {
            val inModel = modelMarker.takeIf { it.exists() }?.readText()
            val inShot = shotMarker.takeIf { it.exists() }?.readText()
            modelMarker.delete(); shotMarker.delete()
            if (inModel != null || inShot != null) {
                consecutiveCrashes++
                if (inModel != null) crashesInModel++ else crashesInShot++
                crashedLastTime = if (inModel != null) "during Google's model run ($inModel)" else "during a Google Pro Zoom shot ($inShot)"
                log("Guard: the previous camera process ended $crashedLastTime; consecutive crashes: $consecutiveCrashes/$MAX_CRASHES")
                if (consecutiveCrashes >= MAX_CRASHES) {
                    disabledFile.writeText("$consecutiveCrashes crashes in a row, last $crashedLastTime")
                    log("Guard: Google's Pro Zoom path switched off (normal HDR+ capture + exact crop)")
                }
                writeStats()
            }
            if (disabledFile.exists()) {
                googleEnabled = false
                disabledReason = disabledFile.readText()
            }
        }.onFailure { log("Guard: state unavailable: $it") }
        log("Guard: Google's Pro Zoom path ${if (googleEnabled) "on" else "off ($disabledReason)"}; " + summary())
    }

    fun summary() = "model runs: $modelOk with output, $modelNoImage without output, crashes: $crashesInModel in model, " +
        "$crashesInShot elsewhere in the shot; last model run ${if (lastModelMs >= 0) "$lastModelMs ms" else "n/a"}"

    fun shotStarted(desc: String) { if (googleEnabled) runCatching { dir.mkdirs(); shotMarker.writeText(desc) } }
    fun shotDone() { runCatching { shotMarker.delete() } }

    fun modelStarted(desc: String) { runCatching { dir.mkdirs(); modelMarker.writeText(desc) } }

    fun modelReturned(gotImage: Boolean, ms: Long) {
        runCatching { modelMarker.delete() }
        if (gotImage) modelOk++ else modelNoImage++
        lastModelMs = ms
        consecutiveCrashes = 0
        writeStats()
    }

    /** Turn Google's path back on (takes effect at the next camera start). */
    fun retry() {
        runCatching { disabledFile.delete(); modelMarker.delete(); shotMarker.delete() }
        consecutiveCrashes = 0
        writeStats()
        disabledReason = null
    }
}
