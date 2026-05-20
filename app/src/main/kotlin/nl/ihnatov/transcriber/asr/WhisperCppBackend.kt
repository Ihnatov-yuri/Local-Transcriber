package nl.ihnatov.transcriber.asr

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Whisper.cpp ASR backend.
 *
 * Loads a quantized .bin (or .gguf) model from local storage via the
 * native shim ([nativeInit]). Calls are serialized — whisper_full is not
 * thread-safe across the same context.
 *
 * The native library `libtranscriber_jni.so` is produced by the CMake
 * subproject in app/src/main/cpp/. It bundles whisper.cpp at build time;
 * see scripts/fetch-whisper-cpp.sh in the project README.
 */
class WhisperCppBackend : AsrBackend {

    override val id: String get() = "whisper.cpp"

    private var handle: Long = 0
    private val mutex = Mutex()

    override val isReady: Boolean
        get() = handle != 0L

    override suspend fun load(modelPath: String): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (handle != 0L) {
                nativeRelease(handle)
                handle = 0
            }
            val h = nativeInit(modelPath)
            if (h == 0L) {
                Result.failure(IllegalStateException("whisper_init failed for $modelPath"))
            } else {
                handle = h
                Log.i(TAG, "loaded model: $modelPath")
                Log.i(TAG, "system: ${nativeSystemInfo()}")
                Result.success(Unit)
            }
        }
    }

    override suspend fun transcribe(
        samples: FloatArray,
        sampleRate: Int,
        language: String?,
        translate: Boolean,
        progress: ((Float) -> Unit)?,
    ): Result<List<RawSegment>> = withContext(Dispatchers.Default) {
        mutex.withLock {
            val h = handle
            if (h == 0L) return@withLock Result.failure(IllegalStateException("model not loaded"))
            try {
                progress?.invoke(0.05f)
                val raw = nativeTranscribe(
                    h,
                    samples,
                    sampleRate,
                    language ?: "auto",
                    translate,
                )
                if (raw == null) {
                    Result.failure(IllegalStateException("whisper_full returned null"))
                } else {
                    progress?.invoke(1f)
                    Result.success(raw.toList())
                }
            } catch (t: Throwable) {
                Result.failure(t)
            }
        }
    }

    override suspend fun release(): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (handle != 0L) {
                nativeRelease(handle)
                handle = 0
            }
        }
    }

    // --- Native methods (implemented in jni_whisper.cpp) ---
    private external fun nativeInit(modelPath: String): Long
    private external fun nativeTranscribe(
        handle: Long,
        samples: FloatArray,
        sampleRate: Int,
        language: String,
        translate: Boolean,
    ): Array<RawSegment>?

    private external fun nativeRelease(handle: Long)
    private external fun nativeSystemInfo(): String

    companion object {
        private const val TAG = "WhisperCppBackend"

        init {
            // libtranscriber_jni.so is the JNI shim; it dynamically links
            // libwhisper.so produced by the bundled whisper.cpp build.
            System.loadLibrary("transcriber_jni")
        }
    }
}
