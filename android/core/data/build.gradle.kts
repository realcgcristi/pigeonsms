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

    // E2EE crypto (shipped in v3-rc3, opt-in and default OFF — see E2eeManager).
    // libsodium bindings for X25519 identity keys, sealed-box DM-key wrapping, and
    // the AEAD + HKDF/HMAC primitives the Double Ratchet is built on.
    //
    // The `@aar` suffix is Gradle's "artifact-only" notation: it pins the exact
    // packaging AND disables transitive dependency resolution from each POM. That's
    // deliberate here — it's what keeps this crypto path pulling in exactly these
    // two artifacts and nothing else. The version catalog's declarative `{ group,
    // name, version.ref }` form has no equivalent for `@aar`, so these two stay as
    // string coordinates on purpose, not because nobody got round to it.
    implementation("com.goterl:lazysodium-android:5.1.0@aar")
    implementation("net.java.dev.jna:jna:5.14.0@aar")
}
