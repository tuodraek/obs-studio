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
    private val model = File(dir, "adcsr_bench_fp16.tflite")
    private val log = File(dir, "adcsr_bench3.txt")
    private lateinit var text: TextView
    private lateinit var button: Button
    @Volatile private var running = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        text = TextView(this).apply { textSize = 11f; setTextIsSelectable(true); setPadding(24, 24, 24, 24) }
        button = Button(this).apply { text = "Run benchmark"; setOnClickListener { start() } }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; fitsSystemWindows = true
            addView(button); addView(ScrollView(this@MainActivity).apply { addView(text) })
        })
        status()
    }

    override fun onResume() { super.onResume(); if (!running) status() }

    private fun status() {
        text.text = when {
            !Environment.isExternalStorageManager() -> "Step 1: tap the button and allow \"All files access\" (needed to read the model and write the result in Download)."
            !model.exists() -> "Model not found: $model\nIn Termux run:\n  cp ~/adcsr/adcsr_bench_fp16.tflite /sdcard/Download/"
            else -> "Ready. Model: ${model.length() / 1000000} MB.\nTap Run benchmark, keep this screen open (about 2-6 minutes).\nResult file: $log"
        }
    }

    private fun start() {
        if (!Environment.isExternalStorageManager()) {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (running || !model.exists()) { status(); return }
        running = true; button.isEnabled = false
        Thread {
            log.writeText("AdcSR bench app 0.1 (LiteRT 2.3.2)  ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())}\n" +
                "device: ${Build.MODEL} / ${Build.SOC_MODEL} / Android ${Build.VERSION.RELEASE} / ${Build.ID}\n" +
                "model: ${model.name} ${model.length()} bytes\n")
            val tests = listOf("gpu" to GpuTest::class.java, "npu" to NpuTest::class.java, "cpu" to CpuTest::class.java)
            for ((name, cls) in tests) {
                log.appendText("\n===== $name =====\n${memLine()}\n")
                startService(Intent(this, cls).putExtra("model", model.path).putExtra("log", log.path))
                val t0 = System.currentTimeMillis(); var seen = false
                while (System.currentTimeMillis() - t0 < 8 * 60_000) {
                    Thread.sleep(1000); show()
                    if (log.readText().contains("END $name ")) break
                    val alive = alive(":$name")
                    if (alive) seen = true
                    if (seen && !alive) { Thread.sleep(500); if (!log.readText().contains("END $name ")) log.appendText("PROCESS DIED during $name (native crash or killed by the system)\n"); break }
                }
                if (alive(":$name")) { log.appendText("TIMEOUT $name after 8 min\n"); stopService(Intent(this, cls)) }
                Thread.sleep(3000)
            }
            log.appendText("\nALL DONE\n"); show()
            runOnUiThread { running = false; button.isEnabled = true }
        }.start()
    }

    private fun alive(suffix: String) = (getSystemService(ACTIVITY_SERVICE) as ActivityManager)
        .runningAppProcesses.orEmpty().any { it.processName == packageName + suffix }

    private fun memLine(): String {
        val mi = ActivityManager.MemoryInfo(); (getSystemService(ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        return "system memory available ${mi.availMem / 1048576} MB of ${mi.totalMem / 1048576} MB"
    }

    private fun show() { val s = runCatching { log.readText() }.getOrDefault(""); runOnUiThread { text.text = s + "\n\n(result file: $log)" } }
}
