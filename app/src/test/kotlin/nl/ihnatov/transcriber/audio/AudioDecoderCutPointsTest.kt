package nl.ihnatov.transcriber.audio

import nl.ihnatov.transcriber.audio.AudioDecoder.SilenceRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioDecoderCutPointsTest {

    @Test
    fun `audio shorter than one chunk needs no cut points`() {
        val cuts = AudioDecoder.computeCutPoints(
            silences = emptyList(),
            durationSec = 20.0,
            targetChunkSec = 28.0,
            flexSec = 2.0,
        )
        assertTrue(cuts.isEmpty())
    }

    @Test
    fun `no silences available falls back to hard cuts at the nominal spacing`() {
        val cuts = AudioDecoder.computeCutPoints(
            silences = emptyList(),
            durationSec = 90.0,
            targetChunkSec = 28.0,
            flexSec = 2.0,
        )
        assertTrue(cuts.isNotEmpty())
        assertTrue(cuts.all { !it.silenceAligned })
        // Hard cuts land exactly on the nominal target spacing.
        assertEquals(28.0, cuts[0].timeSec, 1e-9)
    }

    @Test
    fun `a silence inside the flex window before the target gets snapped to`() {
        // Target for the first cut is at 28s; put a silence's midpoint at
        // 27s (within [28-2, 28] = [26, 28]).
        val cuts = AudioDecoder.computeCutPoints(
            silences = listOf(SilenceRegion(startSec = 26.8, endSec = 27.2)),
            durationSec = 90.0,
            targetChunkSec = 28.0,
            flexSec = 2.0,
        )
        assertTrue(cuts.isNotEmpty())
        assertTrue(cuts[0].silenceAligned)
        assertEquals(27.0, cuts[0].timeSec, 1e-9)
    }

    @Test
    fun `a silence after the nominal target is ignored (asymmetric flex)`() {
        // Silence midpoint at 29s is AFTER the 28s target — flex only
        // looks earlier, never later, so this must NOT be used.
        val cuts = AudioDecoder.computeCutPoints(
            silences = listOf(SilenceRegion(startSec = 28.8, endSec = 29.2)),
            durationSec = 90.0,
            targetChunkSec = 28.0,
            flexSec = 2.0,
        )
        assertTrue(cuts.isNotEmpty())
        assertTrue(!cuts[0].silenceAligned)
        assertEquals(28.0, cuts[0].timeSec, 1e-9)
    }

    @Test
    fun `cut points stay in ascending order across a long file`() {
        val cuts = AudioDecoder.computeCutPoints(
            silences = emptyList(),
            durationSec = 200.0,
            targetChunkSec = 28.0,
            flexSec = 2.0,
        )
        assertTrue(cuts.size >= 5)
        for (i in 1 until cuts.size) {
            assertTrue(cuts[i].timeSec > cuts[i - 1].timeSec)
        }
        assertTrue(cuts.last().timeSec < 200.0)
    }

    @Test
    fun `a silence too far from any target is not used`() {
        // Silence sits at 15s — nowhere near the first target (28s) or
        // the second (56s) within a 2s flex window either side.
        val cuts = AudioDecoder.computeCutPoints(
            silences = listOf(SilenceRegion(startSec = 14.8, endSec = 15.2)),
            durationSec = 90.0,
            targetChunkSec = 28.0,
            flexSec = 2.0,
        )
        assertTrue(cuts.isNotEmpty())
        assertTrue(cuts.none { it.silenceAligned })
    }
}
