package com.experimental.gcam100x.enhancer

object Native {
    init { System.loadLibrary("LiteRt"); System.loadLibrary("gcam100x_engine") }
    @JvmStatic external fun open(model: String, accelerator: String, libDir: String, cacheDir: String?, log: String?): Long
    /** rgbIn: w*h*3 bytes; rgbOut: (4w)*(4h)*3 bytes. Returns 0 on success. */
    @JvmStatic external fun enhance(handle: Long, rgbIn: ByteArray, w: Int, h: Int, rgbOut: ByteArray, colourLock: Float): Int
    @JvmStatic external fun error(): String
    /** (tiles done shl 16) or total tiles. */
    @JvmStatic external fun progress(): Int
    @JvmStatic external fun info(handle: Long): String
    @JvmStatic external fun close(handle: Long)
}
