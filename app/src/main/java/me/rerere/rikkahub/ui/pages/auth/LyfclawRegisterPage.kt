package me.rerere.rikkahub.ui.pages.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.remote.LyfclawAuthClient
import me.rerere.rikkahub.data.remote.LyfclawRegisterRequest
import me.rerere.rikkahub.data.remote.normalizeLyfclawBaseUrl
import org.koin.compose.koinInject

@Composable
fun LyfclawRegisterPage(
    onRegisterSuccess: () -> Unit,
    onNavigateToLogin: () -> Unit,
) {
    val authClient: LyfclawAuthClient = koinInject()
    val settingsStore: SettingsStore = koinInject()
    val scope = rememberCoroutineScope()

    var baseUrl by remember { mutableStateOf(settingsStore.settingsFlow.value.lyfclawBaseUrl) }
    var inviteCode by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("注册账号", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(24.dp))

            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("服务地址") },
                placeholder = { Text("https://lyfclaw.asia") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = inviteCode,
                onValueChange = { inviteCode = it },
                label = { Text("邀请码") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("用户名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("密码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it },
                label = { Text("显示名（可选）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
            }

            Button(
                onClick = {
                    if (loading) return@Button
                    loading = true
                    error = null
                    scope.launch {
                        try {
                            val resp = authClient.register(
                                baseUrl,
                                LyfclawRegisterRequest(inviteCode, username, password, displayName),
                            )
                            settingsStore.update {
                                it.copy(
                                    lyfclawBaseUrl = normalizeLyfclawBaseUrl(baseUrl),
                                    lyfclawToken = resp.session_id,
                                    lyfclawUsername = username,
                                    lyfclawBotLabel = "",
                                )
                            }
                            onRegisterSuccess()
                        } catch (e: Exception) {
                            error = e.message ?: "注册失败"
                        } finally {
                            loading = false
                        }
                    }
                },
                enabled = !loading && baseUrl.isNotBlank() && inviteCode.isNotBlank() && username.isNotBlank() && password.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text("注册")
                }
            }
            Spacer(Modifier.height(8.dp))

            TextButton(onClick = onNavigateToLogin) {
                Text("已有账号？去登录")
            }
        }
    }
}
