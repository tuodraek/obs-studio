
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

// Pro Zoom upscaler models: app/src/main/assets/prozoom_x4_*.tflite (see tools/convert_x4v3.py).

