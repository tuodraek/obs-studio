package com.experimental.gcam100x.enhancer

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var text: TextView
    private val h = Handler(Looper.getMainLooper())
    private val tick = object : Runnable { override fun run() { show(); h.postDelayed(this, 1000) } }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        text = TextView(this).apply { textSize = 11f; setTextIsSelectable(true); setPadding(24, 24, 24, 24) }
        val pick = Button(this).apply { text = "Pick a photo and enhance it"; setOnClickListener { pick() } }
        val clear = Button(this).apply { text = "Clear log"; setOnClickListener { EnhanceService.LOG.delete(); show() } }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; fitsSystemWindows = true
            addView(pick); addView(clear); addView(ScrollView(this@MainActivity).apply { addView(text) })
        })
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        startWatcher()
    }
    override fun onResume() { super.onResume(); startWatcher(); h.post(tick) }
    override fun onPause() { super.onPause(); h.removeCallbacks(tick) }

    private fun startWatcher() {
        if (Environment.isExternalStorageManager()) runCatching { startForegroundService(Intent(this, EnhanceService::class.java).setAction(EnhanceService.ACTION_WATCH)) }
    }

    private fun pick() {
        if (!Environment.isExternalStorageManager()) {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))); return
        }
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), 7)
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        val uri = data?.data ?: return
        if (req != 7 || res != RESULT_OK) return
        val name = runCatching {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()?.substringBeforeLast('.')
        startForegroundService(Intent(this, EnhanceService::class.java).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { if (name != null) putExtra("name", name) })
    }

    private fun show() {
        val head = when {
            !Environment.isExternalStorageManager() -> "Tap the first button and allow \"All files access\" (to read the model and save results), then come back here."
            !EnhanceService.MODEL.exists() -> "Model missing. Put adcsr_128_fp16.tflite in Download:\n${EnhanceService.MODEL}"
            else -> "Ready. Photos you take above 30x in Google Camera are enhanced automatically;\nthe AI version appears in DCIM/Camera as <name>_AI.jpg about half a minute later.\nLog: ${EnhanceService.LOG}"
        }
        val log = runCatching { EnhanceService.LOG.readText().takeLast(6000) }.getOrDefault("")
        text.text = "$head\n\n$log"
    }
}
