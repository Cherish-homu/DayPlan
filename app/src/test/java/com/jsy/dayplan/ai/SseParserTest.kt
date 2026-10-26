package com.jsy.dayplan.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 普通聊天 SSE 解析：正文增量、finish_reason、usage、错误行。 */
class SseParserTest {

    @Test
    fun `解析正文增量`() {
        val chunk = SseParser.parseData(
            """{"choices":[{"delta":{"content":"你好"},"finish_reason":null}]}""",
        )!!
        assertEquals("你好", chunk.delta)
        assertNull(chunk.finishReason)
    }

    @Test
    fun `解析结束原因与用量`() {
        val finish = SseParser.parseData(
            """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
        )!!
        assertEquals("stop", finish.finishReason)

        val usage = SseParser.parseData(
            """{"choices":[],"usage":{"prompt_tokens":3,"completion_tokens":5}}""",
        )!!
        assertEquals(3L, usage.usage!!.promptTokens)
        assertEquals(5L, usage.usage!!.completionTokens)
    }

    @Test
    fun `内容块数组与推理字段都能解析`() {
        val blocks = SseParser.parseData(
            """{"choices":[{"delta":{"content":[{"type":"text","text":"你"},{"type":"text","text":"好"}]}}]}""",
        )!!
        assertEquals("你好", blocks.delta)
        assertNull(blocks.reasoning)

        val reasoning = SseParser.parseData(
            """{"choices":[{"delta":{"reasoning_content":"先想一下"}}]}""",
        )!!
        assertNull(reasoning.delta)
        assertEquals("先想一下", reasoning.reasoning)
    }

    @Test
    fun `非流式支持内容数组 但思考不能冒充最终答复`() {
        val array = DeepSeekClient.parseCompletion(
            """{"choices":[{"message":{"content":[{"type":"text","text":"正文"}]},"finish_reason":"stop"}]}""",
        )
        assertEquals("正文", array.content)

        val reasoning = DeepSeekClient.parseCompletion(
            """{"choices":[{"message":{"content":"","reasoning_content":"思考正文"},"finish_reason":"stop"}]}""",
        )
        assertEquals("", reasoning.content)
        assertEquals("思考正文", reasoning.reasoning)
    }

    @Test
    fun `错误行与无法识别的行`() {
        val error = SseParser.parseData("""{"error":{"message":"bad"}}""")!!
        assertEquals("bad", error.error)
        assertNull(SseParser.parseData("[DONE]"))
        assertNull(SseParser.parseData("not json"))
        assertNull(SseParser.parseData("""{"choices":[{"delta":{}}]}"""))
        assertNull("畸形字段不能抛出中断整条流", SseParser.parseData("""{"error":"oops"}"""))
        assertNull(SseParser.parseData("""{"choices":"not-array"}"""))
    }
}
