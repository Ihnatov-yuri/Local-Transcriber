package nl.ihnatov.transcriber.audio

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
// SupervisorJob + CoroutineScope are used for the local pollScope so a
// crash inside the position-polling loop doesn't cascade up to the rest
// of the app. The Job/launch/cancel triplet manages the per-playback poll.

/**
 * Thin Compose-friendly wrapper around [ExoPlayer] for the Detail screen.
 *
 * Why this isn't `androidx.media3:media3-ui`'s `PlayerView`: PlayerView is
 * a Compose-hostile XML View. We want a play/pause + waveform + click-
 * segment-to-seek surface, all in Compose. ExoPlayer alone gives us the
 * decode pipeline; this wrapper exposes the bits the UI needs as
 * [StateFlow]s and hides the rest.
 *
 * Lifecycle: create once per recording (`prepare` takes a file path),
 * call [release] when the user navigates away. The class is **NOT**
 * thread-safe — touch it from the main thread / a single ViewModel.
 */
class AudioPlayerController(
    context: Context,
) {
    private val player: ExoPlayer = ExoPlayer.Builder(context.applicationContext).build()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private var pollJob: Job? = null
    private val pollScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    init {
        // Listener for play/pause + duration. Position itself is polled
        // because ExoPlayer doesn't emit a per-frame callback — see [startPolling].
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
                if (isPlaying) startPolling() else stopPolling()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    _durationMs.value = player.duration.coerceAtLeast(0L)
                }
                if (playbackState == Player.STATE_ENDED) {
                    _isPlaying.value = false
                    _positionMs.value = _durationMs.value
                    stopPolling()
                }
            }
        })
    }

    /** Load an on-disk audio file. Idempotent; replaces any previous source. */
    fun prepare(path: String) {
        val uri = Uri.fromFile(File(path))
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
    }

    fun playPause() {
        if (player.isPlaying) player.pause() else {
            // If we're at the end, rewind before playing again — otherwise
            // tapping play after EOF does nothing and looks broken.
            if (_durationMs.value > 0 && _positionMs.value >= _durationMs.value - 50L) {
                player.seekTo(0L)
            }
            player.play()
        }
    }

    /** Seek to an absolute timestamp in seconds. Tolerates out-of-range input. */
    fun seekToSeconds(seconds: Double) {
        if (player.duration <= 0L) return
        val target = (seconds * 1000.0).toLong().coerceIn(0L, player.duration)
        player.seekTo(target)
        _positionMs.value = target
    }

    fun release() {
        stopPolling()
        pollScope.cancel()
        player.release()
    }

    /**
     * ExoPlayer doesn't push position updates per frame; we poll at ~30 Hz
     * while playing for a smooth seekbar/waveform cursor. Poll stops the
     * moment playback pauses so we're not burning a coroutine on a
     * background-tab Detail screen.
     */
    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = pollScope.launch {
            while (true) {
                _positionMs.value = player.currentPosition.coerceAtLeast(0L)
                delay(33L)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
        // One final position update so the UI lands on the exact stop point.
        _positionMs.value = player.currentPosition.coerceAtLeast(0L)
    }
}
