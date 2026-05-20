package nl.ihnatov.transcriber.asr

import kotlinx.coroutines.flow.Flow

/**
 * Pluggable ASR backend. Two implementations target the MVP:
 *   - [WhisperCppBackend] (Tier 1 for MVP, MIT).
 *   - [Gemma4Backend] (Tier 1 in the long-term plan, stub until LiteRT-LM
 *     audio is wired up).
 *
 * The interface is intentionally narrow: load model -> run transcribe ->
 * release. Diarization is handled by a separate component; this contract
 * stays pure ASR + AST.
 */
interface AsrBackend {

    /** Human-readable id, e.g. "whisper.cpp / large-v3-turbo-q4_0". */
    val id: String

    /** True once the backend has a model loaded and ready. */
    val isReady: Boolean

    /**
     * Load the model file. Heavy; call on a background dispatcher.
     * Safe to call again to reload (releases the previous handle first).
     */
    suspend fun load(modelPath: String): Result<Unit>

    /**
     * Transcribe a mono 16-bit PCM buffer (already normalized to float32 in [-1,1]).
     *
     * @param samples  audio samples, mono, sample rate must match [sampleRate]
     * @param sampleRate typically 16_000
     * @param language ISO-639-1 (e.g. "ar", "uk"), null/`"auto"` for auto-detect
     * @param translate emit English even if source is another language
     * @param progress optional progress callback (0..1)
     */
    suspend fun transcribe(
        samples: FloatArray,
        sampleRate: Int,
        language: String?,
        translate: Boolean,
        progress: ((Float) -> Unit)? = null,
    ): Result<List<RawSegment>>

    /** Release native resources. Subsequent transcribe() calls must fail. */
    suspend fun release()
}

/**
 * Raw segment as emitted by the native side. Used in JNI as a plain Java
 * class with a (double, double, String) constructor — see [RawSegment]'s
 * field signature; the JNI shim relies on exactly these names.
 *
 * Domain segments ([nl.ihnatov.transcriber.data.Segment]) are derived from
 * this by attaching a recording id, language, and speaker.
 */
data class RawSegment(
    @JvmField val startSeconds: Double,
    @JvmField val endSeconds: Double,
    @JvmField val text: String,
)

enum class AsrBackendKind {
    WhisperCpp,
    Gemma4,
}

/** Stream events for UI status, separate from ASR results themselves. */
sealed interface AsrEvent {
    data class Stage(val label: String, val fraction: Float) : AsrEvent
    data class Done(val segments: List<RawSegment>) : AsrEvent
    data class Failed(val reason: String) : AsrEvent
}

interface AsrProgressStream {
    fun events(): Flow<AsrEvent>
}
