import java.util.Properties
import java.io.File

// ============================================================
//  kabu-kabu-p2p  —  app/build.gradle.kts
//
//  Privacy-first P2P taxi & courier app for Nigeria.
//
//  Transport layer: I2P embedded router (same as buzzr-p2p)
//  Location privacy: GeoHash-5 fuzzy zones (~5 km cell)
//    — exact GPS is NEVER sent to any server
//  Matching: device-to-device over I2P; no central relay
//  Payment signals: on-device only; offline cash-first model
//  Identity: SHA-256 of phone number; no plaintext PII on network
// ============================================================

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.21"
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

// ── Secrets ──────────────────────────────────────────────────────────────────
val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use { localProperties.load(it) }
}
fun getLocalProperty(key: String, defaultValue: String = ""): String =
    localProperties.getProperty(key) ?: System.getenv(key) ?: defaultValue

// ── Android ──────────────────────────────────────────────────────────────────
android {
    namespace  = "com.techducat.kabukabu"
    compileSdk = 36

    defaultConfig {
        applicationId   = "com.techducat.kabukabu"
        minSdk          = 26          // I2P embedded router requires API 26+
        targetSdk       = 36
        versionCode     = 1
        versionName     = "0.1.0-p2p"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        // ── Privacy build flags ───────────────────────────────────────────
        buildConfigField("boolean", "IS_P2P_MODE",          "true")
        buildConfigField("boolean", "STORE_EXACT_GPS",      "false")  // never persist raw coords
        buildConfigField("int",     "GEOHASH_PRECISION",    "5")      // ~5 km cell
        buildConfigField("String",  "I2P_SERVICE_HOST",     "\"127.0.0.1\"")
        buildConfigField("int",     "I2P_SERVICE_PORT",     "8881")   // distinct from Buzzr's 8880
        buildConfigField("String",  "BUGFENDER_KEY",
            "\"${getLocalProperty("BUGFENDER_KEY")}\"")
    }

    lint {
        disable  += setOf("NullSafeMutableLiveData", "RememberInComposition", "OpaqueUnitKey")
        abortOnError = false
        checkOnly    += setOf("HardcodedText", "SetJavaScriptEnabled", "ExportedService")
    }

    signingConfigs {
        create("release") {
            val ksPath = getLocalProperty("RELEASE_STORE_FILE")
            if (ksPath.isNotEmpty() && File(ksPath).exists()) {
                storeFile     = file(ksPath)
                storePassword = getLocalProperty("RELEASE_STORE_PASSWORD")
                keyAlias      = getLocalProperty("RELEASE_KEY_ALIAS")
                keyPassword   = getLocalProperty("RELEASE_KEY_PASSWORD")
            }
            // If no keystore is configured the bundle will be unsigned.
            // Sign manually:
            //   jarsigner -verbose -sigalg SHA256withRSA -digestalg SHA-256
            //             -keystore your.jks app-release.aab your_alias
        }
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("playstore") { dimension = "distribution" }
        create("fdroid") {
            dimension = "distribution"
            applicationIdSuffix = ".fdroid"
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled   = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose     = true
        buildConfig = true
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/INDEX.LIST")
        }
    }
}

// ── Dependencies ─────────────────────────────────────────────────────────────
dependencies {

    // Core desugaring (I2P AAR requires this on API < 26 code paths)
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    // Core Android
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("androidx.activity:activity-compose:1.9.3")

    // Navigation
    val navVersion = "2.8.5"
    implementation("androidx.navigation:navigation-fragment-ktx:$navVersion")
    implementation("androidx.navigation:navigation-ui-ktx:$navVersion")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")

    // Room  — all trip/ride data stays on-device, never shipped to a server
    val roomVersion = "2.8.4"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    // Hilt DI
    implementation("com.google.dagger:hilt-android:2.57")
    ksp("com.google.dagger:hilt-android-compiler:2.57")
    implementation("androidx.hilt:hilt-navigation-fragment:1.2.0")
    ksp("androidx.hilt:hilt-compiler:1.2.0")

    // Compose
    val composeBom = platform("androidx.compose:compose-bom:2026.03.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Security — encrypted SharedPreferences for device identity
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    // Location & GeoHash — fuzzy zone only, raw GPS never leaves the device
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("ch.hsr:geohash:1.4.0")

    // Phone identity (SHA-256 hashed before use)
    implementation("io.michaelrocks:libphonenumber-android:8.13.17")
    implementation("com.hbb20:ccp:2.7.3")

    // JSON
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.json:json:20230227")

    // Maps — fully offline tile support via OSMDroid (no Google Maps API key needed)
    implementation("org.osmdroid:osmdroid-android:6.1.20")

    // UI
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.facebook.shimmer:shimmer:0.5.0")

    // Logging
    implementation("com.bugfender.sdk:android:3.1.1")
    implementation("com.jakewharton.timber:timber:5.0.1")

    // I2P embedded router (place built artifacts in app/libs/ — see README.md)
    if (file("libs/i2p-android-client.aar").exists()) {
        add("implementation", files("libs/i2p-android-client.aar"))
        add("implementation", "net.i2p.android:helper:0.9.5")
    } else {
        logger.warn("⚠️  libs/i2p-android-client.aar not found — I2P disabled. See app/libs/README.md")
    }
    if (file("libs/router.jar").exists())
        add("implementation", files("libs/router.jar"))
    else
        logger.warn("⚠️  libs/router.jar not found. See app/libs/README.md")
    if (file("libs/sam.jar").exists())
        add("implementation", files("libs/sam.jar"))
    else
        logger.warn("⚠️  libs/sam.jar not found. See app/libs/README.md")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("io.mockk:mockk:1.13.8")
    testImplementation("org.json:json:20230227")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}

configurations.all {
    exclude(group = "com.android.support")

    resolutionStrategy {
        force("androidx.core:core:1.17.0")
        force("androidx.core:core-ktx:1.17.0")
        dependencySubstitution {
            substitute(module("com.google.protobuf:protobuf-java"))
                .using(module("com.google.protobuf:protobuf-javalite:4.30.2"))
        }
    }
}
