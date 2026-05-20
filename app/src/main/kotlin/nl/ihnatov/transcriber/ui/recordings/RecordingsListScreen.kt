package nl.ihnatov.transcriber.ui.recordings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import nl.ihnatov.transcriber.data.AppContainer
import nl.ihnatov.transcriber.data.Recording
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
    // Search query state — flatMapLatest swaps between the full
    // observeAll() flow and the search() flow as the user types.
    var query by remember { mutableStateOf("") }
    val source = remember { MutableStateFlow("") }
    val recordings by remember(container) {
        source.flatMapLatest { q ->
            if (q.isBlank()) container.repository.observeAll()
            else container.repository.search(q)
        }
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    androidx.compose.runtime.LaunchedEffect(query) { source.value = query }

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
                            RecordingRow(rec, onClick = { onOpen(rec.id) })
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
private fun RecordingRow(rec: Recording, onClick: () -> Unit) {
    val ink = MaterialTheme.colorScheme.onBackground
    val isToday = isToday(rec.createdAtMillis)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 13.dp),
        verticalAlignment = Alignment.Top,
    ) {
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
