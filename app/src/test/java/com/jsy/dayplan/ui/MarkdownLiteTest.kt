package com.jsy.dayplan.ui

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownLiteTest {

    @Test
    fun `解析标题 列表 代码块与段落`() {
        val blocks = MarkdownLite.parse("## 标题\n- 项目\n```\ncode\n```\n正文")
        assertEquals(4, blocks.size)
        assertTrue(blocks[0] is MarkdownLite.Block.Heading)
        assertTrue(blocks[1] is MarkdownLite.Block.Bullet)
        assertTrue(blocks[2] is MarkdownLite.Block.Code)
        assertTrue(blocks[3] is MarkdownLite.Block.Paragraph)
    }

    @Test
    fun `行内粗体与代码样式`() {
        val text = MarkdownLite.inline("普通 **粗体** 和 `code`")
        assertEquals(2, text.spanStyles.size)
        assertTrue(text.spanStyles.any { it.item.fontWeight == FontWeight.SemiBold })
        assertTrue(text.spanStyles.any { it.item.fontFamily == FontFamily.Monospace })
        assertEquals("普通 粗体 和 code", text.text)
    }
}
