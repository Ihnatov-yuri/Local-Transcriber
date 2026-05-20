package nl.ihnatov.transcriber.audio

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import nl.ihnatov.transcriber.MainActivity
import nl.ihnatov.transcriber.R
import nl.ihnatov.transcriber.TranscriberApplication

/**
 * Tiny foreground service that keeps the OS happy while [WavRecorder] is
 * capturing audio. The recorder itself lives in the [TranscriberApplication]
 * container so its state survives the service lifecycle.
 */
class RecordingService : Service() {

    private val wakeLock by lazy { WakeLockHelper(this, "Recording") }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Keep CPU awake for the whole recording. Mic FGS allows microphone
        // access; the wake lock keeps the SoC from sleeping when the screen
        // turns off — crucial for hands-free recording.
        wakeLock.acquire()
        val pendingFlags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val tap = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            pendingFlags,
        )
        val notif = NotificationCompat.Builder(this, TranscriberApplication.CHANNEL_RECORDING)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.notif_recording_title))
            .setContentText(getString(R.string.notif_recording_text))
            .setContentIntent(tap)
            .setOngoing(true)
            .setSilent(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
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
        private const val NOTIF_ID = 1001

        fun start(ctx: android.content.Context) {
            val i = Intent(ctx, RecordingService::class.java)
            ctx.startForegroundService(i)
        }

        fun stop(ctx: android.content.Context) {
            ctx.stopService(Intent(ctx, RecordingService::class.java))
        }
    }
}
