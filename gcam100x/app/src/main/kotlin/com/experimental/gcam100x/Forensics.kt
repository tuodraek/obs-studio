package com.experimental.gcam100x

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Debug
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Why did Google Camera's previous processes end? Read from Android's own exit
 * records (ApplicationExitInfo): reason (native crash, Java crash, low memory,
 * ANR, ...), description, memory at exit, and for crashes / ANRs the readable
 * parts of the system's crash report (abort message, crashing library and
 * functions). No root or logcat needed.
 */
object Forensics {
    private const val TAG = "GCam100x"
    private val seenFile = File("/data/user/0/com.google.android.GoogleCamera/files/gcam100x_exit_seen")
    @Volatile var lastExit: String? = null; private set

    private fun reasonName(r: Int) = when (r) {
        ApplicationExitInfo.REASON_ANR -> "ANR (app not responding)"
        ApplicationExitInfo.REASON_CRASH -> "Java crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency died"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive resource usage"
        ApplicationExitInfo.REASON_EXIT_SELF -> "exited itself"
        ApplicationExitInfo.REASON_FREEZER -> "freezer"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization failure"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "killed: low memory"
        ApplicationExitInfo.REASON_OTHER -> "other (system)"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "package state change"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "package updated"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission change"
        ApplicationExitInfo.REASON_SIGNALED -> "killed by signal"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "user requested (force stop / swipe)"
        ApplicationExitInfo.REASON_USER_STOPPED -> "user stopped"
        else -> "unknown ($r)"
    }

    /** Printable strings (>= 5 chars) from a binary blob such as a tombstone protobuf. */
    private fun strings(bytes: ByteArray, max: Int): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (b in bytes) {
            val c = b.toInt() and 0xff
            if (c in 0x20..0x7e) sb.append(c.toChar()) else {
                if (sb.length >= 5) out.add(sb.toString())
                sb.setLength(0)
                if (out.size >= max) break
            }
        }
        if (sb.length >= 5 && out.size < max) out.add(sb.toString())
        return out
    }

    /** Logs every exit of Google Camera not reported before. Call once per process, off the main thread. */
    fun report(ctx: Context, log: (Int, String) -> Unit) {
        runCatching {
            val am = ctx.getSystemService(ActivityManager::class.java)
            val seen = runCatching { seenFile.readText().trim().toLong() }.getOrDefault(0L)
            val exits = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 8)
                .filter { it.timestamp > seen && it.processName == ctx.packageName }
                .sortedBy { it.timestamp }
            val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS")
            for (e in exits) {
                val head = "Exit record: ${fmt.format(Date(e.timestamp))} pid ${e.pid}: ${reasonName(e.reason)}" +
                    " (status ${e.status}, importance ${e.importance}); ${e.description ?: "no description"}; " +
                    "PSS ${e.pss / 1024} MB, RSS ${e.rss / 1024} MB"
                log(Log.WARN, head)
                lastExit = head.removePrefix("Exit record: ")
                if (e.reason == ApplicationExitInfo.REASON_CRASH_NATIVE || e.reason == ApplicationExitInfo.REASON_CRASH ||
                    e.reason == ApplicationExitInfo.REASON_ANR) {
                    val trace = runCatching { e.traceInputStream?.use { it.readBytes() } }.getOrNull()
                    if (trace == null || trace.isEmpty()) {
                        log(Log.WARN, "  (no crash report attached)")
                    } else if (e.reason == ApplicationExitInfo.REASON_ANR) {
                        String(trace).lineSequence().take(250).forEach { log(Log.WARN, "  $it") }
                    } else {
                        // Native crashes: the tombstone (protobuf). Its strings hold the
                        // signal, abort message, thread names, library paths and symbols.
                        log(Log.WARN, "  crash report (${trace.size} bytes), readable parts:")
                        strings(trace, 400).forEach { log(Log.WARN, "  | $it") }
                    }
                }
            }
            exits.maxOfOrNull { it.timestamp }?.let { seenFile.parentFile?.mkdirs(); seenFile.writeText(it.toString()) }
            if (exits.isEmpty()) log(Log.INFO, "Exit records: no new exits of Google Camera since the last check")
        }.onFailure { log(Log.WARN, "Exit records unavailable: $it") }
    }

    /** Memory snapshot for the log (before / after Google's model run). */
    fun memory(ctx: Context?): String = runCatching {
        val am = ctx!!.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val rt = Runtime.getRuntime()
        "system avail ${mi.availMem shr 20} MB of ${mi.totalMem shr 20} MB (low=${mi.lowMemory}, threshold ${mi.threshold shr 20} MB); " +
            "process native heap ${Debug.getNativeHeapAllocatedSize() shr 20} MB, java ${(rt.totalMemory() - rt.freeMemory()) shr 20} MB, " +
            "PSS ${Debug.getPss() shr 10} MB"
    }.getOrDefault("memory info unavailable")
}
