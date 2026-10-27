-optimizationpasses 5
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-ignorewarnings

# Model sınıflarını koru (Gson JSON serileştirme için)
-keep class com.alphainventor.filemanager.model.** { *; }
-keep class com.alphainventor.filemanager.cloud.** { *; }
-keep class com.alphainventor.filemanager.remote.** { *; }

# Media3 / ExoPlayer & FFmpeg JNI
-keep class androidx.media3.** { *; }
-keep class org.jellyfin.media3.** { *; }
-dontwarn androidx.media3.**
-dontwarn org.jellyfin.media3.**

# JSch / Commons Net / Commons Compress / OkHttp
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**
-dontwarn org.apache.commons.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn java.awt.**
-dontwarn javax.naming.**
