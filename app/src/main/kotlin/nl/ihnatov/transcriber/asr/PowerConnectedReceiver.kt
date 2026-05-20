package nl.ihnatov.transcriber.asr

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import nl.ihnatov.transcriber.TranscriberApplication

/**
 * Receives [Intent.ACTION_POWER_CONNECTED] and kicks any tasks parked by
 * [TranscriptionJobManager] that were waiting for AC.
 *
 * Registration: done at runtime in [TranscriberApplication.onCreate] via
 * [ContextCompat.registerReceiver] rather than the manifest. Two reasons
 * we can't use the manifest:
 *   1. Android 8 (API 26) blocked manifest-declared receivers for most
 *      implicit broadcasts, including ACTION_POWER_CONNECTED. The system
 *      simply doesn't deliver them to manifest-registered receivers any
 *      more.
 *   2. Android 13 (API 33) added a hard requirement that runtime-
 *      registered receivers for system broadcasts must pass an explicit
 *      RECEIVER_EXPORTED flag. The 2-arg [Context.registerReceiver]
 *      overload throws SecurityException on targetSdk >= 33. We use
 *      ContextCompat's wrapper to pass the flag in a way that's a no-op
 *      on older platforms and correct on 33+.
 *
 * Keep this dumb: just look up the JobManager and call onPowerConnected.
 * All policy (which task to start, whether anything's running) lives there.
 */
class PowerConnectedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_POWER_CONNECTED) return
        val app = context.applicationContext as? TranscriberApplication ?: return
        Log.i(TAG, "AC connected; nudging job manager")
        app.container.transcriptionJobManager.onPowerConnected()
    }

    companion object {
        private const val TAG = "PowerConnectedRx"

        /**
         * Register the receiver. Must pass RECEIVER_EXPORTED so the
         * system (the broadcaster) can deliver ACTION_POWER_CONNECTED
         * to us — system broadcasts originate outside our app's UID, so
         * the receiver must be exported for the system to reach it.
         *
         * The previous 2-arg `context.registerReceiver(rx, filter)`
         * call silently threw SecurityException under targetSdk 33+,
         * which is why charger-parked tasks never woke up when the
         * phone was plugged in.
         */
        fun register(context: Context): PowerConnectedReceiver {
            val rx = PowerConnectedReceiver()
            val res = runCatching {
                ContextCompat.registerReceiver(
                    context,
                    rx,
                    IntentFilter(Intent.ACTION_POWER_CONNECTED),
                    ContextCompat.RECEIVER_EXPORTED,
                )
            }
            if (res.isFailure) {
                Log.e(TAG, "registerReceiver failed — charger-park trigger will not fire",
                    res.exceptionOrNull())
            } else {
                Log.i(TAG, "registered for ACTION_POWER_CONNECTED (RECEIVER_EXPORTED)")
            }
            return rx
        }
    }
}
