package me.rerere.rikkahub.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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

class LyfclawAuthClient(
    private val httpClient: HttpClient,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    suspend fun login(baseUrl: String, username: String, password: String): LyfclawLoginResponse {
        return httpClient.post("$baseUrl/api/app/login") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("username" to username, "password" to password))
        }.let { resp ->
            if (resp.status.value !in 200..299) {
                throw Exception("Login failed: ${resp.status.value} ${resp.bodyAsText()}")
            }
            json.decodeFromString(resp.bodyAsText())
        }
    }

    suspend fun register(baseUrl: String, req: LyfclawRegisterRequest): LyfclawLoginResponse {
        return httpClient.post("$baseUrl/api/app/register") {
            contentType(ContentType.Application.Json)
            setBody(req)
        }.let { resp ->
            if (resp.status.value !in 200..299) {
                throw Exception("Register failed: ${resp.status.value} ${resp.bodyAsText()}")
            }
            json.decodeFromString(resp.bodyAsText())
        }
    }

    suspend fun changePassword(baseUrl: String, token: String, oldPassword: String, newPassword: String) {
        httpClient.post("$baseUrl/api/app/password") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            setBody(LyfclawPasswordRequest(oldPassword, newPassword))
        }.let { resp ->
            if (resp.status.value !in 200..299) {
                throw Exception("Change password failed: ${resp.status.value} ${resp.bodyAsText()}")
            }
        }
    }

    suspend fun me(baseUrl: String, token: String): LyfclawUserInfo {
        return httpClient.post("$baseUrl/api/app/me") {
            header("Authorization", "Bearer $token")
        }.let { resp ->
            if (resp.status.value !in 200..299) {
                throw Exception("Auth check failed: ${resp.status.value}")
            }
            json.decodeFromString(resp.bodyAsText())
        }
    }

    suspend fun refresh(baseUrl: String, token: String): LyfclawLoginResponse {
        return httpClient.post("$baseUrl/api/app/refresh") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
        }.let { resp ->
            if (resp.status.value !in 200..299) {
                throw Exception("Token refresh failed: ${resp.status.value}")
            }
            json.decodeFromString(resp.bodyAsText())
        }
    }
}
