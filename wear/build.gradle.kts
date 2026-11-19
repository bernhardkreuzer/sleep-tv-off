plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.bernhardkreuzer.sleeptvoff.wear"
    compileSdk = 35

    defaultConfig {
        // WICHTIG: gleiche applicationId wie das Handy-Modul, sonst
        // synchronisiert der Wearable Data Layer nichts zwischen Watch und Handy.
        applicationId = "com.bernhardkreuzer.sleeptvoff"
        minSdk = 30
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
    implementation("androidx.health:health-services-client:1.0.0")
    // Health Services liefert ListenableFuture zurück, die Klasse kommt aus Guava.
    implementation("com.google.guava:guava:33.3.1-android")
    implementation("androidx.work:work-runtime:2.9.1")
    implementation("com.google.android.gms:play-services-wearable:18.2.0")
}
