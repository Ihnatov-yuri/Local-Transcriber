package nl.ihnatov.transcriber.ui

import android.app.Activity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * Holds `FLAG_KEEP_SCREEN_ON` on the host Activity's window while [enabled] is
 * true. Removes the flag in the dispose block so the screen can sleep again
 * after the calling composable leaves composition.
 *
 * Why this exists: even with a foreground service + partial wake lock + a
 * battery-optimization exemption, Samsung devices sometimes still suspend
 * long compute jobs when the screen turns off. Keeping the screen on while a
 * job is in flight is the simplest reliable workaround — the battery cost
 * for a transcription that takes a couple of minutes is negligible compared
 * to the cost of the job silently dying.
 *
 * Caller is the source of truth: pass `enabled = uiState.running` and the
 * flag goes on/off in lockstep with the job's lifecycle.
 */
@Composable
fun KeepScreenOn(enabled: Boolean) {
    val context = LocalContext.current
    DisposableEffect(enabled) {
        val activity = context.findActivity()
        if (enabled && activity != null) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

/** Walks the ContextWrapper chain to find the Activity. */
private fun android.content.Context.findActivity(): Activity? {
    var ctx: android.content.Context? = this
    while (ctx is android.content.ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
