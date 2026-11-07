package com.experimental.gcam100x

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.util.TypedValue
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import io.github.libxposed.api.XposedModuleInterface
import java.io.File
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.WeakHashMap

/**
 * Adds a "GCam100x" entry at the end of Google Camera's main settings screen.
 * Tapping it shows module / model / Pro Zoom info and offers "Copy log".
 *
 * AndroidX Preference names in Google Camera 11.1.040 (obfuscated):
 *   gcm = PreferenceFragmentCompat, gcm.ap(PreferenceScreen) = setPreferenceScreen
 *   Preference.P(CharSequence) = setTitle, .n(CharSequence) = setSummary,
 *   .J(String) = setKey, .L(int) = setOrder, field o: gcd = click listener
 *   PreferenceGroup.ai(Preference) = addPreference
 */
class SettingsHook(private val module: MainHook) {
    companion object { private const val TAG = "GCam100x" }

    private val added: MutableMap<Any, Boolean> = Collections.synchronizedMap(WeakHashMap())

    fun install(param: XposedModuleInterface.PackageLoadedParam) {
        val loader = param.defaultClassLoader
        runCatching {
            // gcm.ap() is an abstract-like stub; Google Camera's settings
            // fragment hjv (extends gcm) implements it.
            val screenCls = loader.loadClass("androidx.preference.PreferenceScreen")
            val ap = loader.loadClass("hjv").getDeclaredMethod("ap", screenCls)
            module.hookMethod(ap).intercept { chain ->
                val r = chain.proceed()
                val screen = chain.getArg(0)
                runCatching { addEntry(loader, chain.thisObject, screen) }
                    .onFailure { module.logMessage(Log.WARN, TAG, "Settings entry failed: $it") }
                r
            }
            module.logMessage(Log.INFO, TAG, "Settings hook on hjv.ap()")
        }.onFailure { module.logMessage(Log.WARN, TAG, "Settings hook failed: $it") }
    }

    private fun addEntry(loader: ClassLoader, fragment: Any?, screen: Any?) {
        if (screen == null || fragment == null) return
        // Only the first (root) screen of the main settings activity.
        // Preference.j is the Context the screen was inflated with (the activity).
        val prefBase = loader.loadClass("androidx.preference.Preference")
        var activity = runCatching { prefBase.getField("j").get(screen) }.getOrNull()
        while (activity is android.content.ContextWrapper && activity !is android.app.Activity) activity = activity.baseContext
        val actName = activity?.javaClass?.name ?: ""
        module.logMessage(Log.INFO, TAG, "Settings: screen shown in $actName")
        if (!actName.contains("Settings")) return
        if (added.containsKey(activity)) return
        added[activity!!] = true

        val prefCls = loader.loadClass("androidx.preference.Preference")
        val groupCls = loader.loadClass("androidx.preference.PreferenceGroup")
        val gcd = loader.loadClass("gcd")
        val ctx = activity as Context
        val pref = prefCls.getConstructor(Context::class.java).newInstance(ctx)
        prefCls.getMethod("J", String::class.java).invoke(pref, "gcam100x_info")
        prefCls.getMethod("P", CharSequence::class.java).invoke(pref, "GCam100x")
        prefCls.getMethod("n", CharSequence::class.java).invoke(pref, "Version ${BuildConfig.VERSION_NAME} · Pro Zoom up to 120×")
        runCatching { prefCls.getMethod("L", Int::class.javaPrimitiveType).invoke(pref, Int.MAX_VALUE - 1) }
        val listener = Proxy.newProxyInstance(loader, arrayOf(gcd)) { _, m, _ ->
            if (m.name == "a") { showInfo(ctx); true } else null
        }
        prefCls.getField("o").set(pref, listener)
        groupCls.getMethod("ai", prefCls).invoke(screen, pref)
        module.logMessage(Log.INFO, TAG, "Settings: GCam100x entry added")
    }

    private fun info(ctx: Context): String {
        val cam = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0) }.getOrNull()
        val log = module.logFilePath()
        return buildString {
            appendLine("GCam100x ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine()
            appendLine("Google Camera ${cam?.versionName} (${cam?.longVersionCode})")
            appendLine("Device ${Build.MODEL} (${Build.DEVICE}), ${Build.SOC_MODEL}")
            appendLine("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ${Build.ID}")
            appendLine()
            appendLine("Pro Zoom")
            appendLine("  Range: 30× hardware, up to 120× Pro Zoom")
            appendLine("  Google Pro Zoom model: ${ManifestHook.VARIANT} = Pixel 10 package (TPU chip family 25, buenos_b0 / Tensor G5)")
            appendLine("  Model download: ${ProZoom.modelState ?: "not checked yet"}")
            appendLine("  Framing: centre crop, the same as the viewfinder (Google Camera's own Pro Zoom crop)")
            appendLine("  Preview stabilization above 30×: off (no drift)")
            appendLine("  Processing: Google's Pro Zoom model (CENTAUR) when it delivers; otherwise the exact crop")
            appendLine("  Google's Pro Zoom path: ${if (Guard.googleEnabled) "on" else "off — ${Guard.disabledReason ?: "disabled"}"}")
            appendLine("  Google model: ${Guard.summary()}")
            Guard.crashedLastTime?.let { appendLine("  Previous camera session ended $it") }
            Forensics.lastExit?.let { appendLine("  Last camera exit: $it") }
            appendLine()
            appendLine("Last photos")
            ProZoom.history(8).ifEmpty { listOf("none yet") }.forEach { appendLine("  $it") }
            appendLine("  Last photo: ${ProZoom.lastResult ?: "none yet"}")
            appendLine()
            appendLine("Diagnostics: ${module.hookCount()} hooks installed")
            appendLine("Log: ${log ?: "not available"}")
            log?.let { appendLine("  ${File(it).length() / 1024} KB") }
        }
    }

    private fun showInfo(ctx: Context) {
        val text = info(ctx)
        val tv = TextView(ctx).apply {
            setText(text)
            setTextIsSelectable(true)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            movementMethod = ScrollingMovementMethod()
        }
        val toastBox = android.widget.CheckBox(ctx).apply {
            setText("Show \"GCam100x active\" toast")
            isChecked = Prefs.toastEnabled(ctx)
            setOnCheckedChangeListener { _, on -> Prefs.setToastEnabled(ctx, on) }
        }
        val column = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            addView(toastBox)
            toastBox.setPadding(pad / 2, 0, 0, 0)
            addView(tv)
            setPadding(pad / 2, pad / 2, pad / 2, 0)
        }
        val b = AlertDialog.Builder(ctx)
            .setTitle("GCam100x")
            .setView(ScrollView(ctx).apply { addView(column) })
            .setPositiveButton("Close", null)
            .setNeutralButton("Copy log") { _, _ -> copyLog(ctx, text) }
        if (!Guard.googleEnabled) b.setNegativeButton("Retry Google Pro Zoom") { _, _ ->
            Guard.retry()
            Toast.makeText(ctx, "Google Pro Zoom re-enabled: close and reopen the camera", Toast.LENGTH_LONG).show()
        }
        b.show()
    }

    private fun copyLog(ctx: Context, header: String) {
        val path = module.logFilePath()
        val body = runCatching {
            val f = File(path!!)
            // Clipboard limit: keep the last ~400 KB.
            val bytes = f.readBytes()
            String(if (bytes.size > 400_000) bytes.copyOfRange(bytes.size - 400_000, bytes.size) else bytes)
        }.getOrElse { "Log unavailable: $it" }
        val cm = ctx.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("GCam100x log", header + "\n----\n" + body))
        Toast.makeText(ctx, "GCam100x log copied", Toast.LENGTH_SHORT).show()
    }
}
