package nl.ihnatov.transcriber

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import nl.ihnatov.transcriber.asr.PowerConnectedReceiver
import nl.ihnatov.transcriber.asr.refreshLearnedTerms
import nl.ihnatov.transcriber.data.AppContainer

class TranscriberApplication : Application() {

    // Manual DI container. Keeps the MVP free of Hilt/Koin while still
    // letting screens depend on a single source of repositories/services.
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        registerNotificationChannels()
        // Re-harvest "learned names" from the whole library on every cold
        // start — cheap (text only, off the main thread) and keeps
        // Settings → Learned current without the user having to remember
        // to tap "Rescan". Mirrors the Mac app's launch-time bootstrap().
        refreshLearnedTerms(
            scope = container.appScope,
            repository = container.repository,
            promptStore = container.promptStore,
            store = container.learnedNamesStore,
        )
        // Process-lifetime receiver. Wakes up parked "run on charger"
        // tasks when AC arrives. Parked tasks themselves are persisted in
        // Room (pending_tasks table) so they survive process death — the
        // receiver is what makes them START on plug-in. If the process
        // wasn't running at plug-in time, the next cold start of the app
        // re-loads the queue via JobManager.init {} and drains anything
        // that's eligible given the current charger state.
        PowerConnectedReceiver.register(this)
    }

    private fun registerNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RECORDING,
                getString(R.string.notif_channel_recording),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notif_channel_recording_desc)
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_TRANSCRIPTION,
                getString(R.string.notif_channel_transcription),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notif_channel_transcription_desc)
                setShowBadge(false)
            }
        )
    }

    companion object {
        const val CHANNEL_RECORDING = "recording"
        const val CHANNEL_TRANSCRIPTION = "transcription"
    }
}
