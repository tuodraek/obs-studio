plugins {
    alias(libs.plugins.android.application)
}

// GCam100x Enhancer: the AdcSR engine as its own app and process, so a failure
// in the enhancer can never take the camera down.
android {
    namespace = "com.experimental.gcam100x.enhancer"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.experimental.gcam100x.enhancer"
        minSdk = 33
        targetSdk = 37
        versionCode = 2
        versionName = "0.2"
        ndk { abiFilters += "arm64-v8a" }
    }
    // The LiteRT accelerator libraries are opened by path at run time.
    packaging { jniLibs { useLegacyPackaging = true } }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
