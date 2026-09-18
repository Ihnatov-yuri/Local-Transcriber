package nl.ihnatov.transcriber.asr

import nl.ihnatov.transcriber.asr.DiarizationRunner.SpeakerSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeakerMergeMapApplyTest {

    @Test
    fun `no merges maps every id to itself`() {
        assertEquals(mapOf(0 to 0, 1 to 1, 2 to 2), applyMergeMap(setOf(0, 1, 2), emptyList()))
    }

    @Test
    fun `a merged pair collapses onto the lower id`() {
        assertEquals(mapOf(0 to 0, 1 to 1, 2 to 1), applyMergeMap(setOf(0, 1, 2), listOf(2 to 1)))
    }

    @Test
    fun `merges are transitive`() {
        val mapping = applyMergeMap(setOf(0, 1, 2, 3), listOf(3 to 2, 2 to 1))
        assertEquals(mapOf(0 to 0, 1 to 1, 2 to 1, 3 to 1), mapping)
    }

    @Test
    fun `unknown ids and cycles are harmless`() {
        val mapping = applyMergeMap(setOf(0, 1), listOf(0 to 7, 9 to 1, 0 to 1, 1 to 0))
        assertEquals(mapOf(0 to 0, 1 to 0), mapping)
    }

    @Test
    fun `clusterSummaries totals duration and averages confidence per cluster`() {
        val summaries = clusterSummaries(listOf(
            SpeakerSegment(0f, 4f, speakerId = 0, confidence = 1f),
            SpeakerSegment(4f, 6f, speakerId = 1, confidence = 0.4f),
            SpeakerSegment(6f, 12f, speakerId = 0, confidence = 0.5f),
        )).sortedBy { it.id }
        assertEquals(listOf(0, 1), summaries.map { it.id })
        assertEquals(10.0, summaries[0].durationSeconds, 1e-6)
        assertEquals(0.75f, summaries[0].confidence, 1e-6f)
        assertEquals(2.0, summaries[1].durationSeconds, 1e-6)
    }

    private fun seg(start: Double, text: String) = RawSegment(start, start + 1.0, text)

    @Test
    fun `remapAssignedSpeakers applies the merge and closes the numbering gap`() {
        val assigned = listOf(seg(0.0, "a") to 0, seg(1.0, "b") to 1, seg(2.0, "c") to 2, seg(3.0, "d") to 1)
        // 1 merges into 0; the old 2 must become SPEAKER 1, not stay 2.
        val out = remapAssignedSpeakers(assigned, applyMergeMap(setOf(0, 1, 2), listOf(0 to 1)))
        assertEquals(listOf(0, 0, 1, 0), out.map { it.second })
        assertEquals(assigned.map { it.first }, out.map { it.first })
    }

    @Test
    fun `remapAssignedSpeakers keeps unassigned segments unassigned and passes unknown ids through`() {
        val assigned = listOf(seg(0.0, "a") to null, seg(1.0, "b") to 5, seg(2.0, "c") to 3)
        val out = remapAssignedSpeakers(assigned, mapOf(3 to 3))
        assertNull(out[0].second)
        assertEquals(listOf(0, 1), out.drop(1).map { it.second })
    }
}
