package com.experimental.gcam100x

import android.content.Context

/** GCam100x settings, stored in Google Camera's own data (file "gcam100x"). */
object Prefs {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("gcam100x", Context.MODE_PRIVATE)
    fun toastEnabled(ctx: Context) = sp(ctx).getBoolean("startup_toast", true)
    fun setToastEnabled(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean("startup_toast", on).apply()
}
