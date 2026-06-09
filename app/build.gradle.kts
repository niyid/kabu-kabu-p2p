import java.util.Properties
import java.io.File

// ============================================================
//  kabu-kabu-p2p  —  app/build.gradle.kts
//
//  Privacy-first P2P taxi & courier app.
//
//  Transport layer: I2P embedded router (same as buzzr-p2p)
//  Location privacy: GeoHash-5 fuzzy zones (~5 km cell)
//    — exact GPS is NEVER sent to any server
//  Matching: device-to-device over I2P; no central relay
//  Payment: XMR (Monero) via Monerujo JNI — lifted from Verzus
//    — 2-of-2 multisig escrow; no third-party custodian
//  Identity: SHA-256 of phone number; no plaintext PII on network
// ============================================================

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.21"
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
    // protobuf plugin — needed by Monerujo gRPC stubs (same version as Verzus)
    id("com.google.protobuf")
    // Firebase: google-services for project config + Crashlytics for crash reporting
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
}

// ── Secrets ──────────────────────────────────────────────────────────────────
val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use { localProperties.load(it) }
}
fun getLocalProperty(key: String, defaultValue: String = ""): String =
    localProperties.getProperty(key) ?: System.getenv(key) ?: defaultValue

// ── Monero daemon endpoints (mirrors Verzus) ──────────────────────────────────
val buildEnv      = findProperty("BUILD_ENV")?.toString() ?: "STAGENET"
val isMainnet     = buildEnv.uppercase() == "MAINNET"

val stagenetDaemonHost = "stagenet.xmr-tw.org"
val stagenetDaemonPort = 38081
val mainnetDaemonHost  = "node.moneroworld.com"
val mainnetDaemonPort  = 18089

val daemonHost = if (isMainnet) mainnetDaemonHost else stagenetDaemonHost
val daemonPort = if (isMainnet) mainnetDaemonPort  else stagenetDaemonPort

// ── Protobuf (required by Monerujo gRPC stubs) ───────────────────────────────
protobuf {
    protoc { artifact = "com.google.protobuf:protoc:4.30.2" }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:1.71.0"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("java") { option("lite") }
            }
            task.plugins {
                create("grpc") { option("lite") }
            }
        }
    }
}

// ── Android ──────────────────────────────────────────────────────────────────
android {
    namespace  = "com.techducat.kabukabu"
    compileSdk = 36
    ndkVersion = "29.0.13113456"

    defaultConfig {
        applicationId   = "com.techducat.kabukabu"
        minSdk          = 26          // I2P embedded router requires API 26+
        targetSdk       = 36
        versionCode = 15 
        versionName = "0.1.5-p2p"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        // ── Privacy build flags ───────────────────────────────────────────
        buildConfigField("boolean", "IS_P2P_MODE",          "true")
        buildConfigField("boolean", "STORE_EXACT_GPS",      "false")  // never persist raw coords
        buildConfigField("int",     "GEOHASH_PRECISION",    "5")      // ~5 km cell
        buildConfigField("String",  "I2P_SERVICE_HOST",     "\"127.0.0.1\"")
        buildConfigField("int",     "I2P_SERVICE_PORT",     "8881")   // distinct from Buzzr's 8880

        // ── Monero / XMR build flags ──────────────────────────────────────
        buildConfigField("boolean", "IS_MAINNET",   "$isMainnet")
        buildConfigField("String",  "SERVER_ENV",   "\"$buildEnv\"")
        buildConfigField("String",  "DAEMON_HOST",  "\"$daemonHost\"")
        buildConfigField("int",     "DAEMON_PORT",  "$daemonPort")
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

            // Strip debug symbols from Monerujo .so files in release
            isJniDebuggable = false
            ndk { debugSymbolLevel = "NONE" }
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

    // ── jniLibs packaging — required for Monerujo libmonerujo.so ─────────────
    // jniLibs are at app/src/main/jniLibs/ (copied from Verzus)
    packaging {
        jniLibs {
            // useLegacyPackaging = false ensures .so files are stored uncompressed
            // so the OS can mmap them directly at the correct 16KB page-alignment boundary.
            useLegacyPackaging = false
            pickFirsts += "**/libc++_shared.so"
            pickFirsts += "**/libjsc.so"
            pickFirsts += "**/libmonerujo.so"
        }
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/INDEX.LIST",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*"
            )
        }
    }

    // ── Source sets ───────────────────────────────────────────────────────────
    // The Monerujo sources live under com.m2049r.xmrwallet — they are compiled
    // directly as part of this project (same model as Verzus, no AAR needed).
    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/java")
        }
    }

    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
        arg("dagger.fastInit", "enabled")
        arg("dagger.hilt.android.internal.disableAndroidSuperclassValidation", "true")
        arg("dagger.hilt.android.internal.projectType", "APP")
        arg("dagger.hilt.internal.useAggregatingRootProcessor", "true")
    }
}

// ===== 16KB ELF ALIGNMENT (Android 16KB page size requirement) =====
//
// WHY: Android devices with 16KB page sizes (Pixel 9+, future ARM SoCs) require
// ELF PT_LOAD segments to be aligned to 16384 bytes. Google Play will flag APKs
// whose .so files are not aligned and will block them from 16KB-page devices.
//
// HOW: align_elf.py (from ~/git/server_extras/) patches PT_LOAD p_align to 16384.
//      Hooks into stripDebugSymbols so patching happens after stripping, before
//      both APK packaging AND AAB bundling.
//
// NOTE: useLegacyPackaging = false (set above in jniLibs) is equally required.
// The ELF p_align patch is useless if .so files are compressed in the APK/AAB,
// because the OS cannot mmap them directly — it extracts them first, losing alignment.
// Both fixes together = correct 16KB support.

val alignElfPy = "${System.getProperty("user.home")}/git/server_extras/align_elf.py"

// Kabu-Kabu's native libs that require 16KB alignment patching.
val archsToProcess = listOf("arm64-v8a", "x86_64")

tasks.whenTaskAdded {
    if (name.startsWith("strip") && name.contains("DebugSymbol")) {
        doLast {
            println("=== Checking and realigning native libraries for 16KB page size ===")

            val alignScript = File(alignElfPy)
            if (!alignScript.exists()) {
                println("ERROR: align_elf.py not found at $alignElfPy")
                println("Please ensure align_elf.py exists in ~/git/server_extras/")
                return@doLast
            }

            val strippedLibsDir = File(project.layout.buildDirectory.get().asFile, "intermediates/stripped_native_libs")

            var filesChecked = 0
            var filesAligned = 0
            var filesSkipped = 0

            // Reads the ELF PT_LOAD p_align field directly from the binary header.
            // Returns the alignment value, or -1 if the file is not a valid ELF.
            fun readElfLoadAlignment(file: File): Long {
                try {
                    val bytes = file.readBytes()
                    // ELF magic: 0x7F 'E' 'L' 'F'
                    if (bytes.size < 64 || bytes[0] != 0x7F.toByte() ||
                        bytes[1] != 0x45.toByte() || bytes[2] != 0x4C.toByte() || bytes[3] != 0x46.toByte()) {
                        return -1L
                    }
                    val is64bit = bytes[4] == 0x02.toByte()
                    val isLE    = bytes[5] == 0x01.toByte()

                    fun readU16(offset: Int): Int {
                        val a = bytes[offset].toInt() and 0xFF
                        val b = bytes[offset + 1].toInt() and 0xFF
                        return if (isLE) a or (b shl 8) else (a shl 8) or b
                    }
                    fun readU32(offset: Int): Long {
                        var v = 0L
                        for (i in 0..3) {
                            val b = bytes[offset + i].toLong() and 0xFF
                            v = if (isLE) v or (b shl (i * 8)) else (v shl 8) or b
                        }
                        return v
                    }
                    fun readU64(offset: Int): Long {
                        var v = 0L
                        for (i in 0..7) {
                            val b = bytes[offset + i].toLong() and 0xFF
                            v = if (isLE) v or (b shl (i * 8)) else (v shl 8) or b
                        }
                        return v
                    }

                    // Parse ELF header to find program header table
                    val phoff     = if (is64bit) readU64(32).toInt() else readU32(28).toInt()
                    val phentsize = readU16(if (is64bit) 54 else 42)
                    val phnum     = readU16(if (is64bit) 56 else 44)

                    val PT_LOAD = 1L
                    for (i in 0 until phnum) {
                        val phBase = phoff + i * phentsize
                        if (phBase + phentsize > bytes.size) break
                        val pType = readU32(phBase)
                        if (pType == PT_LOAD) {
                            // p_align offset: 28 (32-bit) or 48 (64-bit) from segment start
                            return if (is64bit) readU64(phBase + 48) else readU32(phBase + 28)
                        }
                    }
                } catch (e: Exception) {
                    // Not readable as ELF
                }
                return -1L
            }

            if (strippedLibsDir.exists()) {
                project.fileTree(strippedLibsDir) {
                    include("**/*.so")
                }.forEach { file ->
                    if (archsToProcess.any { file.path.contains("/$it/") }) {
                        filesChecked++
                        val currentAlign = readElfLoadAlignment(file)
                        when {
                            currentAlign == -1L -> {
                                println("  ⚠ Skipping (not ELF): ${file.name} (${file.parentFile.name})")
                                filesSkipped++
                            }
                            currentAlign >= 16384L -> {
                                println("  ✓ Already aligned ($currentAlign): ${file.name} (${file.parentFile.name})")
                                filesSkipped++
                            }
                            else -> {
                                println("  ↻ Needs alignment ($currentAlign → 16384): ${file.name} (${file.parentFile.name})")
                                try {
                                    val tempFile = File("${file.absolutePath}.tmp")
                                    val processBuilder = ProcessBuilder(
                                        "python3",
                                        alignScript.absolutePath,
                                        file.absolutePath,
                                        tempFile.absolutePath
                                    )
                                    val process = processBuilder.start()
                                    val exitCode = process.waitFor()

                                    if (exitCode == 0 && tempFile.exists()) {
                                        file.delete()
                                        tempFile.renameTo(file)
                                        println("    ✓ Aligned successfully")
                                        filesAligned++
                                    } else {
                                        println("    ✗ Alignment failed")
                                        if (tempFile.exists()) tempFile.delete()
                                    }
                                } catch (e: Exception) {
                                    println("    ✗ Error: ${e.message}")
                                }
                            }
                        }
                    }
                }
            }

            println("=== Realignment complete: $filesChecked checked, $filesAligned patched, $filesSkipped skipped ===")
        }
    }
}
// ===== END 16KB ELF ALIGNMENT =====

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

    // Security — encrypted SharedPreferences for device identity and wallet password
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

    // QR Code — for wallet receive address display
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.google.zxing:core:3.5.3")

    // Logging
    // Bugfender (com.bugfender.sdk:android:3.1.1) REMOVED — replaced by Firebase Crashlytics.
    // Timber is kept for structured local logging; errors are forwarded to Crashlytics in release.
    implementation("com.jakewharton.timber:timber:5.0.1")

    // ===== FIREBASE CRASHLYTICS =====
    // Requires google-services.json placed at app/google-services.json
    // Get it from: Firebase Console → Project Settings → Your Android app
    val firebaseBom = platform("com.google.firebase:firebase-bom:33.7.0")
    implementation(firebaseBom)
    implementation("com.google.firebase:firebase-crashlytics-ktx")
    implementation("com.google.firebase:firebase-analytics-ktx")

    // ── Monerujo / XMR wallet — gRPC stubs (same as Verzus) ──────────────────
    // Monerujo Java sources are compiled directly from src/main/java/com/m2049r/xmrwallet/
    // The JNI .so files live in src/main/jniLibs/ (copied from Verzus build).
    // No external AAR is required — sources + .so files are sufficient.
    implementation("com.google.protobuf:protobuf-kotlin-lite:4.30.2")
    implementation("io.grpc:grpc-stub:1.71.0")
    implementation("io.grpc:grpc-kotlin-stub:1.4.1")
    implementation("io.grpc:grpc-protobuf-lite:1.71.0")
    implementation("io.grpc:grpc-okhttp:1.71.0")

    // Lombok — used by some Monerujo Java classes
    compileOnly("org.projectlombok:lombok:1.18.34")
    annotationProcessor("org.projectlombok:lombok:1.18.34")
    testCompileOnly("org.projectlombok:lombok:1.18.34")
    testAnnotationProcessor("org.projectlombok:lombok:1.18.34")

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
        force("com.google.protobuf:protobuf-javalite:4.30.2")
        dependencySubstitution {
            substitute(module("com.google.protobuf:protobuf-java"))
                .using(module("com.google.protobuf:protobuf-javalite:4.30.2"))
            substitute(module("com.google.protobuf:protobuf-java-util"))
                .using(module("com.google.protobuf:protobuf-javalite:4.30.2"))
            substitute(module("com.google.protobuf:protobuf-kotlin"))
                .using(module("com.google.protobuf:protobuf-kotlin-lite:4.30.2"))
        }
    }
}
