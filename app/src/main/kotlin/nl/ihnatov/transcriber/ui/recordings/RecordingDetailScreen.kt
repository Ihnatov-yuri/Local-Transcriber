package nl.ihnatov.transcriber.ui.recordings

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import nl.ihnatov.transcriber.asr.AsrBackendKind
import nl.ihnatov.transcriber.asr.TextDestutter
import nl.ihnatov.transcriber.asr.TranscriptExporter
import nl.ihnatov.transcriber.asr.defaultEngineFor
import nl.ihnatov.transcriber.audio.AudioPlayerController
import nl.ihnatov.transcriber.audio.WaveformLoader
import nl.ihnatov.transcriber.data.AppContainer
import nl.ihnatov.transcriber.data.Folder
import nl.ihnatov.transcriber.data.Tag
import nl.ihnatov.transcriber.ui.KeepScreenOn
import nl.ihnatov.transcriber.ui.MarkdownText
import nl.ihnatov.transcriber.ui.components.Hairline
import nl.ihnatov.transcriber.ui.components.HairlineSoft
import nl.ihnatov.transcriber.ui.components.InkRule
import nl.ihnatov.transcriber.ui.components.Mono
import nl.ihnatov.transcriber.ui.components.Sheet
import nl.ihnatov.transcriber.ui.theme.Accent
import nl.ihnatov.transcriber.ui.theme.Fraunces
import nl.ihnatov.transcriber.ui.theme.IbmPlexMono
import nl.ihnatov.transcriber.ui.theme.Inter
import nl.ihnatov.transcriber.ui.theme.SairaCondensed
import nl.ihnatov.transcriber.ui.theme.Spacing

/**
 * Recording-detail screen — design `03A · SESSION` (segment-ledger
 * variant) from `screen-detail.jsx`.
 *
 * Outer chrome is editorial paper-and-ink: a back link + meta row, an
 * ink rule, a section index, a Fraunces italic title, a mono-caps
 * metadata strip, a manual tab strip with an Accent underline, then
 * the transcript ledger. The Transcribe RUN controls collapse behind a
 * mono-caps "RUN ▾" expander so they don't fight the title for weight.
 * Post-processing presets are kept as a 2×2 inverse-block grid — the
 * only chip surface in the app, by design exception (§5.4).
 *
 * The underlying [RecordingDetailViewModel] is unchanged.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RecordingDetailScreen(
    container: AppContainer,
    recordingId: Long,
    autoRun: Boolean = false,
    onBack: () -> Unit,
) {
    val vm: RecordingDetailViewModel = viewModel(
        factory = RecordingDetailViewModel.factory(container, recordingId)
    )
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Transcribe-form state (carried over from the previous build).
    val lastLangs by container.uiPrefs.lastLanguages.collectAsStateWithLifecycle()
    var selectedLangs by remember { mutableStateOf(lastLangs) }
    var translateTo by remember { mutableStateOf<String?>(null) }
    // "Auto" engine policy: Parakeet by default, Omnilingual once Arabic is
    // selected — see AsrBackend.defaultEngineFor. Tracks the language pick
    // automatically until the user taps ENGINE to override it explicitly;
    // after that we stop overwriting their choice.
    // If the policy's pick has no model installed yet (fresh install with
    // only Gemma/Whisper, before Parakeet was ever downloaded), fall back
    // to an engine that DOES have one — otherwise auto-run after recording
    // silently never fires and RUN just says "no model installed".
    fun autoEngineFor(langs: Set<String>): AsrBackendKind {
        val preferred = defaultEngineFor(langs)
        val fallbacks = listOf(
            preferred, AsrBackendKind.Gemma4, AsrBackendKind.WhisperCpp,
            AsrBackendKind.Parakeet, AsrBackendKind.Omnilingual,
        )
        return fallbacks.firstOrNull { container.asrFactory.listModels(it).isNotEmpty() } ?: preferred
    }
    var backend by remember { mutableStateOf(autoEngineFor(lastLangs)) }
    var backendManuallySet by remember { mutableStateOf(false) }
    LaunchedEffect(selectedLangs) {
        if (!backendManuallySet) backend = autoEngineFor(selectedLangs)
    }
    // Super mode (Phase 3): run two engines and vote-merge instead of just
    // `backend`. Pair is a small curated list, not two free pickers — most
    // combinations aren't meaningful (e.g. Parakeet+Parakeet), and these
    // two mirror the plan's own default/Arabic split.
    var superMode by remember { mutableStateOf(false) }
    var superPairIdx by remember { mutableIntStateOf(0) }
    val superPair = SUPER_PAIR_PRESETS[superPairIdx]
    var maxQuality by remember { mutableStateOf(false) }

    // File-transcription engine cycle. NemotronStream isn't offered here —
    // it's a streaming-only engine driven by LiveTranscriber on the Record
    // screen, not something that transcribes an already-recorded file.
    val cycleBackend = {
        backendManuallySet = true
        val order = listOf(
            AsrBackendKind.Parakeet, AsrBackendKind.Omnilingual,
            AsrBackendKind.Gemma4, AsrBackendKind.WhisperCpp,
        )
        val idx = order.indexOf(backend).let { if (it < 0) 0 else it }
        backend = order[(idx + 1) % order.size]
    }
    var diarize by remember { mutableStateOf(false) }
    val diarReady = remember { container.diarizationRunner.isEmbeddingModelPresent() }
    val embeddingModelName = remember { container.diarizationRunner.embeddingModelDisplayName() }
    // Installed Gemma models, for the RUN-sheet MODEL picker. Bumped on
    // selection so the active label refreshes. Short label = E2B / E4B
    // (extracted from the filename), or the bare filename as a fallback.
    var gemmaModelTick by remember { mutableIntStateOf(0) }
    val gemmaModels = remember { container.asrFactory.listModels(AsrBackendKind.Gemma4) }
    fun shortGemmaLabel(name: String): String =
        Regex("E\\d+B", RegexOption.IGNORE_CASE).find(name)?.value?.uppercase()
            ?: name.removeSuffix(".litertlm").removeSuffix(".task").take(12)
    var hybridDiar by remember { mutableStateOf(diarReady) }
    var runOnCharger by remember { mutableStateOf(false) }
    // -1 = "auto" (sherpa clusters and picks); 1+ = explicit speaker
    // count forced into clustering. Useful when you know a meeting had
    // exactly N people — pyannote left to its own devices commonly over-
    // segments, producing 5 labels for a 3-person meeting (see Mac app
    // README §"Min/max speakers"). Cycled through Auto/2/3/4/5/6 in the
    // RUN expander.
    var expectedSpeakers by remember { mutableStateOf(-1) }
    var runExpanded by remember { mutableStateOf(false) }
    var langDialogOpen by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<String?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    var historySheetOpen by remember { mutableStateOf(false) }
    var restoreTarget by remember { mutableStateOf<Long?>(null) }
    var fullscreen by remember { mutableStateOf(false) }
    val showTimestamps by container.uiPrefs.showTimestamps.collectAsStateWithLifecycle()
    val proseMode by container.uiPrefs.proseMode.collectAsStateWithLifecycle()
    // Same toggle PostProcessor.assembleTranscriptForPrompt already applies
    // before every preset — applying it here too keeps what's ON SCREEN
    // consistent with what presets read, per the plan's Phase 4 item
    // ("runs before every preset and on segment display when 'Remove
    // fillers' is on"). Display-only: the stored segment.text (and its
    // exported sidecars) are untouched.
    val removeFillers by container.promptStore.removeFillers.collectAsStateWithLifecycle()

    val installedModels = remember(backend) { container.asrFactory.listModels(backend) }
    var autoFired by remember { mutableStateOf(false) }
    LaunchedEffect(autoRun, ui.recording, installedModels) {
        if (autoRun && !autoFired && !ui.running &&
            ui.recording != null && installedModels.isNotEmpty()
        ) {
            autoFired = true
            vm.transcribe(
                backend = backend,
                languages = selectedLangs.toList(),
                translateTo = translateTo,
                diarize = diarize,
                expectedSpeakers = expectedSpeakers,
                hybridDiarize = hybridDiar && diarize && backend == AsrBackendKind.Gemma4 && diarReady,
                superMode = superMode,
                superPairA = superPair.first,
                superPairB = superPair.second,
                maxQuality = maxQuality,
            )
        }
    }

    // ExoPlayer + waveform (preserved from previous build).
    val playerController = remember(recordingId) { AudioPlayerController(context) }
    DisposableEffect(recordingId) { onDispose { playerController.release() } }
    LaunchedEffect(ui.recording?.audioPath) {
        ui.recording?.audioPath?.let { playerController.prepare(it) }
    }
    var waveform by remember(recordingId) { mutableStateOf<FloatArray?>(null) }
    LaunchedEffect(ui.recording?.audioPath) {
        val path = ui.recording?.audioPath ?: return@LaunchedEffect
        val file = java.io.File(path)
        if (!file.exists()) {
            android.util.Log.w("Detail", "waveform: audio file missing at $path")
            return@LaunchedEffect
        }
        val tStart = System.currentTimeMillis()
        val result = runCatching {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                WaveformLoader.extract(file, buckets = 200)
            }
        }
        result.onFailure { android.util.Log.e("Detail", "waveform extract failed", it) }
        result.onSuccess {
            android.util.Log.i(
                "Detail",
                "waveform extracted: ${it.size} buckets in " +
                    "${System.currentTimeMillis() - tStart}ms (file ${file.length() / 1024} KB)",
            )
        }
        waveform = result.getOrNull()
    }
    val playerPosMs by playerController.positionMs.collectAsStateWithLifecycle()
    val activeSegmentId = remember(playerPosMs, ui.segments) {
        if (ui.segments.isEmpty()) null
        else {
            val sec = playerPosMs / 1000.0
            ui.segments.firstOrNull { sec in it.startSeconds..it.endSeconds }?.id
                ?: ui.segments.lastOrNull { it.startSeconds <= sec }?.id
        }
    }

    KeepScreenOn(enabled = ui.running)

    val speakerKeys = remember(ui.segments) { ui.segments.mapNotNull { it.speaker }.distinct() }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Sheet(modifier = Modifier.fillMaxSize()) {
            // ─── Top chrome ────────────────────────────────────────
            DetailTopRow(
                onBack = onBack,
                rec = ui.recording,
                hasTranscript = ui.segments.isNotEmpty(),
                hasHistory = ui.versions.isNotEmpty(),
                fullscreen = fullscreen,
                onToggleFullscreen = { fullscreen = !fullscreen },
                onShare = {
                    val rec = ui.recording ?: return@DetailTopRow
                    val text = TranscriptExporter.toTxt(rec, ui.segments)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, rec.title)
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    context.startActivity(Intent.createChooser(intent, "Share transcript"))
                },
                onShowHistory = { historySheetOpen = true },
                onDelete = { vm.delete(onBack) },
            )
            Spacer(Modifier.height(10.dp))
            InkRule()
            Spacer(Modifier.height(16.dp))

            if (!fullscreen) {
                Mono(
                    "03 / SESSION",
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = ui.recording?.title ?: "Untitled",
                    color = MaterialTheme.colorScheme.onBackground,
                    style = TextStyle(
                        fontFamily = Fraunces,
                        fontStyle = FontStyle.Italic,
                        fontSize = 26.sp,
                        lineHeight = 30.sp,
                        letterSpacing = (-0.38).sp,
                    ),
                )
                Spacer(Modifier.height(12.dp))
                HairlineSoft()
                Spacer(Modifier.height(8.dp))
                MetadataStrip(
                    rec = ui.recording,
                    segmentCount = ui.segments.size,
                    speakerCount = speakerKeys.size,
                )
                Spacer(Modifier.height(8.dp))
                HairlineSoft()
                Spacer(Modifier.height(8.dp))
                OrganizeRow(
                    currentFolderId = ui.recording?.folderId,
                    allFolders = ui.allFolders,
                    tags = ui.tags,
                    onMoveToFolder = vm::moveToFolder,
                    onAddTag = vm::addTag,
                    onRemoveTag = vm::removeTag,
                )
                Spacer(Modifier.height(8.dp))
                HairlineSoft()
                Spacer(Modifier.height(12.dp))

                // Transcribe RUN strip — collapsed by default. Shows current
                // settings inline and the Run/Stop action; expands to a full
                // mono-caps ledger for editing engine / language / diarize /
                // translate / model.
                RunStrip(
                    expanded = runExpanded,
                    onToggle = { runExpanded = !runExpanded },
                    job = ui.job,
                    backend = backend,
                    selectedLangs = selectedLangs,
                    translateTo = translateTo,
                    diarize = diarize,
                    diarReady = diarReady,
                    hybridDiar = hybridDiar,
                    runOnCharger = runOnCharger,
                    installedModelsEmpty = installedModels.isEmpty(),
                    superMode = superMode,
                    superPairLabel = superPairLabel(superPair),
                    onCycleBackend = cycleBackend,
                    onPickLanguages = { langDialogOpen = true },
                    onCycleTranslate = {
                        val cycle = listOf<String?>(null, "en", "ar", "uk", "nl")
                        val i = cycle.indexOf(translateTo).let { if (it < 0) 0 else it }
                        translateTo = cycle[(i + 1) % cycle.size]
                    },
                    onToggleDiarize = { diarize = !diarize },
                    onToggleHybrid = { hybridDiar = !hybridDiar },
                    onToggleCharger = { runOnCharger = !runOnCharger },
                    expectedSpeakers = expectedSpeakers,
                    onCycleSpeakers = {
                        // Cycle Auto → 2 → 3 → 4 → 5 → 6 → Auto. Force-
                        // counts > 6 are rare in practice and over-
                        // segmentation on very crowded meetings is its
                        // own quality problem (see NEXT_STEPS).
                        expectedSpeakers = when (expectedSpeakers) {
                            -1 -> 2
                            6 -> -1
                            else -> expectedSpeakers + 1
                        }
                    },
                    onRun = {
                        vm.transcribe(
                            backend = backend,
                            languages = selectedLangs.toList(),
                            translateTo = translateTo,
                            diarize = diarize,
                            expectedSpeakers = expectedSpeakers,
                            runOnCharger = runOnCharger,
                            hybridDiarize = hybridDiar && diarize &&
                                backend == AsrBackendKind.Gemma4 && diarReady,
                            superMode = superMode,
                            superPairA = superPair.first,
                            superPairB = superPair.second,
                            maxQuality = maxQuality,
                        )
                    },
                    onCancel = { vm.cancelTranscription() },
                    onDismissError = { vm.dismissJobError() },
                )

                // Speaker chips (mono-caps row).
                if (speakerKeys.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    SpeakerChipRow(
                        keys = speakerKeys,
                        segments = ui.segments,
                        onRename = { key ->
                            renameTarget = key
                            renameDraft = ui.segments
                                .firstOrNull { it.speaker == key }?.speakerName ?: ""
                        },
                    )
                }

                Spacer(Modifier.height(12.dp))
            }
            // Post-process presets — visible ONLY in READ ⤢ fullscreen
            // mode (per user direction: "moved to the read transcript
            // modal"). Normal mode keeps the chrome minimal by hiding
            // them. The 2×2 grid is rendered above the tab strip
            // inside the fullscreen view so the quick-actions are
            // reachable without leaving READ.
            if (fullscreen && ui.segments.isNotEmpty() && ui.presets.isNotEmpty()) {
                PresetGrid(
                    presets = ui.presets,
                    statuses = ui.presetStatus,
                    onRun = { id -> vm.runPreset(id) },
                    compact = true,
                )
                Spacer(Modifier.height(8.dp))
            }

            // Tab strip — Transcript / each Output.
            val tabs = remember(ui.outputs) {
                buildList {
                    add(DocTab.Transcript)
                    ui.outputs.forEach { add(DocTab.Output(it)) }
                }
            }
            var selectedTab by remember { mutableIntStateOf(0) }
            LaunchedEffect(tabs.size) { if (selectedTab >= tabs.size) selectedTab = 0 }
            TabStrip(
                tabs = tabs,
                selectedIndex = selectedTab,
                onSelect = { selectedTab = it },
                proseMode = proseMode,
                onToggleProse = { container.uiPrefs.setProseMode(!proseMode) },
                onToggleTimestamps = { container.uiPrefs.setShowTimestamps(!showTimestamps) },
                showTimestamps = showTimestamps,
                fullscreen = fullscreen,
                onToggleFullscreen = { fullscreen = !fullscreen },
            )
            Spacer(Modifier.height(8.dp))

            // Player bar — kept from prior build.
            if (ui.segments.isNotEmpty()) {
                EditorialPlayer(
                    controller = playerController,
                    waveform = waveform,
                )
                Spacer(Modifier.height(8.dp))
            }

            // Body: transcript ledger / prose / output.
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when (val tab = tabs[selectedTab]) {
                    is DocTab.Transcript -> TranscriptBody(
                        segments = ui.segments,
                        speakerColors = speakerKeys.mapIndexed { idx, k -> k to speakerColor(idx) }.toMap(),
                        showTimestamps = showTimestamps,
                        proseMode = proseMode,
                        removeFillers = removeFillers,
                        activeSegmentId = activeSegmentId,
                        onEdit = vm::editSegmentText,
                        onSegmentSeek = { seg -> playerController.seekToSeconds(seg.startSeconds) },
                    )
                    is DocTab.Output -> OutputBody(
                        doc = tab.doc,
                        onDelete = { vm.deleteOutput(tab.doc.id) },
                        onShare = {
                            val rec = ui.recording ?: return@OutputBody
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                // Real markdown content (see MarkdownText.kt) —
                                // apps that understand the type (Obsidian,
                                // most note apps, Gmail's compose) get to
                                // treat it as such instead of literal
                                // asterisks and pound signs.
                                type = "text/markdown"
                                putExtra(Intent.EXTRA_SUBJECT, "${rec.title} — ${tab.doc.title}")
                                putExtra(Intent.EXTRA_TEXT, tab.doc.markdown)
                            }
                            context.startActivity(Intent.createChooser(intent, "Share ${tab.doc.title}"))
                        },
                    )
                }
            }
        }

        // Dialogs (overlays).
        renameTarget?.let { key ->
            RenameSpeakerDialog(
                target = key,
                initial = renameDraft,
                onDismiss = { renameTarget = null },
                onConfirm = { newName ->
                    vm.renameAllByKey(key, newName)
                    renameTarget = null
                },
            )
        }
        if (langDialogOpen) {
            LanguagesDialog(
                initial = selectedLangs,
                onDismiss = { langDialogOpen = false },
                onConfirm = { picks ->
                    selectedLangs = picks
                    container.uiPrefs.setLastLanguages(picks)
                    langDialogOpen = false
                },
            )
        }
        // Version history — past transcript snapshots, taken automatically
        // right before each re-run (and before a restore) overwrites the
        // live transcript. See TranscriptVersion.kt / RecordingRepository.
        if (historySheetOpen) {
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(
                onDismissRequest = { historySheetOpen = false },
                sheetState = sheetState,
                containerColor = MaterialTheme.colorScheme.background,
            ) {
                VersionHistorySheet(
                    versions = ui.versions,
                    onRestore = { restoreTarget = it },
                    onDelete = { vm.deleteVersion(it) },
                )
            }
        }
        restoreTarget?.let { versionId ->
            val version = ui.versions.firstOrNull { it.id == versionId }
            AlertDialog(
                onDismissRequest = { restoreTarget = null },
                containerColor = MaterialTheme.colorScheme.background,
                title = { Mono("RESTORE VERSION", color = MaterialTheme.colorScheme.onBackground) },
                text = {
                    Text(
                        "Replace the current transcript with the " +
                            "${version?.engineLabel ?: "selected"} version from " +
                            (version?.let { formatStampMono(it.createdAtMillis) } ?: "") +
                            "? The current transcript is saved to history first, so " +
                            "this can be undone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        vm.restoreVersion(versionId)
                        restoreTarget = null
                        historySheetOpen = false
                    }) { Mono("RESTORE") }
                },
                dismissButton = {
                    TextButton(onClick = { restoreTarget = null }) { Mono("CANCEL") }
                },
            )
        }
        // RUN options bottom sheet — used to inline-expand below the
        // RunStrip and dominate the screen. Sheet collapses to a tiny
        // header (RUN ▸ + the summary) until the user taps it.
        if (runExpanded) {
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(
                onDismissRequest = { runExpanded = false },
                sheetState = sheetState,
                containerColor = MaterialTheme.colorScheme.background,
                dragHandle = null,
            ) {
                RunOptionsSheetContent(
                    backend = backend,
                    selectedLangs = selectedLangs,
                    translateTo = translateTo,
                    diarize = diarize,
                    diarReady = diarReady,
                    hybridDiar = hybridDiar,
                    embeddingModelName = embeddingModelName,
                    gemmaModelLabel = run {
                        gemmaModelTick // read so this recomputes after a cycle
                        if (gemmaModels.size > 1) {
                            val active = container.asrFactory.resolveModel(AsrBackendKind.Gemma4)?.name
                            active?.let { shortGemmaLabel(it) }
                        } else null
                    },
                    onCycleGemmaModel = {
                        if (gemmaModels.size > 1) {
                            val active = container.asrFactory.resolveModel(AsrBackendKind.Gemma4)?.name
                            val idx = gemmaModels.indexOfFirst { it.name == active }
                            val next = gemmaModels[(idx + 1).mod(gemmaModels.size)]
                            container.asrFactory.setSelectedModel(AsrBackendKind.Gemma4, next.name)
                            gemmaModelTick++
                        }
                    },
                    runOnCharger = runOnCharger,
                    expectedSpeakers = expectedSpeakers,
                    onCycleBackend = cycleBackend,
                    onPickLanguages = { langDialogOpen = true },
                    onCycleTranslate = {
                        val cycle = listOf<String?>(null, "en", "ar", "uk", "nl")
                        val i = cycle.indexOf(translateTo).let { if (it < 0) 0 else it }
                        translateTo = cycle[(i + 1) % cycle.size]
                    },
                    onToggleDiarize = { diarize = !diarize },
                    onToggleHybrid = { hybridDiar = !hybridDiar },
                    onToggleCharger = { runOnCharger = !runOnCharger },
                    onCycleSpeakers = {
                        expectedSpeakers = when (expectedSpeakers) {
                            -1 -> 2
                            6 -> -1
                            else -> expectedSpeakers + 1
                        }
                    },
                    superMode = superMode,
                    onToggleSuper = { superMode = !superMode },
                    superPairLabel = superPairLabel(superPair),
                    onCyclePair = { superPairIdx = (superPairIdx + 1) % SUPER_PAIR_PRESETS.size },
                    maxQuality = maxQuality,
                    onToggleMaxQuality = { maxQuality = !maxQuality },
                )
            }
        }
    }
}

// ─── Top chrome ──────────────────────────────────────────────────────

@Composable
private fun DetailTopRow(
    onBack: () -> Unit,
    @Suppress("UNUSED_PARAMETER") rec: nl.ihnatov.transcriber.data.Recording?,
    hasTranscript: Boolean,
    hasHistory: Boolean,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onShare: () -> Unit,
    onShowHistory: () -> Unit,
    onDelete: () -> Unit,
) {
    val ink = MaterialTheme.colorScheme.onBackground
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The back link is the only always-visible action — every other
        // action moves into the ⋯ overflow menu. The previous row had
        // ← LIBRARY · FULLSCREEN · SHARE · DELETE · date and overflowed
        // the screen on the right edge ("date out of bounds"). Date is
        // moved to the metadata strip below the title where it actually
        // belongs editorially.
        Mono(
            "← LIBRARY",
            color = ink,
            modifier = Modifier.clickable(onClick = onBack).padding(6.dp),
        )
        Spacer(Modifier.weight(1f))
        Box {
            Mono(
                "MORE ⋯",
                color = ink,
                modifier = Modifier
                    .clickable { menuOpen = true }
                    .padding(8.dp),
            )
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                containerColor = MaterialTheme.colorScheme.background,
            ) {
                if (hasTranscript) {
                    DropdownMenuItem(
                        text = {
                            Mono(
                                if (fullscreen) "EXIT FULLSCREEN" else "FULLSCREEN",
                                color = ink,
                            )
                        },
                        onClick = { menuOpen = false; onToggleFullscreen() },
                    )
                    DropdownMenuItem(
                        text = { Mono("SHARE TRANSCRIPT", color = ink) },
                        onClick = { menuOpen = false; onShare() },
                    )
                }
                if (hasHistory) {
                    DropdownMenuItem(
                        text = { Mono("HISTORY", color = ink) },
                        onClick = { menuOpen = false; onShowHistory() },
                    )
                }
                DropdownMenuItem(
                    text = { Mono("DELETE RECORDING", color = Accent) },
                    onClick = { menuOpen = false; onDelete() },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MetadataStrip(
    rec: nl.ihnatov.transcriber.data.Recording?,
    segmentCount: Int,
    speakerCount: Int,
) {
    val ink = MaterialTheme.colorScheme.onBackground
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        rec?.let {
            // Date — was in the top action row but overflowed; lives here
            // now where the metadata band can wrap if it doesn't fit.
            Mono(formatStampMono(it.createdAtMillis), color = ink.copy(alpha = 0.55f))
            Mono("·", color = ink.copy(alpha = 0.3f))
            Mono(formatDurationMono(it.durationSeconds.toInt()), color = ink)
        }
        Mono("·", color = ink.copy(alpha = 0.3f))
        rec?.transcribedWithBackend?.let {
            Mono(it.uppercase().take(18), color = ink.copy(alpha = 0.62f))
            Mono("·", color = ink.copy(alpha = 0.3f))
        }
        rec?.sourceLanguage?.let {
            Mono(it.uppercase(), color = ink.copy(alpha = 0.62f))
            Mono("·", color = ink.copy(alpha = 0.3f))
        }
        nl.ihnatov.transcriber.asr.RecordingCategory.fromId(rec?.category)?.let { cat ->
            Mono(cat.displayName.uppercase(), color = ink.copy(alpha = 0.62f))
            Mono("·", color = ink.copy(alpha = 0.3f))
        }
        if (speakerCount > 0) {
            Mono("$speakerCount SPEAKERS", color = ink.copy(alpha = 0.62f))
            Mono("·", color = ink.copy(alpha = 0.3f))
        }
        if (segmentCount > 0) {
            Mono("$segmentCount TURNS", color = ink.copy(alpha = 0.62f))
        }
    }
}

/**
 * Folder dropdown + tag editor, side by side — mirrors the Mac's
 * `organizeRow()` (`HStack { folderMenu(); TagEditorRow(...) }`).
 */
@Composable
private fun OrganizeRow(
    currentFolderId: Long?,
    allFolders: List<Folder>,
    tags: List<Tag>,
    onMoveToFolder: (Long?) -> Unit,
    onAddTag: (String) -> Unit,
    onRemoveTag: (Long) -> Unit,
) {
    Row(verticalAlignment = Alignment.Top) {
        FolderMenu(currentFolderId = currentFolderId, allFolders = allFolders, onMoveToFolder = onMoveToFolder)
        Spacer(Modifier.width(Spacing.m))
        TagEditorRow(
            tags = tags,
            onAddTag = onAddTag,
            onRemoveTag = onRemoveTag,
            modifier = Modifier.weight(1f),
        )
    }
}

/** "FOLDER: NAME ▾" — every other folder, plus "Remove from Folder" when filed. Mirrors the Mac's `folderMenu()`. */
@Composable
private fun FolderMenu(
    currentFolderId: Long?,
    allFolders: List<Folder>,
    onMoveToFolder: (Long?) -> Unit,
) {
    val ink = MaterialTheme.colorScheme.onBackground
    var menuOpen by remember { mutableStateOf(false) }
    val currentName = allFolders.find { it.id == currentFolderId }?.name
    Box {
        Mono(
            "FOLDER: ${currentName?.uppercase() ?: "—"} ▾",
            color = if (currentFolderId == null) ink.copy(alpha = 0.62f) else Accent,
            modifier = Modifier.clickable { menuOpen = true }.padding(vertical = 4.dp),
        )
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            containerColor = MaterialTheme.colorScheme.background,
        ) {
            for (f in allFolders.filter { it.id != currentFolderId }) {
                DropdownMenuItem(
                    text = { Text(f.name) },
                    onClick = { menuOpen = false; onMoveToFolder(f.id) },
                )
            }
            if (currentFolderId != null) {
                DropdownMenuItem(
                    text = { Mono("REMOVE FROM FOLDER", color = Accent) },
                    onClick = { menuOpen = false; onMoveToFolder(null) },
                )
            }
        }
    }
}

/**
 * Current tags as removable chips (✕, bottom hairline like the Mac's
 * underlined chip) plus an inline "add tag…" field that commits on
 * Enter or a trailing comma. Mirrors the Mac's `TagEditorRow`.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagEditorRow(
    tags: List<Tag>,
    onAddTag: (String) -> Unit,
    onRemoveTag: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ink = MaterialTheme.colorScheme.onBackground
    var draft by remember { mutableStateOf("") }
    fun commitDraft() {
        val name = draft.trim()
        draft = ""
        if (name.isNotEmpty()) onAddTag(name)
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier,
    ) {
        Mono("TAGS", color = ink.copy(alpha = 0.40f), modifier = Modifier.padding(vertical = 4.dp))
        for (tag in tags.sortedBy { it.name }) {
            Box {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                ) {
                    Mono(tag.name, color = ink)
                    Spacer(Modifier.width(5.dp))
                    Mono(
                        "✕",
                        color = ink.copy(alpha = 0.40f),
                        modifier = Modifier.clickable { onRemoveTag(tag.id) },
                    )
                }
                Box(
                    Modifier
                        .matchParentSize()
                        .drawBehind {
                            drawLine(
                                color = ink.copy(alpha = 0.16f),
                                start = Offset(0f, size.height),
                                end = Offset(size.width, size.height),
                                strokeWidth = 1.dp.toPx(),
                            )
                        },
                )
            }
        }
        BasicTextField(
            value = draft,
            onValueChange = { value ->
                if (value.endsWith(",")) {
                    draft = value.removeSuffix(",")
                    commitDraft()
                } else {
                    draft = value
                }
            },
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, color = ink),
            cursorBrush = SolidColor(Accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commitDraft() }),
            modifier = Modifier.width(90.dp).padding(vertical = 4.dp),
            decorationBox = { inner ->
                Box {
                    if (draft.isEmpty()) {
                        Text(
                            "add tag…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ink.copy(alpha = 0.40f),
                        )
                    }
                    inner()
                }
            },
        )
    }
}

// ─── Run strip ───────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RunStrip(
    expanded: Boolean,
    onToggle: () -> Unit,
    job: RecordingDetailViewModel.JobStatus,
    backend: AsrBackendKind,
    selectedLangs: Set<String>,
    translateTo: String?,
    diarize: Boolean,
    diarReady: Boolean,
    hybridDiar: Boolean,
    runOnCharger: Boolean,
    installedModelsEmpty: Boolean,
    superMode: Boolean,
    superPairLabel: String,
    onCycleBackend: () -> Unit,
    onPickLanguages: () -> Unit,
    onCycleTranslate: () -> Unit,
    onToggleDiarize: () -> Unit,
    onToggleHybrid: () -> Unit,
    onToggleCharger: () -> Unit,
    expectedSpeakers: Int,
    onCycleSpeakers: () -> Unit,
    onRun: () -> Unit,
    onCancel: () -> Unit,
    onDismissError: () -> Unit,
) {
    val ink = MaterialTheme.colorScheme.onBackground
    val paper = MaterialTheme.colorScheme.background
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Mono("RUN ${if (expanded) "▾" else "▸"}", color = ink)
            Spacer(Modifier.width(10.dp))
            val summary = buildString {
                append(if (superMode) "SUPER · $superPairLabel" else runSheetEngineLabel(backend))
                append(" · ")
                append(summarizeLanguages(selectedLangs).uppercase())
                translateTo?.let { append(" → ").append(it.uppercase()) }
                if (diarize) append(" · DIARIZE")
            }
            Mono(summary, color = ink.copy(alpha = 0.55f), maxLines = 1)
        }
        // Option rows now live in [RunOptionsSheetContent] rendered in a
        // ModalBottomSheet by the screen body — tapping the RUN header
        // (which flips `expanded`) opens the sheet. The inline expansion
        // dominated the screen on phone-portrait viewports and pushed
        // the transcript below the fold.
        if (installedModelsEmpty) {
            Mono(
                "NO MODEL INSTALLED — SETTINGS · DOWNLOAD",
                color = Accent,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        val active = job.running || job.waitingForCharger || job.queued
        if (active) {
            // Inverse "Stop" / status row. Not clickable while already
            // stopping — cancellation is cooperative (see JobStatus.
            // stopping's doc comment) so a second tap can't speed
            // anything up, and leaving the tap affordance up would read
            // as "my first tap didn't register."
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(ink)
                    .let { if (job.stopping) it else it.clickable(onClick = onCancel) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Mono(
                    when {
                        job.stopping -> "STOPPING…"
                        job.waitingForCharger -> "WAITING FOR CHARGER — TAP TO CANCEL"
                        job.queued -> "QUEUED — TAP TO CANCEL"
                        else -> "TRANSCRIBING — TAP TO STOP"
                    },
                    color = paper,
                )
                if (job.running) {
                    LinearProgressIndicator(
                        progress = { job.progress.coerceIn(0f, 1f) },
                        color = Accent,
                        trackColor = paper.copy(alpha = 0.18f),
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                    )
                    if (job.stageLabel.isNotBlank()) {
                        Mono(job.stageLabel.uppercase(), color = paper.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        } else {
            // Run button — inverse block, no chip.
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(ink)
                    .clickable(onClick = onRun)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (runOnCharger) "QUEUE FOR CHARGER" else "RUN TRANSCRIPTION",
                    color = paper,
                    style = MaterialTheme.typography.displaySmall,
                )
                Spacer(Modifier.weight(1f))
                Mono("→", color = Accent, style = MaterialTheme.typography.displaySmall)
            }
        }
        job.error?.let { err ->
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    err,
                    color = Accent,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                Mono(
                    "DISMISS",
                    color = ink.copy(alpha = 0.55f),
                    modifier = Modifier.clickable(onClick = onDismissError).padding(6.dp),
                )
            }
        }
    }
}

/**
 * Body of the RUN options bottom sheet. Same option rows that used to
 * expand inline below the RunStrip header; lives in a ModalBottomSheet
 * instead so a phone-portrait viewport keeps the transcript visible.
 */
@Composable
private fun RunOptionsSheetContent(
    backend: AsrBackendKind,
    selectedLangs: Set<String>,
    translateTo: String?,
    diarize: Boolean,
    diarReady: Boolean,
    hybridDiar: Boolean,
    /**
     * Friendly name of the active speaker-embedding model (the one
     * DiarizationRunner picks via its priority cascade). Shown next to
     * "ON" on the HYBRID row so the user can tell which model is in
     * use without opening Settings. Null when no embedding model is
     * installed.
     */
    embeddingModelName: String?,
    /**
     * Short label of the active Gemma model (e.g. "E2B" / "E4B") and a
     * cycler, used only when the Gemma backend has >1 model installed.
     * Null label hides the MODEL row (single model = nothing to pick).
     */
    gemmaModelLabel: String?,
    onCycleGemmaModel: () -> Unit,
    runOnCharger: Boolean,
    expectedSpeakers: Int,
    onCycleBackend: () -> Unit,
    onPickLanguages: () -> Unit,
    onCycleTranslate: () -> Unit,
    onToggleDiarize: () -> Unit,
    onToggleHybrid: () -> Unit,
    onToggleCharger: () -> Unit,
    onCycleSpeakers: () -> Unit,
    superMode: Boolean,
    onToggleSuper: () -> Unit,
    superPairLabel: String,
    onCyclePair: () -> Unit,
    maxQuality: Boolean,
    onToggleMaxQuality: () -> Unit,
) {
    // Which row's help dialog is currently shown. Null = none. Per-row
    // help is opened by tapping the (?) chip at the right end of each
    // line. The editorial strip kept labels short by design; the help
    // dialog is the escape hatch for users who want to know what a
    // toggle does without leaving the sheet.
    var helpFor by remember { mutableStateOf<RunOptionHelp?>(null) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Mono(
            "TRANSCRIPTION OPTIONS",
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(6.dp))
        RunOptionLine(
            label = "SUPER",
            value = if (superMode) "ON" else "OFF",
            onClick = onToggleSuper,
            onHelp = { helpFor = RunOptionHelp.SUPER },
        )
        if (superMode) {
            RunOptionLine(
                label = "PAIR",
                value = superPairLabel,
                onClick = onCyclePair,
                onHelp = { helpFor = RunOptionHelp.PAIR },
            )
            RunOptionLine(
                label = "MAX QUALITY",
                value = if (maxQuality) "ON" else "OFF",
                onClick = onToggleMaxQuality,
                onHelp = { helpFor = RunOptionHelp.MAX_QUALITY },
            )
        } else {
            RunOptionLine(
                label = "ENGINE",
                value = runSheetEngineLabel(backend),
                onClick = onCycleBackend,
                onHelp = { helpFor = RunOptionHelp.ENGINE },
            )
            // Gemma model picker — only when the Gemma backend has more than
            // one model installed (E2B + E4B). Tapping cycles the active
            // model and pins it. Hidden otherwise (nothing to choose).
            if (backend == AsrBackendKind.Gemma4 && gemmaModelLabel != null) {
                RunOptionLine(
                    label = "MODEL",
                    value = gemmaModelLabel,
                    onClick = onCycleGemmaModel,
                    onHelp = { helpFor = RunOptionHelp.MODEL },
                )
            }
        }
        RunOptionLine(
            label = "LANG",
            value = summarizeLanguages(selectedLangs).uppercase(),
            onClick = onPickLanguages,
            onHelp = { helpFor = RunOptionHelp.LANG },
        )
        // Only whisper.cpp and Gemma can translate; the sherpa-onnx engines
        // (and a Super pair) transcribe as-is, so don't offer a target the
        // runner would have to ignore.
        if (backend.supportsTranslation && !superMode) {
            RunOptionLine(
                label = "TRANSLATE",
                value = (translateTo?.uppercase() ?: "OFF"),
                onClick = onCycleTranslate,
                onHelp = { helpFor = RunOptionHelp.TRANSLATE },
            )
        }
        RunOptionLine(
            label = "DIARIZE",
            value = if (diarize) "ON" else "OFF",
            onClick = onToggleDiarize,
            onHelp = { helpFor = RunOptionHelp.DIARIZE },
        )
        if (diarize && backend == AsrBackendKind.Gemma4 && diarReady) {
            // Show the active embedding-model name as part of the value
            // when hybrid is ON — "ON · WESPEAKER" / "ON · CAM++ EN".
            // Lets the user see at a glance which clustering model is
            // selected without digging into Settings → Installed. When
            // hybrid is OFF or no model is installed, just show ON/OFF.
            val hybridValue = if (hybridDiar) {
                if (embeddingModelName != null)
                    "ON · ${embeddingModelName.uppercase()}"
                else "ON"
            } else "OFF"
            RunOptionLine(
                label = "HYBRID",
                value = hybridValue,
                onClick = onToggleHybrid,
                onHelp = { helpFor = RunOptionHelp.HYBRID },
            )
        }
        if (diarize) {
            RunOptionLine(
                label = "SPEAKERS",
                value = if (expectedSpeakers <= 0) "AUTO" else expectedSpeakers.toString(),
                onClick = onCycleSpeakers,
                onHelp = { helpFor = RunOptionHelp.SPEAKERS },
            )
        }
        RunOptionLine(
            label = "CHARGER",
            value = if (runOnCharger) "WAIT" else "NOW",
            onClick = onToggleCharger,
            onHelp = { helpFor = RunOptionHelp.CHARGER },
        )
        Spacer(Modifier.height(8.dp))
    }
    helpFor?.let { h ->
        AlertDialog(
            onDismissRequest = { helpFor = null },
            title = { Mono(h.title, color = MaterialTheme.colorScheme.onBackground) },
            text = { Text(h.body) },
            confirmButton = {
                TextButton(onClick = { helpFor = null }) { Text("Got it") }
            },
        )
    }
}

/**
 * Per-row explanation copy for [RunOptionsSheetContent]. Title doubles as
 * the dialog header (mono-caps to match the row label); body is short
 * prose intended to answer the "what does this do?" question without
 * sending the user to the docs. Kept inline rather than in a separate
 * resource file so the strings stay next to the rows they describe.
 */
private enum class RunOptionHelp(val title: String, val body: String) {
    ENGINE(
        "ENGINE",
        "Which ASR backend runs the transcription. GEMMA 4 is the on-device " +
            "multimodal model — best for natural conversational text, supports " +
            "inline speaker labels and translation. WHISPER (whisper.cpp) is " +
            "faster on long files, more robust on noisy audio, English-translate " +
            "only.",
    ),
    MODEL(
        "MODEL",
        "Which Gemma model runs the transcription when more than one is " +
            "installed. E2B (2.6 GB) is faster per chunk; E4B (3.5 GB) is " +
            "more accurate on dialectal Arabic and Ukrainian but slower. " +
            "Tap to switch — the choice persists across runs.",
    ),
    LANG(
        "LANG",
        "Source language(s) of the audio. Tap to pick a set. One language = " +
            "forced; multiple = constrained auto-detect (faster + more accurate " +
            "than full auto over 100+ languages).",
    ),
    TRANSLATE(
        "TRANSLATE",
        "Render the output in this target language instead of the source. " +
            "Gemma supports any-to-any (EN/AR/UK/NL); Whisper only translates to " +
            "English. OFF = transcribe in the source language as heard.",
    ),
    DIARIZE(
        "DIARIZE",
        "Identify and label speakers (Speaker 1, Speaker 2, …). For long " +
            "recordings or interviews. Needs a small speaker-embedding model — " +
            "download it from Settings → Models if asked.",
    ),
    HYBRID(
        "HYBRID",
        "Two-pass diarization. A dedicated clustering model decides who's who " +
            "globally, Gemma still inserts the inline labels. Slower but " +
            "cross-chunk speaker IDs stay consistent — Speaker 1 in minute 1 " +
            "stays Speaker 1 in minute 12.\n\n" +
            "Active model shown next to the ON value. Priority cascade when " +
            "multiple are installed:\n" +
            "  1. WeSpeaker ResNet221-LM (95 MB) — best English EER\n" +
            "  2. CAM++ multilingual zh+en (28 MB) — best for code-switched\n" +
            "  3. CAM++ English (28 MB) — compact baseline\n" +
            "Manage downloads in Settings → Models.",
    ),
    SPEAKERS(
        "SPEAKERS",
        "Hint how many distinct voices to expect. AUTO lets the clustering " +
            "model decide. Use a number when you know up front (2 = interview, " +
            "5 = panel) — improves accuracy in noisy mixes.",
    ),
    CHARGER(
        "CHARGER",
        "WAIT parks the job in the queue until you plug the phone into power. " +
            "NOW starts immediately. Gemma 4 is heavy — long files on battery " +
            "will warm the device and drain quickly.",
    ),
    SUPER(
        "SUPER",
        "Run two engines on every chunk and vote-merge the result instead of " +
            "trusting one. Keeps what both agree on, picks the more plausible " +
            "reading where they differ. Roughly 2× the compute of a single " +
            "engine — for when accuracy matters more than speed.",
    ),
    PAIR(
        "PAIR",
        "Which two engines Super mode runs. PARAKEET + WHISPER for most " +
            "recordings; OMNILINGUAL + GEMMA 4 when Arabic is in the mix — " +
            "Omnilingual reads Gulf Arabic, Gemma covers what it misses.",
    ),
    MAX_QUALITY(
        "MAX QUALITY",
        "On top of the vote, send the chunks where the two engines disagreed " +
            "most to Gemma for a second look — a constrained choice between " +
            "the two readings, never free text, so it can't invent content. " +
            "Slower; only worth it on recordings you'll rely on.",
    ),
}

@Composable
private fun RunOptionLine(
    label: String,
    value: String,
    onClick: () -> Unit,
    onHelp: (() -> Unit)? = null,
) {
    val ink = MaterialTheme.colorScheme.onBackground
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Mono(label, color = ink.copy(alpha = 0.62f), modifier = Modifier.width(84.dp))
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Mono(value, color = ink)
            Box(
                Modifier
                    .height(1.5.dp)
                    .width(56.dp)
                    .padding(top = 1.dp)
                    .background(Accent),
            )
        }
        if (onHelp != null) {
            // (?) chip. Separate clickable from the row's value-cycler so
            // tapping help doesn't also flip the value. Wide hit-target
            // via padding; visually a faint mono character to stay out
            // of the way.
            Mono(
                "?",
                color = ink.copy(alpha = 0.45f),
                modifier = Modifier
                    .clickable(onClick = onHelp)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpeakerChipRow(
    keys: List<String>,
    segments: List<nl.ihnatov.transcriber.data.Segment>,
    onRename: (String) -> Unit,
) {
    val ink = MaterialTheme.colorScheme.onBackground
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        keys.forEachIndexed { idx, key ->
            val display = segments.firstOrNull { it.speaker == key }?.speakerName
                ?: key.replace("SPEAKER_", "Speaker ")
            val tint = speakerColor(idx)
            Row(
                modifier = Modifier
                    .clickable { onRename(key) }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(7.dp).background(tint))
                Spacer(Modifier.width(6.dp))
                Mono(display, color = ink)
            }
        }
        Mono(
            "RENAME ↗",
            color = ink.copy(alpha = 0.45f),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun PresetGrid(
    presets: List<nl.ihnatov.transcriber.asr.PostProcessingPreset>,
    statuses: Map<String, RecordingDetailViewModel.PresetStatus>,
    onRun: (String) -> Unit,
    /**
     * Compact mode for the READ ⤢ fullscreen view. Shrinks cell padding,
     * label type size, and inter-row spacing so the now-five presets
     * (Summary / Context-aware rewrite / Clean / Proofread / Translate &
     * polish — three grid rows) don't eat the transcript's vertical
     * space. Normal Detail view uses the roomier default.
     */
    compact: Boolean = false,
) {
    if (presets.isEmpty()) return
    val ink = MaterialTheme.colorScheme.onBackground
    val paper = MaterialTheme.colorScheme.background
    val cellVPad = if (compact) 7.dp else 12.dp
    val cellHPad = if (compact) 10.dp else 12.dp
    val rowGap = if (compact) 6.dp else 8.dp
    val labelStyle = if (compact)
        MaterialTheme.typography.labelSmall
    else MaterialTheme.typography.labelLarge
    Column(verticalArrangement = Arrangement.spacedBy(rowGap)) {
        Mono(
            "POST-PROCESS · RUN A PRESET",
            color = ink.copy(alpha = 0.55f),
            style = if (compact)
                MaterialTheme.typography.labelSmall
            else MaterialTheme.typography.labelLarge,
        )
        // 2-column grid. Two layout fixes vs the earlier version:
        //   1. `height(IntrinsicSize.Min)` on the Row + `fillMaxHeight`
        //      on each cell makes the two cells in a row match the
        //      taller one's height. Without this, a wrapping label
        //      (CONTEXT-AWARE REWRITE) made the right cell two lines
        //      tall while the left (SUMMARY) stayed one line — visually
        //      jarring.
        //   2. Label gets `weight(1f)`, the trailing ↗ arrow / spinner
        //      sits OUTSIDE the weight. Previously the unweighted label
        //      grabbed all the horizontal space and pushed the arrow
        //      off the right edge of cells with long labels.
        presets.chunked(2).forEach { rowItems ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(rowGap),
                modifier = Modifier.height(IntrinsicSize.Min),
            ) {
                rowItems.forEach { preset ->
                    val st = statuses[preset.id]
                    val running = st?.running == true
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(ink)
                            .clickable(enabled = !running) { onRun(preset.id) }
                            .padding(horizontal = cellHPad, vertical = cellVPad),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Mono(
                            preset.displayName.uppercase(),
                            color = paper,
                            style = labelStyle,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(6.dp))
                        if (running) {
                            CircularProgressIndicator(
                                color = Accent,
                                strokeWidth = 1.5.dp,
                                modifier = Modifier.size(if (compact) 11.dp else 14.dp),
                            )
                        } else {
                            Mono("↗", color = Accent, style = labelStyle)
                        }
                    }
                }
                if (rowItems.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun TabStrip(
    tabs: List<DocTab>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    proseMode: Boolean,
    onToggleProse: () -> Unit,
    onToggleTimestamps: () -> Unit,
    showTimestamps: Boolean,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
) {
    val ink = MaterialTheme.colorScheme.onBackground
    Column {
        HairlineSoft()
        // Tabs row (TRANSCRIPT, post-process outputs). View controls used
        // to share this row but overflowed off-screen on narrow phones once
        // a second tab (SUMMARY, CLEAN, …) appeared. Moved to a dedicated
        // sub-row below so each row can use its full width without clipping.
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { idx, tab ->
                val active = idx == selectedIndex
                val label = when (tab) {
                    is DocTab.Transcript -> "TRANSCRIPT"
                    is DocTab.Output -> tab.doc.title.uppercase().take(20)
                }
                Column(
                    Modifier
                        .clickable { onSelect(idx) }
                        .padding(end = 18.dp, top = 4.dp, bottom = 2.dp),
                    horizontalAlignment = Alignment.Start,
                ) {
                    Mono(label, color = if (active) ink else ink.copy(alpha = 0.45f))
                    Box(
                        Modifier
                            .height(1.5.dp)
                            .padding(top = 4.dp)
                            .width(28.dp)
                            .background(if (active) Accent else Color.Transparent),
                    )
                }
            }
        }
        // View-controls sub-row. Right-aligned. Renders only on the
        // transcript tab (post-process outputs are plain prose — no
        // PROSE/CARDS or +TIMES toggle, but fullscreen still applies).
        if (tabs.getOrNull(selectedIndex) is DocTab.Transcript) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                Mono(
                    if (proseMode) "CARDS" else "PROSE",
                    color = ink.copy(alpha = 0.55f),
                    modifier = Modifier.clickable(onClick = onToggleProse).padding(6.dp),
                )
                Mono(
                    if (showTimestamps) "—TIMES" else "+TIMES",
                    color = ink.copy(alpha = 0.55f),
                    modifier = Modifier.clickable(onClick = onToggleTimestamps).padding(6.dp),
                )
                Mono(
                    if (fullscreen) "EXIT ⤡" else "READ ⤢",
                    color = if (fullscreen) Accent else ink,
                    modifier = Modifier.clickable(onClick = onToggleFullscreen).padding(6.dp),
                )
            }
        } else {
            // Keep the hairline at a consistent vertical offset whether
            // or not the view-controls row is present. Without this,
            // switching to a post-process tab would visually jump the
            // body up by a few dp.
            Spacer(Modifier.height(8.dp))
        }
        HairlineSoft()
    }
}

// ─── Body ────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TranscriptBody(
    segments: List<nl.ihnatov.transcriber.data.Segment>,
    speakerColors: Map<String, Color> = emptyMap(),
    showTimestamps: Boolean,
    proseMode: Boolean,
    removeFillers: Boolean,
    activeSegmentId: Long?,
    onEdit: (nl.ihnatov.transcriber.data.Segment, String) -> Unit,
    onSegmentSeek: (nl.ihnatov.transcriber.data.Segment) -> Unit,
) {
    val ink = MaterialTheme.colorScheme.onBackground
    if (segments.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Mono(
                "TRANSCRIPT WILL APPEAR AFTER THE FIRST RUN",
                color = ink.copy(alpha = 0.40f),
            )
        }
        return
    }
    if (proseMode) {
        ProseBody(segments, speakerColors, showTimestamps, removeFillers)
        return
    }
    var editingId by remember { mutableStateOf<Long?>(null) }
    val listState = rememberLazyListState()
    LaunchedEffect(activeSegmentId) {
        val idx = segments.indexOfFirst { it.id == activeSegmentId }
        if (idx >= 0) listState.animateScrollToItem(idx)
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        items(segments, key = { it.id }) { seg ->
            val isEditing = editingId == seg.id
            val isActive = seg.id == activeSegmentId
            Column {
                HairlineSoft()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // Active-segment highlight. 7% orange was invisible
                        // against PaperDark (the tint had nothing to lift
                        // against on a near-black background). 16% reads
                        // clearly on both surfaces without becoming a
                        // shouty bar on the cream paper.
                        .background(if (isActive) Accent.copy(alpha = 0.16f) else Color.Transparent)
                        .combinedClickable(
                            onClick = { if (!isEditing) onSegmentSeek(seg) },
                            onLongClick = { if (!isEditing) editingId = seg.id },
                        )
                        .padding(vertical = 10.dp, horizontal = 2.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    // Left: timestamp column (46 dp), mono caps.
                    Column(Modifier.width(46.dp)) {
                        Mono(
                            timestamp(seg.startSeconds),
                            color = ink.copy(alpha = 0.55f),
                            style = MaterialTheme.typography.labelSmall,
                        )
                        seg.language?.let {
                            Spacer(Modifier.height(2.dp))
                            Mono(
                                it.uppercase(),
                                color = ink.copy(alpha = 0.30f),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        seg.speaker?.let { key ->
                            val display = seg.speakerName
                                ?: key.replace("SPEAKER_", "Speaker ")
                            val tint = speakerColors[key] ?: ink.copy(alpha = 0.6f)
                            Mono(display, color = tint)
                            Spacer(Modifier.height(2.dp))
                        }
                        if (isEditing) {
                            var draft by remember(seg.id) { mutableStateOf(seg.text) }
                            OutlinedTextField(
                                value = draft,
                                onValueChange = { draft = it },
                                textStyle = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 2,
                                maxLines = 6,
                            )
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                TextButton(onClick = { editingId = null }) { Mono("CANCEL") }
                                TextButton(onClick = {
                                    onEdit(seg, draft.trim())
                                    editingId = null
                                }) { Mono("SAVE") }
                            }
                        } else {
                            val displayText = remember(seg.text, removeFillers) {
                                if (removeFillers) TextDestutter.collapseLine(seg.text) else seg.text
                            }
                            Text(
                                displayText,
                                color = ink,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProseBody(
    segments: List<nl.ihnatov.transcriber.data.Segment>,
    speakerColors: Map<String, Color>,
    showTimestamps: Boolean,
    removeFillers: Boolean,
) {
    val ink = MaterialTheme.colorScheme.onBackground
    val muted = ink.copy(alpha = 0.55f)
    val body = remember(segments, showTimestamps, removeFillers) {
        buildAnnotatedProse(segments, showTimestamps, speakerColors, muted, removeFillers)
    }
    val scroll = rememberScrollState()
    SelectionContainer {
        Text(
            text = body,
            color = ink,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(horizontal = 4.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun OutputBody(
    doc: nl.ihnatov.transcriber.data.OutputDoc,
    onDelete: () -> Unit,
    onShare: () -> Unit,
) {
    val ink = MaterialTheme.colorScheme.onBackground
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Mono(doc.title.uppercase(), color = ink)
            Spacer(Modifier.weight(1f))
            Mono(
                "SHARE",
                color = ink.copy(alpha = 0.55f),
                modifier = Modifier.clickable(onClick = onShare).padding(6.dp),
            )
            Mono(
                "DELETE",
                color = ink.copy(alpha = 0.55f),
                modifier = Modifier.clickable(onClick = onDelete).padding(6.dp),
            )
        }
        // Was missing a scroll modifier entirely — any output longer than
        // one screen (a real Minutes/Summary easily is) had no way to see
        // the rest. Caught live-testing the commonmark rewrite; unrelated
        // to it but a real, user-visible gap worth closing here rather
        // than filing away.
        val outputScroll = rememberScrollState()
        MarkdownText(
            markdown = doc.markdown,
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(outputScroll),
        )
    }
}

// ─── Player ──────────────────────────────────────────────────────────

@Composable
private fun EditorialPlayer(
    controller: AudioPlayerController,
    waveform: FloatArray?,
) {
    val isPlaying by controller.isPlaying.collectAsStateWithLifecycle()
    val positionMs by controller.positionMs.collectAsStateWithLifecycle()
    val durationMs by controller.durationMs.collectAsStateWithLifecycle()
    val ink = MaterialTheme.colorScheme.onBackground
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Mono(
            if (isPlaying) "▌▌" else "▶",
            color = ink,
            modifier = Modifier
                .clickable { controller.playPause() }
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .pointerInput(durationMs) {
                    detectTapGestures { offset ->
                        val frac = (offset.x / size.width).coerceIn(0f, 1f)
                        val totalSec = durationMs.coerceAtLeast(1L) / 1000.0
                        controller.seekToSeconds(frac.toDouble() * totalSec)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            if (waveform != null && waveform.isNotEmpty()) {
                Canvas(Modifier.fillMaxSize()) {
                    val n = waveform.size
                    val gap = 1.dp.toPx()
                    val barW = (size.width - gap * (n - 1)) / n
                    val midY = size.height / 2f
                    val maxH = size.height * 0.85f
                    val progress = if (durationMs <= 0L) 0f
                    else (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                    val progressX = size.width * progress
                    for (i in 0 until n) {
                        val h = (waveform[i] * maxH).coerceAtLeast(2f)
                        val x = i * (barW + gap) + barW / 2f
                        val color = if (x <= progressX) Accent else ink.copy(alpha = 0.30f)
                        drawLine(color, Offset(x, midY - h / 2f), Offset(x, midY + h / 2f), barW)
                    }
                }
            } else {
                // Fallback timeline while the waveform extractor is
                // still running (or if it errored). Earlier ink-alpha
                // 0.25 was nearly invisible on the dark surfaceVariant;
                // bumped to 0.55 and thickened to 3 px so the user
                // can see and tap the scrubber immediately, before
                // the waveform finishes loading.
                Canvas(Modifier.fillMaxSize()) {
                    val midY = size.height / 2f
                    val progress = if (durationMs <= 0L) 0f
                    else (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                    drawLine(
                        ink.copy(alpha = 0.55f),
                        Offset(0f, midY),
                        Offset(size.width, midY),
                        3f,
                    )
                    drawLine(
                        Accent,
                        Offset(0f, midY),
                        Offset(size.width * progress, midY),
                        3f,
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            "${formatPlayerTime(positionMs)} / ${formatPlayerTime(durationMs)}",
            color = ink.copy(alpha = 0.75f),
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = IbmPlexMono),
        )
        Spacer(Modifier.width(6.dp))
    }
}

// ─── Helpers + dialogs (carried over) ────────────────────────────────

/** Curated Super-mode pairs — see the plan's engine matrix (section 3): Parakeet+Whisper by default, Omnilingual+Gemma 4 for Arabic. */
private val SUPER_PAIR_PRESETS = listOf(
    AsrBackendKind.Parakeet to AsrBackendKind.WhisperCpp,
    AsrBackendKind.Omnilingual to AsrBackendKind.Gemma4,
)

private fun superPairLabel(pair: Pair<AsrBackendKind, AsrBackendKind>): String =
    "${runSheetEngineLabel(pair.first)} + ${runSheetEngineLabel(pair.second)}"

private fun runSheetEngineLabel(kind: AsrBackendKind): String = when (kind) {
    AsrBackendKind.Gemma4 -> "GEMMA 4"
    AsrBackendKind.WhisperCpp -> "WHISPER"
    AsrBackendKind.Parakeet -> "PARAKEET"
    AsrBackendKind.Omnilingual -> "OMNILINGUAL"
    // Streaming-only — not a file-transcription choice on this screen.
    AsrBackendKind.NemotronStream -> "NEMOTRON"
}

private fun summarizeLanguages(set: Set<String>): String {
    if (set.isEmpty()) return "Auto"
    if (set.size == 1) return when (set.first()) {
        "ar" -> "Arabic"; "uk" -> "Ukrainian"; "en" -> "English"; "nl" -> "Dutch"
        else -> set.first()
    }
    return "Auto · " + set.joinToString("/")
}

@Composable
private fun LanguagesDialog(
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
        title = { Mono("SOURCE LANGUAGES", color = MaterialTheme.colorScheme.onBackground) },
        text = {
            Column {
                Text(
                    "Leave all unchecked for full auto-detect. Tick two or three " +
                        "to constrain the model.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.62f),
                )
                Spacer(Modifier.height(12.dp))
                options.forEach { (code, label) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable {
                            picks = if (code in picks) picks - code else picks + code
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = code in picks,
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

@Composable
private fun VersionHistorySheet(
    versions: List<nl.ihnatov.transcriber.data.TranscriptVersion>,
    onRestore: (Long) -> Unit,
    onDelete: (Long) -> Unit,
) {
    val ink = MaterialTheme.colorScheme.onBackground
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Mono("HISTORY", color = ink)
        Spacer(Modifier.height(4.dp))
        Text(
            "Past transcript versions, saved automatically before each re-run or restore.",
            style = MaterialTheme.typography.bodySmall,
            color = ink.copy(alpha = 0.62f),
        )
        Spacer(Modifier.height(12.dp))
        if (versions.isEmpty()) {
            Text(
                "No saved versions yet.",
                style = MaterialTheme.typography.bodySmall,
                color = ink.copy(alpha = 0.55f),
            )
            Spacer(Modifier.height(16.dp))
        } else {
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                items(versions, key = { it.id }) { version ->
                    VersionRow(
                        version = version,
                        onRestore = { onRestore(version.id) },
                        onDelete = { onDelete(version.id) },
                    )
                    HairlineSoft()
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun VersionRow(
    version: nl.ihnatov.transcriber.data.TranscriptVersion,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    val ink = MaterialTheme.colorScheme.onBackground
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Mono(version.engineLabel.uppercase(), color = ink)
            Spacer(Modifier.height(2.dp))
            Text(
                "${formatStampMono(version.createdAtMillis)} · ${version.segmentCount} turns",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = IbmPlexMono),
                color = ink.copy(alpha = 0.55f),
            )
        }
        Mono(
            "RESTORE",
            color = Accent,
            modifier = Modifier.clickable(onClick = onRestore).padding(6.dp),
        )
        Mono(
            "DELETE",
            color = ink.copy(alpha = 0.55f),
            modifier = Modifier.clickable(onClick = onDelete).padding(6.dp),
        )
    }
}

private fun timestamp(seconds: Double): String {
    val s = seconds.toInt()
    return "%02d:%02d".format(s / 60, s % 60)
}

private fun formatStampMono(millis: Long): String {
    val fmt = java.text.SimpleDateFormat("yyyy·MM·dd · HH:mm", java.util.Locale.US)
    return fmt.format(java.util.Date(millis))
}

private fun formatDurationMono(seconds: Int): String {
    val s = seconds.coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    else "%d:%02d".format(s / 60, s % 60)
}

private fun formatPlayerTime(ms: Long): String {
    val total = (ms / 1000L).coerceAtLeast(0L)
    return "%d:%02d".format(total / 60L, total % 60L)
}

private fun speakerColor(index: Int): Color = speakerPalette[index % speakerPalette.size]

/**
 * Speaker palette tuned for legibility on BOTH paper (light) and
 * dark-warm backgrounds. The 8th entry was previously `#4F4F4F`
 * graphite, which sat at near-zero contrast against `PaperDark`
 * (`#16130F`) — the speaker chip and segment label became invisible
 * in dark mode. Replaced with a warm tan that reads against both
 * the cream paper and the dark warm.
 */
private val speakerPalette = listOf(
    Color(0xFFFF4726), // accent orange
    Color(0xFF2BA85F), // forest green (brightened from #1F8A4C for dark-mode legibility)
    Color(0xFF5A78FF), // blue (brightened from #335CFF)
    Color(0xFFD49A3E), // amber (brightened from #B8852E)
    Color(0xFFB058D2), // purple (brightened from #8B3CB5)
    Color(0xFF2BA8AE), // teal (brightened from #1F8A8F)
    Color(0xFFE34C84), // rose (brightened from #C72561)
    Color(0xFFC9B68C), // warm tan — dark-mode-safe replacement for graphite
)

private sealed interface DocTab {
    data object Transcript : DocTab
    data class Output(val doc: nl.ihnatov.transcriber.data.OutputDoc) : DocTab
}

private fun buildAnnotatedProse(
    segments: List<nl.ihnatov.transcriber.data.Segment>,
    showTimestamps: Boolean,
    speakerColors: Map<String, Color>,
    mutedColor: Color,
    removeFillers: Boolean,
): AnnotatedString {
    val builder = AnnotatedString.Builder()
    var lastSpeakerKey: String? = "__init__"
    var anyEmitted = false
    for ((index, seg) in segments.withIndex()) {
        val speakerKey = seg.speaker
        val speakerLabel = seg.speakerName ?: speakerKey?.replace("SPEAKER_", "Speaker ")
        val turnChanged = speakerKey != null && speakerKey != lastSpeakerKey
        if (turnChanged) {
            if (anyEmitted) builder.append("\n\n")
            if (!speakerLabel.isNullOrBlank()) {
                val labelColor = speakerKey?.let { speakerColors[it] } ?: mutedColor
                builder.pushStyle(
                    SpanStyle(color = labelColor, fontWeight = FontWeight.SemiBold),
                )
                builder.append(speakerLabel)
                if (showTimestamps) builder.append("  ${timestamp(seg.startSeconds)}")
                builder.pop()
                builder.append("\n")
            }
            lastSpeakerKey = speakerKey
        } else if (!anyEmitted && showTimestamps && speakerKey == null) {
            builder.pushStyle(SpanStyle(color = mutedColor, fontFamily = IbmPlexMono))
            builder.append(timestamp(seg.startSeconds))
            builder.pop()
            builder.append("\n")
        } else if (anyEmitted) {
            builder.append(" ")
        }
        val trimmed = seg.text.trim()
        val body = if (removeFillers) TextDestutter.collapseLine(trimmed) else trimmed
        if (body.isNotEmpty()) {
            builder.append(body)
            anyEmitted = true
        }
    }
    return builder.toAnnotatedString()
}

@Composable
private fun RenameSpeakerDialog(
    target: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String?) -> Unit,
) {
    var draft by remember(target) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.background,
        title = { Mono("RENAME ${target.replace("SPEAKER_", "SPEAKER ")}", color = MaterialTheme.colorScheme.onBackground) },
        text = {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text("Display name") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(draft.ifBlank { null }) }) { Mono("SAVE") }
        },
        dismissButton = {
            TextButton(onClick = { onConfirm(null) }) { Mono("CLEAR") }
        },
    )
}
