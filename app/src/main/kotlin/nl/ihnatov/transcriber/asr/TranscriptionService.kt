package nl.ihnatov.transcriber.asr

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import nl.ihnatov.transcriber.MainActivity
import nl.ihnatov.transcriber.R
import nl.ihnatov.transcriber.TranscriberApplication
import nl.ihnatov.transcriber.audio.WakeLockHelper

/**
 * Foreground service that hosts a transcription pass so the OS doesn't
 * kill us while a large-v3 run is in progress. The actual ASR work runs
 * via [TranscriptionRunner] which is started from a ViewModel; this
 * service exists for its notification + lifecycle.
 */
class TranscriptionService : Service() {

    private val wakeLock by lazy { WakeLockHelper(this, "Transcription") }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Long jobs (model load + Whisper / Gemma 4 inference + diarization) can
        // run for many seconds to minutes. A foreground service alone doesn't
        // prevent the SoC from entering Doze when the screen turns off; the
        // wake lock here does.
        wakeLock.acquire()
        val pendingFlags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val tap = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            pendingFlags,
        )
        val notif = NotificationCompat.Builder(this, TranscriberApplication.CHANNEL_TRANSCRIPTION)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.notif_transcribing_title))
            .setContentIntent(tap)
            .setOngoing(true)
            .setSilent(true)
            .build()

        // Pick the most specific FGS type the OS supports. mediaProcessing
        // was added in API 35 (Android 15) and is the right semantic fit for
        // an on-device transcription job; dataSync covers older devices.
        val fgsType = if (Build.VERSION.SDK_INT >= 35) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, fgsType)
        } else {
            startForeground(NOTIF_ID, notif)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        wakeLock.release()
        super.onDestroy()
    }

    companion object {
        private const val NOTIF_ID = 1002

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, TranscriptionService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, TranscriptionService::class.java))
        }
    }
}
