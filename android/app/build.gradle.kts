plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release builds take their version from -Pherdroid.version (CI passes the git tag).
val appVersion = providers.gradleProperty("herdroid.version").get()
val appVersionCode =
    appVersion
        .substringBefore('-')
        .split('.')
        .map { it.toInt() }
        .let { (major, minor, patch) -> major * 10_000 + minor * 100 + patch }

// Signing comes from the environment so no key material lives in the repo. Without it,
// assembleRelease produces an unsigned APK.
val releaseKeystore = System.getenv("HERDROID_KEYSTORE")?.let(::file)?.takeIf { it.exists() }

android {
    namespace = "dev.herdroid"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "dev.herdroid"
        minSdk = 29
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersion
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("HERDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("HERDROID_KEY_ALIAS")
                keyPassword = System.getenv("HERDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // Installs next to a release build instead of clashing with its signature.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    dependenciesInfo {
        // The signed dependency blob is only readable by Google Play; F-Droid rejects it.
        includeInApk = false
        includeInBundle = false
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":terminal"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.markdown.m3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
