package com.experimental.gcam100x

import android.content.res.Resources
import android.util.Log
import android.widget.TextView
import java.util.Collections

/**
 * Diagnostics: find the code that shows a given user-facing message.
 *
 * When Google Camera loads a string resource or sets TextView text that
 * matches [PATTERN], log the text plus the call stack (once per distinct
 * text). Resources.getText covers both View-based UI and Compose
 * (stringResource), TextView.setText covers hard-coded strings in Views.
 */
class DiagHook(private val module: MainHook) {

    companion object {
        private const val TAG = "GCam100x"
        private val PATTERN = Regex("invalid version|version detected|not compatible|incompatible|unsupported", RegexOption.IGNORE_CASE)
    }

    private val seen: MutableSet<String> = Collections.synchronizedSet(HashSet())

    fun install() {
        try {
            val getText = Resources::class.java.getDeclaredMethod("getText", Int::class.javaPrimitiveType)
            module.hookMethod(getText).intercept { chain ->
                val result = chain.proceed()
                (result as? CharSequence)?.let { check("Resources.getText(0x${Integer.toHexString(chain.getArg(0) as Int)})", it) }
                result
            }
        } catch (t: Throwable) {
            module.logMessage(Log.WARN, TAG, "Diag: Resources.getText hook failed: $t")
        }
        try {
            val setText = TextView::class.java.getDeclaredMethod(
                "setText", CharSequence::class.java, TextView.BufferType::class.java,
                Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType
            )
            module.hookMethod(setText).intercept { chain ->
                (chain.getArg(0) as? CharSequence)?.let { check("TextView.setText", it) }
                chain.proceed()
            }
        } catch (t: Throwable) {
            module.logMessage(Log.WARN, TAG, "Diag: TextView.setText hook failed: $t")
        }
        module.logMessage(Log.INFO, TAG, "Diag hooks installed (watching for: ${PATTERN.pattern})")
    }

    private fun check(source: String, text: CharSequence) {
        val s = text.toString()
        if (s.length > 500 || !PATTERN.containsMatchIn(s)) return
        VersionHook.tripped = true
        if (!seen.add(s)) return
        val frames = Throwable().stackTrace
            .filterNot {
                it.className.startsWith("com.experimental.gcam100x") ||
                    it.className.startsWith("java.lang.reflect") ||
                    it.className.startsWith("dalvik.")
            }
            .take(30)
        module.logMessage(
            Log.INFO, TAG,
            "Diag: \"$s\" via $source from:\n    " + frames.joinToString("\n    ")
        )
    }
}
