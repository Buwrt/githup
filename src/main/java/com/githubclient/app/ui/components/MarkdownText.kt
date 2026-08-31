package com.githubclient.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

/**
 * Lightweight Markdown renderer for Compose.
 * Supports: headers, bold, italic, inline code, code blocks, links, lists, blockquotes, hr.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier
) {
    val lines = markdown.lines()

    Column(
        modifier = modifier
            .fillMaxWidth()
    ) {
        var inCodeBlock = false
        var codeBlockContent = StringBuilder()
        var codeBlockLang = ""

        for (line in lines) {
            // Code block toggle
            if (line.trimStart().startsWith("```")) {
                if (inCodeBlock) {
                    renderCodeBlock(codeBlockContent.toString(), codeBlockLang)
                    codeBlockContent = StringBuilder()
                    codeBlockLang = ""
                    inCodeBlock = false
                } else {
                    codeBlockLang = line.trimStart().removePrefix("```").trim()
                    inCodeBlock = true
                }
                continue
            }

            if (inCodeBlock) {
                if (codeBlockContent.isNotEmpty()) codeBlockContent.append("\n")
                codeBlockContent.append(line)
                continue
            }

            // Empty line
            if (line.isBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                continue
            }

            // Horizontal rule
            if (line.matches(Regex("^[-*_]{3,}\\s*$"))) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                continue
            }

            // Headers
            val headerMatch = Regex("^(#{1,6})\\s+(.+)").matchEntire(line)
            if (headerMatch != null) {
                val level = headerMatch.groupValues[1].length
                val text = headerMatch.groupValues[2]
                renderHeader(text, level)
                continue
            }

            // Blockquote
            if (line.trimStart().startsWith(">")) {
                val text = line.trimStart().removePrefix(">").trim()
                renderBlockquote(text)
                continue
            }

            // Unordered list
            if (line.trimStart().matches(Regex("^[*-+]\\s+.+"))) {
                val text = line.trimStart().replace(Regex("^[-*+]\\s*"), "")
                val indent = line.takeWhile { it == ' ' }.length / 2
                renderListItem(text, indent, ordered = false, number = 0)
                continue
            }

            // Ordered list
            val orderedMatch = Regex("^(\\s*)(\\d+)\\.\\s+(.+)").matchEntire(line)
            if (orderedMatch != null) {
                val indent = orderedMatch.groupValues[1].length / 2
                val number = orderedMatch.groupValues[2].toIntOrNull() ?: 1
                val text = orderedMatch.groupValues[3]
                renderListItem(text, indent, ordered = true, number = number)
                continue
            }

            // Image
            val imgMatch = Regex("!\\[(.+?)]\\((.+?)\\)").find(line)
            if (imgMatch != null) {
                renderImagePlaceholder(imgMatch.groupValues[1], imgMatch.groupValues[2])
                continue
            }

            // Regular paragraph
            renderParagraph(line)
        }

        // Flush remaining code block
        if (inCodeBlock && codeBlockContent.isNotEmpty()) {
            renderCodeBlock(codeBlockContent.toString(), codeBlockLang)
        }
    }
}

@Composable
private fun renderHeader(text: String, level: Int) {
    val (fontSize, fontWeight, topPadding) = when (level) {
        1 -> Triple(22, FontWeight.Bold, 12)
        2 -> Triple(19, FontWeight.Bold, 10)
        3 -> Triple(17, FontWeight.SemiBold, 8)
        4 -> Triple(15, FontWeight.SemiBold, 6)
        5 -> Triple(14, FontWeight.Medium, 4)
        else -> Triple(13, FontWeight.Medium, 4)
    }
    Spacer(modifier = Modifier.height(topPadding.dp))
    Text(
        text = parseInlineMarkdown(text),
        style = MaterialTheme.typography.bodyLarge.copy(
            fontWeight = fontWeight,
            fontSize = fontSize.sp
        ),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth()
    )
    if (level <= 2) {
        HorizontalDivider(
            modifier = Modifier.padding(top = 4.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )
    }
}

@Composable
private fun renderBlockquote(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(20.dp)
                    .background(MaterialTheme.colorScheme.primary)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = parseInlineMarkdown(text),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun renderListItem(text: String, indent: Int, ordered: Boolean, number: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = (indent * 16 + 8).dp,
                end = 8.dp,
                top = 2.dp,
                bottom = 2.dp
            ),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = if (ordered) "$number." else "\u2022",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(end = 8.dp)
        )
        Text(
            text = parseInlineMarkdown(text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun renderCodeBlock(code: String, lang: String) {
    Surface(
        color = Color(0xFF1E1E2E),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            if (lang.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Code,
                        contentDescription = null,
                        tint = Color(0xFF89B4FA),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = lang,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF89B4FA)
                    )
                }
            }
            Text(
                text = code.trimEnd(),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFFCDD6F4)
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun renderImagePlaceholder(alt: String, url: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Image,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "[图片] $alt",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun renderParagraph(text: String) {
    Text(
        text = parseInlineMarkdown(text),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
    )
}

/**
 * Parse inline markdown: **bold**, *italic*, `code`, [link](url), ~~strikethrough~~.
 */
private fun parseInlineMarkdown(text: String): AnnotatedString {
    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            when {
                // Bold **text**
                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end > i + 2) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(text.substring(i + 2, end))
                        }
                        i = end + 2
                        continue
                    }
                }
                // Strikethrough ~~text~~
                text.startsWith("~~", i) -> {
                    val end = text.indexOf("~~", i + 2)
                    if (end > i + 2) {
                        withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                            append(text.substring(i + 2, end))
                        }
                        i = end + 2
                        continue
                    }
                }
                // Italic *text* or _text_
                (text[i] == '*' && i + 1 < text.length && text[i + 1] != '*') ||
                (text[i] == '_' && i + 1 < text.length && text[i + 1] != '_') -> {
                    val delim = text[i]
                    val end = text.indexOf(delim, i + 1)
                    if (end > i + 1) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(text.substring(i + 1, end))
                        }
                        i = end + 1
                        continue
                    }
                }
                // Inline code `text`
                text[i] == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i + 1) {
                        withStyle(SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = Color(0xFFE8E8E8),
                            color = Color(0xFFD63384)
                        )) {
                            append(" " + text.substring(i + 1, end) + " ")
                        }
                        i = end + 1
                        continue
                    }
                }
                // Link [text](url)
                text[i] == '[' -> {
                    val closeBracket = text.indexOf(']', i + 1)
                    if (closeBracket > i + 1 && closeBracket + 1 < text.length && text[closeBracket + 1] == '(') {
                        val closeParen = text.indexOf(')', closeBracket + 2)
                        if (closeParen > closeBracket + 2) {
                            val linkText = text.substring(i + 1, closeBracket)
                            withStyle(SpanStyle(
                                color = Color(0xFF0969DA),
                                textDecoration = TextDecoration.Underline
                            )) {
                                append(linkText)
                            }
                            i = closeParen + 1
                            continue
                        }
                    }
                }
            }
            append(text[i])
            i++
        }
    }
}
