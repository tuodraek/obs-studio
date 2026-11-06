package com.experimental.gcam100x

import android.util.Log
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections

/**
 * Stage 2: Google Camera feature flags.
 *
 * - Forces the Pixel 10 Pro "Pro (Res) Zoom" flags on. GCam Boost lists these
 *   (Flags.fHundredXZoom) but never enables them for any device.
 * - Logs (once each, with the app's original value) every boolean, float and
 *   int flag whose name looks related to zoom / Pro Zoom / on-device models,
 *   so we can find the flag that holds the 30x cap and any model-download
 *   gates.
 *
 * The config class is located the same way GCam Boost does it: when the app
 * opens the trace section "ProcessStablePhInit#GcaConfig", one of the
 * (obfuscated) classes on the stack is the flag reader.
 */
class FlagHook(private val module: MainHook) {

    companion object {
        private const val TAG = "GCam100x"
        private const val CONFIG_TRACE = "ProcessStablePhInit#GcaConfig"

        val FORCE_TRUE = setOf(
            "camera.enable_centaur_setting",
            "camera.enable_centaur_chip",
            "camera.enable_centaur_chip_in_app_flow",
            "camera.enable_boba_jelly",
            "camera.boba_jelly_eligible",
            // Beyond-HAL-limit zoom: viewfinder extension and extended level
            // (boba_jelly_ext_level = 120). Both are false on this phone.
            "camera.enable_boba_jelly_vfe",
            "camera.boba_jelly_ext",
        )

        /** Numeric flags to override (value type and Optional wrapping are preserved). */
        val FORCE_NUMBER = mapOf(
            "camera.manual_max_tele_zoom" to 100.0,
        )

        private val INTERESTING = Regex(
            "zoom|centaur|boba|pro_res|prores|diffusion|tpu|genai|gen_ai|aicore|" +
                "model_download|download|upscal|super_res|superres|crop|" +
                "mdd|manifest|file_group|filegroup|url|http",
            RegexOption.IGNORE_CASE
        )
    }

    /**
     * String flags to override, filled in at install time from the Pro Zoom
     * manifest bundled in Google Camera's own APK (see [findCentaurManifest]).
     */
    private val forceString: MutableMap<String, String> = HashMap()

    private val processedClasses: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private val loggedFlags: MutableSet<String> = Collections.synchronizedSet(HashSet())

    fun install(param: XposedModuleInterface.PackageLoadedParam) {
        // Prefer a manifest bundled in the installed APK; otherwise use the
        // Pixel 10 one that ManifestHook serves.
        val (asset, variant) = findCentaurManifest(param)
            ?: (ManifestHook.ASSET to ManifestHook.VARIANT)
        forceString["camera.centaur_manifest_config_file_name"] = "asset:/$asset"
        forceString["camera.centaur_variant"] = variant
        try {
            val loader = param.defaultClassLoader
            val beginSection = Class.forName("android.os.Trace")
                .getDeclaredMethod("beginSection", String::class.java)
            module.hookMethod(beginSection).intercept { chain ->
                if (chain.getArg(0) == CONFIG_TRACE) {
                    for (frame in Throwable().stackTrace) {
                        val name = frame.className
                        if (name.contains('.') || !processedClasses.add(name)) continue
                        try {
                            val clazz = loader.loadClass(name)
                            if (isConfigClass(clazz)) {
                                module.logMessage(Log.INFO, TAG, "Found flag config class: $name")
                                hookConfigClass(clazz)
                            }
                        } catch (_: Throwable) {
                        }
                    }
                }
                chain.proceed()
            }
            module.logMessage(Log.INFO, TAG, "Flag hook installed; forcing: $FORCE_TRUE, $FORCE_NUMBER, $forceString")
        } catch (t: Throwable) {
            module.logMessage(Log.ERROR, TAG, "Flag hook failed: ${Log.getStackTraceString(t)}")
        }
    }

    /**
     * Pixel 10 Pro builds set camera.centaur_manifest_config_file_name to
     * "asset:/centaur_<gen>_manifest_config_<variant>.pb" (a manifest bundled
     * in the APK) and camera.centaur_variant to "centaur_<gen>". Find that
     * asset in the installed Google Camera APK(s) and return (asset, variant).
     */
    private fun findCentaurManifest(param: XposedModuleInterface.PackageLoadedParam): Pair<String, String>? {
        val info = param.applicationInfo
        val apks = listOfNotNull(info.sourceDir) + (info.splitSourceDirs?.toList() ?: emptyList())
        val pattern = Regex("^assets/((centaur_[a-z0-9]+)_manifest_config[^/]*\\.pb)$")
        val found = ArrayList<Pair<String, String>>()
        for (apk in apks) {
            try {
                java.util.zip.ZipFile(apk).use { zip ->
                    for (e in zip.entries()) {
                        if (!e.name.contains("centaur", ignoreCase = true)) continue
                        module.logMessage(Log.INFO, TAG, "APK asset: ${e.name} (${e.size} bytes) in ${apk.substringAfterLast('/')}")
                        pattern.find(e.name)?.let { m -> found += m.groupValues[1] to m.groupValues[2] }
                    }
                }
            } catch (t: Throwable) {
                module.logMessage(Log.WARN, TAG, "Could not scan $apk: $t")
            }
        }
        // Prefer the default variant, then the newest generation.
        val pick = found.sortedWith(
            compareByDescending<Pair<String, String>> { it.first.contains("default_variant") }
                .thenByDescending { it.second }
        ).firstOrNull()
        module.logMessage(Log.INFO, TAG, "Centaur manifests found: $found -> using $pick")
        return pick
    }

    private fun isConfigClass(clazz: Class<*>): Boolean {
        val methods = clazz.declaredMethods.filter {
            it.parameterTypes.size == 1 && !Modifier.isStatic(it.modifiers)
        }
        return methods.any { it.returnType == java.lang.Boolean.TYPE || it.returnType == java.lang.Boolean::class.java } &&
            methods.any { it.returnType == String::class.java }
    }

    private fun hookConfigClass(clazz: Class<*>) {
        for (method in clazz.declaredMethods) {
            if (Modifier.isStatic(method.modifiers) || method.parameterTypes.size != 1) continue
            when (method.returnType) {
                java.lang.Boolean.TYPE, java.lang.Boolean::class.java -> hookBoolean(method)
                java.lang.Float.TYPE, java.lang.Float::class.java,
                java.lang.Integer.TYPE, java.lang.Integer::class.java,
                java.lang.Long.TYPE, java.lang.Long::class.java,
                java.lang.Double.TYPE, java.lang.Double::class.java -> hookNumberLogOnly(method)
                java.lang.Void.TYPE -> {}
                // Strings, byte[] and proto-valued flags (download manifests,
                // URLs, file group configs).
                else -> hookNumberLogOnly(method)
            }
        }
    }

    private fun hookBoolean(method: Method) {
        try {
            module.hookMethod(method).intercept { chain ->
                val name = flagName(chain.getArg(0))
                val original = chain.proceed()
                when {
                    name == null -> original
                    name in FORCE_TRUE -> {
                        logOnce(name, "Flag $name: original=$original -> forced true")
                        true
                    }
                    else -> {
                        if (INTERESTING.containsMatchIn(name)) logOnce(name, "Flag $name = $original")
                        original
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun hookNumberLogOnly(method: Method) {
        try {
            module.hookMethod(method).intercept { chain ->
                val original = chain.proceed()
                val name = flagName(chain.getArg(0))
                val forcedString = name?.let { forceString[it] }
                if (forcedString != null && (original == null || original is String)) {
                    logOnce(name, "Flag $name: original=${describe(original)} -> forced $forcedString")
                    return@intercept forcedString
                }
                val target = name?.let { FORCE_NUMBER[it] }
                if (target != null) {
                    val replaced = coerce(original, target)
                    logOnce(name, "Flag $name: original=${describe(original)} -> forced ${describe(replaced)}")
                    return@intercept replaced
                }
                if (name != null && INTERESTING.containsMatchIn(name)) {
                    logOnce(name, "Flag $name = ${describe(original)} (${method.returnType.simpleName})")
                }
                original
            }
        } catch (_: Throwable) {
        }
    }

    /** Returns [target] in the same shape as [original] (Float/Double/Int/Long, optionally in an Optional). */
    private fun coerce(original: Any?, target: Double): Any? {
        fun inner(v: Any?): Any = when (v) {
            is Double -> target
            is Int -> target.toInt()
            is Long -> target.toLong()
            else -> target.toFloat()
        }
        if (original is Number) return inner(original)
        if (original != null && original.javaClass.simpleName == "Optional") {
            // java.util.Optional or the desugared j$.util.Optional the app uses.
            return try {
                val c = original.javaClass
                val present = c.getMethod("isPresent").invoke(original) as Boolean
                val v = if (present) c.getMethod("get").invoke(original) else null
                c.getMethod("of", Any::class.java).invoke(null, inner(v))
            } catch (t: Throwable) {
                module.logMessage(Log.ERROR, TAG, "Optional override failed: $t")
                original
            }
        }
        return original
    }

    private fun describe(v: Any?): String = when (v) {
        null -> "null"
        is ByteArray -> {
            // Show readable fragments (URLs, file names) inside serialized protos.
            val text = String(v, Charsets.ISO_8859_1)
            val runs = Regex("[\\x20-\\x7e]{6,}").findAll(text).map { it.value }.take(20).toList()
            "byte[${v.size}] strings=$runs"
        }
        else -> v.toString().let { if (it.length > 400) it.take(400) + "..." else it }
    }

    private fun logOnce(name: String, msg: String) {
        if (loggedFlags.add(name)) module.logMessage(Log.INFO, TAG, msg)
    }

    /** Flag objects carry their name in a String field like "camera.foo_bar". */
    private fun flagName(obj: Any?): String? {
        if (obj == null) return null
        if (obj is String) return obj.takeIf { it.contains('.') || it.contains('_') }
        var c: Class<*>? = obj.javaClass
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                if (f.type != String::class.java || Modifier.isStatic(f.modifiers)) continue
                try {
                    f.isAccessible = true
                    val v = f.get(obj) as? String
                    if (v != null && (v.contains('.') || v.contains('_'))) return v
                } catch (_: Throwable) {
                }
            }
            c = c.superclass
        }
        return null
    }
}
