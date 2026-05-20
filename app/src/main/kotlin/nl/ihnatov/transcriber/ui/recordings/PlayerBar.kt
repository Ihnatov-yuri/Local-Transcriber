package nl.ihnatov.transcriber.ui.recordings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.ihnatov.transcriber.audio.AudioPlayerController

/**
 * Player bar pinned above the transcript in the Detail screen. Renders:
 *   - play/pause button
 *   - waveform (amplitudes + playhead) — tap/drag to seek
 *   - mm:ss / mm:ss timer
 *
 * Compact (64dp tall) so the transcript still gets the lion's share of
 * vertical space. Waveform is null-tolerant — until [WaveformLoader]
 * finishes extracting amplitudes, we render a plain progress track so
 * playback is usable from the first tap.
 */
@Composable
fun PlayerBar(
    controller: AudioPlayerController,
    /** Pre-computed peak amplitudes in [0, 1]. null while loading. */
    waveform: FloatArray?,
    modifier: Modifier = Modifier,
) {
    val isPlaying by controller.isPlaying.collectAsStateWithLifecycle()
    val positionMs by controller.positionMs.collectAsStateWithLifecycle()
    val durationMs by controller.durationMs.collectAsStateWithLifecycle()

    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = { controller.playPause() }) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                )
            }

            // Waveform + playhead. The Box owns the gesture so tap/drag
            // anywhere along the strip seeks. We can't use a Slider here
            // because we want the waveform bars as the affordance — a
            // Slider's thumb covers them and the M3 thumb is too tall
            // for our 36dp bar height.
            val totalSec = durationMs.coerceAtLeast(1L) / 1000.0
            val progress = if (durationMs <= 0L) 0f else
                (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp)
                    .pointerInput(durationMs) {
                        // Tap seek
                        detectTapGestures { offset ->
                            val frac = (offset.x / size.width).coerceIn(0f, 1f)
                            controller.seekToSeconds(frac.toDouble() * totalSec)
                        }
                    }
                    .pointerInput(durationMs) {
                        // Drag seek (continuous scrub). detectDragGestures
                        // gives us start + delta + end so we can stop
                        // tracking the moment the user lifts a finger.
                        detectDragGestures { change, _ ->
                            val frac = (change.position.x / size.width).coerceIn(0f, 1f)
                            controller.seekToSeconds(frac.toDouble() * totalSec)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (waveform != null && waveform.isNotEmpty()) {
                    WaveformView(
                        amps = waveform,
                        progress = progress,
                        idleColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                        playedColor = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    // Fallback: a thin track + playhead while we wait for
                    // WaveformLoader. Playback still works.
                    PlainProgressTrack(
                        progress = progress,
                        idleColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                        playedColor = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            Text(
                "${formatMmSs(positionMs)} / ${formatMmSs(durationMs)}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(4.dp))
        }
    }
}

@Composable
private fun WaveformView(
    amps: FloatArray,
    progress: Float,
    idleColor: Color,
    playedColor: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        if (amps.isEmpty()) return@Canvas
        val barCount = amps.size
        // Leave a 1-px-equivalent gap between bars so dense waveforms
        // still read as discrete columns instead of a smear.
        val totalWidth = size.width
        val barAdvance = totalWidth / barCount
        val barWidth = (barAdvance * 0.7f).coerceAtLeast(1f)
        val midY = size.height / 2f
        val maxBarH = size.height * 0.95f
        val progressX = totalWidth * progress
        for (i in 0 until barCount) {
            val x = i * barAdvance + barAdvance / 2f
            // Min visible height keeps the silence stretches readable as
            // a thin line — otherwise low-amp regions vanish.
            val h = (amps[i] * maxBarH).coerceAtLeast(2f)
            val color = if (x <= progressX) playedColor else idleColor
            drawLine(
                color = color,
                start = Offset(x, midY - h / 2f),
                end = Offset(x, midY + h / 2f),
                strokeWidth = barWidth,
            )
        }
    }
}

@Composable
private fun PlainProgressTrack(
    progress: Float,
    idleColor: Color,
    playedColor: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val midY = size.height / 2f
        // Idle base
        drawLine(
            color = idleColor,
            start = Offset(0f, midY),
            end = Offset(size.width, midY),
            strokeWidth = 4f,
        )
        // Played fill
        drawLine(
            color = playedColor,
            start = Offset(0f, midY),
            end = Offset(size.width * progress, midY),
            strokeWidth = 4f,
        )
    }
}

private fun formatMmSs(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val m = totalSec / 60L
    val s = totalSec % 60L
    return "%d:%02d".format(m, s)
}
