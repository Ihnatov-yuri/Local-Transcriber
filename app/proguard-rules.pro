# Compose + Room are largely auto-handled by their AAR consumer rules.
# Keep JNI entry points for the whisper backend.
-keepclassmembers class nl.ihnatov.transcriber.asr.WhisperCppBackend {
    private native <methods>;
}

# Kotlinx-serialization runtime
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# sherpa-onnx: libsherpa-onnx-jni.so looks up the Kotlin config classes
# (OfflineRecognizerConfig, OfflineModelConfig, OfflineSpeakerDiarizationConfig,
# FeatureConfig, ...) and their FIELDS by name via JNI GetFieldID. The AAR
# ships an empty proguard.txt, so without this R8 renames/strips them and
# every sherpa call aborts with NoSuchFieldError in release builds.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# LiteRT-LM (Gemma 4): same situation — liblitertlm_jni.so resolves its
# Kotlin API classes by name and the AAR ships no consumer rules.
-keep class com.google.ai.edge.litertlm.** { *; }

# jni_whisper.cpp builds each result via FindClass("nl/ihnatov/transcriber/asr/RawSegment")
# + GetMethodID("<init>", "(DDLjava/lang/String;)V"). That 3-arg constructor
# is a @JvmOverloads-generated overload nothing on the Kotlin side calls, so
# R8 strips it (found live: "no non-static method ...RawSegment;.<init>").
-keep class nl.ihnatov.transcriber.asr.RawSegment { <init>(...); *; }
