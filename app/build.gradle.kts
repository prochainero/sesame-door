plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.dooropener"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.dooropener"
        minSdk = 26
        targetSdk = 34
        versionCode = 14
        versionName = "v14-google-auth"
    }

    // 固定簽章：每次編譯都用同一把金鑰，新版才能直接覆蓋安裝，設定資料不會被清掉
    signingConfigs {
        getByName("debug") {
            storeFile = file("door.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
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
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("androidx.core:core-ktx:1.13.1")
}
