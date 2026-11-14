package com.experimental.adcsrbench

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import java.io.File

/** One test in its own process; writes "END <id>" when it returns. */
abstract class Test : Service() {
    override fun onBind(i: Intent?): IBinder? = null
    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        val model = i?.getStringExtra("model") ?: return START_NOT_STICKY
        val log = i.getStringExtra("log") ?: return START_NOT_STICKY
        val acc = i.getStringExtra("acc") ?: "gpu"; val id = i.getStringExtra("id") ?: acc; val runs = i.getIntExtra("runs", 6)
        Thread {
            val f = File(log)
            val cache = if (acc == "npu") File(cacheDir, "npu").apply { mkdirs() }.path else null   // on-device TPU compilation cache
            val rc = runCatching { Native.run(model, acc, runs, applicationInfo.nativeLibraryDir, log, cache) }
                .getOrElse { f.appendText("EXCEPTION $id: $it\n"); -1 }
            f.appendText("END $id rc=$rc\n")
            Process.killProcess(Process.myPid()) // release GPU/NPU memory before the next test
        }.start()
        return START_NOT_STICKY
    }
}
class GpuTest : Test()
class NpuTest : Test()
class CpuTest : Test()
