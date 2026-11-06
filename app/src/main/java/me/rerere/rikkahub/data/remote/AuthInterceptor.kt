package me.rerere.rikkahub.data.remote

import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.datastore.SettingsStore
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/**
 * OkHttp interceptor: 自动注入 lyfclaw Bearer token 到 lyfclaw 后端请求。
 * 仅当请求 URL 以 settings.lyfclawBaseUrl 开头时生效。
 *
 * 收到 401 时调用 [LyfclawAuthClient.refresh] 换新 token 并重试一次；
 * 刷新失败则原样返回 401，由 UI 层处理跳转登录。
 */
class AuthInterceptor(
    private val settingsStore: SettingsStore,
    private val authClient: LyfclawAuthClient,
) : Interceptor {

    // 串行化刷新：并发遇到的 401 只真正刷新一次，其余复用刚换到的 token
    private val refreshLock = Any()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val baseUrl = settingsStore.settingsFlow.value.lyfclawBaseUrl

        // 仅对 lyfclaw 后端请求注入 token
        if (baseUrl.isBlank() || !request.url.toString().startsWith(baseUrl)) {
            return chain.proceed(request)
        }

        val token = settingsStore.settingsFlow.value.lyfclawToken
        val response = chain.proceed(withAuth(request, token))

        // 401 时尝试刷新 token（单次重试，避免无限循环）
        if (response.code != 401 || token == null) {
            return response
        }
        // Session 被 revoke/丢失：refresh 也无济于事，清除 token 让 UI 跳登录页
        val body = response.peekBody(1024).string()
        if (body.contains("Session revoked") || body.contains("Session not found")) {
            response.close()
            runCatching {
                runBlocking { settingsStore.update { it.copy(lyfclawToken = null) } }
            }
            return chain.proceed(withAuth(request, null))
        }
        val refreshed = refreshToken(baseUrl, token) ?: return response
        response.close()
        return chain.proceed(withAuth(request, refreshed))
    }

    private fun withAuth(request: Request, token: String?): Request =
        if (token == null) {
            request
        } else {
            request.newBuilder().header("Authorization", "Bearer $token").build()
        }

    /**
     * 用 [staleToken] 换新 token 并落盘。加锁期间若已被其他请求刷新过，直接复用不重复请求。
     * 刷新失败返回 null。
     */
    private fun refreshToken(baseUrl: String, staleToken: String): String? {
        synchronized(refreshLock) {
            val current = settingsStore.settingsFlow.value.lyfclawToken
            if (current != null && current != staleToken) return current
            return runCatching {
                runBlocking {
                    val newToken = authClient.refresh(baseUrl, staleToken).session_id
                    settingsStore.update { it.copy(lyfclawToken = newToken) }
                    newToken
                }
            }.getOrNull()
        }
    }
}
