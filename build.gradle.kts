// Top-level build file for kabu-kabu-p2p
plugins {
    id("com.android.application") version "8.9.2" apply false
    id("org.jetbrains.kotlin.android") version "2.1.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.21" apply false
    id("com.google.devtools.ksp") version "2.1.21-2.0.1" apply false
    id("com.google.dagger.hilt.android") version "2.57" apply false
    // Required by Monerujo gRPC stubs (lifted from Verzus)
    id("com.google.protobuf") version "0.9.4" apply false
    // Firebase
    id("com.google.gms.google-services") version "4.4.2" apply false
    id("com.google.firebase.crashlytics") version "3.0.3" apply false
}
