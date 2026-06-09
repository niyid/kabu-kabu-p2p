# Kabu-Kabu P2P — ProGuard rules

# Keep Room entities and DAOs
-keep class com.techducat.kabusquared.db.** { *; }

# Keep domain model (passed through JSON reflection)
-keep class com.techducat.kabusquared.model.** { *; }

# Keep Hilt-generated classes
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ActivityComponentManager { *; }

# Keep I2P router classes loaded via reflection
-keep class net.i2p.** { *; }
-keep class org.i2p.** { *; }
-dontwarn net.i2p.**
-dontwarn org.i2p.**

# Keep GeoHash library
-keep class ch.hsr.geohash.** { *; }

# Keep OsmDroid (offline maps)
-keep class org.osmdroid.** { *; }

# Keep Gson serialisation targets
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# General Android
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ── Monerujo / XMR wallet (lifted from Verzus) ───────────────────────────────

# Keep all Monerujo model and wallet classes — they are accessed via JNI
-keep class com.m2049r.xmrwallet.** { *; }
-keepclassmembers class com.m2049r.xmrwallet.** { *; }
-dontwarn com.m2049r.xmrwallet.**

# Keep Kabu-Kabu wallet façade classes
-keep class com.techducat.kabusquared.wallet.** { *; }

# Keep native method declarations (JNI bridge to libmonerujo.so)
-keepclasseswithmembernames class * {
    native <methods>;
}

# Lombok generates code that ProGuard doesn't see — suppress warnings
-dontwarn lombok.**

# gRPC / Protobuf lite
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**
-keep class io.grpc.** { *; }
-dontwarn io.grpc.**

# Prevent stripping of wallet.properties asset (referenced by name at runtime)
# (assets are not subject to ProGuard but this documents the dependency)
