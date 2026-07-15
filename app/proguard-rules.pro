# Keep Room entities
-keep class com.voxli.catalog.db.** { *; }

# Keep Koin
-keep class org.koin.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Keep data classes for serialization
-keepclassmembers class * {
    @kotlin.Metadata <fields>;
}
