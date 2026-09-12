package nl.ihnatov.transcriber.asr

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class ArbitrationResponse(val choices: List<Int> = emptyList())

private val arbitrationJson = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * JSON Schema for the arbitration second pass's constrained decoding
 * (`ResponseFormat.json` + `ConversationConfig(enableResponseFormat = true)`,
 * verified against the actual LiteRT-LM 0.17.0 AAR). Forces the model to
 * choose an index per dispute rather than write free text — the 2026
 * research behind this plan is explicit that free-form LLM correction
 * makes transcripts worse; locking agreed spans and only asking for a
 * choice among options that ALREADY EXIST in one engine's output is what
 * keeps this safe.
 */
const val ARBITRATION_JSON_SCHEMA = "{\"type\":\"object\",\"properties\":{\"choices\":{\"type\":\"array\"," +
    "\"items\":{\"type\":\"integer\"}}},\"required\":[\"choices\"]}"

/**
 * Parse and validate an arbitration response. Defensive: a short array is
 * padded with 0 (prefer engine A, the default/priority sub-engine),
 * anything out of `{0, 1}` clamps to the nearer valid choice, and
 * malformed JSON returns null so the caller falls back to the vote-only
 * merge rather than a mangled one.
 */
fun parseArbitrationResponse(raw: String, disputeCount: Int): List<Int>? {
    val parsed = try {
        arbitrationJson.decodeFromString<ArbitrationResponse>(raw)
    } catch (t: Throwable) {
        return null
    }
    if (parsed.choices.isEmpty() && disputeCount > 0) return null
    return List(disputeCount) { i -> parsed.choices.getOrNull(i)?.coerceIn(0, 1) ?: 0 }
}

internal const val ARBITRATION_SYSTEM_PROMPT =
    "You are resolving disagreements between two automatic speech-recognition " +
        "transcripts of the SAME audio. For each numbered dispute you are given " +
        "two readings, labeled 0 and 1, of the same short span. Pick whichever " +
        "reading is more plausible given normal speech and the surrounding " +
        "words. Respond with one choice (0 or 1) per dispute, in order. Never " +
        "invent a third reading — only choose between the two given."
