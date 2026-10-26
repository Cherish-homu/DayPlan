package com.jsy.dayplan.ai

import org.junit.Assert.*
import org.junit.Test

class AssistantTransportTest {
    @Test fun `工具调用允许正文为 null`() {
        val result = DeepSeekClient.parseCompletion("""{"choices":[{"message":{"content":null,"tool_calls":[{"id":"c1","type":"function","function":{"name":"propose_draft","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}""")
        assertEquals("", result.content)
        assertEquals("propose_draft", result.toolCalls.single().name)
    }
    @Test fun `流式工具参数按 index 合并直到结束`() {
        val accumulator = StreamResultAccumulator()
        accumulator.add(SseParser.parseData("""{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"propose_draft","arguments":"{\"operation\":"}}]}}]}""")!!)
        accumulator.add(SseParser.parseData("""{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"create\"}"}}]},"finish_reason":"tool_calls"}]}""")!!)
        assertEquals("""{"operation":"create"}""", accumulator.finish().toolCalls.single().arguments)
        assertEquals("c1", accumulator.finish().toolCalls.single().id)
    }
    @Test fun `连接提前断开不能把半截参数当完成`() {
        val accumulator = StreamResultAccumulator()
        accumulator.add(SseChunk(toolCalls = listOf(ToolCallDelta(0, "id", "propose_draft", "{"))))
        assertThrows(AiException.Network::class.java) { accumulator.finish() }
    }
    @Test fun `流式思考与最终正文始终分开`() {
        val accumulator = StreamResultAccumulator()
        accumulator.add(SseChunk(reasoning = "思考内容"))
        accumulator.add(SseChunk(finishReason = "stop"))
        assertEquals("", accumulator.finish().content)
        assertEquals("思考内容", accumulator.finish().reasoning)
    }
    @Test fun `兼容 tool_calls 为 null 的普通正文`() {
        val chunk = SseParser.parseData("""{"choices":[{"delta":{"content":"你好","tool_calls":null},"finish_reason":"stop"}]}""")!!
        assertEquals("你好", chunk.delta)
        assertTrue(chunk.toolCalls.isEmpty())
    }
}
