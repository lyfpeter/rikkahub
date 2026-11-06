package me.rerere.rikkahub.ui.pages.assistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.service.LyfclawBot
import me.rerere.rikkahub.service.LyfclawChatService

class AssistantVM(
    private val lyfclawChatService: LyfclawChatService,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    val currentBotLabel: StateFlow<String> = settingsStore.settingsFlow
        .map { it.lyfclawBotLabel }
        .stateIn(viewModelScope, SharingStarted.Lazily, "")

    private val _bots = MutableStateFlow<List<LyfclawBot>>(emptyList())
    val bots: StateFlow<List<LyfclawBot>> = _bots.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        fetchBots()
    }

    fun fetchBots() {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            runCatching {
                lyfclawChatService.fetchBots()
            }.onSuccess { bots ->
                _bots.value = bots
                if (currentBotLabel.value.isBlank() && bots.isNotEmpty()) {
                    settingsStore.update { it.copy(lyfclawBotLabel = bots.first().label) }
                }
            }.onFailure { e ->
                _error.value = e.message ?: "Failed to fetch bots"
            }
            _loading.value = false
        }
    }

    fun createBot(displayName: String, systemPrompt: String, onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            runCatching {
                // 不传 label：标识由后端生成；仅让用户提供显示名 / 系统提示。
                lyfclawChatService.createBot(displayName, systemPrompt)
            }.onSuccess {
                fetchBots()
                onSuccess()
            }.onFailure { e ->
                _error.value = e.message ?: "Failed to create bot"
            }
        }
    }

    fun updateBot(botLabel: String, displayName: String?, systemPrompt: String?) {
        viewModelScope.launch {
            runCatching {
                lyfclawChatService.updateBot(botLabel, displayName, systemPrompt)
            }.onSuccess {
                fetchBots()
            }.onFailure { e ->
                _error.value = e.message ?: "Failed to update bot"
            }
        }
    }

    fun deleteBot(botLabel: String) {
        viewModelScope.launch {
            runCatching {
                lyfclawChatService.deleteBot(botLabel)
            }.onSuccess {
                fetchBots()
            }.onFailure { e ->
                _error.value = e.message ?: "Failed to delete bot"
            }
        }
    }

    fun renameBot(bot: LyfclawBot, newDisplayName: String) {
        viewModelScope.launch {
            runCatching {
                lyfclawChatService.updateBotDisplayName(bot.label, newDisplayName)
            }.onSuccess {
                // 刷新列表
                fetchBots()
            }.onFailure { e ->
                _error.value = e.message ?: "Failed to rename bot"
            }
        }
    }

    fun selectBot(bot: LyfclawBot) {
        // 同步写入，不等 viewModelScope（页面可能马上被销毁）
        kotlinx.coroutines.runBlocking {
            settingsStore.update { it.copy(lyfclawBotLabel = bot.label) }
        }
    }
}