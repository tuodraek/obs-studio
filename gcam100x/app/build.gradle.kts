import java.net.URI

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

// Real-ESRGAN realesr-general-x4v3 (BSD-3-Clause, xinntao/Real-ESRGAN),
// TFLite conversion. Downloaded at build time; not stored in git.
val esrganModel = layout.projectDirectory.file("src/main/assets/realesr_general_x4v3.tflite")
val fetchEsrgan by tasks.registering {
    outputs.file(esrganModel)
    onlyIf { !esrganModel.asFile.exists() }
    doLast {
        val url = "https://huggingface.co/JoPmt/Real_Esrgan_x2_Onnx_Tflite_Tfjs/resolve/main/ano_test/realesr-general-x4v3_float32.tflite"
        esrganModel.asFile.parentFile.mkdirs()
        URI(url).toURL().openStream().use { i -> esrganModel.asFile.outputStream().use { i.copyTo(it) } }
    }
}
tasks.named("preBuild") { dependsOn(fetchEsrgan) }
