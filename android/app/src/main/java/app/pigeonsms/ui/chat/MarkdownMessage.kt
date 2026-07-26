package app.pigeonsms.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.unit.sp
import app.pigeonsms.network.SpaceEmojiDto
import coil.compose.AsyncImage
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

/** Lightweight message Markdown renderer, including practical pipe tables. */
@Composable
fun MarkdownMessage(
    value: String,
    color: Color,
    modifier: Modifier = Modifier,
    /** This nest's emoji, so `:name:` and `::name::` render as images (2.9.5). */
    emoji: List<SpaceEmojiDto> = emptyList(),
    mediaUrl: (String) -> String? = { null },
) {
    val blocks = remember(value) { parseMarkdownBlocks(value) }
    val linkColor = MaterialTheme.colorScheme.primary
    // One inline-content entry per emoji actually referenced in this message.
    // Compose resolves these by id at draw time, which is what lets an image sit
    // on the text baseline instead of breaking the paragraph into pieces.
    val inlineContent = rememberEmojiInlineContent(emoji, mediaUrl)
    SelectionContainer(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            blocks.forEach { block ->
                when (block) {
                    is MarkdownBlock.Paragraph -> Text(
                        inlineMarkdown(block.text, color, linkColor, emoji),
                        color = color,
                        style = MaterialTheme.typography.bodyLarge,
                        inlineContent = inlineContent,
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
                                Text(
                                    inlineMarkdown(item, color, linkColor, emoji),
                                    color = color,
                                    style = MaterialTheme.typography.bodyLarge,
                                    inlineContent = inlineContent,
                                )
                            }
                        }
                    }
                    is MarkdownBlock.Table -> MarkdownTable(block.rows, color, linkColor)
                }
            }
        }
    }
}

/** Width of one table cell. Fixed rather than proportional — see [MarkdownTable]. */
private val TableCellWidth = 132.dp

@Composable
private fun MarkdownTable(rows: List<List<String>>, color: Color, linkColor: Color) {
    if (rows.isEmpty()) return
    val columns = rows.maxOfOrNull(List<String>::size)?.coerceIn(1, 6) ?: 1
    Column(
        Modifier.horizontalScroll(rememberScrollState())
            .border(1.dp, color.copy(alpha = 0.22f), Corners.chip),
    ) {
        rows.take(24).forEachIndexed { rowIndex, values ->
            // `height(IntrinsicSize.Min)` + `fillMaxHeight` on the cells makes every
            // cell in a row as tall as the tallest one, so the dividers line up
            // when one cell wraps onto a second line.
            Row(
                Modifier.height(IntrinsicSize.Min)
                    .background(color.copy(alpha = if (rowIndex == 0) 0.11f else 0.035f)),
            ) {
                repeat(columns) { column ->
                    Text(
                        inlineMarkdown(values.getOrNull(column).orEmpty(), color, linkColor),
                        color = color,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (rowIndex == 0) FontWeight.SemiBold else FontWeight.Normal,
                        // Fixed width, NOT `weight(1f)`: this Column sits inside a
                        // horizontalScroll, which measures its children with an
                        // infinite max width. A weight divides the *remaining*
                        // space, and there is no finite remaining space to divide —
                        // which is what collapsed these tables into unreadable
                        // slivers. `fillMaxWidth()` on the Row was a no-op for the
                        // same reason, so it's gone too; the Row now sizes to the
                        // sum of its cells, which is exactly what should scroll.
                        modifier = Modifier.width(TableCellWidth)
                            .fillMaxHeight()
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

/**
 * `:name:` (emoji, inline-sized) and `::name::` (sticker, larger) — 2.9.5.
 *
 * Matched before the other inline tokens so a shortcode containing markdown-ish
 * characters can't be half-eaten by them. `::name::` is checked first because
 * `:name:` would otherwise match inside it.
 */
private val EMOJI_TOKEN = Regex("::[a-z0-9_]{2,32}::|:[a-z0-9_]{2,32}:")

/** Prefix for the inline-content ids Compose resolves at draw time. */
internal const val EMOJI_INLINE_PREFIX = "emoji:"

private fun inlineMarkdown(
    value: String,
    color: Color,
    linkColor: Color,
    emoji: List<SpaceEmojiDto> = emptyList(),
): AnnotatedString = buildAnnotatedString {
    if (emoji.isNotEmpty()) {
        val byName = emoji.associateBy { it.name }
        var cursor = 0
        for (match in EMOJI_TOKEN.findAll(value)) {
            val sticker = match.value.startsWith("::")
            val name = match.value.trim(':')
            val found = byName[name]
            // Unknown shortcodes are left as literal text — someone typing ":)" or
            // a word between colons shouldn't have it silently vanish.
            if (found == null || (sticker && found.kind != "sticker")) continue
            appendInline(value.substring(cursor, match.range.first), color, linkColor)
            appendInlineContent("$EMOJI_INLINE_PREFIX${found.id}", match.value)
            cursor = match.range.last + 1
        }
        appendInline(value.substring(cursor), color, linkColor)
        return@buildAnnotatedString
    }
    appendInline(value, color, linkColor)
}

/**
 * The ordinary inline pass (bold, code, links, mentions...), factored out of
 * [inlineMarkdown] so the emoji tokenizer can run over the segments between
 * shortcodes and still get full markdown inside them.
 */
private fun androidx.compose.ui.text.AnnotatedString.Builder.appendInline(
    value: String,
    color: Color,
    linkColor: Color,
) {
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
            raw.startsWith('@') -> {
                // Mentions must stay legible on every bubble surface: on classic
                // self bubbles the background is itself `primary`, so a primary
                // foreground washes out. Use the bubble's own content colour for
                // the glyph (guaranteed contrast against its bubble) over a
                // primary-tinted chip so it still reads as an accented mention.
                withStyle(
                    SpanStyle(
                        color = color,
                        fontWeight = FontWeight.Bold,
                        background = linkColor.copy(alpha = 0.22f),
                    ),
                ) { append(raw) }
            }
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

/**
 * Build the inline-content map Compose needs to draw emoji images inside text.
 *
 * One entry per emoji in the nest, keyed by `emoji:<id>` — the same id the
 * tokenizer emits. Stickers get a larger box than inline emoji, matching how they
 * read when sent as a whole message.
 *
 * A `Placeholder` reserves space on the text baseline; without it the image would
 * either break the line or overlap neighbouring glyphs.
 */
@Composable
internal fun rememberEmojiInlineContent(
    emoji: List<SpaceEmojiDto>,
    mediaUrl: (String) -> String?,
): Map<String, InlineTextContent> = remember(emoji) {
    emoji.associate { item ->
        val size = if (item.kind == "sticker") 48.sp else 20.sp
        "$EMOJI_INLINE_PREFIX${item.id}" to InlineTextContent(
            Placeholder(size, size, PlaceholderVerticalAlign.TextCenter),
        ) {
            AsyncImage(
                model = mediaUrl(item.media_key),
                contentDescription = ":${item.name}:",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
