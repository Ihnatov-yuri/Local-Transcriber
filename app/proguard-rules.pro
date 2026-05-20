# Compose + Room are largely auto-handled by their AAR consumer rules.
# Keep JNI entry points for the whisper backend.
-keepclassmembers class nl.ihnatov.transcriber.asr.WhisperCppBackend {
    private native <methods>;
}

# Kotlinx-serialization runtime
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
