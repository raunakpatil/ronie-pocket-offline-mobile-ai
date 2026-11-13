plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.raunakpatil.roniepocket.engine"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
        // The prebuilt llama.cpp libraries are arm64 only (every phone tier is arm64).
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild {
            cmake { cppFlags += "-std=c++17" }
        }
    }

    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
    }

    // llama.cpp / ggml libraries (incl. the 7 CPU-variant plugins and Vulkan) are built by
    // scripts\build-engine-libs.cmd into src/main/jniLibs/arm64-v8a (the default location, so no
    // sourceSets config is needed); headers go to prebuilt/include.
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
