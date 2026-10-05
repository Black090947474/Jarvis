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
        versionCode = 29
        versionName = "18.0"
        // Nur für moderne Handys (64-Bit ARM) – macht die App viel kleiner
        ndk { abiFilters += listOf("arm64-v8a") }
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
    // ONNX Runtime: führt das openWakeWord-Modell "Hey Jarvis" offline auf dem Handy aus
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
    // Geofencing für ortsbasierte Erinnerungen ("wenn ich zu Hause ankomme")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    // Text aus PDF-Dateien lesen (Datei-Assistent)
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
}
