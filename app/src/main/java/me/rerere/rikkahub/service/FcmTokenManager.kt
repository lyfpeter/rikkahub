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
 * - 登出时（lyfclawToken 从非 null → null）：注销 FCM token
 * - 换账号登录（lyfclawToken 从一个非 null 值变为另一个）：先注销旧 token，再注册新 token
 * - [LyfclawFirebaseMessagingService.onNewToken] 处理 token 轮换
 */
class FcmTokenManager(
    private val appScope: AppScope,
    private val settingsStore: SettingsStore,
    private val authClient: LyfclawAuthClient,
) {
    /** 上次处理时的 lyfclawToken，用于检测登出和换账号。 */
    private var lastToken: String? = null
    /** 上缓存的 FCM token，登出时用它调 unregister。 */
    private var cachedFcmToken: String? = null

    fun start() {
        appScope.launch {
            settingsStore.settingsFlow
                .map { it.lyfclawToken }
                .distinctUntilChanged()
                .collect { newToken ->
                    val oldToken = lastToken
                    lastToken = newToken

                    when {
                        // 登出：oldToken 非空 → newToken 为空
                        oldToken != null && newToken == null -> {
                            unregisterToken(oldToken)
                        }
                        // 换账号：oldToken 非空 → newToken 非空且不同
                        oldToken != null && newToken != null && oldToken != newToken -> {
                            unregisterToken(oldToken)
                            registerToken()
                        }
                        // 登录：oldToken 为空 → newToken 非空
                        oldToken == null && newToken != null -> {
                            registerToken()
                        }
                    }
                }
        }
    }

    private suspend fun registerToken() {
        val settings = settingsStore.settingsFlow.value
        if (!settings.isLyfclawEnabled) return

        try {
            val fcmToken = FirebaseMessaging.getInstance().token.await()
            cachedFcmToken = fcmToken
            val base = normalizeLyfclawBaseUrl(settings.lyfclawBaseUrl)
            authClient.registerDevice(base, settings.lyfclawToken!!, fcmToken)
            Log.d(TAG, "FCM token registered to lyfclaw")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register FCM token: ${e.message}")
        }
    }

    private suspend fun unregisterToken(lyfclawToken: String) {
        val fcmToken = cachedFcmToken ?: return
        try {
            val settings = settingsStore.settingsFlow.value
            val base = normalizeLyfclawBaseUrl(settings.lyfclawBaseUrl)
            authClient.unregisterDevice(base, lyfclawToken, fcmToken)
            Log.d(TAG, "FCM token unregistered from lyfclaw")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister FCM token: ${e.message}")
        }
    }
}
