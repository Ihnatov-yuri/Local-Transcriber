package nl.ihnatov.transcriber.asr

import nl.ihnatov.transcriber.asr.DiarizationRunner.SpeakerSegment
import org.junit.Assert.assertEquals
import org.junit.Test

class WordAttributionTest {

    private val speakers = listOf(
        SpeakerSegment(start = 0f, end = 5f, speakerId = 0),
        SpeakerSegment(start = 5f, end = 10f, speakerId = 1),
    )

    @Test
    fun `word fully inside one segment gets that speaker`() {
        val result = assignWordSpeakers(listOf(Word(1.0, 2.0, "hi")), speakers)
        assertEquals(0, result.single().second)
    }

    @Test
    fun `word straddling a boundary gets the dominant-overlap speaker`() {
        // 4.0-6.5: 1s with speaker 0, 1.5s with speaker 1 -> speaker 1 wins.
        val result = assignWordSpeakers(listOf(Word(4.0, 6.5, "word")), speakers)
        assertEquals(1, result.single().second)
    }

    @Test
    fun `word with no overlap takes the nearest segment by midpoint`() {
        val gapSpeakers = listOf(
            SpeakerSegment(start = 0f, end = 2f, speakerId = 0),
            SpeakerSegment(start = 20f, end = 22f, speakerId = 1),
        )
        // Word at 3-4 (midpoint 3.5) is closer to segment ending at 2 than
        // to the one starting at 20.
        val result = assignWordSpeakers(listOf(Word(3.0, 4.0, "word")), gapSpeakers)
        assertEquals(0, result.single().second)
    }

    @Test
    fun `word closer to the later gap segment picks it`() {
        val gapSpeakers = listOf(
            SpeakerSegment(start = 0f, end = 2f, speakerId = 0),
            SpeakerSegment(start = 20f, end = 22f, speakerId = 1),
        )
        val result = assignWordSpeakers(listOf(Word(18.0, 19.0, "word")), gapSpeakers)
        assertEquals(1, result.single().second)
    }

    @Test
    fun `no speakers leaves every word unassigned`() {
        val result = assignWordSpeakers(listOf(Word(0.0, 1.0, "x")), emptyList())
        assertEquals(null, result.single().second)
    }

    // --- assignSpeakersPerWord ---

    private fun words(vararg w: Triple<Double, Double, String>) = w.map { Word(it.first, it.second, it.third) }

    @Test
    fun `segment spanning a turn change is split into per-speaker runs`() {
        val seg = RawSegment(
            3.0, 7.0, "how are you? fine thanks",
            words(
                Triple(3.0, 3.5, "how"), Triple(3.5, 4.0, "are"), Triple(4.0, 4.8, "you?"),
                Triple(5.2, 6.0, "fine"), Triple(6.0, 7.0, "thanks"),
            ),
        )
        val out = assignSpeakersPerWord(listOf(seg), speakers)
        assertEquals(listOf(0, 1), out.map { it.second })
        assertEquals(listOf("how are you?", "fine thanks"), out.map { it.first.text })
        // Outer bounds come from the segment, the inner cut from the words.
        assertEquals(3.0, out[0].first.startSeconds, 1e-9)
        assertEquals(4.8, out[0].first.endSeconds, 1e-9)
        assertEquals(5.2, out[1].first.startSeconds, 1e-9)
        assertEquals(7.0, out[1].first.endSeconds, 1e-9)
        assertEquals(3, out[0].first.words!!.size)
    }

    @Test
    fun `single-speaker segment passes through untouched`() {
        val seg = RawSegment(1.0, 3.0, "Original,  text", words(Triple(1.0, 2.0, "Original,"), Triple(2.0, 3.0, "text")))
        val out = assignSpeakersPerWord(listOf(seg), speakers)
        assertEquals(listOf(seg to 0), out)
    }

    @Test
    fun `segment without words falls back to per-segment max overlap`() {
        val noWords = RawSegment(3.0, 9.0, "mostly speaker one")
        val emptyWords = RawSegment(0.0, 2.0, "speaker zero", emptyList())
        val out = assignSpeakersPerWord(listOf(noWords, emptyWords), speakers)
        assertEquals(assignSpeakers(listOf(noWords, emptyWords), speakers), out)
        assertEquals(listOf(1, 0), out.map { it.second })
    }

    @Test
    fun `sub-quarter-second sandwiched run is absorbed as boundary jitter`() {
        val jitterSpeakers = listOf(
            SpeakerSegment(start = 0f, end = 2f, speakerId = 0),
            SpeakerSegment(start = 2f, end = 2.2f, speakerId = 1),
            SpeakerSegment(start = 2.2f, end = 5f, speakerId = 0),
        )
        val seg = RawSegment(
            0.0, 5.0, "one two three",
            words(Triple(0.5, 1.5, "one"), Triple(2.0, 2.2, "two"), Triple(2.5, 4.0, "three")),
        )
        val out = assignSpeakersPerWord(listOf(seg), jitterSpeakers)
        assertEquals(listOf(seg to 0), out)
    }

    @Test
    fun `a real sandwiched interjection survives as its own run`() {
        val three = listOf(
            SpeakerSegment(start = 0f, end = 2f, speakerId = 0),
            SpeakerSegment(start = 2f, end = 3f, speakerId = 1),
            SpeakerSegment(start = 3f, end = 6f, speakerId = 0),
        )
        val seg = RawSegment(
            0.0, 6.0, "so then yeah we left",
            words(
                Triple(0.5, 1.0, "so"), Triple(1.0, 1.8, "then"), Triple(2.1, 2.8, "yeah"),
                Triple(3.2, 4.0, "we"), Triple(4.0, 5.0, "left"),
            ),
        )
        val out = assignSpeakersPerWord(listOf(seg), three)
        assertEquals(listOf(0, 1, 0), out.map { it.second })
        assertEquals(listOf("so then", "yeah", "we left"), out.map { it.first.text })
    }

    @Test
    fun `no diarization output leaves everything unassigned and unsplit`() {
        val seg = RawSegment(0.0, 2.0, "hi there", words(Triple(0.0, 1.0, "hi"), Triple(1.0, 2.0, "there")))
        assertEquals(listOf(seg to null), assignSpeakersPerWord(listOf(seg), emptyList()))
    }
}
