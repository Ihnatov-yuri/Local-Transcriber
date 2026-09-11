package nl.ihnatov.transcriber.ui.record

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import nl.ihnatov.transcriber.asr.AsrBackendKind
import nl.ihnatov.transcriber.audio.WavRecorder
import nl.ihnatov.transcriber.data.AppContainer
import nl.ihnatov.transcriber.ui.KeepScreenOn
import nl.ihnatov.transcriber.ui.components.BigNumber
import nl.ihnatov.transcriber.ui.components.BrandStrip
import nl.ihnatov.transcriber.ui.components.Hairline
import nl.ihnatov.transcriber.ui.components.InkRule
import nl.ihnatov.transcriber.ui.components.InverseFooter
import nl.ihnatov.transcriber.ui.components.Mono
import nl.ihnatov.transcriber.ui.components.PulseDot
import nl.ihnatov.transcriber.ui.components.SectionIndex
import nl.ihnatov.transcriber.ui.components.Sheet
import nl.ihnatov.transcriber.ui.theme.Accent
import nl.ihnatov.transcriber.ui.theme.Fraunces
import nl.ihnatov.transcriber.ui.theme.Spacing

/**
 * Record screen — design `02A · CAPTURE` (waveform-led variant) from
 * `screen-record.jsx`.
 *
 * Visual layer only; the underlying [RecordViewModel] is unchanged. The
 * old expanded-options card is replaced with the "options as flowing tag
 * strip" pattern from the spec — mono-caps key/value pairs, active values
 * get a 1.5-dp Accent underline. Tap any value to cycle/edit through the
 * appropriate dialog.
 */
@Composable
fun RecordScreen(
    container: AppContainer,
    onRecordingFinished: (Long, Boolean) -> Unit,
    onOpenLibrary: (() -> Unit)? = null,
) {
    val vm: RecordViewModel = viewModel(factory = RecordViewModel.factory(container))
    val ui by vm.ui.collectAsStateWithLifecycle()

    val micPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted -> if (granted) vm.start() },
    )

    LaunchedEffect(ui.finishedRecordingId) {
        ui.finishedRecordingId?.let { id ->
            val autoRun = ui.autoTranscribe
            vm.acknowledgeFinished()
            onRecordingFinished(id, autoRun)
        }
    }

    val isActive = ui.state is WavRecorder.State.Recording || ui.state is WavRecorder.State.Paused
    KeepScreenOn(enabled = isActive)

    // Rolling level history (~64 samples × 80 ms = 5 s window) for the
    // waveform. Sampling polls the VM's live level StateFlow; we don't
    // store this on the VM because it's pure UI state.
    val levels = remember { mutableStateOf(FloatArray(64) { 0f }) }
    LaunchedEffect(isActive) {
        if (!isActive) return@LaunchedEffect
        while (true) {
            val current = ui.level
            val next = FloatArray(64)
            System.arraycopy(levels.value, 1, next, 0, 63)
            next[63] = current
            levels.value = next
            delay(80L)
        }
    }

    var langDialogOpen by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Sheet(modifier = Modifier.fillMaxSize()) {
            BrandStrip(
                right = {
                    if (isActive) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PulseDot(size = 6.dp)
                            Spacer(Modifier.width(2.dp))
                            Mono(
                                if (ui.state is WavRecorder.State.Paused) "PAUSED"
                                else "RECORDING",
                                color = if (ui.state is WavRecorder.State.Paused)
                                    MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
                                else Accent,
                            )
                        }
                    } else if (onOpenLibrary != null) {
                        Mono(
                            "LIBRARY →",
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.62f),
                            modifier = Modifier
                                .clickable(onClick = onOpenLibrary)
                                .padding(6.dp),
                        )
                    }
                },
            )
            Spacer(Modifier.height(14.dp))
            InkRule()
            Spacer(Modifier.height(18.dp))
            SectionIndex(
                n = 2,
                label = "capture",
                summary = "On-device. ${engineLabel(ui.liveEngine)}. ${languagesSummary(ui.liveLanguages)} candidates.",
            )
            Spacer(Modifier.height(18.dp))

            // Timer + level strip — top + bottom hairlines.
            Hairline()
            TimerLevelStrip(elapsedMs = ui.elapsedMs, level = ui.level)
            Hairline()
            Spacer(Modifier.height(12.dp))

            // Waveform.
            WaveformBars(
                levels = levels.value,
                active = isActive,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp),
            )
            Spacer(Modifier.height(6.dp))
            WaveformAxis(elapsedMs = ui.elapsedMs)
            Spacer(Modifier.height(14.dp))
            Hairline()
            Spacer(Modifier.height(14.dp))

            // Last heard.
            LastHeardBlock(
                liveStatus = ui.liveStatus,
                lastLine = ui.liveLines.lastOrNull(),
            )
            Spacer(Modifier.height(14.dp))
            Hairline()
            Spacer(Modifier.height(14.dp))

            // Options as flowing tag strip (no chips).
            OptionsTagStrip(
                ui = ui,
                onToggleAutoTranscribe = { vm.setAutoTranscribe(!ui.autoTranscribe) },
                onToggleLive = { vm.setLiveEnabled(!ui.liveEnabled) },
                onCycleEngine = {
                    vm.setLiveEngine(
                        if (ui.liveEngine == AsrBackendKind.Gemma4) AsrBackendKind.WhisperCpp
                        else AsrBackendKind.Gemma4,
                    )
                },
                onPickLanguages = { langDialogOpen = true },
            )
            Spacer(Modifier.weight(1f))
        }

        // Inverse footer pinned to the bottom.
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            RecordFooter(
                state = ui.state,
                hasMicPermission = ui.hasMicPermission,
                autoTranscribe = ui.autoTranscribe,
                onStart = {
                    if (ui.hasMicPermission) vm.start()
                    else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                },
                onPauseResume = { vm.pauseResume() },
                onStop = { vm.stop() },
            )
        }
    }

    if (langDialogOpen) {
        LiveLanguagesDialog(
            initial = ui.liveLanguages,
            onDismiss = { langDialogOpen = false },
            onConfirm = { picks ->
                vm.setLiveLanguages(picks)
                langDialogOpen = false
            },
        )
    }
}

// ─── Sub-composables ─────────────────────────────────────────────────

@Composable
private fun TimerLevelStrip(elapsedMs: Long, level: Float) {
    val ink = MaterialTheme.colorScheme.onBackground
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BigNumber(value = formatElapsed(elapsedMs), size = 66.sp)
        Spacer(Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            val db = if (level <= 0.0001f) "-∞ DB"
            else "%d DB".format(-(20.0 * kotlin.math.log10(level.toDouble())).toInt().coerceAtMost(99))
            Text(
                db,
                color = ink,
                style = MaterialTheme.typography.displaySmall.copy(letterSpacing = 0.6.sp),
            )
            Mono(
                "LEVEL · ${if (level > 0.6f) "HOT" else "CLEAN"}",
                color = ink.copy(alpha = 0.55f),
                style = MaterialTheme.typography.labelSmall,
            )
            Spacer(Modifier.height(2.dp))
            Mono(
                "16 KHZ · MONO",
                color = ink.copy(alpha = 0.40f),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun WaveformBars(
    levels: FloatArray,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val ink = MaterialTheme.colorScheme.onBackground
    val past = ink.copy(alpha = 0.85f)
    val future = ink.copy(alpha = 0.20f)
    Canvas(modifier = modifier) {
        if (levels.isEmpty()) return@Canvas
        val n = levels.size
        val gap = 2.dp.toPx()
        val barW = (size.width - gap * (n - 1)) / n
        val midY = size.height / 2f
        val maxH = size.height * 0.92f
        // Position of "now" cursor — for the rolling buffer it's the
        // rightmost bar. Bars to the left are past, the rightmost is
        // the current sample (Accent).
        for (i in 0 until n) {
            val v = levels[i].coerceIn(0f, 1f)
            val h = (v * maxH).coerceAtLeast(2f)
            val x = i * (barW + gap) + barW / 2f
            val color = when {
                !active -> future
                i == n - 1 -> Accent
                else -> past
            }
            drawLine(
                color = color,
                start = Offset(x, midY - h / 2f),
                end = Offset(x, midY + h / 2f),
                strokeWidth = barW,
            )
        }
    }
}

@Composable
private fun WaveformAxis(elapsedMs: Long) {
    val ink = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Mono("00:00", color = ink, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.weight(1f))
        Mono(
            "${formatElapsed(elapsedMs)} ▸ NOW",
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.labelSmall,
        )
        Spacer(Modifier.weight(1f))
        Mono(
            formatElapsed(elapsedMs + 60_000L),
            color = ink,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun LastHeardBlock(
    liveStatus: RecordViewModel.LiveStatus,
    lastLine: RecordViewModel.LiveLine?,
) {
    val ink = MaterialTheme.colorScheme.onBackground
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val statusLabel = when (liveStatus) {
            RecordViewModel.LiveStatus.Idle -> "LAST HEARD"
            RecordViewModel.LiveStatus.Loading -> "LAST HEARD · LOADING MODEL"
            RecordViewModel.LiveStatus.Running -> "LAST HEARD · LIVE"
            RecordViewModel.LiveStatus.ModelMissing -> "LIVE NEEDS GGML-TINY.BIN"
            is RecordViewModel.LiveStatus.Failed -> "LIVE FAILED: ${liveStatus.reason}".take(64)
        }
        Mono(statusLabel, color = ink.copy(alpha = 0.55f))
        val text = lastLine?.text ?: "Tap RECORD below. Live transcript will appear as you speak."
        // Fraunces italic statement — the one-per-screen "voice" moment.
        Text(
            text = text,
            color = ink,
            style = TextStyle(
                fontFamily = Fraunces,
                fontStyle = FontStyle.Italic,
                fontSize = 22.sp,
                lineHeight = 28.sp,
                letterSpacing = (-0.3).sp,
            ),
        )
        if (lastLine != null) {
            Mono(
                "${formatElapsed((lastLine.startSec * 1000).toLong())}  ·  LIVE",
                color = ink.copy(alpha = 0.40f),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OptionsTagStrip(
    ui: RecordViewModel.UiState,
    onToggleAutoTranscribe: () -> Unit,
    onToggleLive: () -> Unit,
    onCycleEngine: () -> Unit,
    onPickLanguages: () -> Unit,
) {
    val ink = MaterialTheme.colorScheme.onBackground
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Mono("OPTIONS", color = ink.copy(alpha = 0.55f))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TagPair(
                label = "AUTO-RUN",
                value = if (ui.autoTranscribe) "ON" else "OFF",
                active = ui.autoTranscribe,
                onClick = onToggleAutoTranscribe,
            )
            TagPair(
                label = "LIVE",
                value = if (ui.liveEnabled) "ON" else "OFF",
                active = ui.liveEnabled,
                onClick = onToggleLive,
            )
            if (ui.liveEnabled) {
                TagPair(
                    label = "ENGINE",
                    value = if (ui.liveEngine == AsrBackendKind.Gemma4) "GEMMA 4 E2B" else "WHISPER TINY",
                    active = true,
                    onClick = onCycleEngine,
                )
                TagPair(
                    label = "LANG",
                    value = languagesSummary(ui.liveLanguages),
                    active = ui.liveLanguages.isNotEmpty(),
                    onClick = onPickLanguages,
                )
            }
        }
    }
}

@Composable
private fun TagPair(label: String, value: String, active: Boolean, onClick: () -> Unit) {
    val ink = MaterialTheme.colorScheme.onBackground
    Row(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Mono(label, color = ink.copy(alpha = 0.55f))
        Spacer(Modifier.width(4.dp))
        Column(horizontalAlignment = Alignment.Start) {
            Mono(value, color = ink)
            // Underline the active value with a 1.5-dp accent stroke.
            // Inactive values: zero-height transparent placeholder to
            // keep the row height stable.
            Box(
                Modifier
                    .height(if (active) 1.5.dp else 1.5.dp)
                    .padding(top = 1.dp)
                    .background(if (active) Accent else Color.Transparent)
                    .width(56.dp),
            )
        }
    }
}

@Composable
private fun RecordFooter(
    state: WavRecorder.State,
    hasMicPermission: Boolean,
    autoTranscribe: Boolean,
    onStart: () -> Unit,
    onPauseResume: () -> Unit,
    onStop: () -> Unit,
) {
    val paper = MaterialTheme.colorScheme.background
    val ink = MaterialTheme.colorScheme.onBackground
    when (state) {
        is WavRecorder.State.Idle,
        is WavRecorder.State.Saved,
        is WavRecorder.State.Failed -> {
            InverseFooter(
                onClick = onStart,
                left = { PulseDot(size = 8.dp) },
                right = {
                    Box(
                        Modifier
                            .size(28.dp)
                            .background(Accent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.size(12.dp).background(paper))
                    }
                },
                body = {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            if (hasMicPermission) "RECORD" else "GRANT MIC · RECORD",
                            style = MaterialTheme.typography.displaySmall,
                            color = paper,
                        )
                        Mono(
                            if (autoTranscribe) "AUTO-TRANSCRIBE ON STOP" else "MANUAL RUN",
                            color = paper.copy(alpha = 0.55f),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
            )
        }
        is WavRecorder.State.Recording, is WavRecorder.State.Paused -> {
            val paused = state is WavRecorder.State.Paused
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(ink),
            ) {
                // Pause/Resume square — hairline border on paper bg.
                Box(
                    Modifier
                        .background(paper)
                        .clickable(onClick = onPauseResume)
                        .padding(horizontal = 18.dp, vertical = 18.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Mono(
                        if (paused) "RESUME" else "PAUSE",
                        color = ink,
                    )
                }
                // Center label.
                Box(
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column {
                        Text(
                            "STOP & TRANSCRIBE",
                            style = MaterialTheme.typography.displaySmall,
                            color = paper,
                        )
                        Mono(
                            if (autoTranscribe) "AUTO-RUN · SUMMARY PRESET" else "OPEN DETAIL TO RUN",
                            color = paper.copy(alpha = 0.55f),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                // Accent stop square.
                Box(
                    Modifier
                        .background(Accent)
                        .clickable(onClick = onStop)
                        .padding(horizontal = 22.dp, vertical = 18.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(12.dp).background(paper))
                }
            }
        }
    }
}

// ─── Helpers ─────────────────────────────────────────────────────────

private fun engineLabel(kind: AsrBackendKind): String = when (kind) {
    AsrBackendKind.Gemma4 -> "Gemma 4 E2B"
    AsrBackendKind.WhisperCpp -> "Whisper tiny"
    // Not offered as a live/Record-screen engine choice yet — see LiveTranscriber.pickModel.
    AsrBackendKind.Parakeet -> "Parakeet"
    AsrBackendKind.Omnilingual -> "Omnilingual"
    AsrBackendKind.NemotronStream -> "Nemotron 3.5"
}

private fun languagesSummary(picks: Set<String>): String = when {
    picks.isEmpty() -> "AUTO"
    picks.size == 1 -> picks.first().uppercase()
    else -> picks.sorted().joinToString(" + ") { it.uppercase() }
}

private fun formatElapsed(ms: Long): String {
    val totalSec = ms.coerceAtLeast(0L) / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

@Composable
private fun LiveLanguagesDialog(
    initial: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    var picks by remember(initial) { mutableStateOf(initial) }
    val options = listOf(
        "ar" to "Arabic",
        "uk" to "Ukrainian",
        "en" to "English",
        "nl" to "Dutch",
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.background,
        title = { Mono("LANGUAGES", color = MaterialTheme.colorScheme.onBackground) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Leave all unchecked for full auto-detect. Pick one to force, " +
                        "pick several to constrain.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.62f),
                )
                options.forEach { (code, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = picks.contains(code),
                            onCheckedChange = { on ->
                                picks = if (on) picks + code else picks - code
                            },
                        )
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(picks) }) { Mono("SAVE") } },
        dismissButton = { TextButton(onClick = onDismiss) { Mono("CANCEL") } },
    )
}
