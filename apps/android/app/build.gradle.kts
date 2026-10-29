plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// CI passes the release's version; a local build is a dev build.
val benchVersion: String = (findProperty("benchVersion") as String?) ?: "0.1.0-dev"
val benchCode: Int = (findProperty("benchCode") as String?)?.toIntOrNull() ?: 1

android {
    namespace = "ai.bench.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "ai.bench.app"
        minSdk = 28
        targetSdk = 35
        versionCode = benchCode
        versionName = benchVersion
    }

    // A release is signed with the keystore CI is given; without one it falls back to the
    // debug key, so a fork or a first run still produces an installable APK.
    val ks = System.getenv("BENCH_KEYSTORE")
    signingConfigs {
        if (ks != null && file(ks).exists()) {
            create("release") {
                storeFile = file(ks)
                storePassword = System.getenv("BENCH_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("BENCH_KEY_ALIAS")
                keyPassword = System.getenv("BENCH_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Off: R8 stripped the serializers the API decodes with, which crashed on the
            // first read of /api/state. Turn back on only with a release test on a device.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.zxing.embedded)
    implementation(libs.markdown.m3)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
}
