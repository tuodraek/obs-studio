package com.experimental.gcam100x

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.util.Log
import android.util.Range
import java.util.Collections
import java.util.WeakHashMap

/**
 * Stage 1: metadata override + diagnostics.
 *
 * 1. Reports a max digital zoom / zoom-ratio upper bound of [TARGET_ZOOM] for
 *    rear-facing cameras (SCALER_AVAILABLE_MAX_DIGITAL_ZOOM and
 *    CONTROL_ZOOM_RATIO_RANGE). The real value is always read first and is
 *    only ever raised, never lowered.
 * 2. Logs every camera's original zoom limits when Google Camera opens it.
 * 3. Logs the CONTROL_ZOOM_RATIO that Google Camera actually puts into
 *    capture requests, so we can tell whether the app ever asks the HAL for
 *    more than its stock maximum.
 *
 * Note: this changes what the app is *told*. The camera HAL can still clamp
 * the requested zoom/crop to its real limit.
 */
class ZoomHook(private val module: MainHook) {

    companion object {
        private const val TAG = "GCam100x"
        private const val TARGET_ZOOM = 100.0f
    }

    // Cached "is this a rear camera" per characteristics object. Weak keys so
    // we never keep camera metadata alive.
    private val rearCache: MutableMap<CameraCharacteristics, Boolean> =
        Collections.synchronizedMap(WeakHashMap())

    // Characteristics objects we've already logged an override for.
    private val loggedOverride: MutableMap<CameraCharacteristics, Boolean> =
        Collections.synchronizedMap(WeakHashMap())

    // Set while we read original values for logging.
    private val bypass = ThreadLocal.withInitial { false }

    @Volatile
    private var lastLoggedRequestZoom: Float? = null

    fun install() {
        // Do NOT raise the reported zoom range: Google Camera only enables the
        // Pro Zoom slider path when the Pro Zoom level (100) is greater than
        // the camera's real max zoom (30). Faking 100 disables it.
        runStep("CameraManager.getCameraCharacteristics") { hookCameraManager() }
        runStep("CaptureRequest.Builder.set") { hookCaptureRequestBuilder() }
        info("Zoom diagnostics installed (camera metadata left unmodified)")
    }

    private fun runStep(name: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            module.logMessage(Log.ERROR, TAG, "Failed to hook $name: ${Log.getStackTraceString(t)}")
        }
    }

    private fun info(msg: String) = module.logMessage(Log.INFO, TAG, msg)

    // --- 1. Metadata override --------------------------------------------------

    private fun hookCharacteristicsGet() {
        val method = CameraCharacteristics::class.java.getDeclaredMethod(
            "get", CameraCharacteristics.Key::class.java
        )

        module.hookMethod(method).intercept { chain ->
            val key = chain.getArg(0) as? CameraCharacteristics.Key<*>
            val original = chain.proceed()

            // Fast path: this method is called constantly for every key.
            if (key != CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM &&
                key != CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE
            ) {
                return@intercept original
            }

            if (bypass.get() == true) return@intercept original
            val chars = chain.thisObject as? CameraCharacteristics
            if (chars == null || !isRear(chars)) return@intercept original

            val patched = when (key) {
                CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM -> {
                    val actual = (original as? Number)?.toFloat() ?: return@intercept original
                    maxOf(actual, TARGET_ZOOM)
                }
                else -> {
                    val range = original as? Range<*> ?: return@intercept original
                    val lower = (range.lower as? Number)?.toFloat() ?: return@intercept original
                    val upper = (range.upper as? Number)?.toFloat() ?: return@intercept original
                    Range(lower, maxOf(upper, TARGET_ZOOM))
                }
            }

            if (loggedOverride.put(chars, true) == null) {
                info("Overriding zoom limits on rear camera ($chars): ${key.name} $original -> $patched")
            }
            logCallerOnce(key.name)
            patched
        }
    }

    private val loggedCallers: MutableSet<String> = Collections.synchronizedSet(HashSet())

    /**
     * Logs (once per key + call site) which Google Camera code reads the zoom
     * limit. Those classes are where the app decides its own max zoom, i.e.
     * the next thing to hook if the slider still stops at 30x.
     */
    private fun logCallerOnce(keyName: String) {
        val all = Throwable().stackTrace
        // Skip everything up to the hooked CameraCharacteristics.get frame
        // (the hook framework's own frames have obfuscated names, so they
        // can't be filtered by package).
        val hookedGet = all.indexOfFirst {
            it.methodName == "get" &&
                (it.className.startsWith("Vector_") || it.className.startsWith("LSPHooker") ||
                    it.className == "android.hardware.camera2.CameraCharacteristics")
        }
        val frames = all.drop(hookedGet + 1)
            .filterNot {
                it.className.startsWith("android.") ||
                    it.className.startsWith("java.") ||
                    it.className.startsWith("kotlin.") ||
                    it.className.startsWith("com.experimental.gcam100x") ||
                    it.className.startsWith("dalvik.")
            }
            .take(15)
        val site = frames.firstOrNull()?.toString() ?: "unknown"
        if (!loggedCallers.add("$keyName@$site")) return
        info("Read of $keyName from:\n    " + frames.joinToString("\n    "))
    }

    private fun isRear(chars: CameraCharacteristics): Boolean {
        rearCache[chars]?.let { return it }
        // Re-entrant call into the hooked get(); LENS_FACING isn't patched,
        // so this returns the real value.
        val facing = runCatching { chars.get(CameraCharacteristics.LENS_FACING) }.getOrNull()
        val rear = facing == CameraCharacteristics.LENS_FACING_BACK
        rearCache[chars] = rear
        return rear
    }

    // --- 2. Diagnostics: original limits per camera ---------------------------

    private fun hookCameraManager() {
        // Hook every overload (public (String) and hidden ones added in newer
        // Android releases) so we see the camera id however the app asks.
        val methods = CameraManager::class.java.declaredMethods.filter {
            it.name == "getCameraCharacteristics" &&
                it.returnType == CameraCharacteristics::class.java &&
                it.parameterTypes.firstOrNull() == String::class.java
        }
        for (m in methods) {
            module.hookMethod(m).intercept { chain ->
                val result = chain.proceed()
                if (result is CameraCharacteristics) {
                    logOriginal(chain.getArg(0) as? String ?: "?", result)
                }
                result
            }
        }
    }

    private fun logOriginal(cameraId: String, chars: CameraCharacteristics) {
        // Read the unpatched values: bypass our get() override on this thread.
        bypass.set(true)
        try {
            val facing = runCatching { chars.get(CameraCharacteristics.LENS_FACING) }.getOrNull()
            val maxZoom = runCatching { chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) }.getOrNull()
            val ratioRange = runCatching { chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) }.getOrNull()
            val physicalIds = runCatching { chars.physicalCameraIds }.getOrNull()
            info(
                "Camera=$cameraId Facing=${facingName(facing)} " +
                    "OriginalMaxDigitalZoom=$maxZoom OriginalZoomRatioRange=$ratioRange " +
                    "PhysicalIds=$physicalIds"
            )
        } finally {
            bypass.set(false)
        }
    }

    private fun facingName(facing: Int?) = when (facing) {
        CameraCharacteristics.LENS_FACING_BACK -> "BACK"
        CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
        CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
        else -> "UNKNOWN($facing)"
    }

    // --- 3. Diagnostics: what zoom does the app actually request? -------------

    private fun hookCaptureRequestBuilder() {
        val method = CaptureRequest.Builder::class.java.getDeclaredMethod(
            "set", CaptureRequest.Key::class.java, Any::class.java
        )
        module.hookMethod(method).intercept { chain ->
            if (chain.getArg(0) == CaptureRequest.CONTROL_ZOOM_RATIO) {
                val ratio = (chain.getArg(1) as? Number)?.toFloat()
                // Only log near/above the stock 30x cap to keep the log short.
                if (ratio != null && ratio >= 25f && ratio != lastLoggedRequestZoom) {
                    lastLoggedRequestZoom = ratio
                    info("CaptureRequest CONTROL_ZOOM_RATIO=$ratio")
                }
            }
            chain.proceed()
        }
    }
}
