package com.jsy.dayplan.ai

import com.jsy.dayplan.ai.attach.AttachKind
import com.jsy.dayplan.ai.attach.AttachmentStore
import com.jsy.dayplan.core.FixedClock
import com.jsy.dayplan.reminder.ReminderPlans
import com.jsy.dayplan.data.ScheduleEntity
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3 核心纯逻辑测试：时钟注入、Prompt、协议解析、HTTP 请求/错误映射、
 * Key 加密存储、提醒 0=关闭语义、格式嗅探。
 */
class AiCoreTest {

    private class CaptureTransport(
        private val response: TransportResponse,
    ) : ChatTransport {
        val requests = mutableListOf<TransportRequest>()
        override suspend fun call(request: TransportRequest): TransportResponse {
            requests.add(request)
            return response
        }
    }

    private fun tempDir(): File = Files.createTempDirectory("dayplan-ai-test").toFile()

    // ---------- 固定 Clock 与 Prompt ----------

    @Test
    fun `Prompt 每次读取注入时钟 - 跨午夜跨月跨年 2月29日`() {
        val clock = FixedClock(LocalDateTime.of(2026, 10, 31, 23, 59))
        val before = AiPrompts.nowLine(clock)
        assertTrue(before.contains("2026-10-31"))
        assertTrue(before.contains("Asia/Shanghai"))
        assertTrue(before.contains("星期六"))

        clock.advance(2)
        val after = AiPrompts.nowLine(clock)
        assertTrue("跨午夜后日期基准必须更新：$after", after.contains("2026-11-01"))
        assertTrue(after.contains("星期日"))

        clock.setDateTime(LocalDateTime.of(2026, 12, 31, 23, 30))
        clock.advance(60)
        assertTrue(AiPrompts.nowLine(clock).contains("2027-01-01"))

        clock.setDateTime(LocalDateTime.of(2028, 2, 29, 8, 0))
        assertTrue(AiPrompts.nowLine(clock).contains("2028-02-29"))
    }

    @Test
    fun `system prompt 注入偏好与附件规则`() {
        val clock = FixedClock(LocalDateTime.of(2026, 10, 25, 9, 0))
        val config = AiConfig(defaultDurationMinutes = 45, planWindowStart = "09:00", planWindowEnd = "21:00")
        val plain = AiPrompts.buildSystemPrompt(clock, config, "busy", "existing", attachmentMode = false)
        assertTrue(plain.contains("默认时长 45 分钟"))
        assertTrue(plain.contains("09:00–21:00"))
        assertTrue(plain.contains("2026-10-25"))
        assertFalse(plain.contains("【附件规则】"))
        assertTrue(plain.contains("json"))

        val withAttach = AiPrompts.buildSystemPrompt(clock, config, "busy", "existing", attachmentMode = true)
        assertTrue(withAttach.contains("【附件规则】"))
        assertTrue(withAttach.contains("sourceId"))
        assertTrue(withAttach.contains("不是用户意图"))
    }

    @Test
    fun `关闭参考现有安排时不发送忙碌摘要`() {
        val clock = FixedClock(LocalDateTime.of(2026, 10, 25, 9, 0))
        val prompt = AiPrompts.buildSystemPrompt(
            clock, AiConfig(sendExistingSummary = false), "SECRET-BUSY", "SECRET-ID", false,
        )
        assertFalse(prompt.contains("SECRET-BUSY"))
        assertFalse(prompt.contains("SECRET-ID"))
        assertTrue(prompt.contains("关闭了"))
    }

    @Test
    fun `历史裁剪限制条数与字符`() {
        val history = (1..30).map { "user" to "x".repeat(2000) }
        val trimmed = AiPrompts.trimHistory(history)
        assertTrue(trimmed.size <= AiLimits.HISTORY_MAX_MESSAGES)
        assertTrue(trimmed.sumOf { it.second.length } <= AiLimits.HISTORY_MAX_CHARS)
    }

    // ---------- 协议 ----------

    @Test
    fun `协议解析接受完整计划并忽略未知字段`() {
        val text = """
            {"schemaVersion":1,"kind":"plan","reply":"ok","questions":[],
             "actions":[{"clientActionId":"a1","op":"create_schedule","title":"读文献",
             "startDate":"2026-10-26","endDate":"2026-10-26","allDay":false,
             "startTime":"09:00","endTime":"10:00","remindMinutesBefore":15,
             "futureField":123}]}
        """.trimIndent()
        val r = AiProtocol.parseResponse(text)
        assertEquals("plan", r.kind)
        assertEquals(1, r.actions.size)
        assertEquals("2026-10-26", r.actions[0].startDate)
        assertEquals(15, r.actions[0].remindMinutesBefore)
    }

    @Test
    fun `非法 JSON 给可读协议错误`() {
        val e = assertThrows(AiException.Protocol::class.java) {
            AiProtocol.parseResponse("not json at all")
        }
        assertTrue(e.message!!.contains("不是合法 JSON"))
    }

    // ---------- DeepSeek 请求与错误映射 ----------

    @Test
    fun `请求体包含模型 JSON 模式 非思考与限额`() {
        val transport = CaptureTransport(
            TransportResponse(
                200,
                """{"choices":[{"message":{"content":"{\"kind\":\"chat\",\"reply\":\"hi\"}"},"finish_reason":"stop"}],
                   "usage":{"prompt_tokens":12,"completion_tokens":3}}""",
            ),
        )
        val client = DeepSeekClient(apiKeyProvider = { "sk-test-1234567890" }, transport = transport)
        var result: ChatResult? = null
        kotlinx.coroutines.runBlocking {
            result = client.complete(
                "deepseek-flash",
                listOf(WireMessage.text("system", "sys"), WireMessage.text("user", "hi")),
                jsonMode = true,
            )
        }
        val req = transport.requests.single()
        assertEquals("POST", req.method)
        assertEquals("https://api.deepseek.com/chat/completions", req.url)
        assertEquals("Bearer sk-test-1234567890", req.headers["Authorization"])
        val body = AiProtocol.json.parseToJsonElement(req.body!!).jsonObject
        assertEquals("deepseek-flash", body["model"]!!.jsonPrimitive.content)
        assertEquals("json_object", body["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("disabled", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(AiLimits.MAX_TOKENS, body["max_tokens"]!!.jsonPrimitive.content.toInt())
        assertEquals("false", body["stream"]!!.jsonPrimitive.content)
        assertEquals(2, body["messages"]!!.jsonArray.size)
        assertEquals(12L, result!!.usage!!.promptTokens)
    }

    @Test
    fun `图片以 content 数组 image_url data URL 发送`() {
        val transport = CaptureTransport(TransportResponse(200, """{"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}]}"""))
        val client = DeepSeekClient(apiKeyProvider = { "sk-test-1234567890" }, transport = transport)
        val imageBlock = buildJsonArray {
            add(buildJsonObject { put("type", "text"); put("text", "见图片") })
            add(
                buildJsonObject {
                    put("type", "image_url")
                    put("image_url", buildJsonObject { put("url", "data:image/jpeg;base64,AAAA") })
                },
            )
        }
        assertEquals(1, com.jsy.dayplan.ai.ChatController.countImageBlocks(imageBlock))
        kotlinx.coroutines.runBlocking {
            client.complete("deepseek-flash", listOf(WireMessage("user", imageBlock)), true)
        }
        val body = AiProtocol.json.parseToJsonElement(transport.requests.single().body!!).jsonObject
        val sent = body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        assertTrue(sent[1].toString().contains("data:image/jpeg;base64,AAAA"))
    }

    @Test
    fun `HTTP 状态映射为可读错误类别`() {
        assertTrue(DeepSeekClient.mappedError(401, "{}") is AiException.Auth)
        assertTrue(DeepSeekClient.mappedError(402, "{}") is AiException.Quota)
        assertTrue(DeepSeekClient.mappedError(429, "{}") is AiException.RateLimit)
        assertTrue(DeepSeekClient.mappedError(500, "{}") is AiException.Server)
        assertTrue(DeepSeekClient.mappedError(422, "{}") is AiException.Protocol)
        val detail = DeepSeekClient.mappedError(
            401,
            """{"error":{"message":"Invalid API key"}}""",
        ) as AiException.Auth
        assertTrue(detail.message!!.contains("Invalid API key"))
    }

    @Test
    fun `空内容与截断 finish_reason 可识别`() {
        val empty = DeepSeekClient.parseCompletion(
            """{"choices":[{"message":{"content":""},"finish_reason":"stop"}]}""",
        )
        assertEquals("", empty.content)
        val truncated = DeepSeekClient.parseCompletion(
            """{"choices":[{"message":{"content":"{\"a\""},"finish_reason":"length"}]}""",
        )
        assertEquals("length", truncated.finishReason)
        val e = assertThrows(AiException.Protocol::class.java) {
            DeepSeekClient.parseCompletion("""{"choices":[]}""")
        }
        assertTrue(e.message!!.contains("choices"))
    }

    @Test
    fun `模型列表解析 input_modalities`() {
        val transport = CaptureTransport(
            TransportResponse(
                200,
                """{"data":[{"id":"deepseek-flash","input_modalities":["text","image"]},
                   {"id":"deepseek-v4-pro","input_modalities":["text"]}]}""",
            ),
        )
        val client = DeepSeekClient(apiKeyProvider = { "sk-test-1234567890" }, transport = transport)
        val models = kotlinx.coroutines.runBlocking { client.listModels() }
        assertEquals(2, models.size)
        assertTrue(models.first { it.id == "deepseek-flash" }.modalities.contains("image"))
        assertFalse(models.first { it.id == "deepseek-v4-pro" }.modalities.contains("image"))
    }

    @Test
    fun `超时 断网与取消不会被误判为成功`() {
        val timeout = DeepSeekClient(
            apiKeyProvider = { "sk-test-1234567890" },
            transport = object : ChatTransport {
                override suspend fun call(request: TransportRequest): TransportResponse =
                    throw AiException.Timeout("连接超时")
            },
        )
        val e1 = assertThrows(AiException.Timeout::class.java) {
            kotlinx.coroutines.runBlocking {
                timeout.complete("deepseek-flash", listOf(WireMessage.text("user", "x")), true)
            }
        }
        assertTrue(e1.message!!.contains("超时"))

        val offline = DeepSeekClient(
            apiKeyProvider = { "sk-test-1234567890" },
            transport = object : ChatTransport {
                override suspend fun call(request: TransportRequest): TransportResponse =
                    throw AiException.Network("网络不可用")
            },
        )
        assertTrue(
            assertThrows(AiException.Network::class.java) {
                kotlinx.coroutines.runBlocking {
                    offline.complete("deepseek-flash", listOf(WireMessage.text("user", "x")), true)
                }
            }.message!!.contains("网络"),
        )

        val slow = DeepSeekClient(
            apiKeyProvider = { "sk-test-1234567890" },
            transport = object : ChatTransport {
                override suspend fun call(request: TransportRequest): TransportResponse {
                    kotlinx.coroutines.delay(5_000)
                    return TransportResponse(200, "{}")
                }
            },
        )
        assertThrows(kotlinx.coroutines.TimeoutCancellationException::class.java) {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeout(150) {
                    slow.complete("deepseek-flash", listOf(WireMessage.text("user", "x")), true)
                }
            }
        }
    }

    @Test
    fun `未配置 Key 时不发起请求`() {
        val transport = CaptureTransport(TransportResponse(200, "{}"))
        val client = DeepSeekClient(apiKeyProvider = { null }, transport = transport)
        val e = assertThrows(AiException.NotConfigured::class.java) {
            kotlinx.coroutines.runBlocking {
                client.complete("deepseek-flash", listOf(WireMessage.text("user", "hi")), true)
            }
        }
        assertTrue(e.message!!.contains("Key"))
        assertTrue(transport.requests.isEmpty())
    }

    // ---------- Key 存储 ----------

    @Test
    fun `Key 加密存储 掩码 清除 换机失效`() {
        val dir = tempDir()
        val store = SecureKeyStore(dir, InMemoryKeyCipher())
        assertNull(store.loadApiKey())
        store.saveApiKey("sk-1234567890abcdef")
        assertEquals("sk-1234567890abcdef", store.loadApiKey())
        assertEquals("sk-••••cdef", store.mask("sk-1234567890abcdef"))
        assertTrue(store.looksLikeKey("sk-1234567890abcdef"))
        assertFalse(store.looksLikeKey("1234"))
        val raw = File(dir, "api_key.bin").readBytes().toString(Charsets.UTF_8)
        assertFalse("密文不得包含明文 Key", raw.contains("sk-1234567890abcdef"))
        store.clear()
        assertNull(store.loadApiKey())

        store.saveApiKey("sk-abcdef1234567890")
        val otherDevice = SecureKeyStore(dir, InMemoryKeyCipher())
        assertNull("换机/密钥丢失后按未配置处理", otherDevice.loadApiKey())
    }

    // ---------- 提醒 0=关闭与格式嗅探 ----------

    @Test
    fun `日程提醒 0 表示关闭 - 不能偷偷改成开始时提醒`() {
        val base = ScheduleEntity(
            id = 1, title = "读文献", startDate = "2026-10-26", endDate = "2026-10-26",
            allDay = false, startTime = "09:00", endTime = "10:00", remindMinutesBefore = 0,
        )
        assertNull(ReminderPlans.schedulePlan(base))
        assertNotNull(ReminderPlans.schedulePlan(base.copy(remindMinutesBefore = 15)))
    }

    @Test
    fun `附件格式按文件头判定 - 改扩展名不能假装支持`() {
        val store = AttachmentStore(tempDir())
        assertTrue(store.sniff("a.png", null, byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)) == AttachKind.IMAGE_PNG)
        assertTrue(store.sniff("a.pdf", "application/pdf", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())) == AttachKind.IMAGE_JPEG)
        assertTrue(store.sniff("a.pdf", "application/pdf", "%PDF-1.7".toByteArray()) == AttachKind.PDF)
        assertTrue(store.sniff("a.bin", null, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())) == AttachKind.IMAGE_JPEG)
        assertTrue(store.sniff("a.docx", null, byteArrayOf(0x50, 0x4B, 3, 4)) == AttachKind.DOCX)
        assertTrue(store.sniff("a.xlsx", null, byteArrayOf(0x50, 0x4B, 3, 4)) == AttachKind.XLSX)
        assertTrue(store.sniff("a.exe", "application/octet-stream", byteArrayOf(0x4D, 0x5A)) == AttachKind.UNSUPPORTED)
        assertTrue(store.sniff("a.txt", "text/plain", "中文".toByteArray()) == AttachKind.TXT)
        assertTrue(store.sniff("a.csv", "text/csv", "a,b".toByteArray()) == AttachKind.CSV)
    }
}
