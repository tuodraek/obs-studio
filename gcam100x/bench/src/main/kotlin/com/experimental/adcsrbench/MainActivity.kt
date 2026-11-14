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
    private val log = File(dir, "adcsr_bench4.txt")
    private lateinit var text: TextView
    @Volatile private var running = false

    private class T(val id: String, val file: String, val acc: String, val cls: Class<*>, val proc: String, val runs: Int, val timeoutMin: Int)
    // The decoder is 62% of AdcSR's compute and only 10 M parameters: small enough for the on-device TPU compiler.
    private val quick = listOf(
        T("dec-gpu-fp16", "adcsr_dec_fp16.tflite", "gpu", GpuTest::class.java, ":gpu", 8, 10),
        T("dec-tpu-w8a16", "adcsr_dec_w8a16.tflite", "npu", NpuTest::class.java, ":npu", 8, 25),
        T("dec-tpu-w8a16-2nd-start", "adcsr_dec_w8a16.tflite", "npu", NpuTest::class.java, ":npu", 8, 25),
        T("dec-tpu-float", "adcsr_dec_fp16.tflite", "npu", NpuTest::class.java, ":npu", 8, 25),
    )
    private val full = listOf(
        T("full-tpu-w8a16", "adcsr_128_w8a16.tflite", "npu", NpuTest::class.java, ":npu", 4, 120),
        T("full-tpu-w8a16-2nd-start", "adcsr_128_w8a16.tflite", "npu", NpuTest::class.java, ":npu", 4, 120),
    )

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        text = TextView(this).apply { textSize = 11f; setTextIsSelectable(true); setPadding(24, 24, 24, 24) }
        val b1 = Button(this).apply { text = "1. Decoder on TPU (a few minutes)"; setOnClickListener { start(quick) } }
        val b2 = Button(this).apply { text = "2. Whole model on TPU (can take 1-2 hours, keep on charger)"; setOnClickListener { start(full) } }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; fitsSystemWindows = true
            addView(b1); addView(b2); addView(ScrollView(this@MainActivity).apply { addView(text) })
        })
        status()
    }

    override fun onResume() { super.onResume(); if (!running) status() }

    private fun status() {
        val missing = (quick + full).map { it.file }.distinct().filter { !File(dir, it).exists() }
        text.text = when {
            !Environment.isExternalStorageManager() -> "Tap a button and allow \"All files access\" (to read the models and write the result in Download)."
            else -> "Models expected in Download.\n" + (if (missing.isEmpty()) "All present." else "Missing (tests for them are skipped): $missing") +
                "\nKeep this screen open while a test runs.\nResult file: $log"
        }
    }

    private fun start(tests: List<T>) {
        if (!Environment.isExternalStorageManager()) {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (running) return
        running = true
        Thread {
            log.appendText("\n\n######## AdcSR bench app 0.2 (LiteRT 2.3.2)  ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())}\n" +
                "device: ${Build.MODEL} / ${Build.SOC_MODEL} / Android ${Build.VERSION.RELEASE} / ${Build.ID}\n")
            for (t in tests) {
                val model = File(dir, t.file)
                log.appendText("\n===== ${t.id}  (${t.file}, ${model.length() / 1000000} MB, ${t.acc}) =====\n${memLine()}\n")
                if (!model.exists()) { log.appendText("SKIPPED: ${t.file} is not in Download\n"); continue }
                val t0 = System.currentTimeMillis(); var seen = false; var ended = false
                startService(Intent(this, t.cls).putExtra("model", model.path).putExtra("log", log.path).putExtra("acc", t.acc).putExtra("id", t.id).putExtra("runs", t.runs))
                while (System.currentTimeMillis() - t0 < t.timeoutMin * 60_000L) {
                    Thread.sleep(1000); show("running ${t.id}: ${(System.currentTimeMillis() - t0) / 1000} s")
                    if (log.readText().contains("END ${t.id} ")) { ended = true; break }
                    val alive = alive(t.proc)
                    if (alive) seen = true
                    if (seen && !alive) { Thread.sleep(500); if (!log.readText().contains("END ${t.id} ")) log.appendText("PROCESS DIED during ${t.id} (native crash or killed by the system)\n"); ended = true; break }
                }
                log.appendText("wall time for ${t.id}: ${(System.currentTimeMillis() - t0) / 1000} s\n")
                if (!ended) { log.appendText("TIMEOUT ${t.id} after ${t.timeoutMin} min\n"); stopService(Intent(this, t.cls)); pidOf(t.proc).let { if (it > 0) android.os.Process.killProcess(it) } }
                Thread.sleep(3000)
            }
            log.appendText("\nALL DONE\n"); show("finished")
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
