plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.alphainventor.filemanager"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.alphainventor.filemanager.plus"
        minSdk = 24
        targetSdk = 34
        versionCode = 36700
        versionName = "3.6.7"

        vectorDrawables {
            useSupportLibrary = true
        }

        // Sadece gerekli dilleri paketleyerek APK boyutunu 12-18 MB bandında tutar
        resourceConfigurations += listOf("tr", "en")
    }

    signingConfigs {
        create("release") {
            val ksFile = file("personal-release-key.jks")
            if (ksFile.exists()) {
                storeFile = ksFile
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "android123"
                keyAlias = System.getenv("KEY_ALIAS") ?: "filemanagerplus"
                keyPassword = System.getenv("KEY_PASSWORD") ?: "android123"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (file("personal-release-key.jks").exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE*"
            excludes += "/META-INF/NOTICE*"
            excludes += "/META-INF/INDEX.LIST"
        }
    }
}

dependencies {
    // Çekirdek AndroidX (Minimum RAM ve hızlı başlangıç için hafif bileşenler)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // Coroutines (Paralel Çoklu Bulut Arama ve Asenkron G/Ç Motoru)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Ağ ve Bulut REST İstemcisi (Google Drive v3, Dropbox v2, OneDrive Graph, Box v2, Yandex, WebDAV/Nextcloud)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("com.google.android.gms:play-services-auth:21.2.0")

    // Uzak Bağlantı Protokolleri (FTP, FTPS, SFTP, SMB/CIFS, WebDAV)
    implementation("commons-net:commons-net:3.11.1")
    implementation("com.github.mwiede:jsch:0.2.19")
    implementation("org.codelibs:jcifs:2.1.38")

    // Arşiv Desteği (ZIP, TAR, GZ, 7Z, XZ)
    implementation("org.apache.commons:commons-compress:1.26.2")

    // Dahili Video ve Ses Oynatıcı (Media3 ExoPlayer + Tüm Konteyner/Kodek ve Bulut Akış Desteği)
    val media3Version = "1.4.1"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-exoplayer-dash:$media3Version")
    implementation("androidx.media3:media3-exoplayer-hls:$media3Version")
    implementation("androidx.media3:media3-exoplayer-rtsp:$media3Version")
    implementation("androidx.media3:media3-extractor:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
    implementation("androidx.media3:media3-datasource-okhttp:$media3Version")
    implementation("androidx.media3:media3-session:$media3Version")
    // Yazılım FFmpeg ses/video kodek eklentisi (AC3, EAC3, DTS, FLAC, Vorbis, Opus, HEVC, AV1, VP9 vb. için)
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.4.1+1")
}
