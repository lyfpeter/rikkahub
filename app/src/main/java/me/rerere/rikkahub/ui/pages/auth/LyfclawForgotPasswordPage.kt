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
import me.rerere.rikkahub.data.remote.normalizeLyfclawBaseUrl
import org.koin.compose.koinInject

@Composable
fun LyfclawForgotPasswordPage(
    onBackToLogin: () -> Unit,
) {
    val authClient: LyfclawAuthClient = koinInject()
    val settingsStore: SettingsStore = koinInject()
    val scope = rememberCoroutineScope()

    var baseUrl by remember { mutableStateOf(settingsStore.settingsFlow.value.lyfclawBaseUrl) }
    var username by remember { mutableStateOf("") }
    var resetCode by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    var codeRequested by remember { mutableStateOf(false) }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("找回密码", style = MaterialTheme.typography.headlineMedium)
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
                value = username,
                onValueChange = { username = it; codeRequested = false; info = null },
                label = { Text("用户名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = resetCode,
                onValueChange = { resetCode = it },
                label = { Text("重置码") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = codeRequested,
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = newPassword,
                onValueChange = { newPassword = it },
                label = { Text("新密码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                enabled = codeRequested,
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = confirmPassword,
                onValueChange = { confirmPassword = it },
                label = { Text("确认新密码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                enabled = codeRequested,
            )
            Spacer(Modifier.height(8.dp))

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
            }
            info?.let {
                Text(it, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
            }

            // Step 1: 获取重置码
            if (!codeRequested) {
                Button(
                    onClick = {
                        if (loading) return@Button
                        if (baseUrl.isBlank() || username.isBlank()) {
                            error = "请填写服务地址和用户名"
                            return@Button
                        }
                        loading = true
                        error = null
                        info = null
                        scope.launch {
                            try {
                                val resp = authClient.forgotPassword(baseUrl, username)
                                if (resp.reset_code != null) {
                                    resetCode = resp.reset_code
                                    codeRequested = true
                                    info = "重置码已生成，请使用它设置新密码"
                                } else {
                                    error = "用户名不存在或无法重置"
                                }
                            } catch (e: Exception) {
                                error = e.message ?: "获取重置码失败"
                            } finally {
                                loading = false
                            }
                        }
                    },
                    enabled = !loading && baseUrl.isNotBlank() && username.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (loading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Text("获取重置码")
                    }
                }
            }

            // Step 2: 重置密码
            if (codeRequested) {
                Button(
                    onClick = {
                        if (loading) return@Button
                        if (newPassword != confirmPassword) {
                            error = "两次输入的新密码不一致"
                            return@Button
                        }
                        if (resetCode.isBlank() || newPassword.isBlank()) {
                            error = "请填写重置码和新密码"
                            return@Button
                        }
                        loading = true
                        error = null
                        info = null
                        scope.launch {
                            try {
                                authClient.resetPassword(baseUrl, username, resetCode, newPassword)
                                // 保存服务地址
                                settingsStore.update {
                                    it.copy(lyfclawBaseUrl = normalizeLyfclawBaseUrl(baseUrl))
                                }
                                info = "密码已重置，请使用新密码登录"
                                // 延迟返回登录页
                                kotlinx.coroutines.delay(1500)
                                onBackToLogin()
                            } catch (e: Exception) {
                                error = e.message ?: "重置密码失败"
                            } finally {
                                loading = false
                            }
                        }
                    },
                    enabled = !loading && resetCode.isNotBlank() && newPassword.isNotBlank() && confirmPassword.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (loading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Text("重置密码")
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            TextButton(onClick = onBackToLogin) {
                Text("返回登录")
            }
        }
    }
}
