package com.aras.client.ui.checkupdate

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.res.stringResource
import com.aras.client.R

/**
 * Renders GitHub release notes cleanly: headers become styled titles,
 * bullets become real bullets, `**bold**` becomes bold, code spans get a
 * monospace style, and links/HTML are stripped.
 */
@Composable
fun ReleaseNotesView(notes: String, modifier: Modifier = Modifier) {
    val scrollState = rememberScrollState()
    SelectionContainer {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp)
                .verticalScroll(scrollState)
        ) {
            parseReleaseNotes(notes).forEach { line ->
                when (line) {
                    is ReleaseLine.Header -> Text(
                        text = line.text,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
                    )
                    is ReleaseLine.Bullet -> Text(
                        text = styledText("•  " + line.text),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 6.dp, bottom = 2.dp)
                    )
                    is ReleaseLine.Paragraph -> Text(
                        text = styledText(line.text),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                    ReleaseLine.Spacer -> Text(
                        text = " ",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            if (parseReleaseNotes(notes).isEmpty()) {
                Text(
                    text = stringResource(R.string.update_no_notes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private sealed interface ReleaseLine {
    data class Header(val text: String) : ReleaseLine
    data class Bullet(val text: String) : ReleaseLine
    data class Paragraph(val text: String) : ReleaseLine
    data object Spacer : ReleaseLine
}

private fun parseReleaseNotes(notes: String): List<ReleaseLine> {
    if (notes.isBlank()) return emptyList()
    val lines = mutableListOf<ReleaseLine>()
    notes.lines().forEach { raw ->
        val line = raw.trim().trimEnd('\r')
        when {
            line.isEmpty() -> if (lines.isNotEmpty() && lines.last() != ReleaseLine.Spacer) {
                lines.add(ReleaseLine.Spacer)
            }
            line.startsWith("#") -> lines.add(
                ReleaseLine.Header(line.trimStart('#', ' ', ':'))
            )
            line.startsWith("- ") || line.startsWith("* ") || line.startsWith("• ") ->
                lines.add(ReleaseLine.Bullet(cleanInline(line.substring(2).trim())))
            Regex("^\\d+\\.").containsMatchIn(line) ->
                lines.add(ReleaseLine.Bullet(cleanInline(line)))
            line.startsWith("```") || line.startsWith("<") -> {
                // skip code fences and raw HTML
            }
            else -> lines.add(ReleaseLine.Paragraph(cleanInline(line)))
        }
    }
    return lines
}

/** Strips markdown emphasis, HTML tags and link syntax from a line. */
private fun cleanInline(text: String): String {
    var out = text
        .replace(Regex("<[^>]+>"), "")                       // HTML tags
        .replace(Regex("!\\[([^\\]]*)\\]\\([^)]*\\)"), "$1") // images → alt
        .replace(Regex("\\[([^\\]]+)\\]\\([^)]*\\)"), "$1")  // links → text
        .replace(Regex("(\\*\\*|__)(.+?)\\1"), "$2")         // bold
        .replace(Regex("(\\*|_)(.+?)\\1"), "$2")             // italic
        .replace(Regex("~~(.+?)~~"), "$1")                   // strikethrough
        .replace(Regex("`([^`]*)`"), "$1")                   // code spans
        .trim()
    return out
}

/** Applies **bold** styling to the remaining emphasis markers. */
private fun styledText(text: String): AnnotatedString = buildAnnotatedString {
    val bold = SpanStyle(fontWeight = FontWeight.Bold)
    val mono = SpanStyle(fontFamily = FontFamily.Monospace)
    var i = 0
    var plain = ""
    val ranges = mutableListOf<Pair<IntRange, SpanStyle>>()
    while (i < text.length) {
        when {
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end > 0) {
                    val segment = text.substring(i + 2, end)
                    ranges.add(plain.length..(plain.length + segment.length) to bold)
                    plain += segment
                    i = end + 2
                } else {
                    plain += text[i]; i++
                }
            }
            text[i] == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end > 0) {
                    val segment = text.substring(i + 1, end)
                    ranges.add(plain.length..(plain.length + segment.length) to mono)
                    plain += segment
                    i = end + 1
                } else {
                    plain += text[i]; i++
                }
            }
            else -> {
                plain += text[i]; i++
            }
        }
    }
    append(plain)
    ranges.forEach { (range, style) ->
        if (range.first < plain.length && !range.isEmpty()) {
            addStyle(style, range.first, range.last.coerceAtMost(plain.length - 1) + 1)
        }
    }
}
