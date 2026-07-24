plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}
android {
    namespace = "app.pigeonsms.data"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    api(project(":core:network"))
    api(project(":core:db"))
    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)

    // E2EE crypto (2.8.0, ships flag-OFF / experimental). libsodium bindings for
    // X25519 identity keys, sealed-box DM-key wrapping, and the AEAD + HKDF/HMAC
    // primitives the Double Ratchet is built on. Coordinates are hardcoded rather
    // than routed through the version catalog on purpose — the catalog
    // (gradle/libs.versions.toml) is owned by another agent this cycle.
    // TODO(e2ee): promote these to libs.versions.toml aliases once coordinated.
    implementation("com.goterl:lazysodium-android:5.1.0@aar")
    implementation("net.java.dev.jna:jna:5.14.0@aar")
}
