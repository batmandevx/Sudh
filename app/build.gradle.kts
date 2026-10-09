plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.shuddh.lab"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.shuddh.lab"
        minSdk = 26
        targetSdk = 35
        versionCode = 10
        versionName = "5.1"
        // Modern Android phones are arm64; shipping one ABI keeps the download small.
        ndk { abiFilters += "arm64-v8a" }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging { jniLibs { useLegacyPackaging = true } }
    androidResources { noCompress += listOf("tflite", "task") }
}

configurations.all {
    // ML Kit's telemetry uploader. Removing it means no library can send usage logs even though the
    // app now declares INTERNET for the optional online map.
    exclude(group = "com.google.android.datatransport", module = "transport-backend-cct")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    val camerax = "1.4.1"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    implementation("com.google.zxing:core:3.5.3")

    // On-device AI: MediaPipe LLM runtime (Qwen chat + tool-router models) and bundled ML Kit vision models.
    implementation("com.google.mediapipe:tasks-genai:0.10.27")
    implementation("com.google.mediapipe:tasks-text:0.10.29")
    implementation("com.google.mediapipe:tasks-vision:0.10.29")
    // Compile-time only: the no-op CCTDestination stub implements this interface (no uploader is bundled).
    compileOnly("com.google.android.datatransport:transport-runtime:3.1.0")
    compileOnly("com.google.android.datatransport:transport-api:3.0.0")
    // OpenStreetMap street map (only used when the user enables the online map in Settings).
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    implementation("com.google.mlkit:image-labeling:17.0.9")

    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-devanagari:16.0.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
