package nl.ihnatov.transcriber.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Minimal markdown renderer for Gemma post-processing outputs. Handles:
 *
 *   - `#`, `##`, `###` headings (3 levels)
 *   - Bullet lists (lines beginning with `-` or `*`)
 *   - Numbered lists (lines beginning with `<digits>. `)
 *   - Inline `**bold**`, `*italic*`, and `` `code` ``
 *   - Plain paragraphs separated by blank lines
 *
 * Deliberately not a full CommonMark implementation — adding a third-party
 * markdown library would balloon the APK for a feature only used in three
 * preset outputs. Edge cases the model rarely emits (tables, blockquotes,
 * fenced code blocks, links) get printed as plain text which is fine
 * given the prompts ask for prose + bullets only.
 *
 * Why I rolled this rather than pulling in commonmark-android or
 * compose-richtext: ~2 MB APK saved, no transitive dep churn, and the
 * preset prompts constrain Gemma's output to a tiny subset of markdown
 * anyway. Swap in a real renderer if/when we surface user-written markdown.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
) {
    val blocks = parseBlocks(markdown)
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (block in blocks) {
            when (block) {
                is MdBlock.Heading -> Text(
                    text = renderInlines(block.text),
                    fontSize = when (block.level) { 1 -> 22.sp; 2 -> 19.sp; else -> 17.sp },
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                is MdBlock.Paragraph -> Text(
                    text = renderInlines(block.text),
                    style = MaterialTheme.typography.bodyMedium,
                )
                is MdBlock.BulletList -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (item in block.items) {
                        Row(modifier = Modifier.padding(start = 4.dp)) {
                            Text("•  ", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                renderInlines(item),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                is MdBlock.NumberedList -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    for ((idx, item) in block.items.withIndex()) {
                        Row(modifier = Modifier.padding(start = 4.dp)) {
                            Text("${idx + 1}.  ", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                renderInlines(item),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}

private sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class BulletList(val items: List<String>) : MdBlock
    data class NumberedList(val items: List<String>) : MdBlock
}

/** Line-by-line block parser. Hand-rolled because the input is small. */
private fun parseBlocks(markdown: String): List<MdBlock> {
    val lines = markdown.lines()
    val out = mutableListOf<MdBlock>()
    val paragraph = StringBuilder()
    var bullets: MutableList<String>? = null
    var numbers: MutableList<String>? = null

    fun flushParagraph() {
        val text = paragraph.toString().trim()
        if (text.isNotEmpty()) out += MdBlock.Paragraph(text)
        paragraph.clear()
    }
    fun flushBullets() {
        bullets?.takeIf { it.isNotEmpty() }?.let { out += MdBlock.BulletList(it.toList()) }
        bullets = null
    }
    fun flushNumbers() {
        numbers?.takeIf { it.isNotEmpty() }?.let { out += MdBlock.NumberedList(it.toList()) }
        numbers = null
    }
    fun flushAll() { flushParagraph(); flushBullets(); flushNumbers() }

    val numberedRe = Regex("^\\s*(\\d+)\\.\\s+(.+)$")

    for (raw in lines) {
        val line = raw.trimEnd()
        when {
            line.isEmpty() -> flushAll()
            line.startsWith("### ") -> { flushAll(); out += MdBlock.Heading(3, line.removePrefix("### ").trim()) }
            line.startsWith("## ")  -> { flushAll(); out += MdBlock.Heading(2, line.removePrefix("## ").trim()) }
            line.startsWith("# ")   -> { flushAll(); out += MdBlock.Heading(1, line.removePrefix("# ").trim()) }
            line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") -> {
                flushParagraph(); flushNumbers()
                val item = line.trimStart().removePrefix("- ").removePrefix("* ")
                (bullets ?: mutableListOf<String>().also { bullets = it }) += item
            }
            numberedRe.containsMatchIn(line) -> {
                flushParagraph(); flushBullets()
                val match = numberedRe.find(line)!!
                (numbers ?: mutableListOf<String>().also { numbers = it }) += match.groupValues[2]
            }
            else -> {
                flushBullets(); flushNumbers()
                if (paragraph.isNotEmpty()) paragraph.append(' ')
                paragraph.append(line.trim())
            }
        }
    }
    flushAll()
    return out
}

/**
 * Parse inline markup into an AnnotatedString. Tokenizer state machine handles
 * `**bold**`, `*italic*`, and `` `code` `` without trying to be clever about
 * nesting (which Gemma doesn't emit in our prompts).
 */
private fun renderInlines(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    val n = text.length
    while (i < n) {
        val c = text[i]
        when {
            c == '*' && i + 1 < n && text[i + 1] == '*' -> {
                val end = text.indexOf("**", startIndex = i + 2)
                if (end == -1) { append(c); i++ }
                else {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                        append(text, i + 2, end)
                    }
                    i = end + 2
                }
            }
            c == '*' -> {
                val end = text.indexOf('*', startIndex = i + 1)
                if (end == -1) { append(c); i++ }
                else {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(text, i + 1, end)
                    }
                    i = end + 1
                }
            }
            c == '`' -> {
                val end = text.indexOf('`', startIndex = i + 1)
                if (end == -1) { append(c); i++ }
                else {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) {
                        append(text, i + 1, end)
                    }
                    i = end + 1
                }
            }
            else -> { append(c); i++ }
        }
    }
}

/** Convenience for callers that don't need their own modifier. */
@Suppress("unused")
@Composable
fun MarkdownText(markdown: String, padding: PaddingValues) {
    MarkdownText(markdown = markdown, modifier = Modifier.padding(padding))
}
