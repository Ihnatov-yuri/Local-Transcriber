package nl.ihnatov.transcriber.asr

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForegroundWithFallback(notif)
        } else {
            startForeground(NOTIF_ID, notif)
        }
        return START_STICKY
    }

    /**
     * Prefer specialUse (API 34+): unlike mediaProcessing/dataSync it has
     * no 6-hour-per-day budget, which matters for long transcription runs.
     * If the platform ever refuses it we fall back to the typed
     * alternative rather than letting the service crash outright.
     */
    private fun startForegroundWithFallback(notif: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                return
            } catch (e: Exception) {
                Log.w(TAG, "specialUse FGS start refused, falling back", e)
            }
        }
        val fallbackType = if (Build.VERSION.SDK_INT >= 35) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        startForeground(NOTIF_ID, notif, fallbackType)
    }

    /**
     * Only reachable when we ended up on the mediaProcessing/dataSync
     * fallback (specialUse has no enforced timeout) — the OS gives us a
     * short grace window here before it kills the process outright, so we
     * use it to save the in-flight job rather than silently losing it.
     */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "FGS timeout (type=$fgsType) — checkpointing before the OS kills us")
        (application as? TranscriberApplication)?.container?.transcriptionJobManager?.checkpointRunning()
        stopSelf(startId)
    }

    override fun onDestroy() {
        wakeLock.release()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TranscriptionService"
        private const val NOTIF_ID = 1002

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, TranscriptionService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, TranscriptionService::class.java))
        }
    }
}
