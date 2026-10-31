package me.rerere.rikkahub.data.remote

import me.rerere.rikkahub.data.datastore.SettingsStore
import okhttp3.Interceptor
import okhttp3.Response

/**
 * OkHttp interceptor: 自动注入 lyfclaw Bearer token 到 lyfclaw 后端请求。
 * 仅当请求 URL 以 settings.lyfclawBaseUrl 开头时生效。
 */
class AuthInterceptor(
    private val settingsStore: SettingsStore,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val baseUrl = settingsStore.settingsFlow.value.lyfclawBaseUrl
        val token = settingsStore.settingsFlow.value.lyfclawToken

        // 仅对 lyfclaw 后端请求注入 token
        if (baseUrl.isNotBlank() && request.url.toString().startsWith(baseUrl) && token != null) {
            val authedRequest = request.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
            val response = chain.proceed(authedRequest)
            // 401 时尝试刷新 token（单次重试，避免无限循环）
            if (response.code == 401) {
                response.close()
                // TODO: 触发 token 刷新逻辑（T7 中接入 LyfclawAuthClient.refresh）
                // 当前阶段直接返回 401，由 UI 层处理跳转登录
            }
            return response
        }
        return chain.proceed(request)
    }
}
