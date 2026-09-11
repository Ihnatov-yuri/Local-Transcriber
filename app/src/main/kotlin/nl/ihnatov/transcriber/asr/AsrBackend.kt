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
 * Raw segment as emitted by the native side. whisper.cpp's JNI shim
 * (`jni_whisper.cpp`, `ensureSegmentBinding`) looks up this class's
 * constructor with `GetMethodID(..., "<init>", "(DDLjava/lang/String;)V")`
 * — an exact 3-arg signature. `@JvmOverloads` keeps that overload alive
 * alongside the 4-arg one now that [words] exists; removing it breaks
 * whisper.cpp transcription at the JNI boundary with no compile-time
 * warning, so don't drop it even though [words] having a default makes it
 * look redundant.
 *
 * [words] is optional and currently only populated by the sherpa-onnx-
 * backed engines (Parakeet, Omnilingual, Nemotron) and whisper.cpp's own
 * token timestamps where wired — null means "no word-level data for this
 * segment," not "empty."
 *
 * Domain segments ([nl.ihnatov.transcriber.data.Segment]) are derived from
 * this by attaching a recording id, language, and speaker.
 */
data class RawSegment @JvmOverloads constructor(
    @JvmField val startSeconds: Double,
    @JvmField val endSeconds: Double,
    @JvmField val text: String,
    val words: List<Word>? = null,
)

enum class AsrBackendKind {
    WhisperCpp,
    Gemma4,
    /** sherpa-onnx offline transducer — Parakeet TDT 0.6B v3. English, Dutch, Ukrainian; not Arabic. */
    Parakeet,
    /** sherpa-onnx offline CTC — Meta Omnilingual ASR 300M. Broad language coverage including Gulf Arabic (`afb`). */
    Omnilingual,
    /** sherpa-onnx online (streaming) NeMo CTC — Nemotron 3.5 ASR streaming 0.6B. Record screen / dictation only. */
    NemotronStream,
}

/**
 * "Auto" engine policy for the RUN sheet's default pick, applied whenever
 * the user hasn't manually overridden the engine: Parakeet is the anchor
 * for every language combination except Arabic (it doesn't support
 * Arabic at all); Omnilingual covers Arabic, alone or mixed with other
 * languages, since it's the one engine with real Gulf Arabic (`afb`)
 * coverage. Simpler than the 2026-09 plan's original 4-row language
 * matrix — chosen deliberately over it — while keeping full feature
 * parity elsewhere (Super mode, arbitration, word attribution, etc.).
 */
fun defaultEngineFor(languages: Set<String>): AsrBackendKind =
    if ("ar" in languages) AsrBackendKind.Omnilingual else AsrBackendKind.Parakeet

/** Stream events for UI status, separate from ASR results themselves. */
sealed interface AsrEvent {
    data class Stage(val label: String, val fraction: Float) : AsrEvent
    data class Done(val segments: List<RawSegment>) : AsrEvent
    data class Failed(val reason: String) : AsrEvent
}

interface AsrProgressStream {
    fun events(): Flow<AsrEvent>
}
