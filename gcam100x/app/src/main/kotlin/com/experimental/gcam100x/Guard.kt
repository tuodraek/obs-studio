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
    /** Model runs in a row without an image before Google's path is switched off. */
    private const val MAX_NO_OUTPUT = 2
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
    @Volatile var consecutiveNoOutput = 0; private set

    private fun readStats() = runCatching {
        statsFile.readText().split(',').map { it.trim().toLong() }.let {
            modelOk = it[0].toInt(); modelNoImage = it[1].toInt(); crashesInModel = it[2].toInt()
            crashesInShot = it[3].toInt(); consecutiveCrashes = it[4].toInt(); lastModelMs = it[5]
            consecutiveNoOutput = it.getOrNull(6)?.toInt() ?: 0
        }
    }

    private fun writeStats() = runCatching {
        dir.mkdirs()
        statsFile.writeText("$modelOk,$modelNoImage,$crashesInModel,$crashesInShot,$consecutiveCrashes,$lastModelMs,$consecutiveNoOutput")
    }

    /** At process start, before any hook decides anything. */
    fun load(log: (String) -> Unit) {
        readStats()
        // Crash history belongs to one model package: start fresh when the
        // package changes (e.g. Pixel 11 -> Pixel 10 model).
        runCatching {
            val vf = File(dir, "gcam100x_model_variant")
            val prev = if (vf.exists()) vf.readText().trim() else ""
            if (prev != ManifestHook.VARIANT) {
                if (disabledFile.exists() || consecutiveCrashes > 0)
                    log("Guard: model package changed ($prev -> ${ManifestHook.VARIANT}); Google's Pro Zoom path re-armed")
                disabledFile.delete(); modelMarker.delete(); shotMarker.delete()
                consecutiveCrashes = 0; consecutiveNoOutput = 0
                dir.mkdirs(); vf.writeText(ManifestHook.VARIANT)
                writeStats()
            }
        }
        runCatching {
            val inModel = modelMarker.takeIf { it.exists() }?.readText()
            val inShot = shotMarker.takeIf { it.exists() }?.readText()
            modelMarker.delete(); shotMarker.delete()
            val exit = lastExit()
            if ((inModel != null || inShot != null) && exit?.second == false) {
                // Android says the previous process did not crash (force stop,
                // swipe away, ...): not counted against Google's path.
                log("Guard: previous camera process ended in a Google Pro Zoom ${if (inModel != null) "model run" else "shot"} but was not a crash (${exit.first}); not counted")
            } else if (inModel != null || inShot != null) {
                exit?.let { log("Guard: Android exit record of the previous process: ${it.first}") }
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
        // 7.0: always off. Normal HDR+ capture + exact crop, then the AI enhancer.
        googleEnabled = false
        disabledReason = "retired in 7.0: Google's model is compiled for the Tensor G5 only; the GCam100x Enhancer (AdcSR) replaces it"
        log("Guard: Google's Pro Zoom path ${if (googleEnabled) "on" else "off ($disabledReason)"}; " + summary())
    }

    /**
     * The newest exit record of this app: (description, was it a crash), or
     * null if unavailable. Read through the system context, available before
     * the app's own context exists.
     */
    fun lastExit(): Pair<String, Boolean>? = runCatching {
        val at = Class.forName("android.app.ActivityThread")
        val thread = at.getMethod("currentActivityThread").invoke(null)
        val ctx = at.getMethod("getSystemContext").invoke(thread) as android.content.Context
        val am = ctx.getSystemService(android.app.ActivityManager::class.java)
        val e = am.getHistoricalProcessExitReasons("com.google.android.GoogleCamera", 0, 1).firstOrNull() ?: return null
        val crash = e.reason == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE ||
            e.reason == android.app.ApplicationExitInfo.REASON_CRASH ||
            e.reason == android.app.ApplicationExitInfo.REASON_ANR ||
            e.reason == android.app.ApplicationExitInfo.REASON_LOW_MEMORY ||
            e.reason == android.app.ApplicationExitInfo.REASON_SIGNALED
        "reason ${e.reason}, status ${e.status}, ${e.description ?: ""}" to crash
    }.getOrNull()

    fun summary() = "model runs: $modelOk with output, $modelNoImage without output, crashes: $crashesInModel in model, " +
        "$crashesInShot elsewhere in the shot; last model run ${if (lastModelMs >= 0) "$lastModelMs ms" else "n/a"}"

    fun shotStarted(desc: String) { if (googleEnabled) runCatching { dir.mkdirs(); shotMarker.writeText(desc) } }
    fun shotDone() { runCatching { shotMarker.delete() } }

    private val modelMessages = File(dir, "gcam100x_model_messages")

    /** Before a model run: the native part collects Google's messages of the run here. */
    fun clearModelMessages() { runCatching { modelMessages.delete() } }

    /**
     * Google's own reason for a model run without output: its error lines
     * (or lines that read like a failure) logged during the run.
     */
    fun modelReason(): String = runCatching {
        val lines = modelMessages.readLines().mapNotNull { l ->
            val m = Regex("""\[google model\] ([VDIWEF])/([^:]*): (.*)$""").find(l) ?: return@mapNotNull null
            Triple(m.groupValues[1], m.groupValues[2].trim(), m.groupValues[3].trim())
        }
        val failure = Regex("(?i)fail|error|unable|not ready|not initialized|invalid|unsupported|mismatch|chip|abort|cannot|could not|denied|timeout|timed out")
        val picked = lines.filter { it.first == "E" || it.first == "F" }.ifEmpty { lines.filter { failure.containsMatchIn(it.third) } }
        picked.map { "${it.second}: ${it.third.take(180)}" }.distinct().takeLast(4).joinToString(" | ")
    }.getOrDefault("")

    fun modelStarted(desc: String) { runCatching { dir.mkdirs(); modelMarker.writeText(desc) } }

    /**
     * After Google's model returned. [reason]: Google's own messages when it
     * gave no image. After [MAX_NO_OUTPUT] runs in a row without an image the
     * Google path is switched off from the next shot on: Google's Pro Zoom
     * capture without its model is an unfinished burst merge, the normal HDR+
     * photo + exact crop looks better. Settings can retry.
     */
    fun modelReturned(gotImage: Boolean, ms: Long, reason: String = "") {
        // The model run is the risky part; once it returned, a later exit is
        // not counted against Google's path.
        runCatching { modelMarker.delete(); shotMarker.delete() }
        if (gotImage) { modelOk++; consecutiveNoOutput = 0 } else { modelNoImage++; consecutiveNoOutput++ }
        lastModelMs = ms
        consecutiveCrashes = 0
        if (!gotImage && consecutiveNoOutput >= MAX_NO_OUTPUT && googleEnabled) {
            val why = "Google's model gave no image $consecutiveNoOutput times in a row" +
                if (reason.isNotEmpty()) " (Google: ${reason.take(300)})" else ""
            runCatching { dir.mkdirs(); disabledFile.writeText(why) }
            disabledReason = why
            googleEnabled = false
        }
        writeStats()
    }

    /** Turn Google's path back on (takes effect at the next camera start). */
    fun retry() {
        Native.retry()
        runCatching { disabledFile.delete(); modelMarker.delete(); shotMarker.delete() }
        consecutiveCrashes = 0; consecutiveNoOutput = 0
        writeStats()
        disabledReason = null
    }
}
