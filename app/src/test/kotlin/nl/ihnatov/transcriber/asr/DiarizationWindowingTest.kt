package nl.ihnatov.transcriber.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiarizationWindowingTest {

    private data class Seg(val start: Float, val end: Float)

    @Test
    fun `dropRecapZone keeps only segments reaching past the recap`() {
        val segs = listOf(
            Seg(0f, 10f),     // entirely inside a 45 s recap: already labelled by the previous window
            Seg(30f, 44.9f),  // still inside
            Seg(40f, 50f),    // crosses the recap boundary: keep
            Seg(60f, 70f),    // fresh content: keep
        )
        val kept = DiarizationRunner.dropRecapZone(segs, recapSec = 45.0) { it.end }
        assertEquals(listOf(Seg(40f, 50f), Seg(60f, 70f)), kept)
    }

    @Test
    fun `dropRecapZone is a no-op without a recap`() {
        val segs = listOf(Seg(0f, 1f), Seg(2f, 3f))
        assertEquals(segs, DiarizationRunner.dropRecapZone(segs, recapSec = 0.0) { it.end })
    }

    @Test
    fun `diarization threads scale with cores but stay within 2 to 4`() {
        assertEquals(2, DiarizationRunner.diarizationThreads(cores = 1))
        assertEquals(2, DiarizationRunner.diarizationThreads(cores = 2))
        assertEquals(3, DiarizationRunner.diarizationThreads(cores = 3))
        assertEquals(4, DiarizationRunner.diarizationThreads(cores = 8))
    }

    @Test
    fun `heartbeat advances inside a window and never reports it complete`() {
        val hb = WindowHeartbeat(durationSec = 1000.0)
        // Second window starting at 300 s, 345 s long; the previous one took 60 s of wall time.
        hb.beginWindow(startSec = 300.0, lenSec = 345.0, previousWallMs = 60_000L, nowMs = 0L)
        assertEquals(0.30f, hb.fraction(0L), 0.001f)
        val halfway = hb.fraction(30_000L)
        assertTrue("halfway through the expected wall time the bar is inside the window", halfway > 0.40f && halfway < 0.50f)
        val overdue = hb.fraction(600_000L)
        assertTrue("an overdue window caps below its own end", overdue < (300.0 + 345.0) / 1000.0)
    }

    @Test
    fun `heartbeat assumes realtime before any window has finished`() {
        val hb = WindowHeartbeat(durationSec = 600.0)
        hb.beginWindow(startSec = 0.0, lenSec = 300.0, previousWallMs = -1L, nowMs = 0L)
        // 150 s of wall time into a 300 s window at an assumed 1x → about a quarter of the file.
        assertEquals(0.25f, hb.fraction(150_000L), 0.01f)
    }

    @Test
    fun `heartbeat is zero for an unknown duration`() {
        val hb = WindowHeartbeat(durationSec = 0.0)
        hb.beginWindow(0.0, 300.0, -1L, 0L)
        assertEquals(0f, hb.fraction(10_000L), 0f)
    }
}
