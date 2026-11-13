package com.experimental.adcsrbench

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import java.io.File

/** One accelerator test in its own process; writes "END <name>" when it returns. */
abstract class Test(private val acc: String, private val runs: Int) : Service() {
    override fun onBind(i: Intent?): IBinder? = null
    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        val model = i?.getStringExtra("model") ?: return START_NOT_STICKY
        val log = i.getStringExtra("log") ?: return START_NOT_STICKY
        Thread {
            val f = File(log)
            val rc = runCatching { Native.run(model, acc, runs, applicationInfo.nativeLibraryDir, log) }
                .getOrElse { f.appendText("EXCEPTION $acc: $it\n"); -1 }
            f.appendText("END $acc rc=$rc\n")
            Process.killProcess(Process.myPid()) // release GPU/NPU memory before the next test
        }.start()
        return START_NOT_STICKY
    }
}
class GpuTest : Test("gpu", 8)
class NpuTest : Test("npu", 4)
class CpuTest : Test("cpu", 2)
