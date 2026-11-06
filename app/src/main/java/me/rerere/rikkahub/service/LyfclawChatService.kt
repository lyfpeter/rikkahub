package me.rerere.rikkahub.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.common.http.SseEvent
import me.rerere.common.http.sseFlow
import me.rerere.rikkahub.data.datastore.SettingsStore
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import okio.BufferedSource
import java.io.IOException

@Serializable
data class LyfclawChatRequest(
    val bot_label: String,
    val text: String,
    val conversation_id: String? = null,
    val web_search: Boolean = false,
)

@Serializable
data class LyfclawBot(
    val label: String,
    @SerialName("display_name") val displayName: String = "",
)

@Serializable
data class LyfclawBotsResponse(
    val bots: List<LyfclawBot> = emptyList(),
)

/**
 * 对接 lyfclaw 后端的核心对话服务。
 *
 * 发消息到 POST /api/app/chat/direct/stream，用 OkHttp streaming 逐行解析 SSE 事件。
 * lyfclaw 模式下跳过本地 AgentLoop / ChatRequestFactory / ChatToolFactory，
 * Agent 循环、工具执行、记忆管理全部由后端负责。
 */
class LyfclawChatService(
    private val okHttpClient: OkHttpClient,
    private val settingsStore: SettingsStore,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * 拉取当前登录用户名下可用的 direct bot 列表（GET /api/app/chat/direct/bots）。
     * 用于在没有配置 bot_label 时自动选一个，避免发消息时报 "bot not found"。
     */
    suspend fun createBot(label: String, displayName: String, systemPrompt: String): JsonObject = withContext(Dispatchers.IO) {
        val settings = settingsStore.settingsFlow.value
        val baseUrl = me.rerere.rikkahub.data.remote.normalizeLyfclawBaseUrl(settings.lyfclawBaseUrl)
        val token = settings.lyfclawToken ?: throw IllegalStateException("Not logged in to lyfclaw")
        val body = """{"label":"${label.replace("\"","\\\"")}","display_name":"${displayName.replace("\"","\\\"")}","system_prompt":"${systemPrompt.replace("\"","\\\"").replace("\n","\\n")}"}"""
        val request = Request.Builder()
            .url("$baseUrl/api/app/bots")
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $token")
            .build()
        okHttpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw Exception("Create bot failed: ${resp.code} ${resp.body?.string()}")
            }
            json.parseToJsonElement(resp.body!!.string()).jsonObject
        }
    }

    suspend fun updateBot(botLabel: String, displayName: String?, systemPrompt: String?) = withContext(Dispatchers.IO) {
        val settings = settingsStore.settingsFlow.value
        val baseUrl = me.rerere.rikkahub.data.remote.normalizeLyfclawBaseUrl(settings.lyfclawBaseUrl)
        val token = settings.lyfclawToken ?: throw IllegalStateException("Not logged in to lyfclaw")
        val encoded = java.net.URLEncoder.encode(botLabel, "UTF-8")
        val parts = mutableListOf<String>()
        if (displayName != null) parts.add("\"display_name\":\"${displayName.replace("\"","\\\"")}\"")
        if (systemPrompt != null) parts.add("\"system_prompt\":\"${systemPrompt.replace("\"","\\\"").replace("\n","\\n")}\"")
        val body = "{${parts.joinToString(",")}}"
        val request = Request.Builder()
            .url("$baseUrl/api/app/bots/$encoded")
            .put(body.toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $token")
            .build()
        okHttpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw Exception("Update bot failed: ${resp.code} ${resp.body?.string()}")
            }
        }
    }

    suspend fun deleteBot(botLabel: String) = withContext(Dispatchers.IO) {
        val settings = settingsStore.settingsFlow.value
        val baseUrl = me.rerere.rikkahub.data.remote.normalizeLyfclawBaseUrl(settings.lyfclawBaseUrl)
        val token = settings.lyfclawToken ?: throw IllegalStateException("Not logged in to lyfclaw")
        val encoded = java.net.URLEncoder.encode(botLabel, "UTF-8")
        val request = Request.Builder()
            .url("$baseUrl/api/app/bots/$encoded")
            .delete()
            .header("Authorization", "Bearer $token")
            .build()
        okHttpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw Exception("Delete bot failed: ${resp.code} ${resp.body?.string()}")
            }
        }
    }

    suspend fun updateBotDisplayName(botLabel: String, displayName: String) = withContext(Dispatchers.IO) {
        val settings = settingsStore.settingsFlow.value
        val baseUrl = me.rerere.rikkahub.data.remote.normalizeLyfclawBaseUrl(settings.lyfclawBaseUrl)
        val token = settings.lyfclawToken ?: throw IllegalStateException("Not logged in to lyfclaw")
        val encoded = java.net.URLEncoder.encode(botLabel, "UTF-8")
        val body = """{"display_name":"${displayName.replace("\"", "\\\"")}"}"""
        val request = Request.Builder()
            .url("$baseUrl/api/app/agents/$encoded")
            .put(
                okhttp3.RequestBody.create(
                    "application/json".toMediaTypeOrNull(),
                    body,
                )
            )
            .header("Authorization", "Bearer $token")
            .build()
        okHttpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw Exception("Update bot failed: ${resp.code}")
            }
        }
    }

    suspend fun fetchBots(): List<LyfclawBot> = withContext(Dispatchers.IO) {
        val settings = settingsStore.settingsFlow.value
        val baseUrl = me.rerere.rikkahub.data.remote.normalizeLyfclawBaseUrl(settings.lyfclawBaseUrl)
        val token = settings.lyfclawToken ?: throw IllegalStateException("Not logged in to lyfclaw")

        val request = Request.Builder()
            .url("$baseUrl/api/app/chat/direct/bots")
            .get()
            .header("Authorization", "Bearer $token")
            .build()

        okHttpClient.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw Exception("List bots failed: ${resp.code} $body")
            }
            json.decodeFromString(LyfclawBotsResponse.serializer(), body).bots
        }
    }

    /**
     * 发送消息到 lyfclaw 后端，返回 SSE 事件流。
     *
     * 使用 OkHttp streaming 模式逐行读取 SSE 响应，每解析到一条完整事件就 emit，
     * 不等整个响应结束。这保证了流式渲染体验——前端可以逐 token 看到 delta。
     *
     * @param botLabel 目标 bot 标签
     * @param text 用户消息文本
     * @param conversationId 会话 ID（可选，首次对话为 null）
     */
    fun sendMessage(
        botLabel: String,
        text: String,
        conversationId: String? = null,
    ): Flow<LyfclawSseEvent> = callbackFlow {
        val settings = settingsStore.settingsFlow.value
        val baseUrl = me.rerere.rikkahub.data.remote.normalizeLyfclawBaseUrl(settings.lyfclawBaseUrl)
        val token = settings.lyfclawToken ?: throw IllegalStateException("Not logged in to lyfclaw")

        val requestBody = json.encodeToString(
            LyfclawChatRequest.serializer(),
            LyfclawChatRequest(botLabel, text, conversationId),
        ).toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("$baseUrl/api/app/chat/direct/stream")
            .post(requestBody)
            .header("Authorization", "Bearer $token")
            .header("Accept", "text/event-stream")
            .build()

        val call = okHttpClient.newCall(request)

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                trySend(LyfclawSseEvent.Error(e.message ?: "Network error"))
                close(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: ""
                    trySend(LyfclawSseEvent.Error("lyfclaw request failed: ${response.code} $errorBody"))
                    response.close()
                    close()
                    return
                }

                val source = response.body?.source()
                if (source == null) {
                    trySend(LyfclawSseEvent.Error("Empty response body"))
                    response.close()
                    close()
                    return
                }

                try {
                    var currentEvent = ""
                    val dataBuilder = StringBuilder()

                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: break

                        when {
                            // SSE comment / keepalive — ignore
                            line.startsWith(":") -> continue

                            line.startsWith("event: ") -> {
                                currentEvent = line.removePrefix("event: ").trim()
                            }

                            line.startsWith("data: ") -> {
                                if (dataBuilder.isNotEmpty()) dataBuilder.append("\n")
                                dataBuilder.append(line.removePrefix("data: ").trim())
                            }

                            line.isBlank() -> {
                                // 空行 = 事件边界
                                val data = dataBuilder.toString().trim()
                                if (data.isNotBlank()) {
                                    val event =
                                        parseSseEvent(currentEvent.ifBlank {
                                            data.substringBefore("\"type\":\"", "").let {
                                                // data 里带 type 字段时从 JSON 解析，否则用 event 名
                                                ""
                                            }
                                        }, data)
                                    if (event != null) trySend(event)
                                }
                                currentEvent = ""
                                dataBuilder.clear()
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (!isClosedForSend) {
                        trySend(LyfclawSseEvent.Error(e.message ?: "SSE parse error"))
                    }
                } finally {
                    response.close()
                    close()
                }
            }
        })

        awaitClose {
            call.cancel()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 订阅某个会话的后端推送流（`GET /api/app/sessions/{sessionId}/stream`）。
     *
     * 首帧是当前全量 `snapshot`（进会话即校准），后续为 `node_update`/`snapshot` 实时帧
     * ——定时任务等主动投递的结果即由此秒级送达。`sessionId` 是 **scoped** 键
     * `bot:<bot_id>:conv:<cid>`（来自 `sendMessage` 的 `meta.session_id`），须 URL 编码。
     *
     * 连接结束/失败时流会关闭，重连由调用方（`ChatVM`）用 `retry` 决定。
     */
    fun subscribeSession(sessionId: String): Flow<LyfclawSseEvent> = flow {
        val settings = settingsStore.settingsFlow.value
        val baseUrl = me.rerere.rikkahub.data.remote.normalizeLyfclawBaseUrl(settings.lyfclawBaseUrl)
        val token = settings.lyfclawToken ?: throw IllegalStateException("Not logged in to lyfclaw")
        val encoded = java.net.URLEncoder.encode(sessionId, "UTF-8")

        val request = Request.Builder()
            .url("$baseUrl/api/app/sessions/$encoded/stream")
            .get()
            .header("Authorization", "Bearer $token")
            .header("Accept", "text/event-stream")
            .build()

        emitAll(
            okHttpClient.sseFlow(request).mapNotNull { ev ->
                when (ev) {
                    is SseEvent.Event -> parseSseEvent(ev.type ?: "", ev.data)
                    else -> null
                }
            }
        )
    }.flowOn(Dispatchers.IO)

    /**
     * Bot 级 SSE 订阅（`GET /api/app/bots/{bot_label}/stream`）。
     *
     * 一条连接接收该 bot 下所有会话的定时任务推送。
     * 事件 payload 携带 `conversation_id` 和 `session_id`，由调用方分发到对应会话。
     */
    fun subscribeBot(botLabel: String): Flow<JsonObject> = flow {
        val settings = settingsStore.settingsFlow.value
        val baseUrl = me.rerere.rikkahub.data.remote.normalizeLyfclawBaseUrl(settings.lyfclawBaseUrl)
        val token = settings.lyfclawToken ?: throw IllegalStateException("Not logged in to lyfclaw")
        val encoded = java.net.URLEncoder.encode(botLabel, "UTF-8")

        val request = Request.Builder()
            .url("$baseUrl/api/app/bots/$encoded/stream")
            .get()
            .header("Authorization", "Bearer $token")
            .header("Accept", "text/event-stream")
            .build()

        emitAll(
            okHttpClient.sseFlow(request).mapNotNull { ev ->
                when (ev) {
                    is SseEvent.Event -> {
                        runCatching { json.parseToJsonElement(ev.data) as? JsonObject }.getOrNull()
                    }
                    else -> null
                }
            }
        )
    }.flowOn(Dispatchers.IO)

    /**
     * 解析单条 SSE 事件的 data 字段（JSON）为 LyfclawSseEvent。
     */
    private fun parseSseEvent(eventType: String, data: String): LyfclawSseEvent? {
        return try {
            val obj = json.parseToJsonElement(data) as JsonObject
            val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: eventType
            when (type) {
                "meta" -> {
                    val cid = obj["conversation_id"]?.jsonPrimitive?.contentOrNull
                    if (cid != null) {
                        LyfclawSseEvent.Meta(cid, obj["session_id"]?.jsonPrimitive?.contentOrNull)
                    } else null
                }
                "delta" -> {
                    val text = obj["text"]?.jsonPrimitive?.contentOrNull ?: ""
                    val transient = obj["transient"]?.jsonPrimitive?.contentOrNull == "true"
                    LyfclawSseEvent.Delta(text, transient)
                }
                "done" -> LyfclawSseEvent.Done(
                    text = obj["text"]?.jsonPrimitive?.contentOrNull ?: "",
                    conversationId = obj["conversation_id"]?.jsonPrimitive?.contentOrNull,
                    sessionId = obj["session_id"]?.jsonPrimitive?.contentOrNull,
                )
                "cancelled" -> LyfclawSseEvent.Cancelled(
                    obj["conversation_id"]?.jsonPrimitive?.contentOrNull
                )
                "error" -> LyfclawSseEvent.Error(
                    obj["error"]?.jsonPrimitive?.contentOrNull ?: "Unknown error"
                )
                "node_update" -> {
                    val nodeId = obj["nodeId"]?.jsonPrimitive?.contentOrNull ?: return null
                    val isGenerating = obj["isGenerating"]?.jsonPrimitive?.contentOrNull != "false"
                    LyfclawSseEvent.NodeUpdate(nodeId, isGenerating, obj["node"]?.jsonObject)
                }
                "snapshot" -> LyfclawSseEvent.Snapshot(obj)
                "tool" -> {
                    val toolName = obj["tool_name"]?.jsonPrimitive?.contentOrNull ?: ""
                    val callId = obj["call_id"]?.jsonPrimitive?.contentOrNull ?: ""
                    LyfclawSseEvent.Tool(toolName, callId, obj["text"]?.jsonPrimitive?.contentOrNull ?: "")
                }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }
}

/** lyfclaw SSE 事件 */
sealed class LyfclawSseEvent {
    /**
     * [conversationId] 是后端返回的**裸**外部会话 id（如 `4010d84e`）；
     * [sessionId] 是 **scoped** 会话键 `bot:<bot_id>:conv:<cid>`，用于订阅实时推送流
     * （订阅端点是 `GET /api/app/sessions/{sessionId}/stream`，与发送端点不同）。
     */
    data class Meta(val conversationId: String, val sessionId: String? = null) : LyfclawSseEvent()
    /** [transient] = 可原地替换的临时进度帧（前端应丢弃，避免残留转圈文本）。 */
    data class Delta(val text: String, val transient: Boolean = false) : LyfclawSseEvent()
    data class Tool(val toolName: String, val callId: String, val raw: String) : LyfclawSseEvent()
    /**
     * 结构化节点快照：`node.messages[].parts[]` 携带 reasoning / tool / text 三类 part，
     * 由 `ChatService` 映射成 [me.rerere.ai.ui.UIMessagePart] 以渲染 agent 执行过程。
     */
    data class NodeUpdate(
        val nodeId: String,
        val isGenerating: Boolean,
        val node: JsonObject?,
    ) : LyfclawSseEvent()
    /** 全量快照：`payload.conversation.messageNodes[]` 为当前会话的消息树。 */
    data class Snapshot(val payload: JsonObject?) : LyfclawSseEvent()
    data class Done(
        val text: String,
        val conversationId: String?,
        val sessionId: String? = null,
    ) : LyfclawSseEvent()
    data class Cancelled(val conversationId: String?) : LyfclawSseEvent()
    data class Error(val message: String) : LyfclawSseEvent()
}
