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
}
