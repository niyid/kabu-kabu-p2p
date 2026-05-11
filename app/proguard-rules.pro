# Kabu-Kabu P2P — ProGuard rules

# Keep Room entities and DAOs
-keep class com.techducat.kabukabup2p.db.** { *; }

# Keep domain model (passed through JSON reflection)
-keep class com.techducat.kabukabup2p.model.** { *; }

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
