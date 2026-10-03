plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "de.jarvis.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.jarvis.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"
    }

    buildTypes {
        // Debug-Build: wird automatisch signiert und lässt sich direkt installieren.
        debug { isMinifyEnabled = false }
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Picovoice Porcupine: erkennt das Wort "Jarvis" offline auf dem Handy
    implementation("ai.picovoice:porcupine-android:4.0.2")
}
