package com.jsy.dayplan.ai

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** 可注入的 HTTP 传输层：生产走 HttpURLConnection，测试捕获真实请求报文。 */
interface ChatTransport {
    suspend fun call(request: TransportRequest): TransportResponse
}

/**
 * 演示（Mock）传输层标记：主源码据此显示“演示模式”，
 * 具体实现只存在于 debug 源集，release 包不含演示代码。
 */
interface DemoChatTransport : ChatTransport

data class TransportRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: String? = null,
    val connectTimeoutMs: Long = 15_000,
    val readTimeoutMs: Long = 120_000,
    /** 响应体读取上限（字节）。 */
    val maxResponseBytes: Long = AiLimits.RESPONSE_BODY_MAX_BYTES,
)

data class TransportResponse(val code: Int, val body: String)

/** HttpURLConnection 实现：仅 HTTPS、正常证书校验、有界读取、可取消。 */
class HttpTransport : StreamingChatTransport {
    override suspend fun call(request: TransportRequest): TransportResponse =
        withContext(Dispatchers.IO) {
            val url = URL(request.url)
            if (!url.protocol.equals("https", ignoreCase = true)) {
                throw AiException.Network("仅支持 HTTPS 地址")
            }
            val conn = url.openConnection() as HttpURLConnection
            // 协程取消时立即断开阻塞读取（HttpURLConnection 的 read 本身不可中断）
            val cancelHandle = coroutineContext[kotlinx.coroutines.Job]
                ?.invokeOnCompletion { conn.disconnect() }
            try {
                conn.requestMethod = request.method
                conn.connectTimeout = request.connectTimeoutMs.toInt()
                conn.readTimeout = request.readTimeoutMs.toInt()
                conn.instanceFollowRedirects = false
                request.headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                if (request.body != null) {
                    conn.doOutput = true
                    val bytes = request.body.toByteArray(Charsets.UTF_8)
                    conn.setFixedLengthStreamingMode(bytes.size)
                    conn.outputStream.use { it.write(bytes); it.flush() }
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.use { readBounded(it, request.maxResponseBytes) } ?: ""
                ensureActive()
                TransportResponse(code, body)
            } catch (e: java.net.SocketTimeoutException) {
                throw AiException.Timeout("连接超时，请稍后重试")
            } catch (e: javax.net.ssl.SSLException) {
                throw AiException.Network("安全连接失败：${e.message?.take(120)}")
            } catch (e: IOException) {
                coroutineContext.ensureActive()
                throw AiException.Network("网络不可用或连接中断：${e.message?.take(120)}")
            } finally {
                cancelHandle?.dispose()
                conn.disconnect()
            }
        }

    /** SSE 流式补全：逐行解析 data: JSON，增量回调正文。 */
    override suspend fun stream(
        request: TransportRequest,
        onDelta: (String) -> Unit,
    ): ChatResult = withContext(Dispatchers.IO) {
        val url = URL(request.url)
        if (!url.protocol.equals("https", ignoreCase = true)) {
            throw AiException.Network("仅支持 HTTPS 地址")
        }
        val conn = url.openConnection() as HttpURLConnection
        val cancelHandle = coroutineContext[kotlinx.coroutines.Job]
            ?.invokeOnCompletion { conn.disconnect() }
        try {
            conn.requestMethod = request.method
            conn.connectTimeout = request.connectTimeoutMs.toInt()
            conn.readTimeout = request.readTimeoutMs.toInt()
            conn.instanceFollowRedirects = false
            request.headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.setRequestProperty("Accept", "text/event-stream")
            if (request.body != null) {
                conn.doOutput = true
                val bytes = request.body.toByteArray(Charsets.UTF_8)
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes); it.flush() }
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                val body = conn.errorStream?.use { readBounded(it, request.maxResponseBytes) } ?: ""
                throw DeepSeekClient.mappedError(code, body)
            }

            val content = StringBuilder()
            val reasoning = StringBuilder()
            var finish = "stop"
            var usage: ChatUsage? = null
            var totalBytes = 0L
            conn.inputStream.use { input ->
                val reader = input.bufferedReader(Charsets.UTF_8)
                while (true) {
                    ensureActive()
                    val line = reader.readLine() ?: break
                    totalBytes += line.toByteArray(Charsets.UTF_8).size
                    if (totalBytes > request.maxResponseBytes) {
                        throw AiException.Protocol(
                            "响应体超过 ${request.maxResponseBytes / 1024} KiB 上限",
                        )
                    }
                    val trimmed = line.trimStart()
                    if (!trimmed.startsWith("data:")) continue
                    val data = trimmed.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    val chunk = SseParser.parseData(data) ?: continue
                    chunk.error?.let {
                        throw AiException.Server("DeepSeek 流式响应错误：$it")
                    }
                    chunk.delta?.takeIf { it.isNotEmpty() }?.let {
                        content.append(it)
                        onDelta(it)
                    }
                    chunk.reasoning?.takeIf { it.isNotEmpty() }?.let { reasoning.append(it) }
                    chunk.finishReason?.let { finish = it }
                    chunk.usage?.let { usage = it }
                }
            }
            // 推理模型可能把可见内容放在 reasoning_content；至少保证返回非空正文
            val text = content.toString().ifBlank { reasoning.toString() }
            ChatResult(text, finish, usage)
        } catch (e: java.net.SocketTimeoutException) {
            throw AiException.Timeout("连接超时，请稍后重试")
        } catch (e: javax.net.ssl.SSLException) {
            throw AiException.Network("安全连接失败：${e.message?.take(120)}")
        } catch (e: IOException) {
            coroutineContext.ensureActive()
            throw AiException.Network("网络不可用或连接中断：${e.message?.take(120)}")
        } finally {
            cancelHandle?.dispose()
            conn.disconnect()
        }
    }

    companion object {
        fun readBounded(input: java.io.InputStream, maxBytes: Long): String {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > maxBytes) throw AiException.Protocol("响应体超过 ${maxBytes / 1024} KiB 上限")
                out.write(buf, 0, n)
            }
            return out.toString("UTF-8")
        }
    }
}

/** 客户端限制（需求第 3 节）。 */
object AiLimits {
    /** 非思考模式默认 8K；4096 对 20 条动作的中文 JSON 偏小，容易截断。 */
    const val MAX_TOKENS = 8192
    const val USER_INPUT_MAX_CHARS = 4000
    const val HISTORY_MAX_MESSAGES = 12
    const val HISTORY_MAX_CHARS = 16000
    const val RESPONSE_BODY_MAX_BYTES = 1L * 1024 * 1024
    /** 文档材料单独计入预算：每请求文档提取文本上限。 */
    const val DOC_TEXT_MAX_CHARS = 20000
}

/** 一条消息：content 为纯文本或 content 块数组（视觉请求）。 */
data class WireMessage(val role: String, val content: JsonElement) {
    companion object {
        fun text(role: String, text: String) = WireMessage(role, JsonPrimitive(text))
    }
}

/** 模型条目（GET /models）。 */
data class ModelInfo(val id: String, val modalities: Set<String>)

data class ChatUsage(val promptTokens: Long, val completionTokens: Long) {
    fun display(): String = "本次用量：输入 $promptTokens / 输出 $completionTokens tokens"
}

data class ChatResult(
    val content: String,
    val finishReason: String,
    val usage: ChatUsage?,
)

/** SSE 一行 data 的解析结果（普通聊天流式输出）。 */
data class SseChunk(
    val delta: String? = null,
    /** 部分推理模型把可见/思考文本放在 reasoning_content。 */
    val reasoning: String? = null,
    val finishReason: String? = null,
    val usage: ChatUsage? = null,
    val error: String? = null,
)

/** content 既可能是字符串，也可能是 [{type:text,text:...}] 块数组。 */
private fun textContent(element: JsonElement?): String = when (element) {
    null, is JsonNull -> ""
    is JsonPrimitive -> element.contentOrNull ?: ""
    is JsonArray -> element.joinToString("") { textContent(it) }
    is JsonObject -> textContent(element["text"])
}

/** 解析 DeepSeek/OpenAI 兼容的 SSE data 行；无法识别时返回 null。 */
object SseParser {
    fun parseData(data: String): SseChunk? {
        val trimmed = data.trim()
        if (trimmed.isEmpty() || trimmed == "[DONE]") return null
        return try {
            val obj = AiProtocol.json.parseToJsonElement(trimmed).jsonObject
            obj["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content?.let {
                return SseChunk(error = it.take(200))
            }
            val choice = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            val deltaObject = choice?.get("delta")?.jsonObject
            val content = textContent(deltaObject?.get("content"))
            val reasoning = textContent(deltaObject?.get("reasoning_content"))
                .ifBlank { textContent(deltaObject?.get("reasoning")) }
            val finish = choice?.get("finish_reason")
                ?.takeIf { it !is JsonNull }
                ?.jsonPrimitive?.content
            val usage = obj["usage"]?.jsonObject?.let { u ->
                ChatUsage(
                    u["prompt_tokens"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0,
                    u["completion_tokens"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0,
                )
            }
            if (content.isBlank() && reasoning.isBlank() && finish == null && usage == null) null
            else SseChunk(
                delta = content.ifBlank { null },
                reasoning = reasoning.ifBlank { null },
                finishReason = finish,
                usage = usage,
            )
        } catch (e: Exception) {
            // 单行格式异常不应中断整条流
            null
        }
    }
}

/** 支持 SSE 的传输层；普通聊天优先走流式，非流式 transport 自动回退。 */
interface StreamingChatTransport : ChatTransport {
    suspend fun stream(request: TransportRequest, onDelta: (String) -> Unit): ChatResult
}

/**
 * DeepSeek 官方 API 客户端（App 内运行时）。
 * POST {base}/chat/completions，Bearer 鉴权，JSON Output，非思考模式。
 */
class DeepSeekClient(
    private val baseUrl: String = "https://api.deepseek.com",
    private val apiKeyProvider: () -> String?,
    transport: ChatTransport,
    private val transportProvider: () -> ChatTransport = { transport },
) {

    private fun requireKey(): String {
        val key = apiKeyProvider() ?: throw AiException.NotConfigured("尚未配置 DeepSeek API Key")
        return key
    }

    private fun headers(key: String) = mapOf(
        "Authorization" to "Bearer $key",
        "Content-Type" to "application/json",
        "Accept" to "application/json",
    )

    private fun buildCompletionBody(
        model: String,
        messages: List<WireMessage>,
        jsonMode: Boolean,
        maxTokens: Int,
        temperature: Double,
        stream: Boolean,
        disableThinking: Boolean,
    ): String = buildJsonObject {
        put("model", model)
        put("messages", JsonArray(messages.map { m ->
            buildJsonObject {
                put("role", m.role)
                put("content", m.content)
            }
        }))
        if (jsonMode) {
            put("response_format", buildJsonObject {
                put("type", "json_object")
            })
        }
        // 规划 JSON 需要稳定输出，关闭思考；纯聊天则交给模型自然回答。
        // 对只支持思考的模型强制 disabled 可能得到空 content，因此仅在需要时发送。
        if (disableThinking) {
            put("thinking", buildJsonObject { put("type", "disabled") })
            put("reasoning_effort", "none")
        }
        put("max_tokens", maxTokens)
        put("temperature", temperature)
        put("stream", stream)
        if (stream) {
            put("stream_options", buildJsonObject { put("include_usage", true) })
        }
    }.toString()

    /** 非流式补全。messages 由调用方组装（含视觉 content 数组）。 */
    suspend fun complete(
        model: String,
        messages: List<WireMessage>,
        jsonMode: Boolean,
        maxTokens: Int = AiLimits.MAX_TOKENS,
        temperature: Double = 0.3,
        disableThinking: Boolean = true,
    ): ChatResult {
        val resp = transportProvider().call(
            TransportRequest(
                method = "POST",
                url = "$baseUrl/chat/completions",
                headers = headers(requireKey()),
                body = buildCompletionBody(
                    model, messages, jsonMode, maxTokens, temperature,
                    stream = false, disableThinking = disableThinking,
                ),
            ),
        )
        if (resp.code != 200) throw mappedError(resp.code, resp.body)
        return parseCompletion(resp.body)
    }

    /**
     * 普通聊天流式补全：transport 支持 SSE 时逐块回调；
     * 否则自动回退为非流式（一次回调完整正文）。
     */
    suspend fun streamComplete(
        model: String,
        messages: List<WireMessage>,
        maxTokens: Int = AiLimits.MAX_TOKENS,
        temperature: Double = 0.7,
        onDelta: (String) -> Unit,
    ): ChatResult {
        val streamingRequest = TransportRequest(
            method = "POST",
            url = "$baseUrl/chat/completions",
            headers = headers(requireKey()),
            body = buildCompletionBody(
                model, messages, jsonMode = false, maxTokens, temperature,
                stream = true, disableThinking = false,
            ),
        )
        val t = transportProvider()
        if (t is StreamingChatTransport) return t.stream(streamingRequest, onDelta)

        val resp = t.call(
            streamingRequest.copy(
                body = buildCompletionBody(
                    model, messages, jsonMode = false, maxTokens, temperature,
                    stream = false, disableThinking = false,
                ),
            ),
        )
        if (resp.code != 200) throw mappedError(resp.code, resp.body)
        val result = parseCompletion(resp.body)
        if (result.content.isNotEmpty()) onDelta(result.content)
        return result
    }

    suspend fun listModels(): List<ModelInfo> {
        val resp = transportProvider().call(
            TransportRequest(
                method = "GET",
                url = "$baseUrl/models",
                headers = headers(requireKey()),
            ),
        )
        if (resp.code != 200) throw mappedError(resp.code, resp.body)
        return try {
            val arr = AiProtocol.json.parseToJsonElement(resp.body).jsonObject["data"]?.jsonArray
                ?: return emptyList()
            arr.map { el ->
                val o = el.jsonObject
                val mods = o["input_modalities"]?.jsonArray
                    ?.map { it.jsonPrimitive.content }?.toSet() ?: emptySet()
                ModelInfo(o["id"]!!.jsonPrimitive.content, mods)
            }
        } catch (e: AiException) {
            throw e
        } catch (e: Exception) {
            throw AiException.Protocol("模型列表解析失败：${e.message?.take(120)}")
        }
    }

    /** 连接测试：仅验证 Key 有效与服务可达，不产生对话费用。 */
    suspend fun testConnection(): Pair<Boolean, String> = try {
        val models = listModels()
        Pair(true, "连接成功，可用模型：${models.joinToString("、") { it.id }}")
    } catch (e: AiException.Auth) {
        Pair(false, e.message ?: "Key 无效")
    } catch (e: AiException) {
        Pair(false, e.message ?: "连接失败")
    }

    companion object {
        fun parseCompletion(body: String): ChatResult = try {
            val root = AiProtocol.json.parseToJsonElement(body).jsonObject
            val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                ?: throw AiException.Protocol("响应缺少 choices")
            val message = choice["message"]?.jsonObject
            // content 可能是字符串或文本块数组；推理模型可能只给 reasoning_content
            val content = textContent(message?.get("content"))
                .ifBlank { textContent(message?.get("reasoning_content")) }
            val finish = choice["finish_reason"]?.jsonPrimitive?.content ?: "stop"
            val usage = root["usage"]?.jsonObject?.let { u ->
                ChatUsage(
                    u["prompt_tokens"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0,
                    u["completion_tokens"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0,
                )
            }
            ChatResult(content, finish, usage)
        } catch (e: AiException) {
            throw e
        } catch (e: Exception) {
            throw AiException.Protocol("响应解析失败：${e.message?.take(120)}")
        }

        /** 将 HTTP 状态映射为用户可读错误类别。 */
        fun mappedError(code: Int, body: String): AiException {
            val detail = extractErrorMessage(body)
            return when (code) {
                401 -> AiException.Auth("API Key 无效或已失效${detail?.let { "：$it" } ?: ""}")
                402 -> AiException.Quota("账户余额不足${detail?.let { "：$it" } ?: ""}")
                403 -> AiException.Auth("请求被拒绝（403）${detail?.let { "：$it" } ?: ""}")
                422 -> AiException.Protocol("请求参数被拒绝（422）${detail?.let { "：$it" } ?: ""}")
                429 -> AiException.RateLimit("请求过于频繁（429），请稍后重试")
                in 500..599 -> AiException.Server("DeepSeek 服务异常（$code），请稍后重试")
                else -> AiException.Server("请求失败（HTTP $code）${detail?.let { "：$it" } ?: ""}")
            }
        }

        fun extractErrorMessage(body: String): String? = try {
            val o = AiProtocol.json.parseToJsonElement(body).jsonObject
            o["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content?.take(160)
        } catch (e: Exception) {
            null
        }
    }
}
