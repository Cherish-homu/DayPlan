package com.jsy.dayplan.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 轻量 Markdown：普通聊天里够用的标题、列表、代码块、
 * **粗体** 与 `行内代码`；不引入第三方渲染库。
 */
object MarkdownLite {

    sealed interface Block {
        data class Paragraph(val text: String) : Block
        data class Heading(val level: Int, val text: String) : Block
        data class Bullet(val text: String) : Block
        data class Code(val text: String) : Block
    }

    fun parse(text: String): List<Block> {
        val lines = text.replace("\r\n", "\n").split('\n')
        val out = mutableListOf<Block>()
        val paragraph = mutableListOf<String>()

        fun flushParagraph() {
            if (paragraph.isNotEmpty()) {
                out.add(Block.Paragraph(paragraph.joinToString("\n").trim()))
                paragraph.clear()
            }
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimStart()
            when {
                trimmed.startsWith("```") -> {
                    flushParagraph()
                    val code = mutableListOf<String>()
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                        code.add(lines[i])
                        i++
                    }
                    out.add(Block.Code(code.joinToString("\n")))
                }

                trimmed.startsWith("#") -> {
                    flushParagraph()
                    val level = trimmed.takeWhile { it == '#' }.length.coerceIn(1, 3)
                    out.add(Block.Heading(level, trimmed.drop(level).trim()))
                }

                trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("• ") -> {
                    flushParagraph()
                    out.add(Block.Bullet(trimmed.drop(2).trim()))
                }

                line.isBlank() -> flushParagraph()
                else -> paragraph.add(line)
            }
            i++
        }
        flushParagraph()
        return out
    }

    fun inline(text: String): AnnotatedString = buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            if (text.startsWith("**", i)) {
                val end = text.indexOf("**", i + 2)
                if (end > i + 2) {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                        append(text.substring(i + 2, end))
                    }
                    i = end + 2
                    continue
                }
            }
            if (text[i] == '`') {
                val end = text.indexOf('`', i + 1)
                if (end > i + 1) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                    continue
                }
            }
            append(text[i])
            i++
        }
    }
}

@Composable
fun MarkdownText(
    text: String,
    fontSize: TextUnit = 14.sp,
    color: Color = Color.Unspecified,
) {
    val blocks = remember(text) { MarkdownLite.parse(text) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownLite.Block.Paragraph -> Text(
                    MarkdownLite.inline(block.text),
                    fontSize = fontSize,
                    lineHeight = fontSize * 1.45f,
                    color = color,
                )

                is MarkdownLite.Block.Heading -> Text(
                    MarkdownLite.inline(block.text),
                    fontSize = when (block.level) {
                        1 -> (fontSize.value + 4).sp
                        2 -> (fontSize.value + 2).sp
                        else -> (fontSize.value + 1).sp
                    },
                    fontWeight = FontWeight.SemiBold,
                    color = color,
                )

                is MarkdownLite.Block.Bullet -> Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("•", fontSize = fontSize, color = color)
                    Text(
                        MarkdownLite.inline(block.text),
                        fontSize = fontSize,
                        lineHeight = fontSize * 1.45f,
                        color = color,
                        modifier = Modifier.weight(1f),
                    )
                }

                is MarkdownLite.Block.Code -> Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Text(
                        block.text,
                        fontFamily = FontFamily.Monospace,
                        fontSize = (fontSize.value - 1).sp,
                        color = color,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
        }
    }
}
