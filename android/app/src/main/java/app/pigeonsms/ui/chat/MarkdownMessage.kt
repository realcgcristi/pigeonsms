package app.pigeonsms.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import app.pigeonsms.design.theme.Corners
import app.pigeonsms.design.theme.Spacing

private sealed interface MarkdownBlock {
    data class Paragraph(val text: String) : MarkdownBlock
    data class Heading(val text: String, val level: Int) : MarkdownBlock
    data class Quote(val text: String) : MarkdownBlock
    data class Code(val text: String) : MarkdownBlock
    data class Bullets(val values: List<String>, val ordered: Boolean) : MarkdownBlock
    data class Table(val rows: List<List<String>>) : MarkdownBlock
}

@Composable
fun MarkdownMessage(
    value: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(value) { parseMarkdownBlocks(value) }
    val linkColor = MaterialTheme.colorScheme.primary
    SelectionContainer(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            blocks.forEach { block ->
                when (block) {
                    is MarkdownBlock.Paragraph -> Text(
                        inlineMarkdown(block.text, color, linkColor),
                        color = color,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    is MarkdownBlock.Heading -> Text(
                        inlineMarkdown(block.text, color, linkColor),
                        color = color,
                        style = when (block.level) {
                            1 -> MaterialTheme.typography.headlineSmall
                            2 -> MaterialTheme.typography.titleLarge
                            else -> MaterialTheme.typography.titleMedium
                        },
                        fontWeight = FontWeight.Bold,
                    )
                    is MarkdownBlock.Quote -> Text(
                        inlineMarkdown(block.text, color.copy(alpha = 0.82f), linkColor),
                        color = color.copy(alpha = 0.82f),
                        style = MaterialTheme.typography.bodyMedium,
                        fontStyle = FontStyle.Italic,
                        modifier = Modifier
                            .border(2.dp, linkColor.copy(alpha = 0.7f), Corners.chip)
                            .padding(start = Spacing.s, top = Spacing.xs, bottom = Spacing.xs, end = Spacing.xs),
                    )
                    is MarkdownBlock.Code -> Text(
                        block.text,
                        color = color,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.fillMaxWidth()
                            .background(color.copy(alpha = 0.09f), Corners.chip)
                            .padding(Spacing.s),
                    )
                    is MarkdownBlock.Bullets -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                        block.values.forEachIndexed { index, item ->
                            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                                Text(if (block.ordered) "${index + 1}." else "•", color = color, fontWeight = FontWeight.Bold)
                                Text(inlineMarkdown(item, color, linkColor), color = color, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                    is MarkdownBlock.Table -> MarkdownTable(block.rows, color, linkColor)
                }
            }
        }
    }
}

@Composable
private fun MarkdownTable(rows: List<List<String>>, color: Color, linkColor: Color) {
    if (rows.isEmpty()) return
    val columns = rows.maxOfOrNull(List<String>::size)?.coerceIn(1, 6) ?: 1
    Column(
        Modifier.horizontalScroll(rememberScrollState())
            .widthIn(min = (columns * 92).dp)
            .border(1.dp, color.copy(alpha = 0.22f), Corners.chip),
    ) {
        rows.take(24).forEachIndexed { rowIndex, values ->
            Row(Modifier.fillMaxWidth().background(color.copy(alpha = if (rowIndex == 0) 0.11f else 0.035f))) {
                repeat(columns) { column ->
                    Text(
                        inlineMarkdown(values.getOrNull(column).orEmpty(), color, linkColor),
                        color = color,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (rowIndex == 0) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.weight(1f).widthIn(min = 92.dp)
                            .border(0.5.dp, color.copy(alpha = 0.16f))
                            .padding(horizontal = Spacing.s, vertical = Spacing.xs),
                    )
                }
            }
        }
    }
}

private fun parseMarkdownBlocks(value: String): List<MarkdownBlock> {
    if (value.isBlank()) return emptyList()
    val lines = value.replace("\r\n", "\n").lines()
    val result = mutableListOf<MarkdownBlock>()
    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        if (line.isBlank()) {
            index++
            continue
        }
        when {
            line.trimStart().startsWith("```") -> {
                val code = mutableListOf<String>()
                index++
                while (index < lines.size && !lines[index].trimStart().startsWith("```")) code += lines[index++]
                if (index < lines.size) index++
                result += MarkdownBlock.Code(code.joinToString("\n"))
            }
            HEADING.matches(line) -> {
                val marks = line.takeWhile { it == '#' }.length.coerceIn(1, 3)
                result += MarkdownBlock.Heading(line.drop(marks).trim(), marks)
                index++
            }
            line.trimStart().startsWith(">") -> {
                val quote = mutableListOf<String>()
                while (index < lines.size && lines[index].trimStart().startsWith(">")) {
                    quote += lines[index++].trimStart().removePrefix(">").trimStart()
                }
                result += MarkdownBlock.Quote(quote.joinToString("\n"))
            }
            index + 1 < lines.size && looksLikeTableHeader(line, lines[index + 1]) -> {
                val rows = mutableListOf(splitTableRow(line))
                index += 2 // skip Markdown's delimiter row
                while (index < lines.size && lines[index].contains('|') && lines[index].isNotBlank()) {
                    rows += splitTableRow(lines[index++])
                }
                result += MarkdownBlock.Table(rows)
            }
            BULLET.matches(line) || ORDERED.matches(line) -> {
                val ordered = ORDERED.matches(line)
                val values = mutableListOf<String>()
                while (index < lines.size && (if (ordered) ORDERED else BULLET).matches(lines[index])) {
                    values += if (ordered) lines[index++].replaceFirst(ORDERED_PREFIX, "")
                    else lines[index++].replaceFirst(BULLET_PREFIX, "")
                }
                result += MarkdownBlock.Bullets(values, ordered)
            }
            else -> {
                val paragraph = mutableListOf(line)
                index++
                while (index < lines.size && lines[index].isNotBlank() && !startsSpecialBlock(lines, index)) {
                    paragraph += lines[index++]
                }
                result += MarkdownBlock.Paragraph(paragraph.joinToString("\n"))
            }
        }
    }
    return result
}

private fun startsSpecialBlock(lines: List<String>, index: Int): Boolean {
    val line = lines[index]
    return line.trimStart().startsWith("```") || line.trimStart().startsWith('>') ||
        HEADING.matches(line) || BULLET.matches(line) || ORDERED.matches(line) ||
        (index + 1 < lines.size && looksLikeTableHeader(line, lines[index + 1]))
}

private fun looksLikeTableHeader(header: String, divider: String): Boolean =
    header.contains('|') && splitTableRow(divider).let { cells ->
        cells.size >= 2 && cells.all { it.matches(Regex(":?-{3,}:?")) }
    }

private fun splitTableRow(value: String): List<String> = value.trim().trim('|').split('|').map(String::trim).take(6)

private fun inlineMarkdown(value: String, color: Color, linkColor: Color): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    while (cursor < value.length) {
        val token = INLINE.find(value, cursor)
        if (token == null || token.range.first > cursor) {
            val end = token?.range?.first ?: value.length
            append(value.substring(cursor, end))
            cursor = end
            continue
        }
        val raw = token.value
        when {
            raw.startsWith("**") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(raw.removeSurrounding("**")) }
            raw.startsWith("~~") -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(raw.removeSurrounding("~~")) }
            raw.startsWith('`') -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = color.copy(alpha = 0.11f))) { append(raw.removeSurrounding("`")) }
            raw.startsWith('[') -> {
                val label = raw.substringAfter('[').substringBefore("](")
                val url = raw.substringAfter("](").dropLast(1)
                pushStringAnnotation("URL", url)
                withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) { append(label) }
                pop()
            }
            raw.startsWith('@') -> withStyle(SpanStyle(color = linkColor, fontWeight = FontWeight.SemiBold, background = linkColor.copy(alpha = 0.12f))) { append(raw) }
            raw.startsWith('*') || raw.startsWith('_') -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(raw.substring(1, raw.length - 1)) }
            else -> append(raw)
        }
        cursor = token.range.last + 1
    }
}

private val HEADING = Regex("^#{1,3}\\s+.+")
private val BULLET = Regex("^\\s*[-+*]\\s+.+")
private val ORDERED = Regex("^\\s*\\d+[.)]\\s+.+")
private val BULLET_PREFIX = Regex("^\\s*[-+*]\\s+")
private val ORDERED_PREFIX = Regex("^\\s*\\d+[.)]\\s+")
private val INLINE = Regex("\\*\\*[^*\\n]+\\*\\*|~~[^~\\n]+~~|`[^`\\n]+`|\\[[^]\\n]+]\\([^ )\\n]+\\)|@[A-Za-z0-9_.-]{1,32}|\\*[^*\\n]+\\*|_[^_\\n]+_")
