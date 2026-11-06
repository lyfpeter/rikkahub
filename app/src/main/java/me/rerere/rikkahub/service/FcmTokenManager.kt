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
 * - 登录时：获取 FCM token 并注册到后端
 * - 登出时（lyfclawToken 从非 null → null）：尝试注销 FCM token
 * - token refresh 不触发注销——register 新 token 时后端 ON CONFLICT 自动覆盖
 * - [LyfclawFirebaseMessagingService.onNewToken] 处理 FCM token 轮换
 */
class FcmTokenManager(
    private val appScope: AppScope,
    private val settingsStore: SettingsStore,
    private val authClient: LyfclawAuthClient,
) {
    /** 上次处理时的登录状态，用于检测登出和登录。 */
    private var wasLoggedIn = false
    /** 上缓存的 FCM token，登出时用它调 unregister。 */
    private var cachedFcmToken: String? = null

    fun start() {
        appScope.launch {
            val settings = settingsStore.awaitLoaded()
            wasLoggedIn = settings.lyfclawToken != null

            settingsStore.settingsFlow
                .map { it.lyfclawToken != null }
                .distinctUntilChanged()
                .collect { isLoggedIn ->
                    if (isLoggedIn && !wasLoggedIn) {
                        registerToken()
                    } else if (!isLoggedIn && wasLoggedIn) {
                        unregisterToken()
                    }
                    wasLoggedIn = isLoggedIn
                }
        }
    }

    private suspend fun registerToken() {
        val settings = settingsStore.settingsFlow.value
        val lyfclawToken = settings.lyfclawToken
        if (!settings.isLyfclawEnabled || lyfclawToken == null) return

        try {
            val fcmToken = FirebaseMessaging.getInstance().token.await()
            cachedFcmToken = fcmToken
            val base = normalizeLyfclawBaseUrl(settings.lyfclawBaseUrl)
            authClient.registerDevice(base, lyfclawToken, fcmToken)
            Log.d(TAG, "FCM token registered to lyfclaw")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register FCM token: ${e.message}")
        }
    }

    private suspend fun unregisterToken() {
        val fcmToken = cachedFcmToken ?: return
        // 登出时 lyfclawToken 可能已被清除，用缓存的 token 尝试注销
        // 失败则靠后端 FCM 404 时自动清理死 token
        try {
            val settings = settingsStore.settingsFlow.value
            val base = normalizeLyfclawBaseUrl(settings.lyfclawBaseUrl)
            val token = settings.lyfclawToken
            if (token != null) {
                authClient.unregisterDevice(base, token, fcmToken)
                Log.d(TAG, "FCM token unregistered from lyfclaw")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister FCM token (backend will clean via 404): ${e.message}")
        }
    }
}
