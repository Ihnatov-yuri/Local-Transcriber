package nl.ihnatov.transcriber.asr

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tone applied to post-processing output. Inspired by Wispr Flow's "Styles"
 * feature. Affects cleaning + translation prompts; the literal transcription
 * path ignores it (we don't paraphrase speakers).
 */
enum class Tone(val id: String, val displayName: String, val instruction: String?) {
    Neutral("neutral", "Neutral", null),
    Formal("formal", "Formal", "Tone: formal. Use precise vocabulary and complete sentences. No slang or contractions."),
    Casual("casual", "Casual", "Tone: casual. Conversational, use contractions, light. Don't over-format."),
    Enthusiastic("enthusiastic", "Enthusiastic", "Tone: enthusiastic. Energetic, positive phrasing. Stop short of marketing speak."),
    Technical("technical", "Technical", "Tone: technical. Preserve domain terminology exactly. Concise sentences over flourish."),
}

/**
 * Persisted, user-editable inputs to the Gemma 4 system prompt:
 *
 *   - `transcribePrompt` / `translatePrompt` — full prompt templates with a
 *     `{language_hint}` placeholder
 *   - `vocabulary` — comma-or-newline-separated list of proper nouns / jargon
 *     the model should preserve exactly (the Wispr Flow "dictionary" feature)
 *   - `removeFillers` — strip "um", "uh", "like", "you know" from output
 *   - `verbatim` — disable smart formatting (auto punctuation, casing, etc.)
 *
 * [render] assembles the final system instruction with all of these mixed in.
 * Settings binds directly to the StateFlows here, so edits take effect on the
 * next transcription call with no app restart.
 */
class PromptStore(context: Context) {

    private val prefs = context.getSharedPreferences("prompts", Context.MODE_PRIVATE)

    init {
        // One-shot migration: if the persisted prompt is byte-identical to a
        // past default (v1: engine-persona framing; v2: missing the positive
        // punctuation rule), clear it so the current default kicks in. Users
        // who actually edited their prompts won't match these signatures and
        // keep their edits intact.
        val legacyTranscribeSignatures = setOf(
            LEGACY_DEFAULT_TRANSCRIBE_V1,
            LEGACY_DEFAULT_TRANSCRIBE_V2,
        )
        val legacyTranslateSignatures = setOf(
            LEGACY_DEFAULT_TRANSLATE_V1,
            LEGACY_DEFAULT_TRANSLATE_V2,
        )
        if (prefs.getString(KEY_TRANSCRIBE, null) in legacyTranscribeSignatures) {
            prefs.edit().remove(KEY_TRANSCRIBE).apply()
        }
        if (prefs.getString(KEY_TRANSLATE, null) in legacyTranslateSignatures) {
            prefs.edit().remove(KEY_TRANSLATE).apply()
        }
    }

    private val _transcribePrompt = MutableStateFlow(prefs.getString(KEY_TRANSCRIBE, null) ?: DEFAULT_TRANSCRIBE)
    val transcribePrompt: StateFlow<String> = _transcribePrompt.asStateFlow()

    private val _translatePrompt = MutableStateFlow(prefs.getString(KEY_TRANSLATE, null) ?: DEFAULT_TRANSLATE)
    val translatePrompt: StateFlow<String> = _translatePrompt.asStateFlow()

    private val _vocabulary = MutableStateFlow(prefs.getString(KEY_VOCABULARY, "") ?: "")
    val vocabulary: StateFlow<String> = _vocabulary.asStateFlow()

    private val _removeFillers = MutableStateFlow(prefs.getBoolean(KEY_REMOVE_FILLERS, false))
    val removeFillers: StateFlow<Boolean> = _removeFillers.asStateFlow()

    private val _verbatim = MutableStateFlow(prefs.getBoolean(KEY_VERBATIM, false))
    val verbatim: StateFlow<Boolean> = _verbatim.asStateFlow()

    private val _tone = MutableStateFlow(Tone.entries.firstOrNull { it.id == prefs.getString(KEY_TONE, null) } ?: Tone.Neutral)
    val tone: StateFlow<Tone> = _tone.asStateFlow()

    fun setTranscribe(text: String) {
        _transcribePrompt.value = text
        prefs.edit().putString(KEY_TRANSCRIBE, text).apply()
    }

    fun setTranslate(text: String) {
        _translatePrompt.value = text
        prefs.edit().putString(KEY_TRANSLATE, text).apply()
    }

    fun resetTranscribe() {
        _transcribePrompt.value = DEFAULT_TRANSCRIBE
        prefs.edit().remove(KEY_TRANSCRIBE).apply()
    }

    fun resetTranslate() {
        _translatePrompt.value = DEFAULT_TRANSLATE
        prefs.edit().remove(KEY_TRANSLATE).apply()
    }

    fun setVocabulary(text: String) {
        _vocabulary.value = text
        prefs.edit().putString(KEY_VOCABULARY, text).apply()
    }

    fun setRemoveFillers(value: Boolean) {
        _removeFillers.value = value
        prefs.edit().putBoolean(KEY_REMOVE_FILLERS, value).apply()
    }

    fun setVerbatim(value: Boolean) {
        _verbatim.value = value
        prefs.edit().putBoolean(KEY_VERBATIM, value).apply()
    }

    fun setTone(value: Tone) {
        _tone.value = value
        prefs.edit().putString(KEY_TONE, value.id).apply()
    }

    /**
     * Build the final system-instruction string for the selected mode,
     * mixing in the user's vocabulary and style toggles.
     *
     * [languages] is the allowed-languages set:
     *   - empty → full auto (any language)
     *   - one    → that language is forced
     *   - 2+     → constrained auto. Faster + more accurate than full auto
     *              because Gemma stops considering unrelated languages.
     *
     * [diarize] true asks Gemma to inline speaker labels (`Speaker 1: ...`).
     */
    fun render(
        translate: Boolean,
        languages: List<String>,
        diarize: Boolean = false,
    ): String {
        val template = if (translate) _translatePrompt.value else _transcribePrompt.value
        // Backward-compat: legacy user-edited prompts had {language_hint} in
        // the system template. We still substitute it in case the user
        // hand-edited their template — but the authoritative language anchor
        // lives in the user message (next to the audio), per Google's docs.
        // The `diarize` flag is intentionally unused here: speaker-label
        // instructions are placed in the user turn by Gemma4Backend, not in
        // this system-channel template, to keep them anchored to the audio.
        val hint = languageHint(languages)
        val base = template.replace("{language_hint}", hint)
        return base + buildExtras()
    }

    private fun buildExtras(): String {
        val parts = mutableListOf<String>()
        // Cap the count of terms we inline so a user who installed all
        // domain packs doesn't blow the per-chunk token budget. The
        // capped list keeps the order from parseVocabulary, which has the
        // user's hand-curated entries first (they're appended at the top
        // of the file in DomainVocabulary.apply) — so the truncation
        // drops the LEAST-recently-added domain terms first, not the
        // user's own additions.
        val terms = parseVocabulary(_vocabulary.value).take(VOCAB_TERMS_PROMPT_CAP)
        if (terms.isNotEmpty()) {
            parts += "Vocabulary (spell exactly when heard): " + terms.joinToString(", ") + "."
        }
        if (_removeFillers.value) {
            parts += "Remove filler words: um, uh, uhh, hmm, like, you know, eh, ah, oh."
        }
        if (_verbatim.value) {
            parts += "Verbatim mode: do not add punctuation, capitalization, or paragraph " +
                "breaks. Output a flat stream of spoken words as heard."
        }
        // Tone influences cleaning and translate-to-English (where we have
        // any flexibility in word choice). The literal transcription path
        // ignores it — speakers' actual words don't change because the user
        // picked "casual".
        val toneSentence = _tone.value.instruction
        if (toneSentence != null) parts += toneSentence
        return if (parts.isEmpty()) "" else "\n\nAdditional instructions:\n- " +
            parts.joinToString("\n- ")
    }

    private fun languageHint(languages: List<String>): String {
        val cleaned = languages.map { it.lowercase() }.distinct()
        if (cleaned.isEmpty()) {
            return "The audio may be in any language. Detect it from the audio itself."
        }
        if (cleaned.size == 1) return singleLanguageHint(cleaned.first())
        // Constrained auto: tell Gemma the candidate set explicitly.
        val names = cleaned.map { languageDisplayName(it) }
        return "The audio is in one of: " + names.joinToString(", ") +
            ". Detect which language is being spoken and transcribe in that " +
            "language exactly as heard."
    }

    private fun singleLanguageHint(code: String): String = when (code) {
        "ar" -> "The audio is in Arabic (any dialect, including Gulf/Qatari)."
        "uk" -> "The audio is in Ukrainian."
        "en" -> "The audio is in English."
        "nl" -> "The audio is in Dutch (Nederlands)."
        else -> "The audio is in the language identified by ISO code '$code'."
    }

    private fun languageDisplayName(code: String): String = when (code) {
        "ar" -> "Arabic (any dialect including Gulf/Qatari)"
        "uk" -> "Ukrainian"
        "en" -> "English"
        "nl" -> "Dutch"
        else -> "the language with ISO code '$code'"
    }

    /** Parse comma- or newline-separated terms, trimming and dropping blanks. */
    private fun parseVocabulary(text: String): List<String> =
        text.split('\n', ',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    companion object {
        private const val KEY_TRANSCRIBE = "gemma_prompt_transcribe"
        private const val KEY_TRANSLATE = "gemma_prompt_translate"
        private const val KEY_VOCABULARY = "gemma_vocabulary"
        private const val KEY_REMOVE_FILLERS = "gemma_remove_fillers"
        private const val KEY_VERBATIM = "gemma_verbatim"
        private const val KEY_TONE = "gemma_tone"

        /**
         * Hard cap on how many vocabulary terms get inlined into the
         * Gemma system prompt. Gemma 4 E2B audio mode has ~8K context;
         * a 28-sec audio chunk eats ~700 tokens, the system rules + user
         * template are ~500, leaving ~6800 for output + vocabulary. 250
         * terms is ~1500 tokens worst case (Cyrillic/Arabic terms
         * tokenize fatter than ASCII). Tunable; lower if we see chunk
         * outputs truncated.
         */
        const val VOCAB_TERMS_PROMPT_CAP = 250

        // System-channel prompts. Per Google's audio docs, the language
        // anchor lives in the USER message next to the audio (constructed
        // in Gemma4Backend), not here. These templates carry rules only —
        // they keep `{language_hint}` so existing user edits still apply,
        // but the rendered hint is a no-op when present in the defaults.
        /**
         * Pre-1.x prompt — engine-persona framing, system-channel language
         * anchor. Kept here only so the migration in init{} can recognise it
         * and replace it with the new audio-anchored default. Do not use
         * elsewhere.
         */
        private const val LEGACY_DEFAULT_TRANSCRIBE_V1 =
            "You are an audio transcription engine. {language_hint} " +
                "Transcribe the spoken content verbatim in the source language.\n" +
                "Hard rules:\n" +
                "- Output ONLY the words that are spoken in the audio.\n" +
                "- Do not write any preamble, framing, acknowledgment, or commentary.\n" +
                "- Do not write phrases like \"Sure\", \"Here is\", \"The transcription is\", or \"Translation:\".\n" +
                "- Do not write quotation marks around the output.\n" +
                "- Do not describe the audio or the speaker.\n" +
                "- If the audio is silent or unintelligible, output an empty string."

        private const val LEGACY_DEFAULT_TRANSLATE_V1 =
            "You are an audio transcription engine. {language_hint} " +
                "Translate the spoken content into clear, natural English.\n" +
                "Hard rules:\n" +
                "- Output ONLY the English translation of the spoken words.\n" +
                "- Do not write any preamble, framing, acknowledgment, or commentary.\n" +
                "- Do not write phrases like \"Sure\", \"Here is\", \"The transcription is\", or \"Translation:\".\n" +
                "- Do not write quotation marks around the output.\n" +
                "- Do not describe the audio or the speaker.\n" +
                "- Preserve names, place names, and numerical facts exactly.\n" +
                "- If the audio is silent or unintelligible, output an empty string."

        /**
         * v2 defaults — the no-punctuation-rule version. Same shape as
         * the current default but missing the explicit "End sentences
         * with periods…" positive rule. Migrated to v3 (current) so
         * existing installs gain the punctuation enforcement without
         * the user having to reset their prompt by hand.
         */
        private const val LEGACY_DEFAULT_TRANSCRIBE_V2 =
            "Hard rules for transcript output:\n" +
                "- Output ONLY the transcript content (which may include speaker labels when requested).\n" +
                "- Do not write any preamble, framing, acknowledgment, or commentary.\n" +
                "- Do not write phrases like \"Sure\", \"Here is\", \"The transcription is\", or \"Translation:\".\n" +
                "- Do not wrap the output in quotation marks or code fences.\n" +
                "- Do not describe the audio or the speaker out-of-band.\n" +
                "- If the audio is silent or unintelligible, output an empty string."

        private const val LEGACY_DEFAULT_TRANSLATE_V2 =
            "Hard rules for translation output:\n" +
                "- Output ONLY the English translation of the spoken content.\n" +
                "- Do not write any preamble, framing, acknowledgment, or commentary.\n" +
                "- Do not write phrases like \"Sure\", \"Here is\", \"The transcription is\", or \"Translation:\".\n" +
                "- Do not wrap the output in quotation marks or code fences.\n" +
                "- Do not describe the audio or the speaker out-of-band.\n" +
                "- Preserve names, place names, and numerical facts exactly.\n" +
                "- If the audio is silent or unintelligible, output an empty string."

        const val DEFAULT_TRANSCRIBE =
            "Hard rules for transcript output:\n" +
                "- Output ONLY the transcript content (which may include speaker labels when requested).\n" +
                "- Format the output as proper written prose with full punctuation: end every sentence with a period or question mark, insert commas at natural pauses and in lists, and capitalize sentence starts and proper nouns. Do NOT emit a flat unpunctuated stream of words.\n" +
                "- Do not write any preamble, framing, acknowledgment, or commentary.\n" +
                "- Do not write phrases like \"Sure\", \"Here is\", \"The transcription is\", or \"Translation:\".\n" +
                "- Do not wrap the output in quotation marks or code fences.\n" +
                "- Do not describe the audio or the speaker out-of-band.\n" +
                "- If the audio is silent or unintelligible, output an empty string."

        const val DEFAULT_TRANSLATE =
            "Hard rules for translation output:\n" +
                "- Output ONLY the English translation of the spoken content.\n" +
                "- Format the translation as proper written English with full punctuation: end every sentence with a period or question mark, insert commas at natural pauses and in lists, and capitalize sentence starts and proper nouns.\n" +
                "- Do not write any preamble, framing, acknowledgment, or commentary.\n" +
                "- Do not write phrases like \"Sure\", \"Here is\", \"The transcription is\", or \"Translation:\".\n" +
                "- Do not wrap the output in quotation marks or code fences.\n" +
                "- Do not describe the audio or the speaker out-of-band.\n" +
                "- Preserve names, place names, and numerical facts exactly.\n" +
                "- If the audio is silent or unintelligible, output an empty string."
    }
}
