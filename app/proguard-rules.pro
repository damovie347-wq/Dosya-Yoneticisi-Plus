-optimizationpasses 5
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-verbose

# Model sınıflarını koru (Gson JSON serileştirme için)
-keep class com.alphainventor.filemanager.model.** { *; }
-keep class com.alphainventor.filemanager.cloud.** { *; }
-keep class com.alphainventor.filemanager.remote.** { *; }

# Media3 / ExoPlayer & FFmpeg JNI
-keep class androidx.media3.** { *; }
-keep class org.jellyfin.media3.** { *; }
-dontwarn org.jellyfin.media3.**

# JCIFS / JSch / Commons Net
-keep class jcifs.** { *; }
-dontwarn jcifs.**
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
