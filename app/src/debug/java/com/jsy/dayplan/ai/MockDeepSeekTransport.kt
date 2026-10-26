package com.jsy.dayplan.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 演示（Mock）通道：仅在 debug 变体可用，产生明确标识为 Mock 的演示计划，
 * 不连接真实 DeepSeek 服务。release 包不含本类。
 */
class MockDeepSeekTransport(
    /** 覆盖默认演示回复；测试/演示时可注入不同剧本。 */
    private val scriptedResponse: String? = null,
) : DemoChatTransport, StreamingChatTransport {

    /** 捕获到的请求（供演示页展示与测试断言）。 */
    val capturedRequests = mutableListOf<TransportRequest>()

    /** 演示“附件首次需要澄清，纯文字补充后继续”的多轮链路。 */
    private var attachmentClarified = false

    override suspend fun call(request: TransportRequest): TransportResponse {
        capturedRequests.add(request)
        return withContext(Dispatchers.Default) {
            // 模拟网络延迟，便于界面展示“请求中/可取消”
            kotlinx.coroutines.delay(600)
            // 与官方 Chat Completions 相同的外层结构：content 内是回复文本
            val content = kotlinx.serialization.json.JsonPrimitive(replyContent(request)).toString()
            TransportResponse(
                200,
                """{"choices":[{"message":{"content":$content},"finish_reason":"stop"}],
                   "usage":{"prompt_tokens":0,"completion_tokens":0}}""",
            )
        }
    }

    /** 普通聊天（无 response_format）返回纯文字；日程助手返回计划 JSON。 */
    override suspend fun stream(
        request: TransportRequest,
        onDelta: (String) -> Unit,
    ): ChatResult {
        capturedRequests.add(request)
        val content = replyContent(request)
        var index = 0
        while (index < content.length) {
            val end = minOf(index + 6, content.length)
            onDelta(content.substring(index, end))
            index = end
            kotlinx.coroutines.delay(80)
        }
        return ChatResult(content, "stop", ChatUsage(0, 0))
    }

    private fun replyContent(request: TransportRequest): String {
        val body = request.body ?: ""
        if (!body.contains("\"response_format\"")) {
            return """
                【Mock 演示】你好，我是日序助手。
                这里只做纯文字聊天，不会创建或修改任何日程。
                - 需要安排日程时，请到首页手动新增
                - 这里只做自然语言回答
            """.trimIndent()
        }
        val hasMaterials = body.contains("【附件提取内容")
        return when {
            scriptedResponse != null -> scriptedResponse
            hasMaterials && !attachmentClarified -> {
                attachmentClarified = true
                """{"schemaVersion":1,"kind":"clarify","reply":"【Mock 演示】需要先确认年份。","questions":["这些安排是哪一年？请回复年份（例如 2099）"],"actions":[]}"""
            }
            else -> defaultPlan(request)
        }
    }

    private fun defaultPlan(request: TransportRequest): String {
        // 从请求中的附件提取内容/图片清单里取真实来源 id，保证演示批次可通过本地来源校验
        val body = request.body ?: ""
        // 只从“附件提取内容”之后的用户材料里取来源，避免命中 system prompt 的示例 id
        val marker = "【附件提取内容"
        val idx = body.indexOf(marker)
        val userMaterials = if (idx >= 0) body.substring(idx) else ""
        val sourceIds = LinkedHashSet<String>()
        Regex("""(img\d+|pdf\d+-p\d+|docx\d+-(?:¶|T)\d+|xlsx\d+-第\d+行起|txt\d+-L\d+|csv\d+-R\d+)""")
            .findAll(userMaterials)
            .forEach { sourceIds.add(it.value) }
        val srcJson = if (sourceIds.isEmpty()) {
            ""
        } else {
            ""","sourceIds":${kotlinx.serialization.json.JsonArray(sourceIds.map { kotlinx.serialization.json.JsonPrimitive(it) })}"""
        }

        // 固定演示剧本：明确标注 kind=plan 与来源
        return """
        {
          "schemaVersion": 1,
          "kind": "plan",
          "reply": "【Mock 演示】已整理 2 条计划（演示数据，未连接真实服务）。",
          "questions": [],
          "actions": [
            {
              "clientActionId": "m1",
              "op": "create_schedule",
              "title": "读文献（Mock 演示）",
              "startDate": "$MOCK_TOMORROW",
              "endDate": "$MOCK_TOMORROW",
              "allDay": false,
              "startTime": "09:00",
              "endTime": "10:00",
              "category": 0,
              "remindMinutesBefore": 15$srcJson
            },
            {
              "clientActionId": "m2",
              "op": "create_todo",
              "title": "交报告（Mock 演示）",
              "dueDate": "$MOCK_NEXT_FRIDAY",
              "priority": 1$srcJson
            }
          ]
        }
        """.trimIndent().trim()
    }

    companion object {
        const val MOCK_TOMORROW = "2026-10-26"
        const val MOCK_NEXT_FRIDAY = "2026-10-30"
    }
}
