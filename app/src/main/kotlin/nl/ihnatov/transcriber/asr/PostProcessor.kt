package nl.ihnatov.transcriber.asr

import android.content.Context
import android.util.Log
import nl.ihnatov.transcriber.data.OutputDoc
import nl.ihnatov.transcriber.data.RecordingRepository
import nl.ihnatov.transcriber.data.Segment

/**
 * Runs a [PostProcessingPreset] over a Recording's transcript using Gemma 4
 * (same engine family as audio transcription, in text-only mode).
 *
 * The flow:
 *   1. Resolve the Gemma 4 model file (must be installed).
 *   2. Load a fresh Gemma4Backend, run a single `generateText` call.
 *   3. Release the backend (frees the ~1.5 GB engine).
 *   4. Persist the result as an OutputDoc.
 *
 * Why a fresh backend each call rather than a shared singleton:
 *   - Transcription and post-processing happen sequentially in practice, so
 *     a shared engine would still be idle most of the time.
 *   - A fresh load gives us a clean state — no leftover conversation tokens
 *     from a previous run sneaking into the system instruction.
 *
 * Concurrency: callers serialize their own post-processing requests; this
 * class doesn't guard against two simultaneous runs. (You wouldn't want two
 * 1.5 GB engines in RAM anyway.)
 */
class PostProcessor(
    private val context: Context,
    private val factory: AsrFactory,
    private val promptStore: PromptStore,
    private val presetStore: PresetStore,
    private val snippetStore: SnippetStore,
    private val repository: RecordingRepository,
    private val gemmaSettings: GemmaSettingsStore? = null,
) {

    sealed interface Progress {
        data object Loading : Progress
        data object Running : Progress
        data class Done(val outputId: Long, val markdown: String) : Progress
        data class Failed(val reason: String) : Progress
    }

    suspend fun run(
        recordingId: Long,
        presetId: String,
        segments: List<Segment>,
        language: String?,
        onProgress: suspend (Progress) -> Unit,
    ) {
        val preset = presetStore.byId(presetId)
        if (preset == null) {
            onProgress(Progress.Failed("Unknown preset id: $presetId"))
            return
        }
        val recording = repository.get(recordingId)
        if (recording == null) {
            onProgress(Progress.Failed("Recording $recordingId not found"))
            return
        }
        val modelFile = factory.resolveModel(AsrBackendKind.Gemma4)
        if (modelFile == null) {
            onProgress(Progress.Failed(
                "No Gemma 4 model installed. Open Settings → Download a Gemma 4 model."
            ))
            return
        }

        onProgress(Progress.Loading)
        val backend = Gemma4Backend(context, promptStore, gemmaSettings)
        val loaded = backend.load(modelFile.absolutePath)
        if (loaded.isFailure) {
            onProgress(Progress.Failed(
                "Gemma 4 load failed: ${loaded.exceptionOrNull()?.message ?: "unknown"}"
            ))
            return
        }
        try {
            onProgress(Progress.Running)
            val resolvedLang = recording.sourceLanguage ?: language
            val systemPrompt = renderTemplate(preset.systemTemplate, resolvedLang, segments)
            val userPrompt = renderTemplate(preset.userTemplate, resolvedLang, segments)
            Log.i(TAG, "preset=$presetId  language=$resolvedLang  segments=${segments.size}")

            val res = backend.generateText(systemPrompt, userPrompt)
            res.onSuccess { markdown ->
                val doc = OutputDoc(
                    recordingId = recordingId,
                    presetId = presetId,
                    title = preset.outputTitle,
                    markdown = markdown,
                    createdAtMillis = System.currentTimeMillis(),
                )
                val id = repository.replaceOutput(doc)
                onProgress(Progress.Done(outputId = id, markdown = markdown))
            }
            res.onFailure {
                onProgress(Progress.Failed("Gemma 4 generation failed: ${it.message}"))
            }
        } finally {
            backend.release()
        }
    }

    /**
     * Substitute `{language_hint}`, `{transcript}`, `{vocabulary}` placeholders.
     * Transcript assembly preserves speaker labels — these matter for Summary
     * ("Ahmed proposed X, Sara agreed") and for Q&A-style outputs.
     */
    private fun renderTemplate(
        template: String,
        language: String?,
        segments: List<Segment>,
    ): String {
        val langHint = languageHint(language)
        val vocab = parseVocabulary(promptStore.vocabulary.value)
        val vocabSection = if (vocab.isEmpty()) ""
        else "\nVocabulary (spell exactly when these appear): ${vocab.joinToString(", ")}."
        val resolved = template
            .replace("{language_hint}", langHint)
            .replace("{vocabulary}", vocabSection)
            .replace("{transcript}", assembleTranscriptForPrompt(segments))
        // Snippets last so a `{snippet:foo}` referenced inside the prompt
        // body (not just user-edited templates) still expands.
        return snippetStore.substitute(resolved)
    }

    private fun languageHint(language: String?): String = when (language?.lowercase()) {
        null, "auto", "" -> "The transcript may be in any language."
        "ar" -> "The transcript is in Arabic (any dialect, including Gulf/Qatari)."
        "uk" -> "The transcript is in Ukrainian."
        "en" -> "The transcript is in English."
        "nl" -> "The transcript is in Dutch (Nederlands)."
        else -> "The transcript is in language code '$language'."
    }

    private fun parseVocabulary(text: String): List<String> =
        text.split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /**
     * Build a speaker-labeled plaintext transcript that's compact enough for
     * Gemma's input window. Format:
     *   [00:12] Speaker 0: the words ...
     *   [00:18] Ahmed: more words ...
     * Bold timestamps live in the prompt itself, not here, so this stays
     * mechanical.
     */
    private fun assembleTranscriptForPrompt(segments: List<Segment>): String = buildString {
        for (seg in segments) {
            val ts = "%02d:%02d".format(
                seg.startSeconds.toInt() / 60,
                seg.startSeconds.toInt() % 60,
            )
            val speaker = seg.speakerName ?: seg.speaker?.replace("SPEAKER_", "Speaker ")
            append('[').append(ts).append("] ")
            if (!speaker.isNullOrBlank()) append(speaker).append(": ")
            append(seg.text.trim())
            append('\n')
        }
    }

    companion object {
        private const val TAG = "PostProcessor"
    }
}
