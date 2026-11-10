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
        versionCode = 59
        versionName = "35.1"
        // Nur für moderne Handys (64-Bit ARM) – macht die App viel kleiner
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // Fester Schlüssel: jede neue Version installiert sich ÜBER die alte (kein Löschen mehr nötig)
    signingConfigs {
        create("jarvis") {
            storeFile = file("jarvis.keystore")
            storePassword = "c97ccb371ad9751903dcdbf2289956a2"
            keyAlias = "jarvis"
            keyPassword = "c97ccb371ad9751903dcdbf2289956a2"
        }
    }

    buildTypes {
        // Debug-Build mit festem Schlüssel, lässt sich direkt installieren und updaten.
        debug { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("jarvis") }
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
    // Profi-Modus: Shizuku (ADB-Rechte ohne Root, nur wenn du es einrichtest)
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    // QR-Codes offline lesen (Scan)
    implementation("com.google.zxing:core:3.5.3")
    // Eigene KI: Sprachmodell läuft offline auf dem Handy (Google MediaPipe LLM Inference)
    implementation("com.google.mediapipe:tasks-genai:0.10.24")
}
