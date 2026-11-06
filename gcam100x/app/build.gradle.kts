import java.net.URI
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
}

android {
    buildFeatures { buildConfig = true }
    namespace = "com.experimental.gcam100x"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.experimental.gcam100x"
        minSdk = 36
        targetSdk = 37
        versionCode = project.property("versionCode").toString().toInt()
        versionName = project.property("versionName").toString()
        ndk { abiFilters += "arm64-v8a" }
    }

    // The Real-ESRGAN model is read straight from the APK.
    androidResources { noCompress += "tflite" }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    compileOnly(libs.libxposed.api)
    implementation("com.google.ai.edge.litert:litert:1.4.2")
    implementation("com.google.ai.edge.litert:litert-gpu:1.4.2")
    implementation("com.google.ai.edge.litert:litert-gpu-api:1.4.2")
}

// Real-ESRGAN-x4plus (BSD-3-Clause), TFLite build from Qualcomm AI Hub.
// Downloaded at build time; not stored in git.
val esrganModel = layout.projectDirectory.file("src/main/assets/real_esrgan_x4plus.tflite")
val fetchEsrgan by tasks.registering {
    outputs.file(esrganModel)
    onlyIf { !esrganModel.asFile.exists() }
    doLast {
        val zipUrl = "https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/real_esrgan_x4plus/releases/v0.66.0/real_esrgan_x4plus-tflite-float.zip"
        val tmp = File(temporaryDir, "esrgan.zip")
        URI(zipUrl).toURL().openStream().use { i -> tmp.outputStream().use { i.copyTo(it) } }
        ZipFile(tmp).use { z ->
            val e = z.entries().asSequence().first { it.name.endsWith(".tflite") }
            esrganModel.asFile.parentFile.mkdirs()
            z.getInputStream(e).use { i -> esrganModel.asFile.outputStream().use { i.copyTo(it) } }
        }
    }
}
tasks.named("preBuild") { dependsOn(fetchEsrgan) }
