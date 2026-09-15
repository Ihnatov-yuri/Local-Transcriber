package nl.ihnatov.transcriber.asr

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What we tell Gemma about one diarization cluster when asking for a merge suggestion. */
data class ClusterSummary(
    val id: Int,
    val durationSeconds: Double,
    val confidence: Float,
)

@Serializable
internal data class MergeMapResponse(val merge: List<List<Int>> = emptyList())

private val mergeMapJson = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * JSON Schema passed to `ResponseFormat.json(...)` for constrained decoding
 * (LiteRT-LM 0.17.0, `ConversationConfig(enableResponseFormat = true)`) —
 * forces the model's output to this shape so [parseMergeMapResponse] never
 * has to recover from prose wrapped around the JSON.
 */
const val MERGE_MAP_JSON_SCHEMA = "{\"type\":\"object\",\"properties\":{\"merge\":{\"type\":\"array\"," +
    "\"items\":{\"type\":\"array\",\"items\":{\"type\":\"integer\"},\"minItems\":2,\"maxItems\":2}}}," +
    "\"required\":[\"merge\"]}"

/**
 * Parse and validate a merge-map JSON response. Defensive by design: any
 * malformed JSON, any pair referencing an id outside [validIds], or a
 * self-pair (a, a) is dropped rather than failing the whole response — a
 * partially-useful suggestion is better than none, and this is a best-
 * effort cleanup pass over already-working clustering, never a
 * requirement.
 */
fun parseMergeMapResponse(raw: String, validIds: Set<Int>): List<Pair<Int, Int>> {
    val parsed = try {
        mergeMapJson.decodeFromString<MergeMapResponse>(raw)
    } catch (t: Throwable) {
        return emptyList()
    }
    return parsed.merge.mapNotNull { pair ->
        if (pair.size != 2) return@mapNotNull null
        val a = pair[0]
        val b = pair[1]
        if (a == b || a !in validIds || b !in validIds) return@mapNotNull null
        a to b
    }
}

/**
 * Apply "these two cluster ids are the same speaker" pairs as a union-find
 * merge — never a split, per the plan ("applied only when it merges, never
 * splits"). Each original id maps to the lowest id in its merged group, a
 * stable and deterministic renumbering target (final display numbering
 * still goes through [DiarizationRunner.renumberByFirstAppearance]
 * afterward). Malformed or cyclic input can't produce anything worse than
 * an over-merge; it can never invent a new id or drop one — every id in
 * [ids] is present as a key in the result.
 */
fun applyMergeMap(ids: Set<Int>, merges: List<Pair<Int, Int>>): Map<Int, Int> {
    val parent = ids.associateWithTo(HashMap<Int, Int>()) { it }
    fun find(x: Int): Int {
        var root = x
        while (parent[root] != root) root = parent[root] ?: return x
        return root
    }
    fun union(a: Int, b: Int) {
        val ra = find(a)
        val rb = find(b)
        if (ra == rb) return
        if (ra < rb) parent[rb] = ra else parent[ra] = rb
    }
    for ((a, b) in merges) {
        if (a !in parent || b !in parent) continue
        union(a, b)
    }
    return ids.associateWith { find(it) }
}
