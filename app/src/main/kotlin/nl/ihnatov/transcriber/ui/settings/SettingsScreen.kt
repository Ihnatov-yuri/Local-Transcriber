package nl.ihnatov.transcriber.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
// Card / CardDefaults intentionally not imported — every Settings
// section uses SettingsSection (plain Column + ledger hairlines)
// rather than M3 cards per the editorial design spec §5.6.
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import java.io.File
import nl.ihnatov.transcriber.asr.CatalogEntry
import nl.ihnatov.transcriber.asr.GemmaBackendChoice
import nl.ihnatov.transcriber.asr.GemmaSettingsStore
import nl.ihnatov.transcriber.asr.PromptStore
import nl.ihnatov.transcriber.audio.BatteryOptimization
import nl.ihnatov.transcriber.data.AppContainer

@Composable
fun SettingsScreen(container: AppContainer) {
    val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container))
    val state by vm.state.collectAsStateWithLifecycle()

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) vm.import(uri) }

    // Pending deletion target. Set when the user taps the trash icon on a
    // model row; cleared when they confirm or cancel via the dialog.
    var deleteCandidate by remember { mutableStateOf<File?>(null) }

    // One vertical scroller. Spacing between top-level items is 8dp; cards
    // within a section live closer together (8dp) and sections are separated
    // by SectionHeader's own top padding. This gives a visible rhythm without
    // the old soup-of-cards feel.
    // Reset the section-letter counter so each pass through this screen
    // numbers from A again (SectionHeader auto-advances).
    sectionCounter = 0
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Editorial top chrome — BrandStrip-less here because this is
        // the root of a tab. The 04 / SETTINGS index does the job of
        // anchoring the page.
        nl.ihnatov.transcriber.ui.components.BrandStrip(
            right = {
                nl.ihnatov.transcriber.ui.components.Mono(
                    "ON-DEVICE",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.40f),
                    style = MaterialTheme.typography.labelSmall,
                )
            },
        )
        Spacer(Modifier.height(8.dp))
        nl.ihnatov.transcriber.ui.components.InkRule()
        Spacer(Modifier.height(14.dp))
        nl.ihnatov.transcriber.ui.components.SectionIndex(
            n = 4,
            label = "settings",
            summary = "Engines, prompts, vocabulary, and the rules nothing leaves the phone by.",
        )
        Spacer(Modifier.height(6.dp))

        // ── RELIABILITY ───────────────────────────────────────────────
        SectionHeader("Reliability")
        BatteryOptimizationCard()

        // ── MODELS ────────────────────────────────────────────────────
        SectionHeader("Models")
        DownloadCard(
            catalog = state.catalog,
            downloads = state.downloads,
            installedFiles = state.installedFiles,
            onDownload = vm::download,
            onCancel = vm::cancelDownload,
        )
        InstalledModelsCard(
            installedFiles = state.installedFiles,
            factory = container.asrFactory,
            importing = state.importing,
            importError = state.importError,
            onImport = { importLauncher.launch(arrayOf("*/*")) },
            onDelete = { deleteCandidate = it },
        )
        EmbeddingPickerCard(
            diarizationRunner = container.diarizationRunner,
            uiPrefs = vm.uiPrefs,
        )
        DiarizationTuningCard(uiPrefs = vm.uiPrefs)

        // ── GEMMA 4 ───────────────────────────────────────────────────
        SectionHeader("Gemma 4")
        GemmaComputeCard(settings = vm.gemmaSettings)
        GemmaPromptsCard(promptStore = vm.promptStore)

        // ── POST-PROCESSING ───────────────────────────────────────────
        SectionHeader("Post-processing")
        PostPresetsCard(presetStore = vm.presetStore)
        SnippetsCard(snippetStore = vm.snippetStore)

        // ── STYLE & VOCABULARY ────────────────────────────────────────
        SectionHeader("Style & vocabulary")
        DomainVocabCard(promptStore = vm.promptStore, uiPrefs = vm.uiPrefs)
        StyleAndVocabCard(promptStore = vm.promptStore)

        // Footer — was an "Engines" card with two bullet points of value.
        // Replaced with a single subdued line. The architecture detail is in
        // README; Settings shouldn't host build-time trivia.
        Text(
            "Whisper.cpp · Gemma 4 (LiteRT-LM). Apache-2.0.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
        )
    }

    deleteCandidate?.let { f ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text("Delete model?") },
            text = {
                Text(
                    "${f.name} (${fileOrDirSizeBytes(f) / 1024 / 1024} MB) will be removed " +
                        "from this device. You can re-download it later from this " +
                        "screen.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteFile(f)
                    deleteCandidate = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun DownloadRow(
    entry: CatalogEntry,
    status: SettingsViewModel.DownloadStatus,
    alreadyInstalled: Boolean,
    /** Installed copy is older than the catalog's required-after threshold. */
    needsUpdate: Boolean = false,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                // Name + (optional) Recommended pill on one baseline. Using a
                // small Surface-based pill instead of an AssistChip — the
                // AssistChip's intrinsic min-height (32dp) was bigger than the
                // text, so it pushed the row's vertical centre off and made
                // the right-edge button look misaligned. A flat tinted pill
                // matches the title's text height pixel-for-pixel.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        entry.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (entry.recommended) FontWeight.Medium else FontWeight.Normal,
                    )
                    if (entry.recommended) {
                        Spacer(Modifier.width(6.dp))
                        // Small (R) monogram badge. The old "Recommended" pill
                        // dominated the row visually; a small (R) reads as a
                        // recommendation marker without competing with the
                        // model name for attention. Tooltip on long-press
                        // would be nice as a follow-up.
                        RecommendedBadge()
                    }
                }
                Text(
                    "${entry.sizeMb} MB · ${entry.description}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                )
            }
            // Action area on the right
            when {
                status is SettingsViewModel.DownloadStatus.Running ||
                    status is SettingsViewModel.DownloadStatus.Extracting -> {
                    // Cancel during Extracting is best-effort: the archive
                    // reader only checks for cancellation between tar
                    // entries, not mid-copy of one (there are just 2-3
                    // entries per model), so tapping cancel here can take
                    // a little longer to land than during Running.
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Outlined.Close, contentDescription = "Cancel")
                    }
                }
                (alreadyInstalled || status is SettingsViewModel.DownloadStatus.Done) && needsUpdate -> {
                    // Re-download path. Looks like a regular Get button (so
                    // the affordance is obvious) but tinted "tonal" rather
                    // than primary so it doesn't out-shout an unrelated
                    // primary action elsewhere on the screen.
                    Button(
                        onClick = onDownload,
                        colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(),
                    ) {
                        Icon(Icons.Outlined.Download, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Update")
                    }
                }
                alreadyInstalled || status is SettingsViewModel.DownloadStatus.Done -> {
                    Icon(
                        Icons.Outlined.CheckCircle,
                        contentDescription = "Installed",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                else -> {
                    Button(onClick = onDownload) {
                        Icon(Icons.Outlined.Download, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Get")
                    }
                }
            }
        }
        // Update reason — only visible when the local copy is older than
        // the catalog's cutoff (e.g. Gemma 4 pre-MTP). Re-downloading
        // overwrites in place via the partial→rename path in AsrFactory,
        // so the existing model selection survives.
        if (needsUpdate && entry.updateReason != null &&
            status !is SettingsViewModel.DownloadStatus.Running &&
            status !is SettingsViewModel.DownloadStatus.Extracting
        ) {
            Text(
                entry.updateReason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        // Progress / error line
        when (status) {
            is SettingsViewModel.DownloadStatus.Running -> {
                val total = status.totalBytes
                val frac = if (total != null && total > 0) status.bytesRead.toFloat() / total else null
                val pct = if (frac != null) "${(frac * 100).toInt()}%" else "${status.bytesRead / 1024 / 1024} MB"
                Text("Downloading… $pct", style = MaterialTheme.typography.bodySmall)
                if (frac != null) {
                    LinearProgressIndicator(progress = { frac.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            SettingsViewModel.DownloadStatus.Extracting -> {
                Text("Extracting…", style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            is SettingsViewModel.DownloadStatus.Failed -> {
                Text(
                    "Failed: ${status.reason}",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            else -> Unit
        }
    }
}

/**
 * Compact (R) badge marking a recommended model. Sized to sit on the body-
 * text baseline so it doesn't tug the row off-centre the way the old
 * AssistChip-based pill did. Filled primary so it reads as a positive
 * recommendation without shouting.
 */
@Composable
private fun RecommendedBadge() {
    Surface(
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = RoundedCornerShape(50),
    ) {
        Text(
            "R",
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
            ),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

/**
 * Small status pill rendered with the same height as adjacent body text.
 * Used for "Recommended", "Installed", etc. Replaces ad-hoc AssistChips,
 * which look mis-centred next to inline text because the chip's min-height
 * is larger than the text's intrinsic height.
 */
@Composable
private fun Pill(
    text: String,
    tonal: Boolean = true,
) {
    val bg = if (tonal) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (tonal) MaterialTheme.colorScheme.onSecondaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        color = bg,
        contentColor = fg,
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/**
 * Section divider for the Settings vertical scroll. Small uppercase label
 * with subdued colour and ~16dp top padding so cards in the previous
 * section get visual breathing room. Cheaper than wrapping each section in
 * a Card-with-header (which nests containers and adds visual clutter).
 */
@Composable
private fun SectionHeader(text: String) {
    // Editorial "A · RELIABILITY" pattern — running letter index plus
    // mono-caps label. Letter cycles A..E by call order via a Compose-
    // local counter held in a remember; resets on each composition pass
    // so the order stays stable across rebuilds.
    val letter = nextSectionLetter()
    Column(
        modifier = Modifier.padding(top = 18.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        nl.ihnatov.transcriber.ui.components.InkRule()
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            nl.ihnatov.transcriber.ui.components.Mono(
                letter,
                color = nl.ihnatov.transcriber.ui.theme.Accent,
            )
            Text(
                " · ",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f),
            )
            nl.ihnatov.transcriber.ui.components.Mono(
                text,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
    }
}

private var sectionCounter = 0

@Composable
private fun nextSectionLetter(): String {
    // Section letters reset at the top of each Settings composition pass.
    // The first call returns A, second B, etc. — call order in the file
    // is the source of truth.
    val letter = ('A' + (sectionCounter % 26)).toString()
    sectionCounter = (sectionCounter + 1) % 26
    return letter
}

/**
 * Wraps the "download a model" rows in a Card. Pulled out of the main
 * Column so SettingsScreen reads as a list of named sections rather than
 * a sequence of inline cards.
 */
@Composable
private fun DownloadCard(
    catalog: List<CatalogEntry>,
    downloads: Map<String, SettingsViewModel.DownloadStatus>,
    installedFiles: List<File>,
    onDownload: (CatalogEntry) -> Unit,
    onCancel: (CatalogEntry) -> Unit,
) {
    SettingsSection(title = "DOWNLOAD", subtitle = "Pulled from HuggingFace, stored privately.") {
        catalog.forEach { entry ->
            val status = downloads[entry.id] ?: SettingsViewModel.DownloadStatus.Idle
            val installedFile = installedFiles.firstOrNull { it.name == entry.filename }
            // "Stale" = file exists locally but predates the catalog
            // entry's required-after threshold. Used to surface the MTP
            // re-download prompt for Gemma 4 weights pulled before the
            // LiteRT-LM v0.11.0 release date.
            val stale = installedFile != null &&
                entry.requiredAfterMillis != null &&
                installedFile.lastModified() < entry.requiredAfterMillis
            DownloadRow(
                entry = entry,
                status = status,
                alreadyInstalled = installedFile != null,
                needsUpdate = stale,
                onDownload = { onDownload(entry) },
                onCancel = { onCancel(entry) },
            )
        }
    }
}

/** Installed-files card with import button. Symmetric with DownloadCard. */
@Composable
private fun InstalledModelsCard(
    installedFiles: List<File>,
    factory: nl.ihnatov.transcriber.asr.AsrFactory,
    importing: Boolean,
    importError: String?,
    onImport: () -> Unit,
    onDelete: (File) -> Unit,
) {
    // Bumped whenever the user picks a different active model, to force
    // recomposition so the radio dots and resolveModel() reflect the
    // new selection immediately.
    var selectionTick by remember { mutableIntStateOf(0) }
    SettingsSection(title = "INSTALLED") {
        if (installedFiles.isEmpty()) {
            Text(
                "Nothing installed yet. Download one above or import a custom file.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        } else {
            // Group by backend so we can offer an active-model radio when
            // a backend has more than one model installed (e.g. Gemma
            // E2B + E4B, or two Whisper sizes). With a single model per
            // backend there's nothing to choose, so no radio is shown.
            val byKind = remember(installedFiles, selectionTick) {
                installedFiles.groupBy { factory.kindForFile(it) ?: factory.kindForDirectory(it) }
            }
            installedFiles.forEach { f ->
                val kind = factory.kindForFile(f) ?: factory.kindForDirectory(f)
                val groupSize = kind?.let { byKind[it]?.size } ?: 1
                val selectable = kind != null && groupSize > 1
                // resolveModel reflects the current pin (or biggest-first
                // default). Recomputed each composition; selectionTick
                // forces it after a tap.
                val active = selectable &&
                    factory.resolveModel(kind!!)?.name == f.name
                ModelRow(
                    file = f,
                    showRadio = selectable,
                    active = active,
                    onSelect = if (selectable) {
                        {
                            factory.setSelectedModel(kind!!, f.name)
                            selectionTick++
                        }
                    } else null,
                    onDelete = { onDelete(f) },
                )
            }
        }
        if (importing) {
            Text("Importing model — please wait…", style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            OutlinedButton(
                onClick = onImport,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 12.dp, horizontal = 16.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp),
            ) {
                Icon(Icons.Outlined.FileOpen, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Import custom model file")
            }
        }
        importError?.let {
            Text(it, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * Lets the user pick which speaker-embedding model is active when
 * multiple are installed. WeSpeaker (90 MB) gives the lowest EER on
 * VoxCeleb but takes 3–5 min to cluster a 30-min meeting up-front;
 * CAM++ (28 MB) clusters the same meeting in ~1 min with a modest
 * accuracy hit. Shown only when ≥2 embedding files exist on disk —
 * if there's nothing to pick between, the section is invisible.
 */
@Composable
private fun EmbeddingPickerCard(
    diarizationRunner: nl.ihnatov.transcriber.asr.DiarizationRunner,
    uiPrefs: nl.ihnatov.transcriber.asr.UiPrefs,
) {
    val installed = remember { diarizationRunner.installedEmbeddingFilenames() }
    if (installed.size < 2) return
    val preferred by uiPrefs.preferredEmbedding.collectAsStateWithLifecycle()
    val ink = MaterialTheme.colorScheme.onSurface
    SettingsSection(
        title = "ACTIVE EMBEDDING MODEL",
        subtitle = "Used for hybrid speaker diarization. Smaller models cluster faster, larger ones discriminate voices better. Up-front clustering is what makes the first transcribed chunk arrive late on long meetings.",
    ) {
        installed.forEach { name ->
            val isActive = (preferred ?: installed.first()) == name
            val display = diarizationRunner.displayNameFor(name) ?: name
            val sizeHint = when (name) {
                "embedding-wespeaker.onnx" -> "~90 MB · best EER · slow up-front"
                "embedding-multilingual.onnx" -> "~28 MB · multilingual zh+en · fast"
                "embedding.onnx" -> "~28 MB · English baseline · fast"
                else -> "—"
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        // Setting null when the user picks the
                        // top-priority entry keeps SharedPreferences
                        // clean — the cascade naturally produces the
                        // same choice.
                        uiPrefs.setPreferredEmbedding(if (name == installed.first()) null else name)
                    }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                nl.ihnatov.transcriber.ui.components.Mono(
                    if (isActive) "●" else "○",
                    color = if (isActive) nl.ihnatov.transcriber.ui.theme.Accent else ink.copy(alpha = 0.45f),
                    modifier = Modifier.padding(end = 10.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        display,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    Text(
                        sizeHint,
                        style = MaterialTheme.typography.labelSmall,
                        color = ink.copy(alpha = 0.55f),
                    )
                }
            }
            nl.ihnatov.transcriber.ui.components.HairlineSoft()
        }
    }
}

/**
 * Diarization tuning knobs added for the 2026-09 global-clustering rewrite:
 * clustering threshold, turn-coalescing gap, segment-boundary sensitivity.
 * All three are nullable in [UiPrefs] — "Auto" clears the override and
 * falls back to [nl.ihnatov.transcriber.asr.DiarizationRunner]'s built-in
 * defaults (language-aware for threshold).
 */
@Composable
private fun DiarizationTuningCard(uiPrefs: nl.ihnatov.transcriber.asr.UiPrefs) {
    val threshold by uiPrefs.clusterThreshold.collectAsStateWithLifecycle()
    val gap by uiPrefs.turnCoalesceGapSec.collectAsStateWithLifecycle()
    val minOn by uiPrefs.minDurationOnSec.collectAsStateWithLifecycle()

    SettingsSection(
        title = "DIARIZATION TUNING",
        subtitle = "How aggressively speaker clustering splits or merges voices, and how chunk-sized turns get stitched back into one continuous conversation.",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "Clustering threshold",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                "Higher merges more aggressively (fewer speakers); lower " +
                    "splits more. Auto uses 0.5 for English-only, 0.7 " +
                    "otherwise — non-English speech tends to over-split at 0.5.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                AssistChip(onClick = { uiPrefs.setClusterThreshold(null) }, label = { Text("Auto") }, enabled = threshold != null)
                listOf(0.5f, 0.6f, 0.7f, 0.8f).forEach { t ->
                    AssistChip(
                        onClick = { uiPrefs.setClusterThreshold(t) },
                        label = { Text("%.1f".format(t)) },
                        enabled = threshold != t,
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "Speaker turns",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                "Same-speaker segments closer together than this merge into " +
                    "one turn — the chunk boundaries used during transcription " +
                    "aren't conversational structure. 30s reads as smooth " +
                    "blocks; 2s keeps fine, Samsung-style turns.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                listOf(2f, 10f, 30f, 60f).forEach { g ->
                    AssistChip(
                        onClick = { uiPrefs.setTurnCoalesceGapSec(g) },
                        label = { Text(if (g == nl.ihnatov.transcriber.asr.DEFAULT_TURN_COALESCE_GAP_SEC.toFloat()) "${g.toInt()}s (default)" else "${g.toInt()}s") },
                        enabled = (gap ?: nl.ihnatov.transcriber.asr.DEFAULT_TURN_COALESCE_GAP_SEC.toFloat()) != g,
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "Segment sensitivity",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                "How short a voiced/silent stretch can be before the " +
                    "segmentation model still calls it a real speaker turn. " +
                    "Fine catches quick back-and-forth; Coarse ignores short interjections.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                AssistChip(
                    onClick = { uiPrefs.setMinDurationOnSec(null); uiPrefs.setMinDurationOffSec(null) },
                    label = { Text("Default") },
                    enabled = minOn != null,
                )
                AssistChip(
                    onClick = { uiPrefs.setMinDurationOnSec(0.1f); uiPrefs.setMinDurationOffSec(0.3f) },
                    label = { Text("Fine") },
                    enabled = minOn != 0.1f,
                )
                AssistChip(
                    onClick = { uiPrefs.setMinDurationOnSec(0.3f); uiPrefs.setMinDurationOffSec(0.8f) },
                    label = { Text("Coarse") },
                    enabled = minOn != 0.3f,
                )
            }
        }
    }
}

@Composable
private fun ModelRow(
    file: File,
    onDelete: () -> Unit,
    showRadio: Boolean = false,
    active: Boolean = false,
    onSelect: (() -> Unit)? = null,
) {
    val ink = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onSelect != null) Modifier.clickable(onClick = onSelect) else Modifier)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Active-model radio. Only shown when the backend has more than
        // one model installed (e.g. Gemma E2B + E4B). The filled dot is
        // the one transcription will actually use; tap a row to switch.
        if (showRadio) {
            nl.ihnatov.transcriber.ui.components.Mono(
                if (active) "●" else "○",
                color = if (active) nl.ihnatov.transcriber.ui.theme.Accent else ink.copy(alpha = 0.45f),
                modifier = Modifier.padding(end = 10.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(file.name, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
            Text(
                buildString {
                    append("%d MB".format(fileOrDirSizeBytes(file) / 1024 / 1024))
                    if (showRadio && active) append(" · active")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Outlined.Delete, contentDescription = "Delete ${file.name}")
        }
    }
}

/**
 * Wispr-style Snippets: named text fragments the user can reference in their
 * custom preset prompts via `{snippet:name}` (e.g. `{snippet:signoff}`).
 *
 * UI keeps it simple — a list of name/body rows, an "Add snippet" button,
 * delete per row. No live preview; the value is in writing it once and
 * having it expand everywhere.
 */
@Composable
private fun SnippetsCard(snippetStore: nl.ihnatov.transcriber.asr.SnippetStore) {
    val snippets by snippetStore.snippets.collectAsStateWithLifecycle()
    var draftName by remember { mutableStateOf("") }
    var draftBody by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }

    SettingsSection(
        title = "SNIPPETS",
        subtitle = "Reusable text blocks. Reference one as {snippet:name} in a custom preset.",
    ) {
        if (snippets.isEmpty() && !adding) {
                Text(
                    "No snippets yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
            }

            snippets.forEach { s ->
                SnippetRow(
                    snippet = s,
                    onSave = { snippetStore.upsert(it) },
                    onDelete = { snippetStore.delete(s.name) },
                )
            }

            if (adding) {
                OutlinedTextField(
                    value = draftName,
                    onValueChange = { draftName = it.replace("\\s".toRegex(), "_") },
                    label = { Text("Name (e.g. signoff)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = draftBody,
                    onValueChange = { draftBody = it },
                    label = { Text("Body") },
                    minLines = 2,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = {
                        adding = false
                        draftName = ""; draftBody = ""
                    }) { Text("Cancel") }
                    TextButton(
                        onClick = {
                            snippetStore.upsert(
                                nl.ihnatov.transcriber.asr.Snippet(
                                    name = draftName.trim(),
                                    body = draftBody,
                                )
                            )
                            adding = false
                            draftName = ""; draftBody = ""
                        },
                        enabled = draftName.isNotBlank() && draftBody.isNotBlank(),
                    ) { Text("Save") }
                }
        } else {
            OutlinedButton(
                onClick = { adding = true },
                modifier = Modifier.fillMaxWidth(),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp),
            ) {
                Text("Add snippet")
            }
        }
    }
}

@Composable
private fun SnippetRow(
    snippet: nl.ihnatov.transcriber.asr.Snippet,
    onSave: (nl.ihnatov.transcriber.asr.Snippet) -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var draft by remember(snippet) { mutableStateOf(snippet.body) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "{snippet:${snippet.name}}",
                modifier = Modifier.weight(1f),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Hide" else "Edit")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, contentDescription = "Delete snippet")
            }
        }
        if (!expanded) {
            Text(
                snippet.body.lineSequence().firstOrNull().orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                maxLines = 1,
            )
        } else {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 8,
                textStyle = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = {
                    onSave(snippet.copy(body = draft))
                    expanded = false
                }) { Text("Save") }
            }
        }
    }
}

/**
 * Editable post-processing presets. Each preset is a saved prompt that runs
 * over an existing transcript (not over audio). Settings shows one expandable
 * row per preset: tap the title to expand the prompt editors, edit the
 * `system` + `user` templates, save or reset.
 *
 * The prompts use `{language_hint}`, `{transcript}`, and `{vocabulary}`
 * placeholders that [nl.ihnatov.transcriber.asr.PostProcessor] substitutes
 * at call time.
 */
@Composable
private fun PostPresetsCard(presetStore: nl.ihnatov.transcriber.asr.PresetStore) {
    val presets by presetStore.presets.collectAsStateWithLifecycle()
    SettingsSection(
        title = "PRESETS",
        subtitle = "Run on a transcript via the Detail screen chips. Tap to edit or reset.",
    ) {
        presets.forEach { preset ->
            PresetRow(preset = preset, onSave = presetStore::save, onReset = { presetStore.reset(preset.id) })
        }
    }
}

@Composable
private fun PresetRow(
    preset: nl.ihnatov.transcriber.asr.PostProcessingPreset,
    onSave: (nl.ihnatov.transcriber.asr.PostProcessingPreset) -> Unit,
    onReset: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(preset.displayName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(
                    preset.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Hide" else "Edit")
            }
        }
        if (expanded) {
            Text("System instruction", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
            OutlinedTextField(
                value = preset.systemTemplate,
                onValueChange = { onSave(preset.copy(systemTemplate = it)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 4,
                maxLines = 14,
                textStyle = MaterialTheme.typography.bodySmall,
            )
            Text("User message template", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
            OutlinedTextField(
                value = preset.userTemplate,
                onValueChange = { onSave(preset.copy(userTemplate = it)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 6,
                textStyle = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onReset) {
                    Icon(Icons.Outlined.RestartAlt, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Reset to default")
                }
            }
        }
    }
}

/**
 * Wispr Flow-inspired knobs that mix into the Gemma 4 prompt:
 *
 *   - **Custom vocabulary** — comma- or newline-separated list of proper nouns
 *     the model should preserve verbatim. Improves accuracy on names and
 *     domain jargon dramatically.
 *   - **Remove fillers** — strips "um", "uh", "like", etc. from the transcript.
 *   - **Verbatim mode** — disables auto punctuation / casing for raw output.
 *
 * Settings here apply globally to every Gemma run (both file and live). For
 * per-recording overrides we'd extend the Detail screen later.
 */
/**
 * One-tap domain vocabulary install. Each chip appends a curated list of
 * domain-specific terms (drug INNs, framework names, legal Latin, etc.)
 * to the user's [PromptStore.vocabulary], which then gets inlined into
 * Gemma's system prompt as a "spell these correctly when heard" hint.
 *
 * Languages: uses the user's current [UiPrefs.lastLanguages] picks. Empty
 * picks = English only (which is also a sensible default for domain
 * jargon since most professional terminology code-switches to English in
 * AR/UK/NL conversations anyway — the research that fed [DomainVocabulary]
 * confirms this).
 */
@Composable
private fun DomainVocabCard(
    promptStore: PromptStore,
    uiPrefs: nl.ihnatov.transcriber.asr.UiPrefs,
) {
    // The vocab section has its OWN persistent language set, distinct
    // from the per-transcription `lastLanguages`. Lets the user curate
    // EN+AR vocabulary while their next recording is English-only.
    val languages by uiPrefs.vocabLanguages.collectAsStateWithLifecycle()
    val vocab by promptStore.vocabulary.collectAsStateWithLifecycle()
    val pendingByDomain = remember(vocab, languages) {
        nl.ihnatov.transcriber.asr.DomainVocabulary.Domain.entries
            .associateWith { domain ->
                nl.ihnatov.transcriber.asr.DomainVocabulary.pendingCount(
                    promptStore, domain, languages,
                )
            }
    }
    var sourcesOpen by remember { mutableStateOf<nl.ihnatov.transcriber.asr.DomainVocabulary.Domain?>(null) }
    var langPickerOpen by remember { mutableStateOf(false) }

    SettingsSection(
        title = "QUICK-FILL VOCABULARY",
        subtitle = "One tap adds 30–80 domain-specific terms (drug names, frameworks, Latin legal phrases). Biases Gemma away from common-word homophones.",
    ) {
        // Active-languages summary. The pack always includes English on
        // top of any extra languages. Tapping the row opens a picker so
        // the user can toggle which language variants of each pack get
        // installed.
        val langSummary = when {
            languages.isEmpty() -> "English only"
            languages == setOf("en") -> "English"
            "en" in languages -> "English + " + (languages - "en").joinToString(", ")
            else -> "English + " + languages.joinToString(", ")
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { langPickerOpen = true }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Languages: $langSummary",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
                modifier = Modifier.weight(1f),
            )
            nl.ihnatov.transcriber.ui.components.Mono(
                "EDIT ↗",
                color = nl.ihnatov.transcriber.ui.theme.Accent,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (langPickerOpen) {
            VocabLanguagesDialog(
                initial = languages,
                onDismiss = { langPickerOpen = false },
                onConfirm = {
                    uiPrefs.setVocabLanguages(it)
                    langPickerOpen = false
                },
            )
        }
        // Editorial ledger. One row per domain: name on the left,
        // status (`+ N TERMS` or `INSTALLED`) in the middle, `?` info
        // chip on the right. Tap row = install the pending terms;
        // tap `?` = open sources dialog. Replaces the previous
        // double-FlowRow of AssistChips + "X sources" text buttons,
        // which had every domain name printed twice and bled orange
        // accent across the screen.
        nl.ihnatov.transcriber.ui.components.HairlineSoft()
        nl.ihnatov.transcriber.asr.DomainVocabulary.Domain.entries.forEach { domain ->
            val pending = pendingByDomain[domain] ?: 0
            val installed = pending == 0
            DomainVocabRow(
                title = domain.displayName,
                installed = installed,
                pending = pending,
                onToggle = {
                    if (installed) {
                        nl.ihnatov.transcriber.asr.DomainVocabulary.remove(
                            promptStore, domain, languages,
                        )
                    } else {
                        nl.ihnatov.transcriber.asr.DomainVocabulary.apply(
                            promptStore, domain, languages,
                        )
                    }
                },
                onSources = { sourcesOpen = domain },
            )
            nl.ihnatov.transcriber.ui.components.HairlineSoft()
        }
        sourcesOpen?.let { d ->
            val pack = nl.ihnatov.transcriber.asr.DomainVocabulary.packs[d]
            AlertDialog(
                onDismissRequest = { sourcesOpen = null },
                title = { Text("${d.displayName} — sources") },
                text = { Text(pack?.sourceCredit ?: "—") },
                confirmButton = {
                    TextButton(onClick = { sourcesOpen = null }) { Text("OK") }
                },
            )
        }
    }
}

/**
 * Multi-select dialog for which language variants of each domain pack
 * should be installed. Lives separately from the per-transcription
 * language picker so vocab curation isn't scoped to one recording.
 *
 * English is always included as the base — domain jargon code-switches
 * to English in AR/UK/NL conversations (per the research baked into
 * DomainVocabulary). Toggling EN off in this dialog just means the
 * pack will skip the English term list, but English-only packs will
 * still install (kept as a sensible floor; otherwise an empty set
 * would silently install nothing).
 */
@Composable
private fun VocabLanguagesDialog(
    initial: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    val options = listOf(
        "en" to "English",
        "ar" to "Arabic",
        "uk" to "Ukrainian",
        "nl" to "Dutch",
    )
    var selected by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Vocabulary languages") },
        text = {
            Column {
                Text(
                    "Which language variants to install for each domain pack. " +
                        "Domain jargon code-switches to English; selecting Arabic " +
                        "or Ukrainian adds the localized terms on top.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                Spacer(Modifier.height(8.dp))
                options.forEach { (code, label) ->
                    val isChecked = code in selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selected = if (isChecked) selected - code else selected + code
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = isChecked, onCheckedChange = null)
                        Spacer(Modifier.width(8.dp))
                        Text(label)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun DomainVocabRow(
    title: String,
    installed: Boolean,
    pending: Int,
    onToggle: () -> Unit,
    onSources: () -> Unit,
) {
    val ink = MaterialTheme.colorScheme.onSurface
    // Whole row is clickable in both states: tap an uninstalled row to
    // ADD the pack, tap an installed row to REMOVE it. The right-side
    // status label doubles as the action affordance — `+ N TERMS` /
    // `REMOVE` — so the user always knows what tapping does.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        nl.ihnatov.transcriber.ui.components.Mono(
            title.uppercase(),
            color = ink,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        nl.ihnatov.transcriber.ui.components.Mono(
            if (installed) "REMOVE" else "+ $pending TERMS",
            color = if (installed) ink.copy(alpha = 0.55f) else nl.ihnatov.transcriber.ui.theme.Accent,
        )
        Spacer(Modifier.width(12.dp))
        nl.ihnatov.transcriber.ui.components.Mono(
            "?",
            color = ink.copy(alpha = 0.45f),
            modifier = Modifier
                .clickable(onClick = onSources)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun StyleAndVocabCard(promptStore: PromptStore) {
    val vocab by promptStore.vocabulary.collectAsStateWithLifecycle()
    val removeFillers by promptStore.removeFillers.collectAsStateWithLifecycle()
    val verbatim by promptStore.verbatim.collectAsStateWithLifecycle()
    val tone by promptStore.tone.collectAsStateWithLifecycle()

    SettingsSection(title = "STYLE & VOCABULARY") {
        // ---- Custom vocabulary ----
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Custom vocabulary",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    "Names, places, jargon Gemma should spell exactly. " +
                        "One per line or comma-separated.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                OutlinedTextField(
                    value = vocab,
                    onValueChange = promptStore::setVocabulary,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 6,
                    placeholder = { Text("Yuri Ihnatov, Doha, MBZUAI, …") },
                    textStyle = MaterialTheme.typography.bodySmall,
                )
            }

            // ---- Tone ----
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Tone",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    "Applied to cleaning and translation outputs. Literal transcription " +
                        "is unaffected — speakers' actual words don't get re-cast.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                // FlowRow so the chips wrap onto a second line on narrow
                // screens instead of clipping or forcing a horizontal scroll
                // gesture inside the Settings vertical scroll.
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    nl.ihnatov.transcriber.asr.Tone.entries.forEach { t ->
                        AssistChip(
                            onClick = { promptStore.setTone(t) },
                            label = { Text(t.displayName) },
                            enabled = tone != t,
                        )
                    }
                }
            }

            // ---- Style toggles ----
            ToggleRow(
                title = "Remove fillers",
                subtitle = "Strip \"um / uh / like / you know\" from the output.",
                checked = removeFillers,
                onChange = promptStore::setRemoveFillers,
            )
        ToggleRow(
            title = "Verbatim mode",
            subtitle = "No punctuation, no capitalization. Just the words as spoken.",
            checked = verbatim,
            onChange = promptStore::setVerbatim,
        )
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * Editable Gemma 4 system prompts. Two templates, one per task. Both use the
 * `{language_hint}` placeholder which the backend substitutes at call time
 * with a language-specific sentence (Arabic-dialect-aware, Ukrainian, etc.).
 *
 * Persistence is in [PromptStore]; this card just binds to it.
 */
@Composable
private fun GemmaPromptsCard(promptStore: PromptStore) {
    val transcribe by promptStore.transcribePrompt.collectAsStateWithLifecycle()
    val translate by promptStore.translatePrompt.collectAsStateWithLifecycle()

    SettingsSection(
        title = "GEMMA PROMPTS",
        subtitle = "System instructions for file + live transcription. Use {language_hint} for the auto language sentence.",
    ) {
        PromptEditor(
            title = "Transcribe (source language)",
            value = transcribe,
            onChange = promptStore::setTranscribe,
            onReset = promptStore::resetTranscribe,
            isDefault = transcribe == PromptStore.DEFAULT_TRANSCRIBE,
        )
        PromptEditor(
            title = "Translate → English",
            value = translate,
            onChange = promptStore::setTranslate,
            onReset = promptStore::resetTranslate,
            isDefault = translate == PromptStore.DEFAULT_TRANSLATE,
        )
    }
}

@Composable
private fun PromptEditor(
    title: String,
    value: String,
    onChange: (String) -> Unit,
    onReset: () -> Unit,
    isDefault: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            if (!isDefault) {
                TextButton(onClick = onReset) {
                    Icon(Icons.Outlined.RestartAlt, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Reset")
                }
            }
        }
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            minLines = 4,
            maxLines = 12,
            textStyle = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * Shows the current battery-optimization status and offers a one-tap fix.
 *
 * On Samsung the difference is night and day: with optimization enabled, a
 * Gemma 4 transcription can be silently killed when the screen turns off
 * (despite a foreground service + held wake lock). With the exemption granted,
 * the same job runs to completion.
 *
 * Re-checks status every time the screen recomposes after the user returns
 * from the system dialog, so the card flips to "OK" without a manual refresh.
 */
@Composable
private fun BatteryOptimizationCard() {
    val ctx = LocalContext.current
    // Trigger recomposition every time we want to re-check (after the user
    // returns from the system dialog).
    var checkTick by remember { mutableStateOf(0) }
    val isOk = remember(checkTick) { BatteryOptimization.isIgnoring(ctx) }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        // User returned from the system dialog (whether they tapped Allow or
        // Cancel). Re-check.
        checkTick++
    }
    // Also re-check whenever this composable first attaches — handles the
    // case where the user fixed it from system Settings outside the app.
    LaunchedEffect(Unit) { checkTick++ }

    SettingsSection(title = "RUN UNINTERRUPTED") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (isOk) Icons.Outlined.CheckCircle else Icons.Outlined.BatteryAlert,
                contentDescription = null,
                tint = if (isOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.width(8.dp))
            nl.ihnatov.transcriber.ui.components.Mono(
                if (isOk) "BACKGROUND EXECUTION ALLOWED" else "BACKGROUND BLOCKED",
                color = if (isOk) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error,
            )
        }
        if (isOk) {
            Text(
                "Battery optimization is disabled for Transcriber — long " +
                    "recordings and Gemma 4 transcriptions can run with the " +
                    "screen off.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        } else {
            Text(
                "Samsung's battery manager can kill long jobs when the " +
                    "screen turns off, even with a foreground service. " +
                    "Allow Transcriber to run unrestricted so transcriptions " +
                    "and recordings don't get suspended mid-flight.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Button(
                onClick = {
                    BatteryOptimization.requestIntent(ctx)?.let { launcher.launch(it) }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp),
            ) {
                Icon(Icons.Outlined.BatteryAlert, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Allow background execution")
            }
        }
    }
}

/**
 * Gemma 4 compute knobs: backend (GPU/CPU/Auto), context window, CPU thread
 * count. The settings are read by [nl.ihnatov.transcriber.asr.Gemma4Backend]
 * on every `load()` — change them and the next run picks them up; an
 * in-flight job uses whatever values were live when it started.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GemmaComputeCard(settings: GemmaSettingsStore) {
    val backendChoice by settings.backend.collectAsStateWithLifecycle()
    val maxTokens by settings.maxNumTokens.collectAsStateWithLifecycle()
    val cpuThreads by settings.cpuThreads.collectAsStateWithLifecycle()

    SettingsSection(
        title = "GEMMA 4 COMPUTE",
        subtitle = "Where the model runs and how much context it can hold. Applies to next run.",
    ) {
        // ---- Backend choice ----
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Compute backend",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    GemmaBackendChoice.entries.forEach { choice ->
                        AssistChip(
                            onClick = { settings.setBackend(choice) },
                            label = { Text(choice.displayName) },
                            enabled = backendChoice != choice,
                        )
                    }
                }
                Text(
                    backendChoice.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }

            // ---- Context window ----
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Context window",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    "Total tokens the model can hold in one run. 8K is enough " +
                        "for normal use; bump it if Context-aware rewrite " +
                        "truncates on long meetings. More = more RAM.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    GemmaSettingsStore.MAX_TOKENS_PRESETS.forEach { n ->
                        AssistChip(
                            onClick = { settings.setMaxNumTokens(n) },
                            label = { Text(formatTokens(n)) },
                            enabled = maxTokens != n,
                        )
                    }
                }
            }

            // ---- CPU threads (only when CPU is in play) ----
            if (backendChoice != GemmaBackendChoice.Gpu) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "CPU threads",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        "Only used when the engine runs on CPU. \"Auto\" lets " +
                            "the SDK pick (~half the cores). Higher = faster " +
                            "decode but more thermal throttling on long runs.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        listOf(0, 2, 4, 6, 8).forEach { n ->
                            AssistChip(
                                onClick = { settings.setCpuThreads(n) },
                                label = { Text(if (n == 0) "Auto" else "$n") },
                                enabled = cpuThreads != n,
                            )
                        }
                    }
                }
            }

        // ---- Reset ----
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = { settings.resetToDefaults() }) {
                Icon(Icons.Outlined.RestartAlt, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                nl.ihnatov.transcriber.ui.components.Mono("RESET COMPUTE SETTINGS")
            }
        }
    }
}

/** [File.length] is meaningless for a directory (the sherpa-onnx engines) — sum its contents instead. */
private fun fileOrDirSizeBytes(file: File): Long =
    if (file.isDirectory) file.listFiles()?.sumOf { it.length() } ?: 0L else file.length()

private fun formatTokens(n: Int): String = when {
    n >= 1024 && n % 1024 == 0 -> "${n / 1024}K"
    else -> n.toString()
}

/**
 * Editorial replacement for the old per-section `Card { Column { ... } }`
 * wrapper. Flat container with a mono-caps title eyebrow and an optional
 * subtitle in Inter ink-soft. No card border, no shadow, no rounded
 * corners — separation from the next section comes from the outer
 * SectionHeader's ink rule.
 *
 * Vertical padding matches the previous Card's inner padding so the
 * visual rhythm of the scroll stays roughly the same; only the box
 * boundary disappears.
 */
@Composable
private fun SettingsSection(
    title: String? = null,
    subtitle: String? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier.padding(top = 8.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        title?.let {
            nl.ihnatov.transcriber.ui.components.Mono(
                it,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        subtitle?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.62f),
            )
        }
        content()
    }
}
