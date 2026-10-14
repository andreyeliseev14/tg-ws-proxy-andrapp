plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

android {
    namespace = "app.tgwsproxy"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.tgwsproxy"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.11.2-android"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Подписываем отладочным ключом, чтобы APK можно было сразу установить.
            signingConfig = signingConfigs.getByName("debug")
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

chaquopy {
    defaultConfig {
        version = "3.12"
        pip {
            install("httpx==0.28.1")
            install("h2")
            install("certifi")
            install("cryptography")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}
