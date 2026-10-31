package me.rerere.rikkahub.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.datastore.SettingsStore
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
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
        val baseUrl = settings.lyfclawBaseUrl
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
     * 解析单条 SSE 事件的 data 字段（JSON）为 LyfclawSseEvent。
     */
    private fun parseSseEvent(eventType: String, data: String): LyfclawSseEvent? {
        return try {
            val obj = json.parseToJsonElement(data) as JsonObject
            val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: eventType
            when (type) {
                "meta" -> {
                    val cid = obj["conversation_id"]?.jsonPrimitive?.contentOrNull
                    if (cid != null) LyfclawSseEvent.Meta(cid) else null
                }
                "delta" -> {
                    val text = obj["text"]?.jsonPrimitive?.contentOrNull ?: ""
                    val finish = obj["finish"]?.jsonPrimitive?.contentOrNull == "true"
                    LyfclawSseEvent.Delta(text)
                }
                "done" -> LyfclawSseEvent.Done(
                    text = obj["text"]?.jsonPrimitive?.contentOrNull ?: "",
                    conversationId = obj["conversation_id"]?.jsonPrimitive?.contentOrNull,
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
                    LyfclawSseEvent.NodeUpdate(nodeId, isGenerating)
                }
                "snapshot" -> LyfclawSseEvent.Snapshot
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
    data class Meta(val conversationId: String) : LyfclawSseEvent()
    data class Delta(val text: String) : LyfclawSseEvent()
    data class Tool(val toolName: String, val callId: String, val raw: String) : LyfclawSseEvent()
    data class NodeUpdate(val nodeId: String, val isGenerating: Boolean) : LyfclawSseEvent()
    object Snapshot : LyfclawSseEvent()
    data class Done(val text: String, val conversationId: String?) : LyfclawSseEvent()
    data class Cancelled(val conversationId: String?) : LyfclawSseEvent()
    data class Error(val message: String) : LyfclawSseEvent()
}
