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
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationConfig
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.LyfclawBot
import me.rerere.rikkahub.service.LyfclawChatService
import me.rerere.rikkahub.service.LyfclawRuntime
import kotlin.uuid.Uuid

class AssistantVM(
    private val lyfclawChatService: LyfclawChatService,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
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

    /** 用户可选的 agent 运行时候选（后端白名单；失败时为空 → UI 退化为仅当前值）。 */
    private val _runtimes = MutableStateFlow<List<LyfclawRuntime>>(emptyList())
    val runtimes: StateFlow<List<LyfclawRuntime>> = _runtimes.asStateFlow()

    init {
        fetchBots()
        fetchRuntimes()
    }

    fun fetchRuntimes() {
        viewModelScope.launch {
            runCatching {
                lyfclawChatService.fetchRuntimes()
            }.onSuccess { list ->
                _runtimes.value = list
            }.onFailure {
                // 候选取不到不致命：下拉退化为仅显示当前值。
            }
        }
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

    fun createBot(displayName: String, systemPrompt: String, runtime: String = "", onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            runCatching {
                // 不传 label：标识由后端生成；仅让用户提供显示名 / 系统提示 / 运行时。
                lyfclawChatService.createBot(displayName, systemPrompt, runtime)
            }.onSuccess {
                fetchBots()
                onSuccess()
            }.onFailure { e ->
                _error.value = e.message ?: "Failed to create bot"
            }
        }
    }

    fun updateBot(botLabel: String, displayName: String?, systemPrompt: String?, runtime: String? = null) {
        viewModelScope.launch {
            runCatching {
                lyfclawChatService.updateBot(botLabel, displayName, systemPrompt, runtime)
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

    /**
     * 应用内多 Bot 群聊：新建一个「群会话」并落库。
     *
     * 会话用当前助手的 [assistantId]（群自身的身份），把成员 bot 标签与群裸会话 id
     * 固定在 [ConversationConfig] 上；群用独立的 `grp-<uuid>`，与 1:1 会话隔离。
     * 建好后回调 [onCreated] 让调用方跳到会话页。
     */
    fun createGroupConversation(memberBotLabels: List<String>, onCreated: (Uuid) -> Unit) {
        if (memberBotLabels.size < 2) return
        viewModelScope.launch {
            runCatching {
                val assistantId = settingsStore.settingsFlow.value.assistantId
                val conversation = Conversation.ofId(
                    id = Uuid.random(),
                    assistantId = assistantId,
                ).copy(
                    title = memberBotLabels.joinToString("、"),
                    config = ConversationConfig(
                        groupMemberBotLabels = memberBotLabels,
                        lyfclawGroupConversationId = "grp-${Uuid.random()}",
                    ),
                )
                conversationRepo.insertConversation(conversation)
                conversation.id
            }.onSuccess(onCreated)
                .onFailure { e ->
                    _error.value = e.message ?: "Failed to create group"
                }
        }
    }
}