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
 * The manifest comes from Google Camera 11.1's
 * split_mdd_zoomplus_manifest_assets_module_p26.apk
 * (assets/centaur_p26_manifest_config_default_variant.pb, 2173 bytes). It
 * lists the 9 model files (~1 GB) of file group "centaur", version 19523,
 * hosted on edgedl.me.gvt1.com.
 *
 * FlagHook points camera.centaur_manifest_config_file_name at this asset
 * and sets camera.centaur_variant to "centaur_p26".
 */
class ManifestHook(private val module: MainHook) {

    companion object {
        private const val TAG = "GCam100x"
        const val ASSET = "centaur_p26_manifest_config_default_variant.pb"
        const val VARIANT = "centaur_p26"
        private const val MANIFEST_B64 = "CvoQEvcQCgdjZW50YXVyEuUBEnBodHRwczovL2VkZ2VkbC5tZS5ndnQxLmNvbS9lZGdlZGwvbWRpLXNlcnZpbmcvcGl4ZWxjYW1lcmEtY2VudGF1ci8xOTUyMy9kZWU4MDBkNWMzODkxN2JkNTQ2ZmMzZjYyOWVhNDdjMGQ2NDFjM2E1IJABKihkZWU4MDBkNWMzODkxN2JkNTQ2ZmMzZjYyOWVhNDdjMGQ2NDFjM2E1OkJGXzRkOTA0YWIyYjc5ZTNiY2NmZWNmMGNmNjNkZWE3NzU3MTc3NDQxZGZhMTg0NGNhYjNiZTNjMGY1ZTUxNGExNmZ4ABLnARJwaHR0cHM6Ly9lZGdlZGwubWUuZ3Z0MS5jb20vZWRnZWRsL21kaS1zZXJ2aW5nL3BpeGVsY2FtZXJhLWNlbnRhdXIvMTk1MjMvMDc4NDdmODZkYjQzMDc4YThkMjhiODdmMzU0MjMyZmQxYTVkNmI2YyCw0/MIKigwNzg0N2Y4NmRiNDMwNzhhOGQyOGI4N2YzNTQyMzJmZDFhNWQ2YjZjOkJGX2MyYzZjNDg3MTEyZmY1ZGQ1OTVkOTg3Y2U2NzNiZGU3MDdkMTUxYTdhMTA2YmRkNWFlNGNmZWUxNzhkMmUyZjh4ABLnARJwaHR0cHM6Ly9lZGdlZGwubWUuZ3Z0MS5jb20vZWRnZWRsL21kaS1zZXJ2aW5nL3BpeGVsY2FtZXJhLWNlbnRhdXIvMTk1MjMvODUzOGZiM2QxNzYzNzczZmNhNzkyNmZhNzRkMTMzM2NkZjBiNmJmOSDA7IETKig4NTM4ZmIzZDE3NjM3NzNmY2E3OTI2ZmE3NGQxMzMzY2RmMGI2YmY5OkJGXzBmMTYxMmQzMmUwZjdiOTM4ZGI3MThlZThkZDZjMWZiNWFkNmYwOWQ2ZTU2MmNlYmFhNTlkYmMzMTY0OTg0NDZ4ABLoARJwaHR0cHM6Ly9lZGdlZGwubWUuZ3Z0MS5jb20vZWRnZWRsL21kaS1zZXJ2aW5nL3BpeGVsY2FtZXJhLWNlbnRhdXIvMTk1MjMvMzg5N2Q1M2ZhMzJiZTUxY2VjMGQ2NjM1NjYwNWFhZTc5N2M0NGNkNSCAwuC1AyooMzg5N2Q1M2ZhMzJiZTUxY2VjMGQ2NjM1NjYwNWFhZTc5N2M0NGNkNTpCRl8zZWE5ZDkyODcxN2Q5NzIzOTQyNGQ3ZjkyMDZhYjgyN2Y0MTJhNmI3OWI5ZjdkYTNmMzFmODFjOWE0MjE0YmQ2eAAS5gEScGh0dHBzOi8vZWRnZWRsLm1lLmd2dDEuY29tL2VkZ2VkbC9tZGktc2VydmluZy9waXhlbGNhbWVyYS1jZW50YXVyLzE5NTIzLzJiZTY5YmY4MmU2NGExNmE4MDI0YjA5MDc0MWUwZTIyMGRmZGRiNzAggKETKigyYmU2OWJmODJlNjRhMTZhODAyNGIwOTA3NDFlMGUyMjBkZmRkYjcwOkJGXzkzMzcyMmE2NDFkZDYwN2VhNTRkZjc0ZTJkYWZlYTExYjc2ZmM1Y2FiZjY1YzViMmVkMWQ2YmNlYTUwZTdhNGZ4ABLnARJwaHR0cHM6Ly9lZGdlZGwubWUuZ3Z0MS5jb20vZWRnZWRsL21kaS1zZXJ2aW5nL3BpeGVsY2FtZXJhLWNlbnRhdXIvMTk1MjMvODNmYjI3YTNjODYyNDM0ZjRjMmQxZDVhMmQ0NTAyODBlNjQwNDgyYiDA3uYMKig4M2ZiMjdhM2M4NjI0MzRmNGMyZDFkNWEyZDQ1MDI4MGU2NDA0ODJiOkJGXzNjMTE1NGM3NGUxZjIzZWU0MWQwOTBkODNmZWU4ZGNiMzZiMGZmNDgzOWZlZWZhY2RjZDU4MzExZDU3ZTJhM2R4ABLlARJwaHR0cHM6Ly9lZGdlZGwubWUuZ3Z0MS5jb20vZWRnZWRsL21kaS1zZXJ2aW5nL3BpeGVsY2FtZXJhLWNlbnRhdXIvMTk1MjMvNGM5YTZhMjFjMGZkYmNlMDU2NTIxZTQzZWIzNWVkMjM2Zjc0MTllMCDVBSooNGM5YTZhMjFjMGZkYmNlMDU2NTIxZTQzZWIzNWVkMjM2Zjc0MTllMDpCRl8wODllNDNmMWI2YTAyMTZjYjc0ZWFhOTExYTAwYzRmMGVkNzU1ZGQ4OWQ5MTFjZDUwZTcxMzMzZWEyOTRlOTM2eAAS5wEScGh0dHBzOi8vZWRnZWRsLm1lLmd2dDEuY29tL2VkZ2VkbC9tZGktc2VydmluZy9waXhlbGNhbWVyYS1jZW50YXVyLzE5NTIzLzQ5YzUyYmRkYTg2MWI2ODgzZTE3YWFjOWJlMmRjZjU0MmI1YTU2NjggwJz3AyooNDljNTJiZGRhODYxYjY4ODNlMTdhYWM5YmUyZGNmNTQyYjVhNTY2ODpCRl82NzYyMTA2MGU2YjI3MjE5MzdkYWEwYjgxN2EyN2I0MDJjNzc0ZTZiZDlkMTQ4NmY2YTFkYmNhYmE4ZjM5MzhkeAAS5wEScGh0dHBzOi8vZWRnZWRsLm1lLmd2dDEuY29tL2VkZ2VkbC9tZGktc2VydmluZy9waXhlbGNhbWVyYS1jZW50YXVyLzE5NTIzL2UyYzg2YTE0MzllNTQ3MTlhZjM1ZjE1M2ZjNzVkNDg1M2M1ZmU4MTIgwIaFASooZTJjODZhMTQzOWU1NDcxOWFmMzVmMTUzZmM3NWQ0ODUzYzVmZTgxMjpCRl8zOGQwMTNhOWM2YThjNDQ3NzJhYWYxMWE1MWZjNzEyMzUzMzQwNTgwMTk2NTNhOTUxMjQ1NTE0NThkMThhMTI0eAAYAGoECAEQAKgBALgBw5gB0gEHZGVmYXVsdOIBGwoHCAEQiujfMgoHCAIQi+jfMgoHCAMQjOjfMg=="
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
