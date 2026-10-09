package com.shuddh.lab.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Small-model output cleanup: literal "\n" escapes, stray markdown fences, excess blank lines. */
fun cleanModelText(s: String): String = s
    .replace("\\r\\n", "\n").replace("\\n", "\n").replace("\\t", "  ").replace("\r", "")
    .replace(Regex("""```[a-zA-Z]*\n?"""), "")
    .replace(Regex("""\n{3,}"""), "\n\n")
    .trim()

/** Inline markdown → styled text: **bold**, __bold__, *italic*, _italic_, `code`. */
fun inlineMarkdown(s: String, accent: Color): AnnotatedString = buildAnnotatedString {
    val re = Regex("""\*\*(.+?)\*\*|__(.+?)__|`([^`]+)`|(?<![\w*])\*(?!\s)(.+?)(?<!\s)\*(?![\w*])|(?<![\w_])_(?!\s)(.+?)(?<!\s)_(?![\w_])""")
    var last = 0
    for (m in re.findAll(s)) {
        append(s.substring(last, m.range.first))
        val g = m.groupValues
        when {
            g[1].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(g[1]) }
            g[2].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(g[2]) }
            g[3].isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = accent)) { append(g[3]) }
            g[4].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[4]) }
            else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[5]) }
        }
        last = m.range.last + 1
    }
    append(s.substring(last))
    // Any unmatched leftover asterisks/hashes from a cut-off stream are hidden.
}.let { a -> if (a.text.contains("**")) AnnotatedString(a.text.replace("**", ""), a.spanStyles) else a }

/** Renders chat markdown: headings, bullet and numbered lists, bold/italic/code, paragraphs. */
@Composable
fun MarkdownText(raw: String, color: Color = Palette.text, fontSize: Int = 15) {
    val lines = cleanModelText(raw).lines()
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        for (line in lines) {
            val l = line.trimEnd()
            val t = l.trimStart()
            val indent = ((l.length - t.length) / 2).coerceAtMost(3)
            when {
                t.isEmpty() -> Text("", fontSize = 6.sp)
                Regex("""^#{1,6}\s+""").containsMatchIn(t) -> {
                    val level = t.takeWhile { it == '#' }.length
                    Text(inlineMarkdown(t.dropWhile { it == '#' }.trim(), Palette.cyan), color = color, fontFamily = Display, fontWeight = FontWeight.Bold,
                        fontSize = (fontSize + (if (level <= 2) 3 else 1)).sp, lineHeight = (fontSize + 8).sp)
                }
                Regex("""^[-*•+]\s+""").containsMatchIn(t) -> Row {
                    Text("  ".repeat(indent) + "•", color = Palette.cyan, fontSize = fontSize.sp, fontWeight = FontWeight.Bold)
                    androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
                    Text(inlineMarkdown(t.replace(Regex("""^[-*•+]\s+"""), ""), Palette.cyan), color = color, fontSize = fontSize.sp, lineHeight = (fontSize + 6).sp)
                }
                Regex("""^\d{1,2}[.)]\s+""").containsMatchIn(t) -> Row {
                    val num = Regex("""^\d{1,2}""").find(t)!!.value
                    Text("  ".repeat(indent) + "$num.", color = Palette.cyan, fontSize = fontSize.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(26.dp))
                    Text(inlineMarkdown(t.replace(Regex("""^\d{1,2}[.)]\s+"""), ""), Palette.cyan), color = color, fontSize = fontSize.sp, lineHeight = (fontSize + 6).sp)
                }
                Regex("""^[-*_]{3,}$""").matches(t) -> Text("—", color = Palette.muted)
                else -> Text(inlineMarkdown(t, Palette.cyan), color = color, fontSize = fontSize.sp, lineHeight = (fontSize + 6).sp)
            }
        }
    }
}
