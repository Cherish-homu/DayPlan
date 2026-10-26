package com.jsy.dayplan.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiModeIdentityTest {
    @Test
    fun `识别截图中的模式询问及常见问法`() {
        listOf("你现在是助手模式还是聊天模式", "请问，你现在是什么模式？", "当前模式是什么？")
            .forEach { assertTrue(it, AiModeIdentity.isModeQuestion(it)) }
    }

    @Test
    fun `普通问题和包含安排的复合请求不能被模式说明截走`() {
        listOf("聊天模式有什么用", "你现在是什么模式？另外明天九点读文献", "帮我安排明天的日程", "你好")
            .forEach { assertFalse(it, AiModeIdentity.isModeQuestion(it)) }
    }
}
