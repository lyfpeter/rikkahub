package me.rerere.rikkahub.service

import android.app.Application
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus

private const val TAG = "LyfclawPushMgr"

/**
 * Bot 中心化推送管理器：以 bot 为单位订阅 SSE，一条连接收该 bot 下所有会话推送。
 *
 * 定时任务消息经后端 send_message → bot 级 session_stream_bus publish → 本 SSE 连接送达。
 * 事件 payload 携带 conversation_id，按它查找本地会话并 merge。
 *
 * 仍保留 per-conversation 的 [checkPendingMessages]（App 启动时一次性拉取积压消息）。
 */
class LyfclawPushManager(
    private val appScope: AppScope,
    private val settingsStore: SettingsStore,
    private val lazyChatService: Lazy<ChatService>,
    private val context: Application,
    private val appEventBus: AppEventBus,
) {
    private val chatService by lazyChatService
    private val subscriptions = mutableMapOf<String, Job>() // botLabel → SSE 订阅协程
    private val mutex = Mutex()

    private val isForeground = MutableStateFlow(true)
    private var lastLyfclawToken: String? = null

    init {
        appScope.launch {
            ProcessLifecycleOwner.get().lifecycle.addObserver(
                LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_START -> isForeground.value = true
                        Lifecycle.Event.ON_STOP -> isForeground.value = false
                        else -> {}
                    }
                }
            )
        }
    }

    fun start() {
        appScope.launch {
            val settings = settingsStore.awaitLoaded()
            lastLyfclawToken = settings.lyfclawToken
            if (!settings.isLyfclawEnabled) return@launch

            // App 启动时检查积压消息
            checkPendingMessages()

            // 始终启动 bot 级订阅（不依赖 lyfclawBackgroundPush 开关——bot 订阅更轻量）
            refreshBotSubscriptions()

            // 监听设置变化
            settingsStore.settingsFlow
                .map { Triple(it.isLyfclawEnabled, it.lyfclawToken, it.lyfclawBotLabel) }
                .distinctUntilChanged()
                .collect { (enabled, token, botLabel) ->
                    val tokenChanged = lastLyfclawToken != null && token != null && lastLyfclawToken != token
                    val loggedOut = lastLyfclawToken != null && token == null
                    lastLyfclawToken = token

                    when {
                        !enabled -> stopAll()
                        tokenChanged || loggedOut -> {
                            stopAll()
                            refreshBotSubscriptions()
                        }
                        else -> refreshBotSubscriptions()
                    }
                }
        }
    }

    /** 会话被删除时调用（保留兼容，bot 级订阅不需要关心单个会话删除）。 */
    fun onConversationDeleted(conversationId: kotlin.uuid.Uuid) {
        // bot 级订阅不按会话管理，无需处理
    }

    /** 会话获得新的 lyfclawSessionId 时调用（保留兼容）。 */
    fun onSessionIdUpdated(conversationId: kotlin.uuid.Uuid, sessionId: String?) {
        // bot 级订阅不依赖 per-conversation session ID，无需处理
    }

    /** 启动/刷新 bot 级订阅。 */
    private suspend fun refreshBotSubscriptions() {
        val settings = settingsStore.settingsFlow.value
        if (!settings.isLyfclawEnabled) return

        // 获取当前 bot label
        val botLabel = settings.lyfclawBotLabel
        if (botLabel.isBlank()) {
            Log.d(TAG, "refreshBotSubscriptions: no bot label configured")
            return
        }

        // 如果开启了后台推送，启动前台 Service
        if (settings.lyfclawBackgroundPush) {
            LyfclawPushService.start(context)
        }

        mutex.withLock {
            // 已订阅该 bot 则跳过
            if (botLabel in subscriptions) return@withLock
            Log.i(TAG, "Starting bot-level push subscription for bot=$botLabel")
            subscriptions[botLabel] = appScope.launch {
                try {
                    chatService.subscribeBotPush(
                        botLabel = botLabel,
                        isAppInForeground = { isForeground.value },
                        onPushEvent = { event ->
                            handleBotPushEvent(botLabel, event)
                        },
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Bot push subscription failed for $botLabel: ${e.message}")
                }
            }
        }
    }

    /** 收到 bot 级推送事件，按 conversation_id 分发到对应本地会话。 */
    private fun handleBotPushEvent(botLabel: String, event: me.rerere.rikkahub.service.BotPushEvent) {
        appScope.launch {
            val convId = event.conversationId
            if (convId.isNullOrBlank()) return@launch

            // 在本地查找匹配 lyfclawConversationId 的会话
            val localConvId = chatService.findConversationByLyfclawId(convId)
            if (localConvId != null) {
                // 已有会话：拉取 snapshot merge
                val sessionId = event.sessionId
                if (sessionId != null) {
                    chatService.fetchAndMergePendingMessages(
                        conversationId = localConvId,
                        sessionId = sessionId,
                        isAppInForeground = { isForeground.value },
                        onNewMessage = { preview ->
                            if (!isForeground.value) {
                                appEventBus.tryEmit(
                                    AppEvent.LyfclawPushReceived(
                                        conversationId = localConvId,
                                        senderName = botLabel.ifBlank { "lyfclaw" },
                                        contentPreview = preview,
                                    )
                                )
                            }
                        },
                    )
                }
            } else {
                // 未知会话：发通知（用户打开后会发现新消息）
                if (!isForeground.value) {
                    appEventBus.tryEmit(
                        AppEvent.LyfclawPushReceived(
                            conversationId = kotlin.uuid.Uuid.random(),
                            senderName = botLabel.ifBlank { "lyfclaw" },
                            contentPreview = "新消息",
                        )
                    )
                }
            }
        }
    }

    /** App 启动时检查积压消息（per-conversation snapshot 拉取）。 */
    private suspend fun checkPendingMessages() {
        val settings = settingsStore.settingsFlow.value
        if (!settings.isLyfclawEnabled) return

        appScope.launch {
            try {
                chatService.checkAllPendingMessages(
                    isAppInForeground = { isForeground.value },
                    onNewMessage = { convId, preview ->
                        if (!isForeground.value) {
                            appEventBus.tryEmit(
                                AppEvent.LyfclawPushReceived(
                                    conversationId = convId,
                                    senderName = settings.lyfclawBotLabel.ifBlank { "lyfclaw" },
                                    contentPreview = preview,
                                )
                            )
                        }
                    },
                )
            } catch (e: Exception) {
                Log.w(TAG, "checkPendingMessages failed: ${e.message}")
            }
        }
    }

    private fun stopAll() {
        LyfclawPushService.stop(context)
        appScope.launch {
            mutex.withLock {
                subscriptions.values.toList().forEach { it.cancel() }
                subscriptions.clear()
            }
        }
        Log.d(TAG, "Stopped all bot push subscriptions")
    }
}

/** Bot 级推送事件。 */
data class BotPushEvent(
    val type: String,
    val sessionId: String?,
    val conversationId: String?,
)
