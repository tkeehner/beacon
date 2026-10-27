import java.util.Properties
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
// Release signing comes from YOUR OWN keystore, never from the repo. Supply it via environment variables
// (BEACON_KEYSTORE, BEACON_KEYSTORE_PASSWORD, BEACON_KEY_ALIAS, BEACON_KEY_PASSWORD) or a gitignored
// keystore.properties at the project root (see keystore.properties.example). Without either, release builds are unsigned.
val signProps = Properties().apply { rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }
fun signVal(env: String, prop: String): String? = System.getenv(env)?.takeIf { it.isNotBlank() } ?: signProps.getProperty(prop)?.takeIf { it.isNotBlank() }
val releaseStore = signVal("BEACON_KEYSTORE", "storeFile")
android {
    namespace = "com.tyler.primelauncher"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.tyler.primelauncher"
        minSdk = 33 // Android 13+
        targetSdk = 35
        versionCode = 15
        versionName = "0.1.14"
        // arm64 for phones; x86_64 so the same APK can be smoke-tested on an emulator.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    signingConfigs {
        if (releaseStore != null) create("release") {
            storeFile = rootProject.file(releaseStore)
            storePassword = signVal("BEACON_KEYSTORE_PASSWORD", "storePassword")
            keyAlias = signVal("BEACON_KEY_ALIAS", "keyAlias")
            keyPassword = signVal("BEACON_KEY_PASSWORD", "keyPassword") ?: storePassword
        }
    }
    buildTypes {
        release {
            // Vosk reaches native code through JNA (reflection), so R8 stays off.
            isMinifyEnabled = false
            if (releaseStore != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    // Compose is only for Emit's in-app keyboard (ported from Emit 0.1.47, emitkb/); the rest of the launcher stays plain Views.
    buildFeatures { compose = true }
    // forkEvery: Compose's UI dispatcher is per-process, so each Robolectric test class gets a fresh JVM.
    testOptions { unitTests.isReturnDefaultValues = true; unitTests.isIncludeAndroidResources = true; unitTests.all { it.forkEvery = 1 } }
    packaging { jniLibs { useLegacyPackaging = false } }
    lint { abortOnError = true; checkReleaseBuilds = true; disable += listOf("MissingTranslation") }
    androidResources { noCompress += listOf("mdl", "fst", "int", "conf", "mat", "dubm", "ie", "stats") }
}
dependencies {
    implementation("androidx.core:core:1.15.0")
    val bom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")
}
