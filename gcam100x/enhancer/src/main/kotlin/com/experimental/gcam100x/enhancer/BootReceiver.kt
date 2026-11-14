package com.experimental.gcam100x.enhancer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts the photo watcher after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        runCatching { ctx.startForegroundService(Intent(ctx, EnhanceService::class.java).setAction(EnhanceService.ACTION_WATCH)) }
    }
}
