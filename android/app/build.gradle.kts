plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.pigeonsms"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.pigeonsms"
        minSdk = 26
        targetSdk = 36
        versionCode = 41
        versionName = "2.8.0"
        // Native WebRTC ships .so for every ABI; ship only the common phone ABIs
        // to keep the APK from ballooning (~47MB → ~20MB).
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    signingConfigs {
        // Project-local copy of the original debug keystore so builds from any
        // machine keep the same signature as installed releases.
        create("pigeon") {
            storeFile = rootProject.file("pigeon-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // proper release key lands with the in-app updater milestone
            signingConfig = signingConfigs.getByName("pigeon")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core:design"))
    implementation(project(":core:network"))
    implementation(project(":core:db"))
    implementation(project(":core:data"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)
    implementation(libs.haze)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.datastore.preferences)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.coil.svg)
    implementation(libs.coil.video)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.paging.compose)
    implementation(libs.work.runtime)
    implementation(libs.firebase.messaging)
    // Native WebRTC (org.webrtc.*) — replaces the WebView getUserMedia path,
    // which fails with NotReadableError on some devices' mic capture.
    implementation(libs.webrtc)
    // Ktor websocket client for call signaling (app module needs its own copy).
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.websockets)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
}

// Push goes live only when the Firebase config is present — the build (and the
// FCM code paths, which check FirebaseApp.getApps) degrade gracefully without it.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}
