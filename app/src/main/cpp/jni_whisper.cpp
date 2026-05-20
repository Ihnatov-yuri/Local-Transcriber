// Thin JNI wrapper around whisper.cpp.
//
// Exposes four entry points to Kotlin:
//   nativeInit(modelPath) -> long  (opaque context handle)
//   nativeTranscribe(handle, samples[], sampleRate, langTag, translate) -> Segment[]
//   nativeRelease(handle)
//   nativeSystemInfo() -> String
//
// Each call holds the context across the JNI boundary as a jlong. Whisper's
// own context is single-threaded; the Kotlin side serializes calls via a
// Mutex.

#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <mutex>

#include "whisper.h"

#define LOG_TAG "transcriber_jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// Cached Kotlin class + method handles for emitting Segment objects.
struct SegmentBinding {
    jclass    cls          = nullptr;
    jmethodID ctor         = nullptr;   // (DDLjava/lang/String;)V
};

SegmentBinding gSeg;

bool ensureSegmentBinding(JNIEnv* env) {
    if (gSeg.cls && gSeg.ctor) return true;
    jclass local = env->FindClass("nl/ihnatov/transcriber/asr/RawSegment");
    if (!local) {
        LOGE("FindClass RawSegment failed");
        return false;
    }
    gSeg.cls = static_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    gSeg.ctor = env->GetMethodID(gSeg.cls, "<init>", "(DDLjava/lang/String;)V");
    return gSeg.ctor != nullptr;
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_nl_ihnatov_transcriber_asr_WhisperCppBackend_nativeInit(
    JNIEnv* env, jobject /*thiz*/, jstring modelPath) {

    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    LOGI("nativeInit model=%s", path);

    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;   // No reliable GPU path on Android yet.

    whisper_context* ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(modelPath, path);

    if (!ctx) {
        LOGE("whisper_init_from_file_with_params returned null");
        return 0;
    }
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_nl_ihnatov_transcriber_asr_WhisperCppBackend_nativeRelease(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    if (handle == 0) return;
    auto* ctx = reinterpret_cast<whisper_context*>(handle);
    whisper_free(ctx);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_nl_ihnatov_transcriber_asr_WhisperCppBackend_nativeTranscribe(
    JNIEnv* env, jobject /*thiz*/,
    jlong handle,
    jfloatArray samplesArr,
    jint /*sampleRate*/,
    jstring langTag,
    jboolean translate) {

    if (handle == 0) return nullptr;
    if (!ensureSegmentBinding(env)) return nullptr;
    auto* ctx = reinterpret_cast<whisper_context*>(handle);

    jsize n = env->GetArrayLength(samplesArr);
    if (n <= 0) {
        return env->NewObjectArray(0, gSeg.cls, nullptr);
    }
    std::vector<float> samples(n);
    env->GetFloatArrayRegion(samplesArr, 0, n, samples.data());

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_progress = false;
    params.print_realtime = false;
    params.print_special  = false;
    params.print_timestamps = true;
    params.translate      = translate == JNI_TRUE;
    params.no_context     = true;
    params.suppress_blank = true;

    std::string lang;
    if (langTag) {
        const char* tag = env->GetStringUTFChars(langTag, nullptr);
        lang = tag ? tag : "";
        env->ReleaseStringUTFChars(langTag, tag);
    }
    if (!lang.empty() && lang != "auto") {
        params.language = lang.c_str();
    } else {
        params.language = nullptr;   // auto-detect
    }

    int rc = whisper_full(ctx, params, samples.data(), n);
    if (rc != 0) {
        LOGE("whisper_full failed: %d", rc);
        return nullptr;
    }

    int nSeg = whisper_full_n_segments(ctx);
    jobjectArray out = env->NewObjectArray(nSeg, gSeg.cls, nullptr);
    if (!out) return nullptr;

    for (int i = 0; i < nSeg; ++i) {
        // Whisper timestamps are in 10ms units.
        double t0 = whisper_full_get_segment_t0(ctx, i) * 0.01;
        double t1 = whisper_full_get_segment_t1(ctx, i) * 0.01;
        const char* text = whisper_full_get_segment_text(ctx, i);
        jstring jtext = env->NewStringUTF(text ? text : "");
        jobject seg = env->NewObject(gSeg.cls, gSeg.ctor, t0, t1, jtext);
        env->SetObjectArrayElement(out, i, seg);
        env->DeleteLocalRef(jtext);
        env->DeleteLocalRef(seg);
    }
    return out;
}

extern "C" JNIEXPORT jstring JNICALL
Java_nl_ihnatov_transcriber_asr_WhisperCppBackend_nativeSystemInfo(
    JNIEnv* env, jobject /*thiz*/) {
    const char* info = whisper_print_system_info();
    return env->NewStringUTF(info ? info : "");
}
