// Thin JNI wrapper around whisper.cpp.
//
// Exposes six entry points to Kotlin:
//   nativeInit(modelPath) -> long  (opaque handle, see WhisperHandle below)
//   nativeTranscribe(handle, samples[], sampleRate, langTag, translate, initialPrompt) -> Segment[]
//   nativeRequestCancel(handle)  (flips the abort flag whisper_full polls)
//   nativeResetCancel(handle)  (Kotlin calls this before each new transcribe, see its own doc comment)
//   nativeRelease(handle)
//   nativeSystemInfo() -> String
//
// Each call holds the context across the JNI boundary as a jlong. Whisper's
// own context is single-threaded; the Kotlin side serializes calls via a
// Mutex.

#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <string>
#include <vector>
#include <mutex>

#include "whisper.h"

#define LOG_TAG "transcriber_jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// Wraps the whisper_context so a cancel flag can travel alongside the
// opaque jlong handle Kotlin holds. whisper_full() has no suspension
// points of its own — it's one long blocking call — so Kotlin-side
// coroutine cancellation can't interrupt it on its own. Instead, Kotlin
// sets this flag (nativeRequestCancel) and whisper's own abort_callback
// (polled during ggml graph computation, much finer-grained than a whole
// 30s chunk) picks it up and unwinds whisper_full() early.
struct WhisperHandle {
    whisper_context* ctx = nullptr;
    std::atomic<bool> cancelRequested{false};
};

bool checkAbort(void* data) {
    return static_cast<WhisperHandle*>(data)->cancelRequested.load(std::memory_order_relaxed);
}

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
    auto* handle = new WhisperHandle{ctx};
    return reinterpret_cast<jlong>(handle);
}

extern "C" JNIEXPORT void JNICALL
Java_nl_ihnatov_transcriber_asr_WhisperCppBackend_nativeRelease(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    if (handle == 0) return;
    auto* h = reinterpret_cast<WhisperHandle*>(handle);
    whisper_free(h->ctx);
    delete h;
}

// Called from Kotlin's CancellableContinuation.invokeOnCancellation while
// nativeTranscribe is still blocked on whisper_full() in another call —
// this only flips a flag, it never touches the whisper_context itself, so
// it's safe to call concurrently from a different thread than the one
// running the transcription.
extern "C" JNIEXPORT void JNICALL
Java_nl_ihnatov_transcriber_asr_WhisperCppBackend_nativeRequestCancel(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    if (handle == 0) return;
    reinterpret_cast<WhisperHandle*>(handle)->cancelRequested.store(true, std::memory_order_relaxed);
}

// Called from Kotlin BEFORE arming invokeOnCancellation for a new
// nativeTranscribe call, not from inside nativeTranscribe itself. Doing
// the reset there (as an earlier version of this file did) raced against
// invokeOnCancellation firing synchronously for an already-cancelled Job:
// if invokeOnCancellation ran (setting the flag) before nativeTranscribe's
// own entry-reset, that reset would silently wipe the pending cancel.
// Resetting here, strictly before Kotlin registers the cancellation
// handler, closes that window.
extern "C" JNIEXPORT void JNICALL
Java_nl_ihnatov_transcriber_asr_WhisperCppBackend_nativeResetCancel(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    if (handle == 0) return;
    reinterpret_cast<WhisperHandle*>(handle)->cancelRequested.store(false, std::memory_order_relaxed);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_nl_ihnatov_transcriber_asr_WhisperCppBackend_nativeTranscribe(
    JNIEnv* env, jobject /*thiz*/,
    jlong handle,
    jfloatArray samplesArr,
    jint /*sampleRate*/,
    jstring langTag,
    jboolean translate,
    jstring initialPrompt) {

    if (handle == 0) return nullptr;
    if (!ensureSegmentBinding(env)) return nullptr;
    auto* h = reinterpret_cast<WhisperHandle*>(handle);
    auto* ctx = h->ctx;

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
    params.abort_callback           = checkAbort;
    params.abort_callback_user_data = h;

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

    // Vocabulary bias: a short list of proper nouns / jargon the decoder
    // should be primed to expect (PromptStore's global + per-language
    // terms, joined by the Kotlin side). Same std::string-lifetime pattern
    // as `lang` above — params.initial_prompt just holds the pointer, so
    // `prompt` must outlive the whisper_full() call below.
    std::string prompt;
    if (initialPrompt) {
        const char* p = env->GetStringUTFChars(initialPrompt, nullptr);
        prompt = p ? p : "";
        env->ReleaseStringUTFChars(initialPrompt, p);
    }
    params.initial_prompt = prompt.empty() ? nullptr : prompt.c_str();

    int rc = whisper_full(ctx, params, samples.data(), n);
    if (rc != 0) {
        // Always log at ERROR so a logcat/crash-triage filter on
        // "whisper_full failed" never misses a genuine failure — whisper.cpp
        // has several internal failure paths (mel-spectrogram, language
        // auto-detect, decoder/audio_ctx validation) that return non-zero
        // without ever consulting abort_callback, so a cancel request being
        // in flight at the same moment doesn't mean THIS rc is just a clean
        // abort. Log the cancel as separate, additional context instead of
        // downgrading or replacing the error.
        LOGE("whisper_full failed: %d", rc);
        if (h->cancelRequested.load(std::memory_order_relaxed)) {
            LOGI("(a cancel was also requested for this call)");
        }
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
