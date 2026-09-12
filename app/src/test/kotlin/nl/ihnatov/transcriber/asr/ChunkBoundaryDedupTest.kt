package nl.ihnatov.transcriber.asr

import nl.ihnatov.transcriber.data.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkBoundaryDedupTest {

    private fun seg(start: Double, text: String, speaker: String? = null) = Segment(
        recordingId = 1L,
        startSeconds = start,
        endSeconds = start + 3.0,
        text = text,
        speaker = speaker,
    )

    // ---- findBoundaryOverlapDrop ----

    @Test
    fun `finds an exact run at the boundary with no prefix skip`() {
        val prev = listOf("we", "discussed", "the", "budget", "report")
        val curr = listOf("the", "budget", "report", "needs", "revision")
        assertEquals(3, findBoundaryOverlapDrop(prev, curr))
    }

    @Test
    fun `allows a small prefix skip when the next chunk mis-transcribed the divergent lead-in word`() {
        // "literally refused to see" vs "Really refused to see" — the
        // matching run "refused to see" sits after a 1-token divergent
        // prefix ("Really") on the next side.
        val prev = listOf("literally", "refused", "to", "see")
        val curr = listOf("really", "refused", "to", "see", "him", "anymore")
        assertEquals(4, findBoundaryOverlapDrop(prev, curr))
    }

    @Test
    fun `no match returns zero`() {
        val prev = listOf("hello", "world", "foo")
        val curr = listOf("completely", "different", "text", "here")
        assertEquals(0, findBoundaryOverlapDrop(prev, curr))
    }

    @Test
    fun `matching is case and punctuation insensitive`() {
        val prev = listOf("The", "Budget", "Report.")
        val curr = listOf("the", "budget", "report,", "continues")
        assertEquals(3, findBoundaryOverlapDrop(prev, curr))
    }

    @Test
    fun `runs shorter than the minimum are never matched`() {
        // Only a 2-token overlap ("you know") — below MIN_DEDUP_RUN_TOKENS.
        val prev = listOf("well", "you", "know")
        val curr = listOf("you", "know", "it", "went", "fine")
        assertEquals(0, findBoundaryOverlapDrop(prev, curr))
    }

    @Test
    fun `too-short token lists never match`() {
        assertEquals(0, findBoundaryOverlapDrop(listOf("hi"), listOf("hi", "there", "friend")))
        assertEquals(0, findBoundaryOverlapDrop(listOf("a", "b", "c"), listOf("c")))
    }

    // ---- dedupChunkBoundaries ----

    @Test
    fun `no registered boundaries means no dedup at all`() {
        val rows = listOf(
            seg(0.0, "we discussed the budget report"),
            seg(28.0, "the budget report needs revision"),
        )
        val result = dedupChunkBoundaries(rows, chunkBoundaryStartSeconds = emptyList())
        assertEquals(rows, result)
    }

    @Test
    fun `trims the duplicated prefix from the segment at a registered boundary`() {
        val rows = listOf(
            seg(0.0, "we discussed the budget report"),
            seg(28.0, "the budget report needs revision"),
        )
        val result = dedupChunkBoundaries(rows, chunkBoundaryStartSeconds = listOf(28.0))
        assertEquals(2, result.size)
        assertEquals("we discussed the budget report", result[0].text)
        assertEquals("needs revision", result[1].text)
    }

    @Test
    fun `a fully duplicate segment at a boundary is dropped entirely`() {
        val rows = listOf(
            seg(0.0, "the budget report"),
            seg(28.0, "the budget report"),
        )
        val result = dedupChunkBoundaries(rows, chunkBoundaryStartSeconds = listOf(28.0))
        assertEquals(1, result.size)
        assertEquals("the budget report", result[0].text)
    }

    @Test
    fun `different speakers at a boundary are never deduped even with matching text`() {
        val rows = listOf(
            seg(0.0, "the budget report is due", speaker = "SPEAKER_00"),
            seg(28.0, "the budget report is due tomorrow", speaker = "SPEAKER_01"),
        )
        val result = dedupChunkBoundaries(rows, chunkBoundaryStartSeconds = listOf(28.0))
        assertEquals(2, result.size)
        assertEquals("the budget report is due tomorrow", result[1].text)
    }

    @Test
    fun `a segment far from any registered boundary keeps repeated phrases intact`() {
        // Legitimate mid-chunk repetition ("right, right, that's it") must
        // never be treated as a boundary duplicate.
        val rows = listOf(
            seg(0.0, "the budget report is due"),
            seg(5.0, "the budget report is due again"),
        )
        val result = dedupChunkBoundaries(rows, chunkBoundaryStartSeconds = listOf(28.0))
        assertEquals(rows, result)
    }

    @Test
    fun `a boundary segment within tolerance of the registered time is still matched`() {
        val rows = listOf(
            seg(0.0, "we discussed the budget report"),
            // 1.2s off from the registered 28.0s boundary — within the 2s tolerance.
            seg(29.2, "the budget report needs revision"),
        )
        val result = dedupChunkBoundaries(rows, chunkBoundaryStartSeconds = listOf(28.0))
        assertEquals("needs revision", result[1].text)
    }
}
