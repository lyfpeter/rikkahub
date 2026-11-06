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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.model.ConversationConfig
import me.rerere.rikkahub.utils.JsonInstant
import kotlin.uuid.Uuid

private const val TAG = "LyfclawPushMgr"

/**
 * 管理多个会话的 lyfclaw SSE 后台推送订阅。
 *
 * 进程启动时（DI [createdAtStart][org.koin.dsl.module.createdAtStart]）检查 lyfclaw 状态和用户开关，
 * 启用则启动 [LyfclawPushService] 前台保活 + 为每个有 `lyfclawSessionId` 的会话建立 SSE 订阅。
 * 订阅在 [AppScope] 中运行，不依赖任何 ViewModel 生命周期。
 *
 * 与 [ChatService.subscribeLyfclawPush]（ChatVM 调用）并行时，[ChatService.mergeLyfclawMessages]
 * 基于高水位 `lyfclawSyncedAt` 去重，多路订阅幂等安全。
 */
class LyfclawPushManager(
    private val appScope: AppScope,
    private val settingsStore: SettingsStore,
    private val conversationDAO: ConversationDAO,
    private val lazyChatService: Lazy<ChatService>,
    private val context: Application,
) {
    private val chatService by lazyChatService
    private val subscriptions = mutableMapOf<Uuid, Job>()
    private val mutex = Mutex()

    // App 前后台状态，传给 ChatService 用于决定是否发通知
    private val isForeground = MutableStateFlow(true)

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

    /** 进程启动时调用。 */
    fun start() {
        appScope.launch {
            val settings = settingsStore.awaitLoaded()
            if (!settings.isLyfclawEnabled || !settings.lyfclawBackgroundPush) return@launch

            LyfclawPushService.start(context)
            refreshSubscriptions()

            // 监听开关变化
            settingsStore.settingsFlow
                .map { it.isLyfclawEnabled && it.lyfclawBackgroundPush }
                .distinctUntilChanged()
                .collect { enabled ->
                    if (enabled) {
                        refreshSubscriptions()
                    } else {
                        stopAll()
                    }
                }
        }
    }

    /** 从 DB 扫描所有有 lyfclawSessionId 的会话，增减订阅。 */
    private suspend fun refreshSubscriptions() {
        val configRows = withContext(Dispatchers.IO) {
            conversationDAO.getConversationsWithLyfclawSession()
        }
        // 解析出 (conversationId, lyfclawSessionId) 对
        val active = configRows.mapNotNull { row ->
            val convId = runCatching { Uuid.parse(row.id) }.getOrNull() ?: return@mapNotNull null
            val config = runCatching {
                JsonInstant.decodeFromString<ConversationConfig>(row.config)
            }.getOrNull() ?: return@mapNotNull null
            val sid = config.lyfclawSessionId
            if (sid.isNullOrBlank()) return@mapNotNull null
            convId to sid
        }

        val activeIds = active.map { it.first }.toSet()

        mutex.withLock {
            // 移除已不需要的订阅
            subscriptions.keys.filter { it !in activeIds }.forEach { removeSubscriptionLocked(it) }
            // 添加新订阅
            active.forEach { (convId, sid) ->
                if (convId !in subscriptions) {
                    addSubscriptionLocked(convId, sid)
                }
            }
        }
    }

    private fun addSubscriptionLocked(conversationId: Uuid, sessionId: String) {
        if (conversationId in subscriptions) return
        Log.d(TAG, "Starting background push subscription for conversation $conversationId")
        // 持有会话引用，防止 idle 回收
        chatService.addConversationReference(conversationId)
        subscriptions[conversationId] = appScope.launch {
            try {
                chatService.subscribeLyfclawPushInBackground(
                    conversationId,
                    sessionId,
                    isAppInForeground = { isForeground.value },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Subscription failed for $conversationId: ${e.message}")
            }
        }
    }

    private fun removeSubscriptionLocked(conversationId: Uuid) {
        subscriptions.remove(conversationId)?.cancel()
        chatService.removeConversationReference(conversationId)
        Log.d(TAG, "Removed background push subscription for conversation $conversationId")
    }

    private fun stopAll() {
        LyfclawPushService.stop(context)
        appScope.launch {
            mutex.withLock {
                subscriptions.keys.toList().forEach { removeSubscriptionLocked(it) }
            }
        }
        Log.d(TAG, "Stopped all background push subscriptions")
    }

    /**
     * 会话获得新的 lyfclawSessionId 时调用，及时启动订阅。
     * 由 [ChatService.applyLyfclawPush] 在收到 meta/done 后触发。
     */
    fun onSessionIdUpdated(conversationId: Uuid, sessionId: String?) {
        if (!settingsStore.settingsFlow.value.let { it.isLyfclawEnabled && it.lyfclawBackgroundPush }) return
        appScope.launch {
            if (sessionId.isNullOrBlank()) {
                mutex.withLock { removeSubscriptionLocked(conversationId) }
            } else {
                mutex.withLock {
                    // 先取消旧订阅再建新的
                    subscriptions[conversationId]?.cancel()
                    addSubscriptionLocked(conversationId, sessionId)
                }
            }
        }
    }
}
