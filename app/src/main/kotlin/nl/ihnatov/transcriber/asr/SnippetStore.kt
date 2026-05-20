package nl.ihnatov.transcriber.asr

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Re-usable text fragments the user defines once and references in custom
 * post-processing prompts via the `{snippet:name}` placeholder.
 *
 * Mirrors Wispr Flow's Snippets feature, adapted for our transcript-oriented
 * app: instead of voice-triggered keyboard expansion, snippets are name →
 * text mappings that get substituted at prompt-render time.
 *
 * Example: define a snippet named "signoff" with body
 *     Best regards,
 *     Yuri
 *
 * Then a custom preset prompt can include:
 *     ...end the email with this signoff:
 *     {snippet:signoff}
 *
 * Persisted as a single JSON blob in SharedPreferences so adding fields to
 * Snippet doesn't require schema gymnastics.
 */
@Serializable
data class Snippet(
    val name: String,
    val body: String,
)

class SnippetStore(context: Context) {

    private val prefs = context.getSharedPreferences("snippets", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    private val _snippets = MutableStateFlow(load())
    val snippets: StateFlow<List<Snippet>> = _snippets.asStateFlow()

    fun byName(name: String): Snippet? =
        _snippets.value.firstOrNull { it.name.equals(name, ignoreCase = true) }

    fun upsert(snippet: Snippet) {
        if (snippet.name.isBlank()) return
        val key = snippet.name.trim()
        val next = _snippets.value
            .filterNot { it.name.equals(key, ignoreCase = true) } + snippet.copy(name = key)
        _snippets.value = next.sortedBy { it.name.lowercase() }
        persist(_snippets.value)
    }

    fun delete(name: String) {
        _snippets.value = _snippets.value.filterNot { it.name.equals(name, ignoreCase = true) }
        persist(_snippets.value)
    }

    /**
     * Substitute every `{snippet:NAME}` placeholder in [text] with the
     * matching snippet body. Unknown snippet names are left as-is so the user
     * notices the typo in the output rather than silently losing content.
     */
    fun substitute(text: String): String {
        if (!text.contains("{snippet:")) return text
        val re = Regex("\\{snippet:([^}]+)}")
        return re.replace(text) { match ->
            val name = match.groupValues[1].trim()
            byName(name)?.body ?: match.value
        }
    }

    private fun load(): List<Snippet> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString<List<Snippet>>(raw).sortedBy { it.name.lowercase() }
        }.getOrDefault(emptyList())
    }

    private fun persist(list: List<Snippet>) {
        prefs.edit().putString(KEY, json.encodeToString(list)).apply()
    }

    companion object {
        private const val KEY = "snippets_v1"
    }
}
