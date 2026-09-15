package nl.ihnatov.transcriber.asr

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
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
 *
 * [promptStore] is optional so tests and any other caller that doesn't
 * care about vocabulary biasing can still construct this directly; real
 * app wiring (see [AsrFactory]) always passes one.
 */
class WhisperCppBackend(private val promptStore: PromptStore? = null) : AsrBackend {

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
                // nativeTranscribe is one long blocking JNI call — whisper_full
                // has no suspension points of its own, so plain coroutine
                // cancellation can't interrupt it. suspendCancellableCoroutine
                // lets us bridge the two: invokeOnCancellation fires on
                // whatever thread calls Job.cancel() (e.g. the user tapping
                // Stop), even while THIS thread is still blocked inside
                // nativeTranscribe below — it just flips a flag that
                // whisper.cpp's abort_callback polls internally, so the
                // native call itself unwinds early instead of running to
                // completion regardless of Stop.
                val raw = suspendCancellableCoroutine<Array<RawSegment>?> { cont ->
                    cont.invokeOnCancellation { nativeRequestCancel(h) }
                    val result = nativeTranscribe(
                        h,
                        samples,
                        sampleRate,
                        language ?: "auto",
                        translate,
                        promptStore?.whisperPrompt(language) ?: "",
                    )
                    if (cont.isActive) cont.resume(result)
                }
                if (raw == null) {
                    Result.failure(IllegalStateException("whisper_full returned null"))
                } else {
                    progress?.invoke(1f)
                    Result.success(raw.toList())
                }
            } catch (t: CancellationException) {
                // Let cooperative cancellation propagate — TranscriptionJobManager's
                // catch (t: CancellationException) paints "Cancelled", not an
                // error. Catching Throwable below would otherwise swallow this
                // into a Result.failure and break that contract.
                throw t
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
        initialPrompt: String,
    ): Array<RawSegment>?

    private external fun nativeRelease(handle: Long)
    private external fun nativeSystemInfo(): String
    private external fun nativeRequestCancel(handle: Long)

    companion object {
        private const val TAG = "WhisperCppBackend"

        init {
            // libtranscriber_jni.so is the JNI shim; it dynamically links
            // libwhisper.so produced by the bundled whisper.cpp build.
            System.loadLibrary("transcriber_jni")
        }
    }
}
