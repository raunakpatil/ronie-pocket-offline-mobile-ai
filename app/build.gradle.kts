plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.raunakpatil.roniepocket"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.raunakpatil.roniepocket"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures { compose = true; buildConfig = true }

    // ggml loads its CPU-variant and Vulkan plugins from the native library directory at runtime,
    // so the .so files must be extracted on install rather than kept inside the APK.
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    implementation(project(":core:engine"))
    implementation(project(":core:imagegen"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.onnxruntime.android)   // Paradee TTS + bge-small memory embeddings (int8 ONNX)
    implementation(libs.sqlcipher.android)     // encrypted memory database (Phase 6)
    implementation(libs.androidx.sqlite)
    implementation(libs.jsoup)                  // web search + page reading (Phase 8)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.json)   // real org.json on the PC (Android's is a stub in unit tests)
}
