package com.experimental.gcam100x

import android.content.res.AssetFileDescriptor
import android.content.res.AssetManager
import android.os.ParcelFileDescriptor
import android.util.Base64
import android.util.Log
import io.github.libxposed.api.XposedModuleInterface
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Serves the Pixel 10 Pro Zoom ("centaur") download manifest to Google
 * Camera as if it were bundled in its own APK.
 *
 * The manifest is the Pixel 10 Pro one: Google Camera 11.1.040's
 * split_mdd_zoomplus_manifest_assets_module_p25.apk (Play device group of
 * the Pixel 10 Pro, "blazer"), assets/centaur_p25_manifest_config_default_variant.pb
 * (2172 bytes). It lists the 9 files (~1 GB) of model set 11422 on
 * edgedl.me.gvt1.com, whose TPU executables are compiled for chip family 25
 * (buenos_b0, Tensor G5): one generation from the G4, instead of the Pixel
 * 11 set (19523, family 26, santafe_a0 / Tensor G6) in the installed bundle.
 *
 * FlagHook points camera.centaur_manifest_config_file_name at this asset
 * and sets camera.centaur_variant to "centaur_p25", whatever the installed
 * APK bundles.
 */
class ManifestHook(private val module: MainHook) {

    companion object {
        private const val TAG = "GCam100x"
        const val ASSET = "centaur_p25_manifest_config_default_variant.pb"
        const val VARIANT = "centaur_p25"
        private const val MANIFEST_B64 = "CvkQEvYQCgdjZW50YXVyEuUBEnBodHRwczovL2VkZ2VkbC5tZS5ndnQxLmNvbS9lZGdlZGwvbWRpLXNlcnZpbmcvcGl4ZWxjYW1lcmEtY2VudGF1ci8xMTQyMi9kZWU4MDBkNWMzODkxN2JkNTQ2ZmMzZjYyOWVhNDdjMGQ2NDFjM2E1IJABKihkZWU4MDBkNWMzODkxN2JkNTQ2ZmMzZjYyOWVhNDdjMGQ2NDFjM2E1OkJGXzRkOTA0YWIyYjc5ZTNiY2NmZWNmMGNmNjNkZWE3NzU3MTc3NDQxZGZhMTg0NGNhYjNiZTNjMGY1ZTUxNGExNmZ4ABLnARJwaHR0cHM6Ly9lZGdlZGwubWUuZ3Z0MS5jb20vZWRnZWRsL21kaS1zZXJ2aW5nL3BpeGVsY2FtZXJhLWNlbnRhdXIvMTE0MjIvMDc4NDdmODZkYjQzMDc4YThkMjhiODdmMzU0MjMyZmQxYTVkNmI2YyCw0/MIKigwNzg0N2Y4NmRiNDMwNzhhOGQyOGI4N2YzNTQyMzJmZDFhNWQ2YjZjOkJGX2MyYzZjNDg3MTEyZmY1ZGQ1OTVkOTg3Y2U2NzNiZGU3MDdkMTUxYTdhMTA2YmRkNWFlNGNmZWUxNzhkMmUyZjh4ABLnARJwaHR0cHM6Ly9lZGdlZGwubWUuZ3Z0MS5jb20vZWRnZWRsL21kaS1zZXJ2aW5nL3BpeGVsY2FtZXJhLWNlbnRhdXIvMTE0MjIvYTRmNjI4NzUxOGQyYWU4ZmNjZDBjNDgyNjcyYzA5N2FlMThhNGI5NiDApfAVKihhNGY2Mjg3NTE4ZDJhZThmY2NkMGM0ODI2NzJjMDk3YWUxOGE0Yjk2OkJGXzBmMTYxMmQzMmUwZjdiOTM4ZGI3MThlZThkZDZjMWZiNWFkNmYwOWQ2ZTU2MmNlYmFhNTlkYmMzMTY0OTg0NDZ4ABLoARJwaHR0cHM6Ly9lZGdlZGwubWUuZ3Z0MS5jb20vZWRnZWRsL21kaS1zZXJ2aW5nL3BpeGVsY2FtZXJhLWNlbnRhdXIvMTE0MjIvZmM2OGIxNmQ1YWY5YjAyZjFhYzY5MjY0ZTY5NzgzYjRiZjY4NjU0NCDAp8O/AyooZmM2OGIxNmQ1YWY5YjAyZjFhYzY5MjY0ZTY5NzgzYjRiZjY4NjU0NDpCRl8zZWE5ZDkyODcxN2Q5NzIzOTQyNGQ3ZjkyMDZhYjgyN2Y0MTJhNmI3OWI5ZjdkYTNmMzFmODFjOWE0MjE0YmQ2eAAS5gEScGh0dHBzOi8vZWRnZWRsLm1lLmd2dDEuY29tL2VkZ2VkbC9tZGktc2VydmluZy9waXhlbGNhbWVyYS1jZW50YXVyLzExNDIyLzJiZTY5YmY4MmU2NGExNmE4MDI0YjA5MDc0MWUwZTIyMGRmZGRiNzAggKETKigyYmU2OWJmODJlNjRhMTZhODAyNGIwOTA3NDFlMGUyMjBkZmRkYjcwOkJGXzkzMzcyMmE2NDFkZDYwN2VhNTRkZjc0ZTJkYWZlYTExYjc2ZmM1Y2FiZjY1YzViMmVkMWQ2YmNlYTUwZTdhNGZ4ABLnARJwaHR0cHM6Ly9lZGdlZGwubWUuZ3Z0MS5jb20vZWRnZWRsL21kaS1zZXJ2aW5nL3BpeGVsY2FtZXJhLWNlbnRhdXIvMTE0MjIvMzcyMWYzMGU0ZjkxZDc2NmVjZTIzYTRiYWRkYzRkNWEyODc5YTNjZiDAy7kKKigzNzIxZjMwZTRmOTFkNzY2ZWNlMjNhNGJhZGRjNGQ1YTI4NzlhM2NmOkJGXzNjMTE1NGM3NGUxZjIzZWU0MWQwOTBkODNmZWU4ZGNiMzZiMGZmNDgzOWZlZWZhY2RjZDU4MzExZDU3ZTJhM2R4ABLlARJwaHR0cHM6Ly9lZGdlZGwubWUuZ3Z0MS5jb20vZWRnZWRsL21kaS1zZXJ2aW5nL3BpeGVsY2FtZXJhLWNlbnRhdXIvMTE0MjIvNWY5MjNkNmE2ZDdjYzQyOGY4NGZjNDczOGYwZWUyZjQ5NzE0ZDM4YiCqBSooNWY5MjNkNmE2ZDdjYzQyOGY4NGZjNDczOGYwZWUyZjQ5NzE0ZDM4YjpCRl8wODllNDNmMWI2YTAyMTZjYjc0ZWFhOTExYTAwYzRmMGVkNzU1ZGQ4OWQ5MTFjZDUwZTcxMzMzZWEyOTRlOTM2eAAS5wEScGh0dHBzOi8vZWRnZWRsLm1lLmd2dDEuY29tL2VkZ2VkbC9tZGktc2VydmluZy9waXhlbGNhbWVyYS1jZW50YXVyLzExNDIyLzMxZDUzNDgyNDdhZjZhNGU5MGQwNjdjNjFlZTEwMjJiOGEyMjliODQgwM3hBCooMzFkNTM0ODI0N2FmNmE0ZTkwZDA2N2M2MWVlMTAyMmI4YTIyOWI4NDpCRl82NzYyMTA2MGU2YjI3MjE5MzdkYWEwYjgxN2EyN2I0MDJjNzc0ZTZiZDlkMTQ4NmY2YTFkYmNhYmE4ZjM5MzhkeAAS5wEScGh0dHBzOi8vZWRnZWRsLm1lLmd2dDEuY29tL2VkZ2VkbC9tZGktc2VydmluZy9waXhlbGNhbWVyYS1jZW50YXVyLzExNDIyL2RmODdkNzljZGQzZGNhODhlY2Q4YjU2NmM0NjljYTNmYmFhMDk1NDYgwOaAASooZGY4N2Q3OWNkZDNkY2E4OGVjZDhiNTY2YzQ2OWNhM2ZiYWEwOTU0NjpCRl8zOGQwMTNhOWM2YThjNDQ3NzJhYWYxMWE1MWZjNzEyMzUzMzQwNTgwMTk2NTNhOTUxMjQ1NTE0NThkMThhMTI0eAAYAGoECAEQAKgBALgBnlnSAQdkZWZhdWx04gEbCgcIARCUlagyCgcIAhCVlagyCgcIAxCWlagy"
        val MANIFEST: ByteArray by lazy { Base64.decode(MANIFEST_B64, Base64.DEFAULT) }
    }

    private fun matches(name: Any?) =
        (name as? String)?.trimStart('/')?.substringAfterLast('/') == ASSET

    fun install(param: XposedModuleInterface.PackageLoadedParam) {
        val cacheFile = File(param.applicationInfo.dataDir, "cache/$ASSET")
        try {
            val open = AssetManager::class.java.getDeclaredMethod(
                "open", String::class.java, Int::class.javaPrimitiveType
            )
            module.hookMethod(open).intercept { chain ->
                if (matches(chain.getArg(0))) {
                    module.logMessage(Log.INFO, TAG, "Serving Pro Zoom manifest via AssetManager.open(${chain.getArg(0)})")
                    ByteArrayInputStream(MANIFEST)
                } else chain.proceed()
            }
            val openFd = AssetManager::class.java.getDeclaredMethod("openFd", String::class.java)
            module.hookMethod(openFd).intercept { chain ->
                if (matches(chain.getArg(0))) {
                    module.logMessage(Log.INFO, TAG, "Serving Pro Zoom manifest via AssetManager.openFd(${chain.getArg(0)})")
                    cacheFile.parentFile?.mkdirs()
                    if (!cacheFile.exists() || cacheFile.length() != MANIFEST.size.toLong()) cacheFile.writeBytes(MANIFEST)
                    val pfd = ParcelFileDescriptor.open(cacheFile, ParcelFileDescriptor.MODE_READ_ONLY)
                    AssetFileDescriptor(pfd, 0, MANIFEST.size.toLong())
                } else chain.proceed()
            }
            module.logMessage(Log.INFO, TAG, "Manifest hook installed (${MANIFEST.size} bytes, variant $VARIANT)")
        } catch (t: Throwable) {
            module.logMessage(Log.ERROR, TAG, "Manifest hook failed: ${Log.getStackTraceString(t)}")
        }
    }
}
