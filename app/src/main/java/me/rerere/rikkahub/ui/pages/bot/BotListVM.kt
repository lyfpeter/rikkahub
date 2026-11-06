package me.rerere.rikkahub.ui.pages.bot

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

class BotListVM(
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

    fun selectBot(bot: LyfclawBot) {
        // 同步写入，不等 viewModelScope（页面可能马上被销毁）
        kotlinx.coroutines.runBlocking {
            settingsStore.update { it.copy(lyfclawBotLabel = bot.label) }
        }
    }
}
