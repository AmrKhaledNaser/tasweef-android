plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The build number comes from GitHub Actions; it must keep growing so phones accept the update.
val buildNumber = (System.getenv("VERSION_CODE") ?: "2").toInt()

android {
    namespace = "app.web.tasweef.twa"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.web.tasweef.twa" // same id and signing key as the first APK, so it installs as an update
        minSdk = 24
        targetSdk = 35
        versionCode = buildNumber
        versionName = System.getenv("VERSION_NAME") ?: "2.0.0"
    }

    signingConfigs {
        create("release") {
            val ks = System.getenv("KEYSTORE_FILE")
            if (ks != null) {
                storeFile = file(ks)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS") ?: "my-key-alias"
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (System.getenv("KEYSTORE_FILE") != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    // One APK per processor type: a universal one carries Python and ffmpeg twice (about 100 MB).
    // tasweef.apk is the 64-bit one almost every phone uses; tasweef-32bit.apk is for older phones.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    // yt-dlp's Python and ffmpeg ship as native libraries that must be extracted on install
    packaging { jniLibs.useLegacyPackaging = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.google.androidbrowserhelper:androidbrowserhelper:2.6.2")
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
    implementation("androidx.core:core-ktx:1.13.1")
}
