package nl.ihnatov.transcriber.asr

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetachedNativeSessionTest {

    @Test
    fun `call returns the block's result and release is immediate when idle`() = runBlocking {
        val releases = AtomicInteger()
        val session = DetachedNativeSession("t") { releases.incrementAndGet() }
        assertEquals(42, session.call { 42 })
        assertFalse(session.isReleased)
        session.release()
        assertTrue(session.isReleased)
        assertEquals(1, releases.get())
        session.release()
        assertEquals("release is idempotent", 1, releases.get())
    }

    @Test
    fun `release during an in-flight call is deferred until the call finishes`() = runBlocking {
        val releases = AtomicInteger()
        val blockStarted = CountDownLatch(1)
        val letBlockFinish = CountDownLatch(1)
        val session = DetachedNativeSession("t") { releases.incrementAndGet() }

        val job = async(Dispatchers.IO) {
            session.call {
                blockStarted.countDown()
                letBlockFinish.await(5, TimeUnit.SECONDS)
                "done"
            }
        }
        assertTrue(blockStarted.await(5, TimeUnit.SECONDS))
        assertTrue(session.isBusy)

        // The crash this guards against: releasing while native code is
        // still inside the call. Must not happen yet.
        session.release()
        assertEquals(0, releases.get())
        assertFalse(session.isReleased)

        letBlockFinish.countDown()
        assertEquals("done", job.await())
        // The worker thread performs the deferred release right after the
        // block returns; give it a moment.
        withTimeout(5_000) { while (!session.isReleased) delay(10) }
        assertEquals(1, releases.get())
    }

    @Test
    fun `cancelling the caller returns promptly and still releases only after the block ends`() = runBlocking {
        val releases = AtomicInteger()
        val letBlockFinish = CountDownLatch(1)
        val session = DetachedNativeSession("t") { releases.incrementAndGet() }

        val job = async(Dispatchers.IO) {
            try {
                session.call {
                    letBlockFinish.await(5, TimeUnit.SECONDS)
                    1
                }
            } finally {
                session.release()
            }
        }
        withTimeout(5_000) { while (!session.isBusy) delay(10) }
        job.cancel()
        // Stop latency: the caller is done long before the native block is.
        val cancelled = withTimeout(1_000) {
            try { job.await(); false } catch (e: CancellationException) { true }
        }
        assertTrue(cancelled)
        assertEquals("native still running, nothing released", 0, releases.get())

        letBlockFinish.countDown()
        withTimeout(5_000) { while (!session.isReleased) delay(10) }
        assertEquals(1, releases.get())
    }

    @Test
    fun `a call after release fails instead of touching a freed object`() = runBlocking {
        val session = DetachedNativeSession("t") { }
        session.release()
        val failed = try { session.call { 1 }; false } catch (e: IllegalStateException) { true }
        assertTrue(failed)
    }
}
