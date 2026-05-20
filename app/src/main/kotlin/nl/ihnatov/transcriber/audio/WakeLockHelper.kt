package nl.ihnatov.transcriber.audio

import android.content.Context
import android.os.PowerManager
import android.util.Log

/**
 * Small wrapper around `PARTIAL_WAKE_LOCK`. A foreground service keeps the
 * process alive across app backgrounding, but it does **not** prevent the SoC
 * from entering Doze when the screen turns off — long compute jobs (model
 * load, whisper transcription, sherpa-onnx diarization, Gemma 4 inference)
 * would stall mid-flight.
 *
 * Lifecycle: services should acquire on `onStartCommand` (or `onCreate`) and
 * release on `onDestroy`. Acquire is idempotent — re-acquiring while held is a
 * no-op. The timeout is a safety net for service crashes that bypass release.
 *
 * Tag prefix `Transcriber:` lets users identify our locks in `dumpsys power`.
 */
class WakeLockHelper(context: Context, private val tag: String) {

    private val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var lock: PowerManager.WakeLock? = null

    /**
     * Acquire a partial wake lock with [timeoutMs] safety cap (default 3 h).
     *
     * 3 h is the longest plausible job (long recording on flagship + Gemma 4
     * chunked transcription of the same). Without a timeout, a service crash
     * that bypasses [release] could pin the SoC awake forever.
     */
    fun acquire(timeoutMs: Long = 3L * 60L * 60L * 1000L) {
        if (lock?.isHeld == true) return
        val l = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Transcriber:$tag")
        l.setReferenceCounted(false)
        try {
            l.acquire(timeoutMs)
            lock = l
            Log.i(TAG, "acquired $tag (timeout ${timeoutMs / 1000}s)")
        } catch (t: Throwable) {
            Log.w(TAG, "wake lock acquire failed", t)
        }
    }

    fun release() {
        lock?.let {
            if (it.isHeld) {
                runCatching { it.release() }
                Log.i(TAG, "released $tag")
            }
        }
        lock = null
    }

    companion object {
        private const val TAG = "WakeLockHelper"
    }
}
