plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.raunakpatil.roniepocket.imagegen"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild {
            cmake { cppFlags += "-std=c++17" }
        }
    }

    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
    }
}

dependencies {
    api(libs.onnxruntime.android)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
    testImplementation(libs.json)
}
