package com.jsy.dayplan.ai

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
class HttpTransport : ChatTransport {
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
    const val MAX_TOKENS = 4096
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

/**
 * DeepSeek 官方 API 客户端（App 内运行时）。
 * POST {base}/chat/completions，Bearer 鉴权，JSON Output，非思考模式。
 */
class DeepSeekClient(
    private val baseUrl: String = "https://api.deepseek.com",
    private val apiKeyProvider: () -> String?,
    private val transport: ChatTransport,
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

    /** 非流式补全。messages 由调用方组装（含视觉 content 数组）。 */
    suspend fun complete(
        model: String,
        messages: List<WireMessage>,
        jsonMode: Boolean,
        maxTokens: Int = AiLimits.MAX_TOKENS,
        temperature: Double = 0.3,
    ): ChatResult {
        val body = buildJsonObject {
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
            put("thinking", buildJsonObject { put("type", "disabled") })
            put("max_tokens", maxTokens)
            put("temperature", temperature)
            put("stream", false)
        }.toString()

        val resp = transport.call(
            TransportRequest(
                method = "POST",
                url = "$baseUrl/chat/completions",
                headers = headers(requireKey()),
                body = body,
            ),
        )
        if (resp.code != 200) throw mappedError(resp.code, resp.body)
        return parseCompletion(resp.body)
    }

    suspend fun listModels(): List<ModelInfo> {
        val resp = transport.call(
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
            val content = choice["message"]?.jsonObject?.get("content")?.jsonPrimitive?.content
                ?: throw AiException.Protocol("响应缺少 content")
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
