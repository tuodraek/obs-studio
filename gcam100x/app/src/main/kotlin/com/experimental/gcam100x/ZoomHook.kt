package com.experimental.gcam100x

import android.hardware.camera2.CaptureRequest
import android.util.Log

/**
 * Past 30x the Pro Zoom viewfinder is a magnified crop of the 30x preview, so
 * the HAL's preview stabilization shifts show up as drift. Keep it off while
 * Pro Zoom is active. Camera metadata is never modified (Google Camera only
 * enables Pro Zoom when the hardware max, 30x, is below the Pro Zoom level).
 */
class ZoomHook(private val module: MainHook) {

    @Volatile private var logged = false

    fun install() {
        try {
            val set = CaptureRequest.Builder::class.java.getDeclaredMethod("set", CaptureRequest.Key::class.java, Any::class.java)
            val stab = CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE
            module.hookMethod(set).intercept { chain ->
                if (ProZoom.liveScale < 0.999f && chain.getArg(0) == stab && (chain.getArg(1) as? Int ?: 0) != 0) {
                    if (!logged) { logged = true; module.logMessage(Log.INFO, "GCam100x", "Preview stabilization off while Pro Zoom is active") }
                    chain.proceed(arrayOf(stab, 0))
                } else chain.proceed()
            }
        } catch (t: Throwable) {
            module.logMessage(Log.WARN, "GCam100x", "Stabilization hook failed: $t")
        }
    }
}
