package me.rerere.rikkahub.service

import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.remote.LyfclawAuthClient
import me.rerere.rikkahub.data.remote.normalizeLyfclawBaseUrl

private const val TAG = "FcmTokenMgr"

/**
 * 管理 FCM token 的注册 / 注销生命周期。
 *
 * - 进程启动且 lyfclaw 已登录时：获取 FCM token 并注册到后端
 * - 监听 lyfclaw 登录状态变化：登录 → 注册，登出 → 注销
 * - [LyfclawFirebaseMessagingService.onNewToken] 处理 token 轮换
 */
class FcmTokenManager(
    private val appScope: AppScope,
    private val settingsStore: SettingsStore,
    private val authClient: LyfclawAuthClient,
) {
    fun start() {
        appScope.launch {
            settingsStore.settingsFlow
                .map { it.isLyfclawEnabled to (it.lyfclawBaseUrl to it.lyfclawToken) }
                .distinctUntilChanged()
                .collect { (enabled, _) ->
                    if (enabled) {
                        registerToken()
                    }
                }
        }
    }

    private suspend fun registerToken() {
        val settings = settingsStore.settingsFlow.value
        if (!settings.isLyfclawEnabled) return

        try {
            val fcmToken = FirebaseMessaging.getInstance().token.await()
            val base = normalizeLyfclawBaseUrl(settings.lyfclawBaseUrl)
            authClient.registerDevice(base, settings.lyfclawToken!!, fcmToken)
            Log.d(TAG, "FCM token registered to lyfclaw")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register FCM token: ${e.message}")
        }
    }
}
