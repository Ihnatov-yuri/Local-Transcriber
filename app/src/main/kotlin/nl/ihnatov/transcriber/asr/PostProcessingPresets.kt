package nl.ihnatov.transcriber.asr

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * One preset = a saved Gemma prompt that runs over an existing transcript.
 *
 * Templates use these substitution placeholders:
 *   `{language_hint}` — language description ("Arabic (Gulf/Qatari)", etc.)
 *   `{transcript}`    — the full transcript text (assembled with speaker
 *                       labels by [PostProcessor.assembleTranscriptForPrompt])
 *   `{vocabulary}`    — comma-joined user-defined names (from PromptStore)
 *
 * `systemTemplate` goes into the LLM's systemInstruction channel — role +
 * rules. `userTemplate` is what we send as the user message.
 */
@Serializable
data class PostProcessingPreset(
    val id: String,
    val displayName: String,
    val description: String,
    val systemTemplate: String,
    val userTemplate: String,
    val outputTitle: String = displayName,
)

/**
 * Persisted store of post-processing presets. The user can edit any preset's
 * prompt (system + user template) and reset back to the default. We store a
 * single JSON blob in SharedPreferences so adding a field doesn't require a
 * SharedPreferences key rename.
 *
 * Defaults are the three v1 presets agreed earlier:
 *   - Summary
 *   - Clean (verbatim-but-fixed)
 *   - Translate & polish (idiomatic English prose)
 */
class PresetStore(context: Context) {

    private val prefs = context.getSharedPreferences("presets", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    private val _presets = MutableStateFlow(load())
    val presets: StateFlow<List<PostProcessingPreset>> = _presets.asStateFlow()

    fun byId(id: String): PostProcessingPreset? = _presets.value.firstOrNull { it.id == id }

    fun save(preset: PostProcessingPreset) {
        val next = _presets.value.map { if (it.id == preset.id) preset else it }
        _presets.value = next
        persist(next)
    }

    /** Reset a single preset to its default. */
    fun reset(id: String) {
        val def = DEFAULTS.firstOrNull { it.id == id } ?: return
        save(def)
    }

    /** Reset every preset back to defaults. */
    fun resetAll() {
        _presets.value = DEFAULTS
        persist(DEFAULTS)
    }

    private fun load(): List<PostProcessingPreset> {
        val raw = prefs.getString(KEY, null) ?: return DEFAULTS
        return runCatching {
            val saved: List<PostProcessingPreset> = json.decodeFromString(raw)
            // If we add a new default preset in a future release, surface it
            // alongside the user's edits to existing ones.
            val byId = saved.associateBy { it.id }
            DEFAULTS.map { def -> byId[def.id] ?: def }
        }.getOrDefault(DEFAULTS)
    }

    private fun persist(list: List<PostProcessingPreset>) {
        prefs.edit().putString(KEY, json.encodeToString(list)).apply()
    }

    companion object {
        private const val KEY = "post_presets"

        val DEFAULT_SUMMARY = PostProcessingPreset(
            id = "summary",
            displayName = "Summary",
            description = "TL;DR + bullet key points + decisions",
            outputTitle = "Summary",
            systemTemplate = """
                You are a transcript-summarization assistant. The transcript that follows
                may contain diarized speaker labels (e.g. "Speaker 0", "Ahmed").
                {language_hint}

                Output a markdown document with these sections:
                1. **TL;DR** — two sentences capturing the conversation.
                2. **Key points** — bullets grouped by topic, no more than 10 bullets total.
                3. **Decisions / action items** — bullets, each attributed to the speaker who proposed it. If there are none, write "None".

                Hard rules:
                - Keep names, numbers, and dates exactly as written in the transcript.
                - Do not write any preamble. Begin your reply with "## TL;DR".
                - Do not wrap the response in triple-backtick code fences.
                {vocabulary}
            """.trimIndent(),
            userTemplate = "Transcript:\n{transcript}",
        )

        val DEFAULT_CLEAN = PostProcessingPreset(
            id = "clean",
            displayName = "Clean",
            description = "Fix STT errors, add punctuation, keep language",
            outputTitle = "Cleaned transcript",
            systemTemplate = """
                You are cleaning a speech-to-text transcript. The audio is in
                {language_hint}. The transcript may contain misheard words and
                missing punctuation.

                Your job:
                - Fix obvious transcription errors (homophones, missed words).
                - Add punctuation and casing in the source language.
                - Preserve the meaning exactly. Do NOT paraphrase or summarize.
                - Do NOT translate. Keep every segment in its original language.
                - Preserve speaker labels and the order of segments.

                Hard rules:
                - Output the cleaned transcript as plain text, preserving the
                  speaker-label blocks from the input.
                - Do not write any preamble. Start with the first speaker block.
                - Do not wrap the response in code fences.
                {vocabulary}
            """.trimIndent(),
            userTemplate = "Transcript to clean:\n{transcript}",
        )

        val DEFAULT_TRANSLATE_POLISH = PostProcessingPreset(
            id = "translate_polish",
            displayName = "Translate & polish",
            description = "Idiomatic English prose, not segment-by-segment",
            outputTitle = "English translation",
            systemTemplate = """
                You are translating a transcript into idiomatic, natural English.
                The audio is in {language_hint}.

                Your job:
                - Translate the full transcript into one or two paragraphs of
                  natural English prose. Do NOT preserve segment boundaries.
                - Smooth out spoken-language artefacts (repetitions, false starts)
                  but do not change meaning.
                - Preserve names, place names, and numerical facts exactly.
                - For dialect-specific expressions with no direct English equivalent,
                  give the most natural English version and add a brief
                  [lit.: "…"] note in brackets.
                - Preserve speaker attribution when it materially changes meaning;
                  otherwise prose is fine.

                Hard rules:
                - Output ONLY the English translation. No preamble, no commentary,
                  no quotation marks around the entire output.
                {vocabulary}
            """.trimIndent(),
            userTemplate = "Transcript to translate:\n{transcript}",
        )

        /**
         * Whole-transcript context-aware rewrite. Unlike [DEFAULT_CLEAN]
         * (which fixes errors line-by-line), this one tells Gemma to read
         * the FULL transcript first, build a mental model of topic + named
         * entities, then rewrite each line using that context. Designed for
         * the case where STT mishears a word but the surrounding lines make
         * the correct word obvious — e.g. a name first heard as "Acme" and
         * later spelled correctly as "Akhmed" gets back-propagated.
         */
        val DEFAULT_CONTEXT_REWRITE = PostProcessingPreset(
            id = "context_rewrite",
            displayName = "Context-aware rewrite",
            description = "Re-reads the whole conversation and fixes weird words using context",
            outputTitle = "Context-corrected transcript",
            systemTemplate = """
                You are revising a speech-to-text transcript. The transcript may
                contain mishearings, homophone errors, dropped words, garbled
                proper nouns, and stray non-word tokens from the recognizer.

                {language_hint}

                Step 1 — read the ENTIRE transcript before writing anything.
                Build a mental picture of: the topic, who is speaking,
                recurring named entities (people, products, places), and the
                jargon in use.

                Step 2 — rewrite the transcript so each line makes sense given
                the whole conversation. Specifically:
                - If a word is wrong but the right word is obvious from
                  context (later mentions, the topic, a recurring entity),
                  replace it. Propagate the correct spelling backwards.
                - If a token is gibberish (a non-word that fits nowhere), drop it.
                - Fix homophones using context ("their" vs "there", "كتب" vs "كذب").
                - Add punctuation and casing in the source language.
                - Preserve speaker labels exactly (do not invent new ones).
                - Preserve segment ordering. Preserve line breaks between
                  speaker turns and roughly between sentences.
                - Do NOT translate. Every line stays in its source language.
                - Do NOT paraphrase or summarize. Keep the speaker's voice
                  and word choice — only fix recognizer errors.
                - Do NOT add commentary, explanations, or footnotes.

                Hard rules:
                - Output ONLY the corrected transcript text. Plain text.
                - Begin directly with the first speaker turn. No preamble.
                - Do not wrap the output in code fences or quotation marks.
                - Preserve the leading timestamps from each line, in the same
                  [MM:SS] format they appear in the input.
                {vocabulary}
            """.trimIndent(),
            userTemplate = "Transcript to revise (read the whole thing first, then rewrite):\n{transcript}",
        )

        val DEFAULTS: List<PostProcessingPreset> = listOf(
            DEFAULT_SUMMARY,
            DEFAULT_CONTEXT_REWRITE,
            DEFAULT_CLEAN,
            DEFAULT_TRANSLATE_POLISH,
        )
    }
}
