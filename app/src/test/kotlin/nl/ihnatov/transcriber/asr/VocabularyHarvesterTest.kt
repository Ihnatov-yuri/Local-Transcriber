package nl.ihnatov.transcriber.asr

import nl.ihnatov.transcriber.asr.VocabularyHarvester.HarvestItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported verbatim from the Mac app's `TranscriberrTests/DictationTextTests.swift` (`VocabularyHarvesterTests`). */
class VocabularyHarvesterTest {

    @Test
    fun `candidates skip sentence initials but keep runs and acronyms`() {
        assertEquals(
            listOf("KimKim", "OWASP", "Blits Insurance"),
            VocabularyHarvester.candidates("Yesterday we met KimKim at OWASP. Blits Insurance called. Then nothing."),
        )
        assertEquals(emptyList<String>(), VocabularyHarvester.candidates("Okay. Sure thing."))
    }

    @Test
    fun `harvest requires recurrence and rejects ordinary words`() {
        val items = listOf(
            HarvestItem(1L, "We spoke with Kaiko about the Project. The project is late."),
            HarvestItem(2L, "Kaiko sent the plan. Another Project meeting tomorrow."),
            HarvestItem(3L, "Nothing about the project today. Kaiko again."),
        )
        val terms = VocabularyHarvester.harvest(items, existingVocabulary = emptyList())
        assertEquals(listOf("Kaiko"), terms.map { it.spelling })
        assertEquals(3, terms.first().recordings)

        // Already in the vocabulary → not suggested.
        assertTrue(VocabularyHarvester.harvest(items, existingVocabulary = listOf("kaiko")).isEmpty())
    }

    @Test
    fun `harvest rejects contractions fillers and repeats`() {
        val items = listOf(
            HarvestItem(1L, "So That's it. Uh I'm done, Kim KimKim said. Ask Дякую again and Дякую."),
            HarvestItem(2L, "That's fine. Uh I'm here. Kim KimKim agreed. Дякую all."),
        )
        assertTrue(VocabularyHarvester.harvest(items, existingVocabulary = emptyList()).isEmpty())
    }

    @Test
    fun `harvest prefers most common spelling`() {
        val items = listOf(
            HarvestItem(1L, "Ask Kimkim. Then Kimkim again and KimKim once."),
            HarvestItem(2L, "Kimkim replied."),
        )
        assertEquals(
            "Kimkim",
            VocabularyHarvester.harvest(items, existingVocabulary = emptyList()).first().spelling,
        )
    }
}
