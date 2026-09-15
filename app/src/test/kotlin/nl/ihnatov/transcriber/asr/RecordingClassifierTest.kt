package nl.ihnatov.transcriber.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordingClassifierTest {

    @Test
    fun `parses each known category id`() {
        assertEquals(RecordingCategory.Meeting, parseClassifyResponse("""{"category": "meeting"}"""))
        assertEquals(RecordingCategory.Interview, parseClassifyResponse("""{"category": "interview"}"""))
        assertEquals(RecordingCategory.Note, parseClassifyResponse("""{"category": "note"}"""))
        assertEquals(RecordingCategory.Idea, parseClassifyResponse("""{"category": "idea"}"""))
    }

    @Test
    fun `matches category id case-insensitively`() {
        assertEquals(RecordingCategory.Meeting, parseClassifyResponse("""{"category": "MEETING"}"""))
    }

    @Test
    fun `unknown category id returns null rather than guessing`() {
        assertNull(parseClassifyResponse("""{"category": "podcast"}"""))
    }

    @Test
    fun `malformed json returns null instead of throwing`() {
        assertNull(parseClassifyResponse("not json at all"))
    }

    @Test
    fun `missing category field returns null`() {
        assertNull(parseClassifyResponse("""{}"""))
    }
}
