package nl.ihnatov.transcriber.asr

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What kind of recording this is, for Library browsing/filtering. New to
 * the Android app — the Mac app has no classification feature at all (no
 * category field, no structured-output plumbing in its backend protocol;
 * verified by reading its full ASR backend layer), so this is fresh
 * design per the 2026-09 plan's own item, not a port. See
 * [Gemma4Backend.suggestCategory].
 */
enum class RecordingCategory(val id: String, val displayName: String) {
    Meeting("meeting", "Meeting"),
    Interview("interview", "Interview"),
    Note("note", "Note"),
    Idea("idea", "Idea"),
    ;

    companion object {
        fun fromId(id: String?): RecordingCategory? = entries.firstOrNull { it.id == id }
    }
}

@Serializable
internal data class ClassifyResponse(val category: String)

private val classifyJson = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * JSON Schema passed to `ResponseFormat.json(...)` for constrained decoding
 * — restricts the model's output to exactly one of the four known category
 * ids, so [parseClassifyResponse] never has to guess at free text.
 */
const val CLASSIFY_JSON_SCHEMA = "{\"type\":\"object\",\"properties\":{\"category\":{\"type\":\"string\"," +
    "\"enum\":[\"meeting\",\"interview\",\"note\",\"idea\"]}},\"required\":[\"category\"]}"

/** Parse and validate a classify response. Malformed JSON or any value outside the four known ids returns null — never guessed at. */
fun parseClassifyResponse(raw: String): RecordingCategory? {
    val parsed = try {
        classifyJson.decodeFromString<ClassifyResponse>(raw)
    } catch (t: Throwable) {
        return null
    }
    return RecordingCategory.fromId(parsed.category.lowercase())
}
