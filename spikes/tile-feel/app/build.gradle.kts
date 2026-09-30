plugins {
    // AGP 9.x ships built-in Kotlin support: applying org.jetbrains.kotlin.android
    // is an error now ("no longer required for Kotlin support since AGP 9.0").
    alias(libs.plugins.android.application)
    // Still required even with built-in Kotlin: AGP refuses buildFeatures.compose
    // without the Compose Compiler Gradle plugin on Kotlin 2.x.
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "be.nealgysemans.tilespike"

    // 37, not 36: current androidx artifacts (core-ktx 1.19.1, Compose BOM 2026.09.00,
    // lifecycle 2.11.0) fail checkDebugAarMetadata against compileSdk 36.
    compileSdk = 37

    defaultConfig {
        applicationId = "be.nealgysemans.tilespike"
        minSdk = 35
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-spike"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.savedstate)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
}
