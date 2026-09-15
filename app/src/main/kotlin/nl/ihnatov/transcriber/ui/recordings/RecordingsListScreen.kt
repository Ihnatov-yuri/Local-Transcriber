package nl.ihnatov.transcriber.ui.recordings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import nl.ihnatov.transcriber.asr.TranscriptionJobManager
import nl.ihnatov.transcriber.data.AppContainer
import nl.ihnatov.transcriber.data.FolderWithCount
import nl.ihnatov.transcriber.data.Recording
import nl.ihnatov.transcriber.data.TagWithCount
import nl.ihnatov.transcriber.ui.components.BigNumber
import nl.ihnatov.transcriber.ui.components.BrandStrip
import nl.ihnatov.transcriber.ui.components.Hairline
import nl.ihnatov.transcriber.ui.components.HairlineSoft
import nl.ihnatov.transcriber.ui.components.InkRule
import nl.ihnatov.transcriber.ui.components.InverseFooter
import nl.ihnatov.transcriber.ui.components.Mono
import nl.ihnatov.transcriber.ui.components.PulseDot
import nl.ihnatov.transcriber.ui.components.SectionIndex
import nl.ihnatov.transcriber.ui.components.Sheet
import nl.ihnatov.transcriber.ui.theme.Accent
import nl.ihnatov.transcriber.ui.theme.SairaCondensed
import nl.ihnatov.transcriber.ui.theme.Spacing

/**
 * Library screen — design `01 · LIBRARY` from `screen-library.jsx`.
 *
 * Layout, top to bottom:
 *   1. BrandStrip with version label on the right
 *   2. InkRule
 *   3. SectionIndex(1, "library", summary sentence)
 *   4. Three-up metric strip (recordings · duration · languages)
 *   5. Eyebrow row: RECORDINGS / + IMPORT / NEWEST ↓
 *   6. Inline search field (substring match across title + segments)
 *   7. LazyColumn of recording rows
 *   8. Bottom inverse footer: "RECORD A NEW SESSION" CTA
 *
 * Notes:
 *   - No `Card`, no rounded corners. Row separation is hairline only.
 *   - The import affordance moved out of the FAB (no FAB per spec) and
 *     into the eyebrow row as a mono-caps "+ IMPORT" link.
 *   - Search is preserved from the previous build but restyled as a
 *     paper-on-paper text field with a single hairline border.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Composable
fun RecordingsListScreen(
    container: AppContainer,
    onOpen: (Long) -> Unit,
    onStartRecording: (() -> Unit)? = null,
) {
    // Folder, tag, and search are independently-settable, AND-combined
    // filters (see RecordingRepository.observeLibrary) — selecting a
    // folder chip or a tag doesn't replace the search, it narrows it
    // further, matching the Mac app's own progressive-narrowing Library.
    var query by remember { mutableStateOf("") }
    val source = remember { MutableStateFlow("") }
    var selectedFolderId by remember { mutableStateOf<Long?>(null) }
    var selectedTagId by remember { mutableStateOf<Long?>(null) }
    val recordings by remember(container, selectedFolderId, selectedTagId) {
        source.flatMapLatest { q -> container.repository.observeLibrary(selectedFolderId, selectedTagId, q) }
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    androidx.compose.runtime.LaunchedEffect(query) { source.value = query }
    val jobStatuses by container.transcriptionJobManager.statuses.collectAsStateWithLifecycle()
    val folders by container.repository.observeFoldersWithCounts()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val tags by container.repository.observeTagsWithCounts()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    var newFolderPrompt by remember { mutableStateOf(false) }
    var folderError by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val imported = container.repository.importFromUri(uri)
                val id = container.repository.create(
                    title = imported.file.nameWithoutExtension,
                    audioPath = imported.file.absolutePath,
                    durationSeconds = imported.durationSeconds,
                )
                onOpen(id)
            }
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Sheet(
            modifier = Modifier.fillMaxSize(),
            padding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = Spacing.sheetPadding,
                vertical = 14.dp,
            ),
        ) {
            BrandStrip(
                right = {
                    Mono(
                        "V1·0 / ANDROID",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.40f),
                    )
                },
            )
            Spacer(Modifier.height(14.dp))
            InkRule()
            Spacer(Modifier.height(18.dp))
            SectionIndex(
                n = 1,
                label = "library",
                summary = summarySentence(recordings),
            )
            Spacer(Modifier.height(20.dp))
            MetricStrip(recordings)
            Spacer(Modifier.height(18.dp))
            InkRule()
            Spacer(Modifier.height(14.dp))
            EyebrowRow(
                onImport = { importLauncher.launch(arrayOf("audio/*")) },
            )
            Spacer(Modifier.height(8.dp))
            // Folder (chip strip, single-select) and tag (compact dropdown,
            // single-select even though tags are many-to-many on a
            // recording) filters — same split the Mac app makes: a chip
            // per folder reads fine since there are usually a handful, but
            // a chip-wrap of every tag would crowd this narrow column.
            FolderChipStrip(
                folders = folders,
                selectedFolderId = selectedFolderId,
                onSelect = { selectedFolderId = it },
                onNewFolder = { folderError = null; newFolderPrompt = true },
                onRename = { folder, name ->
                    scope.launch {
                        folderError = runCatching { container.repository.renameFolder(folder.folder, name) }
                            .exceptionOrNull()?.message
                    }
                },
                onDelete = { folder ->
                    if (selectedFolderId == folder.folder.id) selectedFolderId = null
                    scope.launch { container.repository.deleteFolder(folder.folder) }
                },
            )
            if (tags.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                TagFilterRow(tags = tags, selectedTagId = selectedTagId, onSelect = { selectedTagId = it })
            }
            Spacer(Modifier.height(4.dp))
            SearchField(query) { query = it }
            Spacer(Modifier.height(6.dp))
            // List + inverse footer share the remaining vertical space.
            // LazyColumn with weight(1f) so the footer pins to the bottom
            // and the rows scroll within. Bottom padding leaves room for
            // the footer height; the footer itself sits in the Box's
            // BottomCenter overlay.
            Box(Modifier.weight(1f)) {
                if (recordings.isEmpty()) {
                    EmptyState(query)
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(recordings, key = { it.id }) { rec ->
                            HairlineSoft()
                            RecordingRow(
                                rec,
                                job = jobStatuses[rec.id],
                                folderName = rec.folderId?.let { fid -> folders.find { it.folder.id == fid }?.folder?.name },
                                allFolders = folders,
                                onClick = { onOpen(rec.id) },
                                onMoveToFolder = { folderId ->
                                    scope.launch { container.repository.moveToFolder(rec, folderId) }
                                },
                            )
                        }
                    }
                }
            }
        }
        // Inverse footer pins to the bottom of the screen. Sits OUTSIDE
        // the Sheet's horizontal padding via Modifier.fillMaxWidth on
        // the Box's BottomCenter — bleeds edge-to-edge per the spec.
        if (onStartRecording != null) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
            ) {
                LibraryFooter(onStartRecording)
            }
        }
        if (newFolderPrompt) {
            NewFolderDialog(
                error = folderError,
                onDismiss = { newFolderPrompt = false; folderError = null },
                onConfirm = { name ->
                    scope.launch {
                        val result = runCatching { container.repository.createFolder(name) }
                        result.onSuccess { newFolderPrompt = false; folderError = null }
                        result.onFailure { folderError = it.message }
                    }
                },
            )
        }
    }
}

// ─── Building blocks ─────────────────────────────────────────────────

@Composable
private fun MetricStrip(recordings: List<Recording>) {
    val totalSec = recordings.sumOf { it.durationSeconds }.toInt()
    val langs = recordings.mapNotNull { it.sourceLanguage }.toSet().size
    Row(Modifier.fillMaxWidth().height(72.dp)) {
        MetricCell(
            value = recordings.size.toString().padStart(2, '0'),
            suffix = "REC",
            modifier = Modifier.weight(1f),
        )
        VRule()
        MetricCell(
            value = formatDuration(totalSec),
            suffix = null,
            modifier = Modifier.weight(1.2f),
        )
        VRule()
        MetricCell(
            value = langs.coerceAtLeast(0).toString(),
            suffix = "LANG",
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun MetricCell(value: String, suffix: String?, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        BigNumber(value = value, suffix = suffix, size = 42.sp)
        Mono(
            when (suffix) {
                "REC" -> "RECORDINGS"
                "LANG" -> "LANGUAGES"
                else -> "DURATION"
            },
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun VRule() {
    Box(
        Modifier
            .fillMaxHeight()
            .width(1.dp)
            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.16f)),
    )
}

@Composable
private fun EyebrowRow(onImport: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Mono(
            "RECORDINGS",
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.62f),
        )
        Spacer(Modifier.weight(1f))
        Mono(
            "+ IMPORT",
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier
                .clickable(onClick = onImport)
                .padding(horizontal = 6.dp, vertical = 4.dp),
        )
        Spacer(Modifier.width(8.dp))
        Mono("NEWEST ↓", color = MaterialTheme.colorScheme.onBackground)
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    val ink = MaterialTheme.colorScheme.onBackground
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        BasicTextField(
            value = query,
            onValueChange = onChange,
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            textStyle = LocalTextStyle.current.copy(
                fontSize = 13.5.sp,
                color = ink,
            ),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(Accent),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            decorationBox = { inner ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Mono(
                        "FIND  ",
                        color = ink.copy(alpha = 0.40f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) {
                            Text(
                                "title or transcript",
                                style = MaterialTheme.typography.bodyMedium,
                                color = ink.copy(alpha = 0.40f),
                            )
                        }
                        inner()
                    }
                    if (query.isNotEmpty()) {
                        Mono(
                            "✕",
                            color = ink.copy(alpha = 0.62f),
                            modifier = Modifier
                                .clickable { onChange("") }
                                .padding(start = 8.dp),
                        )
                    }
                }
            },
        )
        // Underline hairline beneath the row.
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .align(Alignment.BottomCenter)
                .background(ink.copy(alpha = 0.16f)),
        )
    }
}

/**
 * ALL · one chip per folder ("NAME (count)") · + NEW. Single-select filter
 * (matches the Mac's `FolderStrip` — `selectedFolderID` is a single value,
 * not a set), long-press a folder chip for rename/delete.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun FolderChipStrip(
    folders: List<FolderWithCount>,
    selectedFolderId: Long?,
    onSelect: (Long?) -> Unit,
    onNewFolder: () -> Unit,
    onRename: (FolderWithCount, String) -> Unit,
    onDelete: (FolderWithCount) -> Unit,
) {
    val ink = MaterialTheme.colorScheme.onBackground
    var renameTarget by remember { mutableStateOf<FolderWithCount?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        chipFilter(label = "ALL", selected = selectedFolderId == null, onClick = { onSelect(null) })
        for (f in folders) {
            var menuOpen by remember(f.folder.id) { mutableStateOf(false) }
            Box {
                Mono(
                    "${f.folder.name.uppercase()} (${f.recordingCount})",
                    color = if (selectedFolderId == f.folder.id) Accent else ink.copy(alpha = 0.62f),
                    modifier = Modifier
                        .combinedClickable(
                            onClick = { onSelect(f.folder.id) },
                            onLongClick = { menuOpen = true },
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    containerColor = MaterialTheme.colorScheme.background,
                ) {
                    DropdownMenuItem(
                        text = { Mono("RENAME…", color = ink) },
                        onClick = { menuOpen = false; renameDraft = f.folder.name; renameTarget = f },
                    )
                    DropdownMenuItem(
                        text = { Mono("DELETE FOLDER", color = Accent) },
                        onClick = { menuOpen = false; onDelete(f) },
                    )
                }
            }
        }
        Mono(
            "+ NEW",
            color = ink.copy(alpha = 0.45f),
            modifier = Modifier.clickable(onClick = onNewFolder).padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            containerColor = MaterialTheme.colorScheme.background,
            title = { Mono("RENAME FOLDER", color = ink) },
            text = {
                OutlinedTextField(
                    value = renameDraft,
                    onValueChange = { renameDraft = it },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onRename(target, renameDraft)
                    renameTarget = null
                }) { Mono("SAVE") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Mono("CANCEL") }
            },
        )
    }
}

@Composable
private fun chipFilter(label: String, selected: Boolean, onClick: () -> Unit) {
    Mono(
        label,
        color = if (selected) Accent else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.62f),
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/**
 * Compact "TAG: name ▾" dropdown — deliberately not a chip row. The Mac
 * app's own comment explains why: a chip per tag would crowd the list
 * column the way a chip per folder doesn't (there are usually far more
 * tags than folders). Single-select, even though a recording can carry
 * several tags at once — this only narrows the Library, it doesn't edit
 * anything (tag editing lives on the Detail screen).
 */
@Composable
private fun TagFilterRow(
    tags: List<TagWithCount>,
    selectedTagId: Long?,
    onSelect: (Long?) -> Unit,
) {
    val ink = MaterialTheme.colorScheme.onBackground
    var menuOpen by remember { mutableStateOf(false) }
    val selectedName = tags.find { it.tag.id == selectedTagId }?.tag?.name ?: "ALL"
    Box {
        Mono(
            "TAG: ${selectedName.uppercase()} ▾",
            color = if (selectedTagId == null) ink.copy(alpha = 0.55f) else Accent,
            modifier = Modifier.clickable { menuOpen = true }.padding(vertical = 4.dp),
        )
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            containerColor = MaterialTheme.colorScheme.background,
        ) {
            DropdownMenuItem(
                text = { Mono("ALL", color = ink) },
                onClick = { menuOpen = false; onSelect(null) },
            )
            for (t in tags) {
                DropdownMenuItem(
                    text = { Text("${t.tag.name} (${t.recordingCount})") },
                    onClick = { menuOpen = false; onSelect(t.tag.id) },
                )
            }
        }
    }
}

@Composable
private fun NewFolderDialog(
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.background,
        title = { Mono("NEW FOLDER", color = MaterialTheme.colorScheme.onBackground) },
        text = {
            Column {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    placeholder = { Text("Folder name") },
                )
                if (error != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(error, color = Accent, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(draft) }) { Mono("CREATE") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Mono("CANCEL") }
        },
    )
}

@Composable
private fun EmptyState(query: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Mono(
            if (query.isBlank()) "NO RECORDINGS YET · TAP RECORD BELOW"
            else "NO MATCHES FOR “$query”",
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.40f),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun RecordingRow(
    rec: Recording,
    job: TranscriptionJobManager.JobStatus?,
    folderName: String?,
    allFolders: List<FolderWithCount>,
    onClick: () -> Unit,
    onMoveToFolder: (Long?) -> Unit,
) {
    val ink = MaterialTheme.colorScheme.onBackground
    val isToday = isToday(rec.createdAtMillis)
    var moveMenuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = { moveMenuOpen = true },
            )
            .padding(vertical = 13.dp),
        verticalAlignment = Alignment.Top,
    ) {
        DropdownMenu(
            expanded = moveMenuOpen,
            onDismissRequest = { moveMenuOpen = false },
            containerColor = MaterialTheme.colorScheme.background,
        ) {
            // Mirrors the Mac's "Move to Folder…" context-menu submenu:
            // every OTHER folder, plus "Remove from Folder" when filed.
            for (f in allFolders.filter { it.folder.id != rec.folderId }) {
                DropdownMenuItem(
                    text = { Text(f.folder.name) },
                    onClick = { moveMenuOpen = false; onMoveToFolder(f.folder.id) },
                )
            }
            if (rec.folderId != null) {
                DropdownMenuItem(
                    text = { Mono("REMOVE FROM FOLDER", color = Accent) },
                    onClick = { moveMenuOpen = false; onMoveToFolder(null) },
                )
            }
        }
        // Date column (54 dp). Time stamp ink + day mono-caps soft.
        Column(Modifier.width(54.dp)) {
            Text(
                formatTime(rec.createdAtMillis),
                style = androidx.compose.ui.text.TextStyle(
                    fontFamily = SairaCondensed,
                    fontSize = 14.sp,
                    fontFeatureSettings = "tnum",
                    color = ink,
                ),
            )
            Spacer(Modifier.height(2.dp))
            Mono(
                formatDayShort(rec.createdAtMillis),
                style = MaterialTheme.typography.labelSmall,
                color = ink.copy(alpha = 0.40f),
            )
        }
        Spacer(Modifier.width(Spacing.m))
        // Title + meta column.
        Column(Modifier.weight(1f)) {
            Text(
                rec.title,
                style = MaterialTheme.typography.bodyLarge,
                color = ink,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                folderName?.let {
                    Mono("▸ ${it.uppercase()}", color = Accent, style = MaterialTheme.typography.labelSmall)
                    Mono(
                        " · ",
                        color = ink.copy(alpha = 0.30f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                rec.sourceLanguage?.takeIf { it.isNotBlank() }?.let {
                    Mono(it, color = ink.copy(alpha = 0.55f), style = MaterialTheme.typography.labelSmall)
                    Mono(
                        " · ",
                        color = ink.copy(alpha = 0.30f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                if (rec.translateToEnglish) {
                    Mono(
                        "TRANSLATED",
                        color = ink.copy(alpha = 0.55f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Mono(
                        " · ",
                        color = ink.copy(alpha = 0.30f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                if (isToday) {
                    Box(
                        Modifier
                            .width(5.dp)
                            .height(5.dp)
                            .offset(y = (-1).dp)
                            .background(Accent),
                    )
                    Spacer(Modifier.width(4.dp))
                    Mono(
                        "TODAY",
                        color = Accent,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            // Job-state line — only while there's open work for this
            // recording (queued, charger-parked, or actively running).
            // Reuses TranscriptionJobManager.statuses, the same per-
            // recording status map the Detail screen's RUN strip already
            // reads, so a job started from one screen shows up correctly
            // on both without a second source of truth.
            if (job != null && job.active) {
                Spacer(Modifier.height(3.dp))
                Mono(
                    when {
                        job.stopping -> "STOPPING…"
                        job.waitingForCharger -> "WAITING FOR CHARGER"
                        job.queued -> "QUEUED"
                        job.stageLabel.isNotBlank() -> job.stageLabel.uppercase()
                        else -> "TRANSCRIBING"
                    },
                    color = Accent,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        Spacer(Modifier.width(Spacing.m))
        // Right column: duration in Saira Condensed tabular.
        Text(
            formatDuration(rec.durationSeconds.toInt()),
            style = androidx.compose.ui.text.TextStyle(
                fontFamily = SairaCondensed,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                fontSize = 16.sp,
                fontFeatureSettings = "tnum, lnum",
                color = ink,
            ),
        )
    }
}

@Composable
private fun LibraryFooter(onStartRecording: () -> Unit) {
    InverseFooter(
        onClick = onStartRecording,
        left = { PulseDot(size = 8.dp) },
        right = {
            Mono(
                "→",
                style = MaterialTheme.typography.displaySmall,
                color = Accent,
            )
        },
        body = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "RECORD A NEW SESSION",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.background,
                )
                Mono(
                    "TAP TO START · LONG-PRESS FOR OPTIONS",
                    color = MaterialTheme.colorScheme.background.copy(alpha = 0.55f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        },
    )
}

// ─── Helpers ─────────────────────────────────────────────────────────

private fun summarySentence(recordings: List<Recording>): String {
    val n = recordings.size
    val durMin = (recordings.sumOf { it.durationSeconds } / 60.0).toInt()
    val langs = recordings.mapNotNull { it.sourceLanguage }.toSet().size
    if (n == 0) return "Nothing recorded yet. Tap RECORD A NEW SESSION below to start."
    val h = durMin / 60
    val m = durMin % 60
    val durStr = if (h > 0) "${h}h ${m}m" else "${m}m"
    val langStr = if (langs > 0) " across $langs ${if (langs == 1) "language" else "languages"}" else ""
    return "$n recording${if (n == 1) "" else "s"}, $durStr of speech$langStr, transcribed on-device."
}

private fun formatDuration(seconds: Int): String {
    val s = seconds.coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    else "%d:%02d".format(s / 60, s % 60)
}

private fun formatTime(millis: Long): String =
    SimpleDateFormat("HH:mm", Locale.US).format(Date(millis))

private fun formatDayShort(millis: Long): String =
    SimpleDateFormat("d MMM", Locale.US).format(Date(millis)).uppercase()

private fun isToday(millis: Long): Boolean {
    val now = java.util.Calendar.getInstance()
    val then = java.util.Calendar.getInstance().apply { timeInMillis = millis }
    return now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) &&
        now.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)
}
