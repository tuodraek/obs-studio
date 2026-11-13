package com.experimental.adcsrbench

object Native {
    init { System.loadLibrary("LiteRt"); System.loadLibrary("lrjni") }
    /** Runs the benchmark on one accelerator ("gpu", "npu", "cpu"); everything is appended to [log]. */
    @JvmStatic external fun run(model: String, accelerator: String, runs: Int, libDir: String, log: String): Int
}
