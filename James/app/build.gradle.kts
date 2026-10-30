plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.james.assistant"
    compileSdk = 35
    // Matches the build-tools you installed with sdkmanager, so nothing extra downloads.
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.james.assistant"
        minSdk = 29          // Android 10+
        targetSdk = 35
        versionCode = 16
        versionName = "0.6-places-parking"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    // Music player (~1-2 MB): plays the MP3s and handles car / earbud buttons
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    // "Hey James" wake word: Vosk, free and open source, runs offline on the phone (no account)
    implementation("com.alphacephei:vosk-android:0.3.75") {
        exclude(group = "net.java.dev.jna")   // added once below, as the Android (aar) build
    }
    implementation("net.java.dev.jna:jna:5.18.1@aar")
}
