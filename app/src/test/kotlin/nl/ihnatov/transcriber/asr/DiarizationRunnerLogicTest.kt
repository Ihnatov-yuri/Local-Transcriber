package nl.ihnatov.transcriber.asr

import nl.ihnatov.transcriber.asr.DiarizationRunner.SpeakerSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiarizationRunnerLogicTest {

    private fun seg(start: Double, end: Double, text: String) = RawSegment(start, end, text)

    // ---- assignSpeakers ----

    @Test
    fun `assignSpeakers picks the speaker with dominant overlap`() {
        val speakers = listOf(
            SpeakerSegment(start = 0f, end = 5f, speakerId = 0),
            SpeakerSegment(start = 5f, end = 10f, speakerId = 1),
        )
        val transcript = listOf(seg(4.0, 7.0, "hello"))
        val result = assignSpeakers(transcript, speakers)
        // Overlaps speaker 0 for 1s (4-5) and speaker 1 for 2s (5-7) — speaker 1 wins.
        assertEquals(1, result.single().second)
    }

    @Test
    fun `assignSpeakers leaves segment unassigned with zero overlap`() {
        val speakers = listOf(SpeakerSegment(start = 100f, end = 105f, speakerId = 0))
        val result = assignSpeakers(listOf(seg(0.0, 1.0, "x")), speakers)
        assertNull(result.single().second)
    }

    @Test
    fun `assignSpeakers with no speakers leaves everything unassigned`() {
        val result = assignSpeakers(listOf(seg(0.0, 1.0, "x")), emptyList())
        assertNull(result.single().second)
    }

    // ---- coalesceBackchannels ----

    @Test
    fun `coalesceBackchannels reassigns a sandwiched short backchannel`() {
        val assigned = listOf(
            seg(0.0, 3.0, "so I was thinking") to 0,
            seg(3.0, 3.5, "yeah") to 1,
            seg(3.5, 6.0, "we should go") to 0,
        )
        val result = coalesceBackchannels(assigned)
        assertEquals(0, result[1].second)
    }

    @Test
    fun `coalesceBackchannels leaves a non-backchannel word alone`() {
        val assigned = listOf(
            seg(0.0, 3.0, "so I was thinking") to 0,
            seg(3.0, 3.5, "actually wait") to 1,
            seg(3.5, 6.0, "we should go") to 0,
        )
        val result = coalesceBackchannels(assigned)
        assertEquals(1, result[1].second)
    }

    @Test
    fun `coalesceBackchannels leaves a long segment alone even if the word matches`() {
        val assigned = listOf(
            seg(0.0, 3.0, "a") to 0,
            seg(3.0, 6.5, "yeah") to 1, // 3.5s, over the 2.0s cap
            seg(6.5, 9.0, "b") to 0,
        )
        val result = coalesceBackchannels(assigned)
        assertEquals(1, result[1].second)
    }

    // ---- dropPureFillerSegments ----

    @Test
    fun `dropPureFillerSegments drops filler-only text`() {
        val assigned = listOf(seg(0.0, 1.0, "Um.") to 0, seg(1.0, 2.0, "real content") to 0)
        val result = dropPureFillerSegments(assigned)
        assertEquals(1, result.size)
        assertEquals("real content", result.single().first.text)
    }

    @Test
    fun `dropPureFillerSegments keeps digits even if short`() {
        val assigned = listOf(seg(0.0, 1.0, "3") to 0)
        val result = dropPureFillerSegments(assigned)
        assertEquals(1, result.size)
    }

    @Test
    fun `dropPureFillerSegments keeps a real short reply`() {
        val assigned = listOf(seg(0.0, 1.0, "Okay.") to 0)
        val result = dropPureFillerSegments(assigned)
        assertEquals(1, result.size)
    }

    // ---- coalesceTurns ----

    @Test
    fun `coalesceTurns merges same-speaker segments within the gap`() {
        val assigned = listOf(
            seg(0.0, 5.0, "first part.") to 0,
            seg(6.0, 10.0, "second part.") to 0,
        )
        val result = coalesceTurns(assigned, gapSec = 30.0)
        assertEquals(1, result.size)
        assertEquals("first part. second part.", result.single().first.text)
        assertEquals(10.0, result.single().first.endSeconds, 1e-9)
    }

    @Test
    fun `coalesceTurns does not merge across a speaker change`() {
        val assigned = listOf(seg(0.0, 5.0, "a") to 0, seg(6.0, 10.0, "b") to 1)
        val result = coalesceTurns(assigned, gapSec = 30.0)
        assertEquals(2, result.size)
    }

    @Test
    fun `coalesceTurns does not merge across a gap wider than the threshold`() {
        val assigned = listOf(seg(0.0, 5.0, "a") to 0, seg(40.0, 45.0, "b") to 0)
        val result = coalesceTurns(assigned, gapSec = 30.0)
        assertEquals(2, result.size)
    }

    // ---- renumberByFirstAppearance ----

    @Test
    fun `renumberByFirstAppearance orders ids by earliest start time`() {
        val segs = listOf(
            SpeakerSegment(start = 10f, end = 15f, speakerId = 7),
            SpeakerSegment(start = 0f, end = 5f, speakerId = 3),
            SpeakerSegment(start = 5f, end = 10f, speakerId = 7),
        )
        val result = DiarizationRunner.renumberByFirstAppearance(segs)
        // speaker 3 appears first (t=0) -> 0; speaker 7 appears second (t=5) -> 1
        assertEquals(0, result.first { it.start == 0f }.speakerId)
        assertEquals(1, result.first { it.start == 5f }.speakerId)
        assertEquals(1, result.first { it.start == 10f }.speakerId)
    }

    // ---- defaultClusterThreshold ----

    @Test
    fun `defaultClusterThreshold is 0_5 for English-only`() {
        assertEquals(0.5f, DiarizationRunner.defaultClusterThreshold(listOf("en")), 1e-6f)
    }

    @Test
    fun `defaultClusterThreshold is 0_7 for auto, multilingual, or non-English`() {
        assertEquals(0.7f, DiarizationRunner.defaultClusterThreshold(emptyList()), 1e-6f)
        assertEquals(0.7f, DiarizationRunner.defaultClusterThreshold(listOf("ar")), 1e-6f)
        assertEquals(0.7f, DiarizationRunner.defaultClusterThreshold(listOf("en", "nl")), 1e-6f)
    }
}
