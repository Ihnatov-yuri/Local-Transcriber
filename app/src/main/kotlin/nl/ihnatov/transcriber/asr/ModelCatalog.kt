package nl.ihnatov.transcriber.asr

/**
 * Curated list of downloadable ASR models, with stable HuggingFace URLs.
 *
 * Tuning notes for the Transcriber use case (Arabic dialects + Ukrainian → English):
 *   - ggml-tiny:       fast, good enough for English. Arabic/Ukrainian quality is poor.
 *   - ggml-base:       reasonable English; light Arabic. Still misses dialects.
 *   - ggml-small:      first model with decent Arabic + Ukrainian. Recommended baseline.
 *   - large-v3-turbo:  matches the Mac app. Quantized q5_0 is the sweet spot on mobile.
 *
 * Gemma 4 LiteRT-LM is intentionally not auto-downloaded — its distribution channel
 * is google-ai-edge LiteRT-LM and gating differs. Hooked up in milestone M6.
 */
enum class ModelRole { Asr, Diarization }

data class CatalogEntry(
    val id: String,
    val displayName: String,
    val description: String,
    val sizeMb: Int,
    val url: String,
    val filename: String,
    val role: ModelRole = ModelRole.Asr,
    val kind: AsrBackendKind = AsrBackendKind.WhisperCpp,   // unused when role=Diarization
    val recommended: Boolean = false,
    /**
     * Epoch-millis cutoff: a local copy of this file with `lastModified()`
     * older than this is missing a meaningful upstream improvement and
     * should be re-downloaded. The Settings → Installed UI surfaces an
     * "Update" affordance in that case.
     *
     * Set for Gemma 4 LiteRT-LM weights to the v0.11.0 release date
     * (2026-05-05) — that release ships **MTP speculative decoding** which
     * needs new weights AND the new SDK; users on older `.litertlm` files
     * get the old, ~2× slower decode path and have no way to know.
     */
    val requiredAfterMillis: Long? = null,
    /** Short reason shown next to the Update affordance. */
    val updateReason: String? = null,
)

/**
 * 2026-05-05 00:00:00 UTC, epoch millis. The day LiteRT-LM v0.11.0 shipped
 * MTP (multi-token-prediction) speculative decoding for Gemma 4 — requires
 * matching weights re-pulled on/after this date plus the v0.11.0 SDK.
 * Source: https://github.com/google-ai-edge/LiteRT-LM/releases
 */
const val GEMMA_4_MTP_CUTOFF_MILLIS: Long = 1_777_939_200_000L

object ModelCatalog {

    val entries: List<CatalogEntry> = listOf(
        CatalogEntry(
            id = "whisper-tiny",
            displayName = "Whisper tiny",
            description = "Fastest. English-OK. Marginal on Arabic and Ukrainian.",
            sizeMb = 75,
            filename = "ggml-tiny.bin",
            url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin",
        ),
        CatalogEntry(
            id = "whisper-base",
            displayName = "Whisper base",
            description = "Light multilingual. Reasonable English, weak Arabic dialects.",
            sizeMb = 142,
            filename = "ggml-base.bin",
            url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin",
        ),
        CatalogEntry(
            id = "whisper-small",
            displayName = "Whisper small",
            description = "First step into real multilingual. Decent Arabic + Ukrainian.",
            sizeMb = 466,
            filename = "ggml-small.bin",
            url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin",
            recommended = true,
        ),
        CatalogEntry(
            id = "whisper-large-v3-turbo-q5_0",
            displayName = "Whisper large-v3-turbo (q5_0)",
            description = "Best for Arabic dialects + Ukrainian. Quantized for mobile.",
            sizeMb = 574,
            filename = "ggml-large-v3-turbo-q5_0.bin",
            url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin",
            recommended = true,
        ),
        CatalogEntry(
            id = "gemma-4-e2b",
            displayName = "Gemma 4 E2B (audio)",
            description = "Google's on-device multimodal model. Transcribe + translate in one pass. " +
                "Big download — plan on Wi-Fi.",
            sizeMb = 2590,
            filename = "gemma-4-E2B-it.litertlm",
            url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
            kind = AsrBackendKind.Gemma4,
            // 2026-05-05 00:00:00 UTC: LiteRT-LM 0.11.0 release date.
            // Weights re-pulled after this point enable MTP speculative
            // decoding (~2× faster decode on mobile GPUs).
            requiredAfterMillis = GEMMA_4_MTP_CUTOFF_MILLIS,
            updateReason = "Re-download to enable MTP (~2× faster decode on GPU)",
        ),
        CatalogEntry(
            id = "gemma-4-e4b",
            displayName = "Gemma 4 E4B (audio, larger)",
            description = "Bigger Gemma 4. Higher accuracy on dialectal Arabic and Ukrainian; " +
                "slower per chunk than E2B.",
            sizeMb = 3490,
            filename = "gemma-4-E4B-it.litertlm",
            url = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
            kind = AsrBackendKind.Gemma4,
            requiredAfterMillis = GEMMA_4_MTP_CUTOFF_MILLIS,
            updateReason = "Re-download to enable MTP (~2× faster decode on GPU)",
        ),
        CatalogEntry(
            id = "diar-3dspeaker-campplus-en",
            displayName = "Speaker embedding (compact, 28 MB)",
            description = "3D-Speaker CAM++ trained on VoxCeleb. Required for speaker " +
                "diarization. Smallest option, good baseline for English.",
            sizeMb = 28,
            filename = "embedding.onnx",
            url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx",
            role = ModelRole.Diarization,
        ),
        CatalogEntry(
            id = "diar-3dspeaker-campplus-zh-en",
            displayName = "Speaker embedding (multilingual, 28 MB)",
            description = "3D-Speaker CAM++ trained on Chinese + English data. Same size " +
                "as the compact model, but the multilingual training set improves " +
                "speaker discrimination on code-switched or non-English audio. " +
                "Modest gain on Arabic/Ukrainian conversations.",
            sizeMb = 28,
            filename = "embedding-multilingual.onnx",
            url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx",
            role = ModelRole.Diarization,
        ),
        CatalogEntry(
            id = "diar-wespeaker-resnet221-lm",
            displayName = "Speaker embedding (high accuracy, 95 MB)",
            description = "WeSpeaker ResNet221-LM trained on VoxCeleb with large-margin " +
                "fine-tuning. ~25–30% lower EER than CAM++ on the hard VoxCeleb1 " +
                "split. Best choice when speakers are getting mis-attributed in " +
                "the compact model. ~67 MB extra to download.",
            sizeMb = 95,
            filename = "embedding-wespeaker.onnx",
            url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/wespeaker_en_voxceleb_resnet221_LM.onnx",
            role = ModelRole.Diarization,
            recommended = true,
        ),
    )

    fun byId(id: String): CatalogEntry? = entries.firstOrNull { it.id == id }
}
