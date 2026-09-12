package nl.ihnatov.transcriber.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser
import org.commonmark.node.Text as MdText

/**
 * CommonMark renderer for Gemma post-processing outputs (Phase 5 of the
 * 2026-09 plan — replaces a hand-rolled subset parser that had no path for
 * tables, fenced code, or blockquotes; Minutes/Summary outputs hit all
 * three often enough that the gap was worth closing). Parses with
 * commonmark-java plus the GFM tables extension, then walks the resulting
 * AST straight into Compose — no intermediate custom block model, since
 * commonmark's own `Node` hierarchy already is one.
 */

private val markdownParser: Parser =
    Parser.builder().extensions(listOf(TablesExtension.create())).build()

@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
) {
    val document = remember(markdown) { markdownParser.parse(markdown) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RenderChildren(document)
    }
}

@Composable
private fun RenderChildren(parent: Node) {
    var child = parent.firstChild
    while (child != null) {
        RenderBlock(child)
        child = child.next
    }
}

@Composable
private fun RenderBlock(node: Node) {
    when (node) {
        is Heading -> Text(
            text = renderInlines(node),
            fontSize = when (node.level) { 1 -> 22.sp; 2 -> 19.sp; 3 -> 17.sp; else -> 15.sp },
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        is Paragraph -> Text(
            text = renderInlines(node),
            style = MaterialTheme.typography.bodyMedium,
        )
        is BulletList -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            var item = node.firstChild
            while (item != null) {
                if (item is ListItem) {
                    Row(modifier = Modifier.padding(start = 4.dp)) {
                        Text("•  ", style = MaterialTheme.typography.bodyMedium)
                        Column { RenderChildren(item) }
                    }
                }
                item = item.next
            }
        }
        is OrderedList -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            var idx = node.markerStartNumber ?: 1
            var item = node.firstChild
            while (item != null) {
                if (item is ListItem) {
                    Row(modifier = Modifier.padding(start = 4.dp)) {
                        Text("$idx.  ", style = MaterialTheme.typography.bodyMedium)
                        Column { RenderChildren(item) }
                    }
                    idx++
                }
                item = item.next
            }
        }
        is BlockQuote -> Row(modifier = Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .width(3.dp)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)),
            ) {}
            Column(
                modifier = Modifier.padding(start = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                RenderChildren(node)
            }
        }
        is FencedCodeBlock -> MarkdownCodeBlock(node.literal)
        is IndentedCodeBlock -> MarkdownCodeBlock(node.literal)
        is ThematicBreak -> Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)),
        )
        is TableBlock -> MarkdownTable(node)
        // Unknown/unhandled block (e.g. an HTML block the model emitted) —
        // recurse into its children rather than silently dropping content.
        else -> RenderChildren(node)
    }
}

@Composable
private fun MarkdownCodeBlock(code: String) {
    val scroll = rememberScrollState()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            .padding(8.dp),
    ) {
        Text(
            text = code.trimEnd('\n'),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.horizontalScroll(scroll),
        )
    }
}

/**
 * Simple even-width grid — Compose has no table primitive, and matching
 * column widths to content would need a two-pass measurement that's not
 * worth it for model-generated tables (a handful of short cells, not a
 * data grid).
 */
@Composable
private fun MarkdownTable(table: TableBlock) {
    Column(modifier = Modifier.fillMaxWidth()) {
        var section = table.firstChild
        while (section != null) {
            val isHeader = section is TableHead
            if (section is TableHead || section is TableBody) {
                var row = section.firstChild
                while (row != null) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        var cell = row.firstChild
                        while (cell != null) {
                            Text(
                                text = renderInlines(cell),
                                style = if (isHeader) {
                                    MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                                } else {
                                    MaterialTheme.typography.bodyMedium
                                },
                                modifier = Modifier.weight(1f).padding(4.dp),
                            )
                            cell = cell.next
                        }
                    }
                    if (isHeader) HorizontalDivider()
                    row = row.next
                }
            }
            section = section.next
        }
    }
}

/**
 * Walk [parent]'s inline children into an AnnotatedString — `**bold**`,
 * `*italic*`, `` `code` ``, links (underlined; not clickable — these are
 * generated-text outputs, not a browsing surface), and line breaks.
 */
private fun renderInlines(parent: Node): AnnotatedString = buildAnnotatedString {
    appendInlineChildren(parent)
}

private fun AnnotatedString.Builder.appendInlineChildren(parent: Node) {
    var child = parent.firstChild
    while (child != null) {
        appendInlineNode(child)
        child = child.next
    }
}

private fun AnnotatedString.Builder.appendInlineNode(node: Node) {
    when (node) {
        is MdText -> append(node.literal)
        is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
            appendInlineChildren(node)
        }
        is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
            appendInlineChildren(node)
        }
        is Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) {
            append(node.literal)
        }
        is Link -> withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) {
            appendInlineChildren(node)
        }
        is SoftLineBreak -> append(' ')
        is HardLineBreak -> append('\n')
        else -> appendInlineChildren(node)
    }
}

/** Convenience for callers that don't need their own modifier. */
@Suppress("unused")
@Composable
fun MarkdownText(markdown: String, padding: PaddingValues) {
    MarkdownText(markdown = markdown, modifier = Modifier.padding(padding))
}
