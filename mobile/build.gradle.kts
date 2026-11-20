plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.bernhardkreuzer.sleeptvoff.mobile"
    compileSdk = 35

    defaultConfig {
        // WICHTIG: gleiche applicationId wie das Watch-Modul, sonst
        // synchronisiert der Wearable Data Layer nichts zwischen Watch und Handy.
        applicationId = "com.bernhardkreuzer.sleeptvoff"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // Fester Schlüssel im Projekt: Watch- und Handy-App brauchen dieselbe Signatur,
    // egal ob sie am PC oder auf GitHub gebaut werden.
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("signing/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
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
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.google.android.gms:play-services-wearable:18.2.0")

    testImplementation("junit:junit:4.13.2")
}
