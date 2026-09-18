package nl.ihnatov.transcriber.asr

/**
 * One transcribed word with its own timestamps, carried on
 * [RawSegment.words]. Populated by Parakeet/Omnilingual (sherpa-onnx token
 * timestamps, no confidence — upstream doesn't expose one yet) and by
 * whisper.cpp (token timestamps + [confidence] = geometric mean of the
 * word's token probabilities, see [WhisperWordGrouping]). Gemma has no
 * word-level data at all.
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

/**
 * A speaker run shorter than this, sandwiched between two runs of the
 * SAME other speaker inside one segment, is treated as diarization
 * boundary jitter rather than a real interjection and absorbed. Real
 * backchannels ("yeah", "так") run ~0.3 s and up, so they survive.
 */
private const val MIN_SANDWICHED_RUN_SEC = 0.25

/**
 * Production entry point for speaker attribution: per WORD where the
 * engine gave us words ([assignWordSpeakers]), splitting a segment into
 * consecutive same-speaker runs when its words disagree — an ASR segment
 * routinely spans a turn change, and per-segment max-overlap
 * ([assignSpeakers]) hands the whole thing to whoever talked longest.
 * Segments without word data keep exactly that per-segment rule.
 *
 * A split piece takes its text from its words (the segment's own text
 * can't be cut reliably) and its bounds from them too, except that the
 * first/last piece keep the segment's outer start/end. Unsplit segments
 * pass through untouched, text included.
 */
fun assignSpeakersPerWord(
    transcript: List<RawSegment>,
    speakers: List<DiarizationRunner.SpeakerSegment>,
): List<Pair<RawSegment, Int?>> {
    if (speakers.isEmpty()) return transcript.map { it to null }
    return transcript.flatMap { seg ->
        val words = seg.words
        if (words.isNullOrEmpty()) return@flatMap assignSpeakers(listOf(seg), speakers)
        val runs = speakerRuns(assignWordSpeakers(words, speakers))
        if (runs.size == 1) return@flatMap listOf(seg to runs[0].second)
        runs.mapIndexed { i, (runWords, speaker) ->
            RawSegment(
                startSeconds = if (i == 0) seg.startSeconds else runWords.first().start,
                endSeconds = if (i == runs.lastIndex) seg.endSeconds else runWords.last().end,
                text = RoverMerge.joinSurfaces(runWords.map { it.text }),
                words = runWords,
            ) to speaker
        }
    }
}

/** Group consecutive same-speaker words, then absorb sandwiched jitter runs (see [MIN_SANDWICHED_RUN_SEC]). */
private fun speakerRuns(attributed: List<Pair<Word, Int?>>): List<Pair<List<Word>, Int?>> {
    fun group(items: List<Pair<Word, Int?>>): MutableList<Pair<MutableList<Word>, Int?>> {
        val runs = mutableListOf<Pair<MutableList<Word>, Int?>>()
        for ((word, speaker) in items) {
            val last = runs.lastOrNull()
            if (last != null && last.second == speaker) last.first.add(word)
            else runs.add(mutableListOf(word) to speaker)
        }
        return runs
    }
    val runs = group(attributed)
    if (runs.size < 3) return runs
    val smoothed = mutableListOf<Pair<Word, Int?>>()
    for ((i, run) in runs.withIndex()) {
        val (runWords, speaker) = run
        val sandwiched = i in 1 until runs.lastIndex && runs[i - 1].second == runs[i + 1].second
        val duration = runWords.last().end - runWords.first().start
        val effective = if (sandwiched && duration < MIN_SANDWICHED_RUN_SEC) runs[i - 1].second else speaker
        runWords.forEach { smoothed.add(it to effective) }
    }
    return group(smoothed)
}
