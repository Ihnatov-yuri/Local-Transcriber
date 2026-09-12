package nl.ihnatov.transcriber.asr

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import nl.ihnatov.transcriber.data.RecordingRepository

/**
 * Persisted state for the "Learned names" feature (Settings → Learned) —
 * behavior port of the Mac app's `Dictation/DictationSettings.swift`
 * learned-terms fields, minus the live-dictation-only "suggest after each
 * utterance" half (this app has no dictation feature — dictation is out of
 * scope per the 2026-09 catch-up plan).
 */
class LearnedNamesStore(context: Context) {

    private val prefs = context.getSharedPreferences("learned_names", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _terms = MutableStateFlow(decodeTerms(prefs.getString(KEY_TERMS, null)))
    val terms: StateFlow<List<VocabularyHarvester.Term>> = _terms.asStateFlow()

    private val _dismissedKeys = MutableStateFlow(
        prefs.getStringSet(KEY_DISMISSED, null)?.toSet() ?: emptySet()
    )
    val dismissedKeys: StateFlow<Set<String>> = _dismissedKeys.asStateFlow()

    private val _lastScanMillis = MutableStateFlow(prefs.getLong(KEY_SCAN_AT, 0L).takeIf { it > 0L })
    val lastScanMillis: StateFlow<Long?> = _lastScanMillis.asStateFlow()

    /** Replace the harvested set (a fresh scan supersedes the previous one entirely). */
    fun setTerms(newTerms: List<VocabularyHarvester.Term>) {
        _terms.value = newTerms
        val now = System.currentTimeMillis()
        _lastScanMillis.value = now
        prefs.edit()
            .putString(KEY_TERMS, json.encodeToString(newTerms))
            .putLong(KEY_SCAN_AT, now)
            .apply()
    }

    /** Reject a suggestion — never offered again unless the user clears app data. */
    fun dismiss(key: String) {
        val updated = _dismissedKeys.value + key
        _dismissedKeys.value = updated
        prefs.edit().putStringSet(KEY_DISMISSED, updated).apply()
    }

    /** Drop a term outright — used once it's promoted into the vocabulary, so it stops showing as "still suggested". */
    fun remove(key: String) {
        val updated = _terms.value.filter { it.key != key }
        _terms.value = updated
        prefs.edit().putString(KEY_TERMS, json.encodeToString(updated)).apply()
    }

    private fun decodeTerms(raw: String?): List<VocabularyHarvester.Term> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<VocabularyHarvester.Term>>(raw) }.getOrDefault(emptyList())
    }

    companion object {
        private const val KEY_TERMS = "terms"
        private const val KEY_DISMISSED = "dismissed"
        private const val KEY_SCAN_AT = "scan_at"
    }
}

/**
 * Re-harvest names from every recording's transcript and replace the
 * stored suggestion set. Runs on [scope] — pass a process-lifetime scope
 * ([nl.ihnatov.transcriber.data.AppContainer.appScope]) so the scan
 * survives navigating away from whichever screen triggered it. Mirrors
 * the Mac's `DictationController.bootstrap()` → `refreshLearnedTerms()`,
 * called once at app launch and again from the "Rescan library" button in
 * Settings.
 */
fun refreshLearnedTerms(
    scope: CoroutineScope,
    repository: RecordingRepository,
    promptStore: PromptStore,
    store: LearnedNamesStore,
) {
    scope.launch(Dispatchers.Default) {
        val items = repository.allTranscriptsForHarvest()
        // Every language's list, not just one — a name already known in
        // any language shouldn't be re-suggested just because it hasn't
        // been typed into the global box too.
        val existing = promptStore.vocabularyTerms()
        val harvested = VocabularyHarvester.harvest(items, existingVocabulary = existing)
        store.setTerms(harvested)
    }
}

/** Promote a learned spelling into the permanent global vocabulary — mirrors the Mac's `addToVocabulary`. */
fun addLearnedTerm(promptStore: PromptStore, store: LearnedNamesStore, term: VocabularyHarvester.Term) {
    val current = parseVocabulary(promptStore.vocabulary.value)
    if (current.none { VocabularyHarvester.key(it) == term.key }) {
        promptStore.setVocabulary((current + term.spelling).joinToString(", "))
    }
    store.remove(term.key)
}
