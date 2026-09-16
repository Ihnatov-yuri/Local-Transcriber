import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "nl.ihnatov.transcriber"
    compileSdk = 37
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "nl.ihnatov.transcriber"
        minSdk = 29
        targetSdk = 37
        // Bump both per shipped build (docs/PLAN-2026-09.md §6). versionCode
        // must stay monotonic — Obtainium and the OS use it to decide
        // whether an APK is an upgrade.
        versionCode = 103
        versionName = "1.1.2"

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
                    // page boundaries. The NDK does this by default for our
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

    // Release signing. The keystore and its secrets never enter git: they
    // are read from local.properties (gitignored) —
    //   release.storeFile=/Users/you/.android/transcriber.jks
    //   release.storePassword=…
    //   release.keyAlias=transcriber
    //   release.keyPassword=…
    // When the keys are absent the release build is produced UNSIGNED
    // (app-release-unsigned.apk), which still compiles and lets CI/R8
    // run; scripts/release.sh refuses to publish an unsigned APK.
    val localProps = Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    val releaseStoreFile = localProps.getProperty("release.storeFile")?.let { file(it) }
    val hasReleaseSigning = releaseStoreFile?.exists() == true &&
        localProps.getProperty("release.storePassword") != null &&
        localProps.getProperty("release.keyAlias") != null &&
        localProps.getProperty("release.keyPassword") != null

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = localProps.getProperty("release.storePassword")
                keyAlias = localProps.getProperty("release.keyAlias")
                keyPassword = localProps.getProperty("release.keyPassword")
                // v1 is irrelevant at minSdk 29; v2 + v3 (key rotation) as
                // the plan asks. v4 is only for incremental installs.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
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
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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

// Built-in Kotlin (AGP 9+) reads jvmTarget from android.compileOptions by
// default; set it explicitly here anyway since we also carry a compiler arg.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // Stable replacement for the old -Xjvm-default=all (deprecated
        // since Kotlin 2.2.0); no-compatibility is its direct equivalent.
        freeCompilerArgs.addAll("-jvm-default=no-compatibility")
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

    // Frosted-glass backdrop blur for the PlayerBar "veil" — see
    // ui/theme/Type.kt / Primitives.kt header comments and
    // memory: project-design-migration-lit-field-2026-09.
    implementation(libs.haze)

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
    implementation(libs.commons.compress)
    implementation(libs.commonmark)
    implementation(libs.commonmark.ext.gfm.tables)

    // Gemma 4 audio backend (LiteRT-LM Engine + Conversation with AudioBytes)
    implementation(libs.litertlm.android)

    // On-device speaker diarization (pyannote segmentation + 3D-Speaker
    // embeddings) and ASR engines added in the 2026-09 catch-up (Parakeet,
    // Omnilingual, Nemotron streaming) all come from the same runtime.
    //
    // This is the official k2-fsa Android AAR, not a Maven artifact — see
    // app/libs/README.md and scripts/fetch-sherpa-onnx.sh. It bundles its
    // own libonnxruntime.so per ABI (16 KB aligned, verified via
    // `llvm-readelf -l`), so there's no separate onnxruntime dependency or
    // versioned-symbol pin to maintain.
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
}
