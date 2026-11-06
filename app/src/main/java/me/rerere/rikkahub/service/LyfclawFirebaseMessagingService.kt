package me.rerere.rikkahub.service

import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import me.rerere.rikkahub.LYFCLAW_PUSH_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.conversationIntent
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.remote.LyfclawAuthClient
import me.rerere.rikkahub.data.remote.normalizeLyfclawBaseUrl
import me.rerere.rikkahub.utils.sendNotification
import org.koin.android.ext.android.inject
import kotlin.uuid.Uuid

private const val TAG = "LyfclawFCM"

/**
 * FCM 消息接收 + token 轮换。
 *
 * - [onMessageReceived]：进程被系统杀死时，FCM 系统服务收到 push 并拉起本 Service。
 *   显示本地通知，用户点击后打开对应会话。
 * - [onNewToken]：FCM token 轮换时自动重新注册到 lyfclaw 后端。
 */
class LyfclawFirebaseMessagingService : FirebaseMessagingService() {

    private val settingsStore: SettingsStore by inject()
    private val authClient: LyfclawAuthClient by inject()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(message: RemoteMessage) {
        val title = message.notification?.title
            ?: message.data["title"]
            ?: "lyfclaw"
        val body = message.notification?.body
            ?: message.data["body"]
            ?: return

        val conversationId = message.data["conversation_id"]?.let {
            runCatching { Uuid.parse(it) }.getOrNull()
        }
        val notificationId = conversationId?.hashCode() ?: System.currentTimeMillis().toInt()
        val pendingIntent = conversationId?.let {
            android.app.PendingIntent.getActivity(
                this,
                it.hashCode(),
                conversationIntent(this, it.toString()),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        sendNotification(
            channelId = LYFCLAW_PUSH_NOTIFICATION_CHANNEL_ID,
            notificationId = notificationId
        ) {
            this.title = title
            content = body
            autoCancel = true
            category = NotificationCompat.CATEGORY_MESSAGE
            useBigTextStyle = true
            contentIntent = pendingIntent
        }
        Log.d(TAG, "FCM notification shown: $title")
    }

    override fun onNewToken(token: String) {
        Log.d(TAG, "FCM token rotation, re-registering")
        val settings = settingsStore.settingsFlow.value
        if (!settings.isLyfclawEnabled) return

        serviceScope.launch {
            runCatching {
                val base = normalizeLyfclawBaseUrl(settings.lyfclawBaseUrl)
                authClient.registerDevice(base, settings.lyfclawToken!!, token)
            }.onFailure {
                Log.w(TAG, "Failed to re-register FCM token: ${it.message}")
            }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}

private fun CoroutineScope.cancel() = this.coroutineContext[kotlinx.coroutines.Job]?.cancel()
