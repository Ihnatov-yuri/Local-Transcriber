package nl.ihnatov.transcriber.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingRepositorySearchTest {

    @Test
    fun `plain text passes through unchanged`() {
        assertEquals("hello world", escapeLikePattern("hello world"))
    }

    @Test
    fun `percent is escaped so it is not treated as a LIKE wildcard`() {
        assertEquals("100\\%", escapeLikePattern("100%"))
    }

    @Test
    fun `underscore is escaped so it is not treated as a LIKE single-char wildcard`() {
        assertEquals("foo\\_bar", escapeLikePattern("foo_bar"))
    }

    @Test
    fun `a literal backslash is escaped first so it cannot combine with a later escape`() {
        // Naively escaping % before \ would turn "50\%" into "50\\%" (an
        // escaped backslash followed by an UNescaped %) instead of the
        // intended "50\\\%" (backslash, then escaped percent).
        assertEquals("50\\\\\\%", escapeLikePattern("50\\%"))
    }

    @Test
    fun `mixed wildcards and backslashes all get escaped`() {
        assertEquals("a\\\\b\\%c\\_d", escapeLikePattern("a\\b%c_d"))
    }

    @Test
    fun `empty string stays empty`() {
        assertEquals("", escapeLikePattern(""))
    }
}
