package nl.ihnatov.transcriber.asr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnsembleBackendTest {

    @Test
    fun `shouldBench is false below the wedge limit`() {
        assertFalse(EnsembleBackend.shouldBench(wedgeCount = 1, maxWedges = 2))
    }

    @Test
    fun `shouldBench is true at the wedge limit`() {
        assertTrue(EnsembleBackend.shouldBench(wedgeCount = 2, maxWedges = 2))
    }

    @Test
    fun `shouldBench stays true past the wedge limit`() {
        assertTrue(EnsembleBackend.shouldBench(wedgeCount = 5, maxWedges = 2))
    }

    @Test
    fun `votePrior defaults to neutral 1f for every pairing`() {
        assertEqualsFloat(1f, EnsembleBackend.votePrior(AsrBackendKind.Parakeet, "uk"))
        assertEqualsFloat(1f, EnsembleBackend.votePrior(AsrBackendKind.WhisperCpp, null))
        assertEqualsFloat(1f, EnsembleBackend.votePrior(AsrBackendKind.Gemma4, "ar"))
    }

    // --- ChunkConfidence gate ---

    private val sane = "We moved the release to Thursday because the signing keys were not ready."
    private val looped = "thank you so much thank you so much thank you so much thank you so much thank you so much"

    @Test
    fun `a repetition loop loses to the other engine's sane text`() {
        org.junit.Assert.assertSame(sane, EnsembleBackend.pickBySanity(looped, sane, durationSec = 20.0))
        org.junit.Assert.assertSame(sane, EnsembleBackend.pickBySanity(sane, looped, durationSec = 20.0))
    }

    @Test
    fun `text far too short for the audio loses to a full transcript`() {
        org.junit.Assert.assertSame(sane, EnsembleBackend.pickBySanity("Okay.", sane, durationSec = 25.0))
    }

    @Test
    fun `sanity gate stays out of it when both texts pass or both fail`() {
        org.junit.Assert.assertNull(EnsembleBackend.pickBySanity(sane, "$sane Then we shipped.", durationSec = 20.0))
        org.junit.Assert.assertNull(EnsembleBackend.pickBySanity(looped, "Hm.", durationSec = 20.0))
    }

    // --- whisper word confidence in the vote ---

    private fun w(text: String, confidence: Float? = null) = Word(0.0, 0.0, text, confidence)

    @Test
    fun `scoredWords keeps real confidence and defaults to 1f when neither side has any`() {
        val plain = listOf(w("a"), w("b"))
        assertEqualsFloat(1f, EnsembleBackend.scoredWords(plain, plain).first().confidence)
        val whisper = listOf(w("a", 0.42f))
        assertEqualsFloat(0.42f, EnsembleBackend.scoredWords(whisper, plain).single().confidence)
    }

    @Test
    fun `an unscored engine gets the neutral stand-in against a scored one`() {
        val scored = EnsembleBackend.scoredWords(listOf(w("a")), listOf(w("a", 0.9f)))
        assertEqualsFloat(EnsembleBackend.UNSCORED_WORD_CONFIDENCE, scored.single().confidence)
    }

    @Test
    fun `confident whisper word wins a substitution and an unsure one loses it`() {
        val parakeet = listOf(w("the"), w("overas"), w("ten"))
        fun vote(whisperP: Float): String {
            val whisper = listOf(w("the", 0.95f), w("OWASP", whisperP), w("ten", 0.95f))
            return RoverMerge.merge(
                EnsembleBackend.scoredWords(parakeet, whisper),
                EnsembleBackend.scoredWords(whisper, parakeet),
            )
        }
        org.junit.Assert.assertEquals("the OWASP ten", vote(0.93f))
        org.junit.Assert.assertEquals("the overas ten", vote(0.31f))
    }

    @Test
    fun `unscored engine's extra word survives the insertion floor`() {
        val parakeet = listOf(w("we"), w("really"), w("left"))
        val whisper = listOf(w("we", 0.9f), w("left", 0.9f))
        val merged = RoverMerge.merge(
            EnsembleBackend.scoredWords(parakeet, whisper),
            EnsembleBackend.scoredWords(whisper, parakeet),
        )
        org.junit.Assert.assertEquals("we really left", merged)
    }

    private fun assertEqualsFloat(expected: Float, actual: Float) {
        org.junit.Assert.assertEquals(expected, actual, 1e-6f)
    }
}
