package nl.ihnatov.transcriber.asr

import nl.ihnatov.transcriber.data.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeakerNameInferenceTest {

    private fun row(speaker: String?, text: String, start: Double = 0.0) = Segment(
        recordingId = 1L,
        startSeconds = start,
        endSeconds = start + 1.0,
        text = text,
        speaker = speaker,
    )

    // ---- findIntroducedName: per-language self-introductions ----

    @Test
    fun `English self-introductions are detected`() {
        assertEquals("Ahmed", findIntroducedName("Hi, I'm Ahmed, nice to meet you"))
        assertEquals("Sara", findIntroducedName("My name is Sara"))
        assertEquals("Yuri", findIntroducedName("This is Yuri speaking"))
    }

    @Test
    fun `stoplisted words after I'm are not treated as names`() {
        assertNull(findIntroducedName("I'm tired"))
        assertNull(findIntroducedName("I'm just looking around"))
    }

    @Test
    fun `Ukrainian self-introduction is detected`() {
        assertEquals("Олена", findIntroducedName("Мене звати Олена, дуже приємно"))
    }

    @Test
    fun `Dutch self-introduction is detected`() {
        assertEquals("Jan", findIntroducedName("Mijn naam is Jan"))
        assertEquals("Anke", findIntroducedName("Ik ben Anke"))
    }

    @Test
    fun `Arabic self-introduction is detected`() {
        assertEquals("أحمد", findIntroducedName("اسمي أحمد"))
    }

    @Test
    fun `Arabic stoplist blocks a common non-name word`() {
        assertNull(findIntroducedName("أنا متأكد"))
    }

    @Test
    fun `no introduction returns null`() {
        assertNull(findIntroducedName("the weather is nice today"))
    }

    // ---- applyInferredSpeakerNames: propagation + first-match-wins ----

    @Test
    fun `inferred name propagates to every segment for that speaker`() {
        val rows = listOf(
            row("SPEAKER_00", "Hi, I'm Ahmed", start = 0.0),
            row("SPEAKER_00", "so anyway", start = 5.0),
            row("SPEAKER_01", "okay got it", start = 6.0),
        )
        val result = applyInferredSpeakerNames(rows)
        assertEquals("Ahmed", result[0].speakerName)
        assertEquals("Ahmed", result[1].speakerName)
        assertNull(result[2].speakerName)
    }

    @Test
    fun `first match wins per speaker`() {
        val rows = listOf(
            row("SPEAKER_00", "Hi, I'm Ahmed", start = 0.0),
            row("SPEAKER_00", "This is Omar speaking", start = 5.0),
        )
        val result = applyInferredSpeakerNames(rows)
        assertEquals("Ahmed", result[0].speakerName)
        assertEquals("Ahmed", result[1].speakerName)
    }

    @Test
    fun `existing user-edited name is preserved`() {
        val rows = listOf(
            row("SPEAKER_00", "Hi, I'm Ahmed", start = 0.0).copy(speakerName = "Boss"),
        )
        val result = applyInferredSpeakerNames(rows)
        assertEquals("Boss", result.single().speakerName)
    }

    // ---- Addressee rule: two-person conversation only ----

    @Test
    fun `addressee rule names the other speaker in a two-person conversation`() {
        val rows = listOf(
            row("SPEAKER_00", "Hi, Lana, how are you?", start = 0.0),
            row("SPEAKER_01", "I'm good, thanks", start = 2.0),
        )
        val result = applyInferredSpeakerNames(rows)
        assertEquals("Lana", result.first { it.speaker == "SPEAKER_01" }.speakerName)
        assertNull(result.first { it.speaker == "SPEAKER_00" }.speakerName)
    }

    @Test
    fun `addressee rule does not apply with three or more speakers`() {
        val rows = listOf(
            row("SPEAKER_00", "Hi, Lana, how are you?", start = 0.0),
            row("SPEAKER_01", "I'm good, thanks", start = 2.0),
            row("SPEAKER_02", "hello everyone", start = 4.0),
        )
        val result = applyInferredSpeakerNames(rows)
        assertNull(result.first { it.speaker == "SPEAKER_01" }.speakerName)
    }

    @Test
    fun `self-introduction wins over addressee rule for the same speaker`() {
        val rows = listOf(
            row("SPEAKER_00", "Hi, Lana", start = 0.0),
            row("SPEAKER_01", "Actually, I'm Sara, not Lana", start = 2.0),
        )
        val result = applyInferredSpeakerNames(rows)
        assertEquals("Sara", result.first { it.speaker == "SPEAKER_01" }.speakerName)
    }

    // ---- coalesceTurnSegments ----

    @Test
    fun `coalesceTurnSegments merges same-speaker rows within the gap`() {
        val rows = listOf(
            row("SPEAKER_00", "first.", start = 0.0).copy(endSeconds = 5.0),
            row("SPEAKER_00", "second.", start = 6.0).copy(endSeconds = 10.0),
        )
        val result = coalesceTurnSegments(rows, gapSec = 30.0)
        assertEquals(1, result.size)
        assertEquals("first. second.", result.single().text)
    }

    @Test
    fun `coalesceTurnSegments keeps different speakers separate`() {
        val rows = listOf(
            row("SPEAKER_00", "a", start = 0.0).copy(endSeconds = 5.0),
            row("SPEAKER_01", "b", start = 6.0).copy(endSeconds = 10.0),
        )
        val result = coalesceTurnSegments(rows, gapSec = 30.0)
        assertEquals(2, result.size)
    }
}

class SpeakerNameInferenceRegressionTest {
    private fun row(speaker: String, text: String, start: Double) = nl.ihnatov.transcriber.data.Segment(
        recordingId = 1L, startSeconds = start, endSeconds = start + 1.0, text = text, speaker = speaker,
    )

    @org.junit.Test
    fun `lowercase word after a greeting is never taken as the other speaker's name`() {
        // "hello, my name is Sara" used to hand "my" to SPEAKER_01 because
        // the whole pattern was IGNORE_CASE; the name must be capitalised.
        val rows = listOf(
            row("SPEAKER_00", "hello, my name is Sara", start = 0.0),
            row("SPEAKER_01", "nice to meet you", start = 2.0),
        )
        val result = applyInferredSpeakerNames(rows)
        org.junit.Assert.assertEquals("Sara", result.first { it.speaker == "SPEAKER_00" }.speakerName)
        org.junit.Assert.assertNull(result.first { it.speaker == "SPEAKER_01" }.speakerName)
    }

    @org.junit.Test
    fun `hi there and hey guys do not produce names`() {
        val rows = listOf(
            row("SPEAKER_00", "hi there, how are you", start = 0.0),
            row("SPEAKER_01", "Hey guys, welcome back", start = 2.0),
        )
        val result = applyInferredSpeakerNames(rows)
        org.junit.Assert.assertTrue(result.all { it.speakerName == null })
    }

    @org.junit.Test
    fun `lowercase greeting still names a capitalised addressee`() {
        val rows = listOf(
            row("SPEAKER_00", "hi Lana, how are you?", start = 0.0),
            row("SPEAKER_01", "I'm good, thanks", start = 2.0),
        )
        val result = applyInferredSpeakerNames(rows)
        org.junit.Assert.assertEquals("Lana", result.first { it.speaker == "SPEAKER_01" }.speakerName)
    }
}
