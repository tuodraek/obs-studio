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
    }

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
}
