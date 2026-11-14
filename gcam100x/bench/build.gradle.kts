plugins {
    alias(libs.plugins.android.application)
}

// AdcSR engine test app (Phase 3): runs the converted model inside a real app
// process on the phone's GPU / NPU and writes the numbers to Download/.
android {
    namespace = "com.experimental.adcsrbench"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.experimental.adcsrbench"
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
