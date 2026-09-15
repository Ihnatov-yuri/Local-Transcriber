package nl.ihnatov.transcriber.asr

import org.junit.Assert.assertEquals
import org.junit.Test

class PromptStoreVocabularyTest {

    private val byLanguage = mapOf(
        "uk" to "Хрещатик, Оболонь",
        "ar" to "دوحة, مسقط",
        "en" to "Kaiko, Blits Insurance",
    )

    @Test
    fun `empty languages pulls in every per-language list plus global`() {
        val terms = selectVocabularyTerms("Yuri Ihnatov", byLanguage, emptySet())
        assertEquals(
            setOf("Yuri Ihnatov", "Хрещатик", "Оболонь", "دوحة", "مسقط", "Kaiko", "Blits Insurance"),
            terms.toSet(),
        )
    }

    @Test
    fun `one selected language pulls in only that list plus global`() {
        val terms = selectVocabularyTerms("Yuri Ihnatov", byLanguage, setOf("uk"))
        assertEquals(setOf("Yuri Ihnatov", "Хрещатик", "Оболонь"), terms.toSet())
    }

    @Test
    fun `language codes are matched case-insensitively`() {
        val terms = selectVocabularyTerms("", byLanguage, setOf("UK"))
        assertEquals(setOf("Хрещатик", "Оболонь"), terms.toSet())
    }

    @Test
    fun `a selected language with no list contributes nothing beyond global`() {
        val terms = selectVocabularyTerms("Yuri Ihnatov", byLanguage, setOf("nl"))
        assertEquals(listOf("Yuri Ihnatov"), terms)
    }

    @Test
    fun `blank global and empty map yields an empty list`() {
        assertEquals(emptyList<String>(), selectVocabularyTerms("", emptyMap(), emptySet()))
    }

    @Test
    fun `duplicate terms across global and per-language lists are deduplicated`() {
        val terms = selectVocabularyTerms("Kaiko", mapOf("en" to "Kaiko, Other"), setOf("en"))
        assertEquals(listOf("Kaiko", "Other"), terms)
    }
}
