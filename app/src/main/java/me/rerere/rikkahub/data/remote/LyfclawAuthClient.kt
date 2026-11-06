package me.rerere.rikkahub.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class LyfclawLoginRequest(
    val username: String,
    val password: String,
)

@Serializable
data class LyfclawLoginResponse(
    val session_id: String,
    val token_type: String = "Bearer",
)

@Serializable
data class LyfclawUserInfo(
    val username: String,
    val principal_id: String? = null,
    val tenant_id: String? = null,
)

@Serializable
data class LyfclawRegisterRequest(
    val code: String,
    val username: String,
    val password: String,
    val display_name: String = "",
)

@Serializable
data class LyfclawPasswordRequest(
    val old_password: String,
    val new_password: String,
)

@Serializable
data class LyfclawForgotPasswordRequest(
    val username: String,
)

@Serializable
data class LyfclawForgotPasswordResponse(
    val ok: Boolean,
    val reset_code: String? = null,
)

@Serializable
data class LyfclawResetPasswordRequest(
    val username: String,
    val reset_code: String,
    val new_password: String,
)

/**
 * 规整 lyfclaw 服务地址：去首尾空白、缺协议时补 `http://`、去掉结尾的 `/`。
 * 这样用户只填 `192.168.1.7:8000` 也能正常解析，不用担心漏写 scheme。
 */
fun normalizeLyfclawBaseUrl(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return ""
    val withScheme = if (trimmed.contains("://")) trimmed else "http://$trimmed"
    return withScheme.trimEnd('/')
}

class LyfclawAuthClient(
    private val httpClient: HttpClient,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    suspend fun login(baseUrl: String, username: String, password: String): LyfclawLoginResponse {
        val base = normalizeLyfclawBaseUrl(baseUrl)
        val payload = json.encodeToString(
            LyfclawLoginRequest.serializer(),
            LyfclawLoginRequest(username, password),
        )
        return httpClient.post("$base/api/app/login") {
            contentType(ContentType.Application.Json)
            setBody(payload)
        }.let { resp ->
            if (resp.status.value !in 200..299) {
                throw Exception("Login failed: ${resp.status.value} ${resp.bodyAsText()}")
            }
            json.decodeFromString(resp.bodyAsText())
        }
    }

    suspend fun register(baseUrl: String, req: LyfclawRegisterRequest): LyfclawLoginResponse {
        val base = normalizeLyfclawBaseUrl(baseUrl)
        val payload = json.encodeToString(LyfclawRegisterRequest.serializer(), req)
        return httpClient.post("$base/api/app/register") {
            contentType(ContentType.Application.Json)
            setBody(payload)
        }.let { resp ->
            if (resp.status.value !in 200..299) {
                throw Exception("Register failed: ${resp.status.value} ${resp.bodyAsText()}")
            }
            json.decodeFromString(resp.bodyAsText())
        }
    }

    suspend fun changePassword(baseUrl: String, token: String, oldPassword: String, newPassword: String) {
        val base = normalizeLyfclawBaseUrl(baseUrl)
        val payload = json.encodeToString(
            LyfclawPasswordRequest.serializer(),
            LyfclawPasswordRequest(oldPassword, newPassword),
        )
        httpClient.post("$base/api/app/password") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            setBody(payload)
        }.let { resp ->
            if (resp.status.value !in 200..299) {
                throw Exception("Change password failed: ${resp.status.value} ${resp.bodyAsText()}")
            }
        }
    }

    suspend fun me(baseUrl: String, token: String): LyfclawUserInfo {
        val base = normalizeLyfclawBaseUrl(baseUrl)
        return httpClient.get("$base/api/app/me") {
            header("Authorization", "Bearer $token")
        }.let { resp ->
            if (resp.status.value !in 200..299) {
                throw Exception("Auth check failed: ${resp.status.value}")
            }
            json.decodeFromString(resp.bodyAsText())
        }
    }

    suspend fun refresh(baseUrl: String, token: String): LyfclawLoginResponse {
        val base = normalizeLyfclawBaseUrl(baseUrl)
        return httpClient.post("$base/api/app/refresh") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
        }.let { resp ->
            if (resp.status.value !in 200..299) {
                throw Exception("Token refresh failed: ${resp.status.value}")
            }
            json.decodeFromString(resp.bodyAsText())
        }
    }

    suspend fun forgotPassword(baseUrl: String, username: String): LyfclawForgotPasswordResponse {
        val base = normalizeLyfclawBaseUrl(baseUrl)
        val payload = json.encodeToString(
            LyfclawForgotPasswordRequest.serializer(),
            LyfclawForgotPasswordRequest(username),
        )
        return httpClient.post("$base/api/app/forgot_password") {
            contentType(ContentType.Application.Json)
            setBody(payload)
        }.let { resp ->
            if (resp.status.value !in 200..299) {
                throw Exception("Forgot password failed: ${resp.status.value} ${resp.bodyAsText()}")
            }
            json.decodeFromString(resp.bodyAsText())
        }
    }

    suspend fun resetPassword(baseUrl: String, username: String, resetCode: String, newPassword: String) {
        val base = normalizeLyfclawBaseUrl(baseUrl)
        val payload = json.encodeToString(
            LyfclawResetPasswordRequest.serializer(),
            LyfclawResetPasswordRequest(username, resetCode, newPassword),
        )
        httpClient.post("$base/api/app/reset_password") {
            contentType(ContentType.Application.Json)
            setBody(payload)
        }.let { resp ->
            if (resp.status.value !in 200..299) {
                throw Exception("Reset password failed: ${resp.status.value} ${resp.bodyAsText()}")
            }
        }
    }

    suspend fun registerDevice(baseUrl: String, token: String, fcmToken: String) {
        val base = normalizeLyfclawBaseUrl(baseUrl)
        httpClient.post("$base/api/app/devices/register") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            setBody("""{"token":"$fcmToken","platform":"android"}""")
        }.let { resp ->
            if (resp.status.value !in 200..299) {
                throw Exception("Device register failed: ${resp.status.value}")
            }
        }
    }

    suspend fun unregisterDevice(baseUrl: String, token: String, fcmToken: String) {
        val base = normalizeLyfclawBaseUrl(baseUrl)
        httpClient.post("$base/api/app/devices/unregister") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            setBody("""{"token":"$fcmToken"}""")
        }.let { resp ->
            if (resp.status.value !in 200..299) {
                throw Exception("Device unregister failed: ${resp.status.value}")
            }
        }
    }
}
