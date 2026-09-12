package nl.ihnatov.transcriber.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported verbatim from the Mac app's CoreLogicTests.swift / PipelineLogicTests.swift TextDestutter cases. */
class TextDestutterTest {

    @Test
    fun `stutter runs collapse`() {
        assertEquals("For person came.", TextDestutter.collapseLine("For for for for person came."))
    }

    @Test
    fun `phrase echo collapses`() {
        assertEquals(
            "So the next conversation begins.",
            TextDestutter.collapseLine("So the next the next conversation begins."),
        )
    }

    @Test
    fun `deliberate repeats survive`() {
        assertEquals("Thank you. Thank you.", TextDestutter.collapseLine("Thank you. Thank you."))
        assertEquals("Yeah. Yeah. Thanks. Thanks.", TextDestutter.collapseLine("Yeah. Yeah. Thanks. Thanks."))
    }

    @Test
    fun `filler punctuation carries to boundary`() {
        // "Okay, um. Okay" must keep its sentence boundary.
        assertEquals("Okay. Okay let's go.", TextDestutter.collapseLine("Okay, um. Okay let's go."))
    }

    @Test
    fun `ukrainian capitalization preserved`() {
        assertEquals("Дякую за увагу.", TextDestutter.collapseLine("Дякую дякую дякую за увагу."))
    }

    @Test
    fun `numbers are never treated as stutters`() {
        assertEquals("The budget is 400 thousand.", TextDestutter.collapseLine("The budget is 400 400 thousand."))
        // Different numbers untouched.
        assertEquals("Prices rose 50 60 percent.", TextDestutter.collapseLine("Prices rose 50 60 percent."))
    }

    @Test
    fun `multiline input preserves each speaker line independently`() {
        val input = "Yuri: so so so we agree.\nLana: yes yes we do."
        // "yes" is a legit double — deliberate emphasis survives.
        assertEquals("Yuri: so we agree.\nLana: yes yes we do.", TextDestutter.collapse(input))
    }

    // ---- Additional Android-side coverage ----

    @Test
    fun `single word input is returned unchanged`() {
        assertEquals("Hello", TextDestutter.collapseLine("Hello"))
    }

    @Test
    fun `empty line stays empty`() {
        assertEquals("", TextDestutter.collapseLine(""))
    }

    @Test
    fun `a lone filler word is dropped entirely`() {
        assertEquals("", TextDestutter.collapseLine("um"))
    }

    @Test
    fun `legit doubles at run length 2 survive outside the stoplist word`() {
        // "that that's" is called out in the file's own doc comment as a legit double.
        val result = TextDestutter.collapseLine("I think that that's right.")
        assertTrue(result.contains("that that's"))
    }

    @Test
    fun `three-plus repeats always collapse even for a legit-double word`() {
        assertEquals("So we agree.", TextDestutter.collapseLine("So so so we agree."))
    }
}
