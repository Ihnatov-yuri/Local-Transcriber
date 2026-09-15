package nl.ihnatov.transcriber.asr

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Lightweight UI / cross-screen preferences that don't fit cleanly into
 * [PromptStore] (prompt rules) or [GemmaSettingsStore] (compute knobs).
 *
 * Two distinct concerns living here on purpose:
 *
 *   1. **Last-used languages** — picked once on Record, inherited as the
 *      default on the Detail "Transcribe" card. Without this the user has
 *      to re-pick the same languages every time they navigate to Detail,
 *      which is the most common operation in the app.
 *
 *   2. **Transcript view toggles** — `showTimestamps` and `proseMode`. The
 *      user told us they sometimes want timestamps off and sometimes want
 *      one continuous block instead of per-chunk cards. Both are sticky
 *      so the user's preference survives navigation.
 *
 * All flows are non-suspending; SharedPreferences writes are commit()-fast.
 */
class UiPrefs(context: Context) {

    private val prefs = context.getSharedPreferences("ui_prefs", Context.MODE_PRIVATE)

    // ---- Last-used languages (shared between Record + Detail) ----
    private val _lastLanguages = MutableStateFlow(loadLangs())
    val lastLanguages: StateFlow<Set<String>> = _lastLanguages.asStateFlow()

    fun setLastLanguages(value: Set<String>) {
        _lastLanguages.value = value
        // Persist as a comma-separated string; SharedPreferences' Set<String>
        // API is documented as "preserve no order" which doesn't matter here,
        // but a comma string is simpler to log and inspect via `adb shell`.
        prefs.edit()
            .putString(KEY_LAST_LANGUAGES, value.joinToString(","))
            .apply()
    }

    private fun loadLangs(): Set<String> {
        val raw = prefs.getString(KEY_LAST_LANGUAGES, null) ?: return emptySet()
        return raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    // ---- Preferred speaker-embedding model ----
    //
    // Filename of the active embedding model when multiple are
    // installed. null = use the built-in priority cascade
    // (DiarizationRunner.embeddingModelFile). Surfaced as a radio
    // selector in Settings → Models so the user can trade off
    // accuracy vs latency: WeSpeaker ResNet221-LM (90 MB, best EER,
    // 3–5 min full-file clustering on a long meeting) vs CAM++
    // (28 MB, ~1 min for the same file with modest EER hit).
    private val _preferredEmbedding = MutableStateFlow(
        prefs.getString(KEY_PREFERRED_EMBEDDING, null)?.takeIf { it.isNotBlank() }
    )
    val preferredEmbedding: StateFlow<String?> = _preferredEmbedding.asStateFlow()

    fun setPreferredEmbedding(filename: String?) {
        _preferredEmbedding.value = filename
        val editor = prefs.edit()
        if (filename.isNullOrBlank()) editor.remove(KEY_PREFERRED_EMBEDDING)
        else editor.putString(KEY_PREFERRED_EMBEDDING, filename)
        editor.apply()
    }

    // ---- Quick-Fill vocabulary languages ----
    //
    // Distinct from [lastLanguages] (which is the LAST set used on the
    // Record/Detail screen for a transcription run). The vocab section
    // in Settings has its OWN persistent language picker so installing
    // a domain pack doesn't get scoped to whatever the user last
    // transcribed — the user can curate this independently and keep
    // EN+AR vocabulary even when their next recording is English-only.
    // Default: empty → "English only" until the user picks.
    private val _vocabLanguages = MutableStateFlow(loadVocabLangs())
    val vocabLanguages: StateFlow<Set<String>> = _vocabLanguages.asStateFlow()

    fun setVocabLanguages(value: Set<String>) {
        _vocabLanguages.value = value
        prefs.edit().putString(KEY_VOCAB_LANGUAGES, value.joinToString(",")).apply()
    }

    private fun loadVocabLangs(): Set<String> {
        val raw = prefs.getString(KEY_VOCAB_LANGUAGES, null) ?: return emptySet()
        return raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    // ---- Transcript view toggles ----
    private val _showTimestamps = MutableStateFlow(prefs.getBoolean(KEY_SHOW_TIMESTAMPS, true))
    val showTimestamps: StateFlow<Boolean> = _showTimestamps.asStateFlow()

    fun setShowTimestamps(value: Boolean) {
        _showTimestamps.value = value
        prefs.edit().putBoolean(KEY_SHOW_TIMESTAMPS, value).apply()
    }

    /**
     * Prose mode: render the whole transcript as one continuous selectable
     * block instead of per-segment Cards. Inline edit isn't available in
     * prose mode — that flow needs the per-segment cards — but reading,
     * selecting, and copying a long transcript is much easier this way.
     */
    private val _proseMode = MutableStateFlow(prefs.getBoolean(KEY_PROSE_MODE, false))
    val proseMode: StateFlow<Boolean> = _proseMode.asStateFlow()

    fun setProseMode(value: Boolean) {
        _proseMode.value = value
        prefs.edit().putBoolean(KEY_PROSE_MODE, value).apply()
    }

    // ---- Diarization tuning (Settings → Models → Diarization) ----
    //
    // All three are nullable: null means "use the built-in default"
    // (language-aware for threshold, fixed for the other two — see
    // DiarizationRunner.DEFAULT_* / defaultClusterThreshold). Stored as
    // -1f sentinel in SharedPreferences since it has no native nullable
    // float getter.
    private val _clusterThreshold = MutableStateFlow(loadNullableFloat(KEY_CLUSTER_THRESHOLD))
    val clusterThreshold: StateFlow<Float?> = _clusterThreshold.asStateFlow()

    fun setClusterThreshold(value: Float?) {
        _clusterThreshold.value = value
        saveNullableFloat(KEY_CLUSTER_THRESHOLD, value)
    }

    private val _minDurationOnSec = MutableStateFlow(loadNullableFloat(KEY_MIN_DURATION_ON))
    val minDurationOnSec: StateFlow<Float?> = _minDurationOnSec.asStateFlow()

    fun setMinDurationOnSec(value: Float?) {
        _minDurationOnSec.value = value
        saveNullableFloat(KEY_MIN_DURATION_ON, value)
    }

    private val _minDurationOffSec = MutableStateFlow(loadNullableFloat(KEY_MIN_DURATION_OFF))
    val minDurationOffSec: StateFlow<Float?> = _minDurationOffSec.asStateFlow()

    fun setMinDurationOffSec(value: Float?) {
        _minDurationOffSec.value = value
        saveNullableFloat(KEY_MIN_DURATION_OFF, value)
    }

    /**
     * Speaker-turn coalescing gap in seconds — segments from the same
     * speaker separated by less than this merge into one turn. Mac app
     * default is 30s ("smooth blocks"); Settings can tune down to ~2s
     * ("fine Samsung-style turns"). null = use [DEFAULT_TURN_COALESCE_GAP_SEC].
     */
    private val _turnCoalesceGapSec = MutableStateFlow(loadNullableFloat(KEY_TURN_COALESCE_GAP))
    val turnCoalesceGapSec: StateFlow<Float?> = _turnCoalesceGapSec.asStateFlow()

    fun setTurnCoalesceGapSec(value: Float?) {
        _turnCoalesceGapSec.value = value
        saveNullableFloat(KEY_TURN_COALESCE_GAP, value)
    }

    private fun loadNullableFloat(key: String): Float? {
        val v = prefs.getFloat(key, Float.NaN)
        return if (v.isNaN()) null else v
    }

    private fun saveNullableFloat(key: String, value: Float?) {
        val editor = prefs.edit()
        if (value == null) editor.remove(key) else editor.putFloat(key, value)
        editor.apply()
    }

    companion object {
        private const val KEY_LAST_LANGUAGES = "last_languages"
        private const val KEY_VOCAB_LANGUAGES = "vocab_languages"
        private const val KEY_PREFERRED_EMBEDDING = "preferred_embedding"
        private const val KEY_SHOW_TIMESTAMPS = "show_timestamps"
        private const val KEY_PROSE_MODE = "prose_mode"
        private const val KEY_CLUSTER_THRESHOLD = "diar_cluster_threshold"
        private const val KEY_MIN_DURATION_ON = "diar_min_duration_on"
        private const val KEY_MIN_DURATION_OFF = "diar_min_duration_off"
        private const val KEY_TURN_COALESCE_GAP = "turn_coalesce_gap_sec"
    }
}
