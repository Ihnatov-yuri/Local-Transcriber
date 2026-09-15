package nl.ihnatov.transcriber.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptVersionTest {

    private fun seg(text: String, speaker: String? = "SPEAKER_00", speakerName: String? = null) = Segment(
        recordingId = 1L,
        startSeconds = 0.0,
        endSeconds = 1.0,
        text = text,
        language = "en",
        speaker = speaker,
        speakerName = speakerName,
    )

    @Test
    fun `encode then decode round-trips every field`() {
        val original = listOf(
            seg("Hello there", speaker = "SPEAKER_00", speakerName = "Ahmed"),
            seg("General Kenobi", speaker = "SPEAKER_01"),
        )
        val json = encodeSegments(original)
        val decoded = decodeSegments(json)
        assertEquals(2, decoded.size)
        assertEquals("Hello there", decoded[0].text)
        assertEquals("Ahmed", decoded[0].speakerName)
        assertEquals("SPEAKER_01", decoded[1].speaker)
        assertEquals(null, decoded[1].speakerName)
    }

    @Test
    fun `toSegment attaches the given recordingId`() {
        val snapshot = SegmentSnapshot(startSeconds = 1.0, endSeconds = 2.0, text = "hi")
        val restored = snapshot.toSegment(recordingId = 42L)
        assertEquals(42L, restored.recordingId)
        assertEquals("hi", restored.text)
    }

    @Test
    fun `decodeSegments returns empty list for corrupt JSON instead of throwing`() {
        val result = decodeSegments("not valid json at all { [ }")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `decodeSegments returns empty list for empty input`() {
        assertTrue(decodeSegments("").isEmpty())
    }

    @Test
    fun `round-trip through Segment then back preserves text and timing`() {
        val original = seg("Round trip test")
        val snapshot = original.toSnapshot()
        val restored = snapshot.toSegment(recordingId = original.recordingId)
        assertEquals(original.text, restored.text)
        assertEquals(original.startSeconds, restored.startSeconds, 1e-9)
        assertEquals(original.endSeconds, restored.endSeconds, 1e-9)
    }
}
