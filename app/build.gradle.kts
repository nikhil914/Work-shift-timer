import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
}

// The Maps API key lives in local.properties (never committed). An empty key still builds;
// the map picker (Phase 2) shows a "missing API key" state instead of crashing.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

/** Environment variable first (CI), then local.properties (your PC). */
fun setting(name: String): String? =
    System.getenv(name)?.takeIf { it.isNotBlank() } ?: localProperties.getProperty(name)?.takeIf { it.isNotBlank() }

// Release signing: ONE keystore for every version, forever. Android only installs an update over
// an existing install when the certificate matches, so this file must never change or be lost.
// CI decodes it from GitHub secrets; locally point local.properties at your backup copy.
// The keystore itself is never committed.
val releaseKeystore = setting("ARRIVELOG_KEYSTORE_FILE")?.let { rootProject.file(it) }?.takeIf { it.exists() }

// Monotonic versionCode: CI passes the run number; local builds stay at 1.
val ciVersionCode = setting("ARRIVELOG_VERSION_CODE")?.toIntOrNull()
val ciVersionName = setting("ARRIVELOG_VERSION_NAME")

android {
    namespace = "com.arrivelog"
    // compileSdk = latest stable (Android 17). targetSdk 36 = Play's minimum since 31 Aug 2026
    // and the API level One UI 8 runs; see README "Spec deviations".
    compileSdk = 37

    defaultConfig {
        applicationId = "com.arrivelog"
        minSdk = 26
        targetSdk = 36
        versionCode = ciVersionCode ?: 1
        versionName = ciVersionName ?: "0.1.0-dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["MAPS_API_KEY"] = setting("MAPS_API_KEY").orEmpty()
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = setting("ARRIVELOG_KEYSTORE_PASSWORD")
                keyAlias = setting("ARRIVELOG_KEY_ALIAS")
                keyPassword = setting("ARRIVELOG_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Without the keystore the release build is produced unsigned (CI fails loudly instead).
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(project(":domain"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.viewmodel.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
