plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "nl.ihnatov.transcriber"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "nl.ihnatov.transcriber"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-mvp"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        ndk {
            abiFilters += setOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DGGML_OPENMP=OFF",
                    // Android 15+ requires native .so files aligned at 16 KB
                    // page boundaries. NDK r27 does this by default for our
                    // own targets, but we need to propagate it to whisper.cpp's
                    // libraries (libggml*.so, libwhisper.so) too.
                    "-DCMAKE_SHARED_LINKER_FLAGS_INIT=-Wl,-z,max-page-size=16384",
                )
                cppFlags += "-std=c++17"
            }
        }
    }

    // whisper.cpp lives at app/src/main/cpp/whisper.cpp/ (see README).
    // We build it via the CMakeLists in app/src/main/cpp/.
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-Xjvm-default=all")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.text.google.fonts)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.common)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Gemma 4 audio backend (LiteRT-LM Engine + Conversation with AudioBytes)
    implementation(libs.litertlm.android)

    // On-device speaker diarization (pyannote segmentation + 3D-Speaker embeddings).
    //
    // sherpa-onnx 8.5.1's `libsherpa-onnx-jni.so` requires the
    // versioned symbol `OrtGetApiBase@VERS_1.24.3` (verified via
    // `llvm-readelf --dyn-syms`). The community-bundled
    // `com.bihe0832.android:lib-onnx:6.16.7` ships ORT 1.17.1
    // (symbol `VERS_1.17.1` — wrong). We exclude it and pull in
    // Microsoft's ORT 1.24.3 instead — that publishes the matching
    // versioned symbol AND ships 16 KB-aligned .so files for the
    // Android 15 page-size requirement. Diarization works AND no
    // alignment warning. If sherpa-onnx is upgraded the
    // `onnxruntime` version pin in libs.versions.toml must move
    // with it.
    implementation(libs.sherpa.onnx) {
        exclude(group = "com.bihe0832.android", module = "lib-onnx")
    }
    implementation(libs.onnxruntime.android)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
}
