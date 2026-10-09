plugins {
    // AGP 9 brings its own Kotlin support; applying org.jetbrains.kotlin.android
    // on top of it fails the build, so only the Compose compiler plugin is added.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "be.nealgysemans.focusmodes"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "be.nealgysemans.focusmodes"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 3
        versionName = "1.0.1"
    }

    // Release signing comes from the *user-level* ~/.gradle/gradle.properties, never from
    // this repo: the keystore and its passwords must not be committed. Without those
    // properties the release build is simply unsigned, so anyone can still build it.
    val releaseStoreFile = providers.gradleProperty("focusModes.storeFile").orNull
    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = providers.gradleProperty("focusModes.storePassword").get()
                keyAlias = providers.gradleProperty("focusModes.keyAlias").get()
                keyPassword = providers.gradleProperty("focusModes.keyPassword").get()
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
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

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)

    // The home-screen widget. Glance composes RemoteViews, so the widget shares the
    // app's glyph drawables and reads the same Room/DataStore truth every other
    // surface reads — no parallel widget state to drift.
    implementation(libs.androidx.glance.appwidget)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    // Real org.json for the JVM tests only. android.jar's copy is stubs that throw,
    // which would make every params_json decode silently fail in a unit test.
    testImplementation(libs.json)
}
