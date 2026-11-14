package com.experimental.adcsrbench

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

class MainActivity : Activity() {
    private val dir = File(Environment.getExternalStorageDirectory(), "Download")
    private val log = File(dir, "adcsr_bench5.txt")
    private lateinit var text: TextView
    @Volatile private var running = false

    private class T(val id: String, val file: File, val acc: String, val cls: Class<*>, val proc: String, val runs: Int, val timeoutMin: Int)

    /** Tiny single-layer models shipped inside the app, then the real decoder. */
    private fun tests(): List<T> {
        val d = File(filesDir, "probe").apply { mkdirs() }
        for (n in assets.list("probe").orEmpty()) assets.open("probe/$n").use { i -> File(d, n).outputStream().use { o -> i.copyTo(o) } }
        val l = ArrayList<T>()
        for (f in d.listFiles().orEmpty().sortedBy { it.name }) {
            val id = f.nameWithoutExtension
            if (id == "resblock_gn_silu_float" || id == "conv_float") l.add(T("$id@gpu", f, "gpu", GpuTest::class.java, ":gpu", 6, 4))
            l.add(T("$id@tpu", f, "npu", NpuTest::class.java, ":npu", 6, if (id.startsWith("zz_")) 20 else 4))
        }
        l.add(T("decoder_float@tpu", File(dir, "adcsr_dec_fp16.tflite"), "npu", NpuTest::class.java, ":npu", 6, 30))
        return l
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        text = TextView(this).apply { textSize = 11f; setTextIsSelectable(true); setPadding(24, 24, 24, 24) }
        val b1 = Button(this).apply { text = "Run TPU tests"; setOnClickListener { start() } }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; fitsSystemWindows = true
            addView(b1); addView(ScrollView(this@MainActivity).apply { addView(text) })
        })
        status()
    }

    override fun onResume() { super.onResume(); if (!running) status() }

    private fun status() {
        text.text = if (!Environment.isExternalStorageManager()) "Tap the button and allow \"All files access\"."
        else "Tap Run TPU tests and keep this screen open until it says ALL DONE (10-40 minutes).\nResult file: $log"
    }

    private fun start() {
        if (!Environment.isExternalStorageManager()) {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (running) return
        running = true
        Thread {
            val summary = StringBuilder()
            // A fresh file every run: old "END" markers must never be mistaken for this run's.
            log.writeText("######## AdcSR bench app 0.3 (LiteRT 2.3.2)  ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())}\n" +
                "device: ${Build.MODEL} / ${Build.SOC_MODEL} / Android ${Build.VERSION.RELEASE} / ${Build.ID}\n")
            for (t in tests()) {
                log.appendText("\n===== ${t.id}  (${t.file.name}, ${t.file.length() / 1024} KB, ${t.acc}) =====\n")
                val from = log.length()
                if (!t.file.exists()) { log.appendText("SKIPPED: ${t.file} not found\n"); summary.append("${t.id}: skipped (file missing)\n"); continue }
                val t0 = System.currentTimeMillis(); var seen = false; var ended = false
                fun seg() = log.readText().let { it.substring(minOf(from.toInt(), it.length)) }
                startService(Intent(this, t.cls).putExtra("model", t.file.path).putExtra("log", log.path).putExtra("acc", t.acc).putExtra("id", t.id).putExtra("runs", t.runs))
                while (System.currentTimeMillis() - t0 < t.timeoutMin * 60_000L) {
                    Thread.sleep(1000); show("running ${t.id}: ${(System.currentTimeMillis() - t0) / 1000} s")
                    if (seg().contains("END ${t.id} ")) { ended = true; break }
                    val alive = alive(t.proc)
                    if (alive) seen = true
                    if (seen && !alive) { Thread.sleep(700); if (!seg().contains("END ${t.id} ")) log.appendText("PROCESS DIED during ${t.id}\n"); ended = true; break }
                }
                val secs = (System.currentTimeMillis() - t0) / 1000
                if (!ended) { log.appendText("TIMEOUT ${t.id} after ${t.timeoutMin} min\n"); stopService(Intent(this, t.cls)); pidOf(t.proc).let { if (it > 0) android.os.Process.killProcess(it) } }
                val s = seg()
                val verdict = Regex("tile_ms_avg=(\\d+)").find(s)?.let { m -> "RUNS, ${m.groupValues[1]} ms" + (Regex("init_ms=(\\d+) fully_accelerated=(\\w+)").find(s)?.let { ", init ${it.groupValues[1]} ms, fully accelerated ${it.groupValues[2]}" } ?: "") }
                    ?: Regex("Compile failed, error code (\\d+), message: ([^\\n]*)").find(s)?.let { "COMPILE FAILED (${it.groupValues[1]}): ${it.groupValues[2].take(90)}" }
                    ?: if (!ended) "TIMEOUT after ${t.timeoutMin} min (still compiling)" else if (s.contains("PROCESS DIED")) "CRASHED" else "FAILED (see log)"
                summary.append("${t.id}: $verdict  [${secs} s]\n")
                var w = 0; while (alive(t.proc) && w++ < 10) Thread.sleep(500)   // let the test process exit before the next one
            }
            log.appendText("\n===== SUMMARY =====\n$summary\nALL DONE\n"); show("finished")
            runOnUiThread { running = false }
        }.start()
    }

    private fun pidOf(suffix: String) = (getSystemService(ACTIVITY_SERVICE) as ActivityManager)
        .runningAppProcesses.orEmpty().firstOrNull { it.processName == packageName + suffix }?.pid ?: -1

    private fun alive(suffix: String) = (getSystemService(ACTIVITY_SERVICE) as ActivityManager)
        .runningAppProcesses.orEmpty().any { it.processName == packageName + suffix }

    private fun memLine(): String {
        val mi = ActivityManager.MemoryInfo(); (getSystemService(ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        return "system memory available ${mi.availMem / 1048576} MB of ${mi.totalMem / 1048576} MB"
    }

    private fun show(state: String) { val s = runCatching { log.readText().takeLast(9000) }.getOrDefault(""); runOnUiThread { text.text = "[$state]\n\n$s\n\n(result file: $log)" } }
}
