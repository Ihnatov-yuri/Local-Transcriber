package nl.ihnatov.transcriber.asr

/**
 * One transcribed word with its own timestamps. Segment-level ASR backends
 * (whisper.cpp today) don't populate this yet; Phase 2 of the 2026-09 plan
 * wires it up for Parakeet/Omnilingual/Nemotron (native word timestamps)
 * and whisper.cpp (token timestamps via the JNI shim) and adds an optional
 * `words` list to [RawSegment]. The attribution rule below is written and
 * tested against this type now so it's ready the moment real data exists.
 */
data class Word(
    val start: Double,
    val end: Double,
    val text: String,
    val confidence: Float? = null,
)

/**
 * Per-word speaker attribution — the WhisperX rule
 * (github.com/m-bain/whisperX `diarize.py`): each word gets the speaker
 * segment it overlaps MOST with in time. A word with no temporal overlap
 * at all (a gap in diarization coverage, or the word falls between two
 * segments) instead takes the speaker of the NEAREST segment by midpoint
 * distance — every word ends up assigned, never left null, unlike
 * [assignSpeakers]'s segment-level version which leaves a segment
 * unassigned when it has zero overlap.
 */
fun assignWordSpeakers(
    words: List<Word>,
    speakers: List<DiarizationRunner.SpeakerSegment>,
): List<Pair<Word, Int?>> {
    if (speakers.isEmpty()) return words.map { it to null }
    return words.map { word ->
        var bestOverlap = 0.0
        var bestSpeaker: Int? = null
        for (sp in speakers) {
            val overlap = (minOf(word.end, sp.end.toDouble()) -
                maxOf(word.start, sp.start.toDouble())).coerceAtLeast(0.0)
            if (overlap > bestOverlap) {
                bestOverlap = overlap
                bestSpeaker = sp.speakerId
            }
        }
        if (bestSpeaker == null) {
            val mid = (word.start + word.end) / 2.0
            var nearest = speakers.first()
            var bestGap = Double.MAX_VALUE
            for (sp in speakers) {
                val spStart = sp.start.toDouble()
                val spEnd = sp.end.toDouble()
                val gap = when {
                    mid < spStart -> spStart - mid
                    mid > spEnd -> mid - spEnd
                    else -> 0.0
                }
                if (gap < bestGap) {
                    bestGap = gap
                    nearest = sp
                }
            }
            bestSpeaker = nearest.speakerId
        }
        word to bestSpeaker
    }
}
