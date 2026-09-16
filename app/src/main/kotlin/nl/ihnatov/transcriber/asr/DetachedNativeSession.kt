package nl.ihnatov.transcriber.asr

import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Owns a native object whose blocking calls cannot be interrupted from
 * Kotlin (sherpa-onnx's `OfflineSpeakerDiarization.process()` sits on the
 * JNI thread for minutes) and guarantees it is never released while such a
 * call is in flight.
 *
 * Why this exists: the previous cancellation strategy released the native
 * object from a sibling coroutine the moment the parent was cancelled, on
 * the theory that the in-flight `process()` would then "error out instead
 * of hanging". It does not error out — ONNX Runtime tears its session
 * mutexes down under the working thread and bionic aborts the whole
 * process (`FORTIFY: pthread_mutex_lock called on a destroyed mutex`).
 * Pressing Stop during a long diarization killed the app.
 *
 * [call] runs the block on its own daemon thread. Cancelling the calling
 * coroutine returns immediately (the caller's Stop latency stays low); the
 * native pass keeps running until it finishes on its own, and a [release]
 * requested in the meantime is deferred until then and performed by that
 * thread. A [release] while idle happens right away.
 */
internal class DetachedNativeSession(
    private val name: String,
    private val releaseNative: () -> Unit,
) {
    private val lock = Any()
    private var busy = false
    private var releaseRequested = false
    private var released = false

    /** True once [releaseNative] has actually run. */
    val isReleased: Boolean get() = synchronized(lock) { released }

    /** True while a [call] block is executing on the worker thread. */
    val isBusy: Boolean get() = synchronized(lock) { busy }

    /**
     * Run [block] on a dedicated thread and suspend until it returns.
     * Cancellation resumes the caller at once; the block still runs to
     * completion in the background. One call at a time per session.
     */
    suspend fun <T> call(block: () -> T): T = suspendCancellableCoroutine { cont ->
        val rejected: Throwable? = synchronized(lock) {
            when {
                released -> IllegalStateException("$name: native object already released")
                busy -> IllegalStateException("$name: a native call is already in flight")
                else -> { busy = true; null }
            }
        }
        if (rejected != null) {
            cont.resumeWithException(rejected)
            return@suspendCancellableCoroutine
        }
        thread(name = "native-$name", isDaemon = true) {
            val result = runCatching(block)
            val doRelease = synchronized(lock) {
                busy = false
                val pending = releaseRequested && !released
                if (pending) released = true
                pending
            }
            if (doRelease) runCatching(releaseNative)
            // Resuming a cancelled CancellableContinuation is a documented
            // no-op, so a caller that gave up while we were busy is unaffected.
            result.fold({ cont.resume(it) }, { cont.resumeWithException(it) })
        }
    }

    /** Release now if idle, otherwise as soon as the in-flight call returns. */
    fun release() {
        val doNow = synchronized(lock) {
            when {
                released -> false
                busy -> { releaseRequested = true; false }
                else -> { released = true; true }
            }
        }
        if (doNow) runCatching(releaseNative)
    }
}
