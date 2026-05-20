package nl.ihnatov.transcriber.audio

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Helpers around the Android "ignore battery optimizations" API.
 *
 * On vanilla Android, a foreground service + partial wake lock is enough to
 * keep a long-running compute task alive. On Samsung devices this isn't true —
 * Samsung's Game Optimizing Service, App Standby Buckets, and Device Care
 * routinely suspend or kill background apps regardless. Requesting a
 * battery-optimization exemption lifts most of those restrictions.
 *
 * The request opens a system dialog; the user has to tap "Allow". We never
 * silently grant ourselves the exemption — the OS forbids it.
 */
object BatteryOptimization {

    fun isIgnoring(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Build an Intent that opens the system dialog asking the user to grant
     * the exemption. The caller (Activity) starts it. Returns null if the
     * device doesn't expose the action (very old AOSP forks).
     */
    fun requestIntent(context: Context): Intent? {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        val resolves = context.packageManager.resolveActivity(intent, 0) != null
        return if (resolves) intent else fallbackIntent(context)
    }

    /**
     * Some OEM skins hide the per-app screen behind their own UI. As a fallback
     * we send the user to the generic Settings screen for our app, where the
     * battery section is one tap away.
     */
    private fun fallbackIntent(context: Context): Intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
    ).apply {
        data = Uri.parse("package:${context.packageName}")
    }
}
