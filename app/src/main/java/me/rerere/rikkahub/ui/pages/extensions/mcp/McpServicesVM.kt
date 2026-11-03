package me.rerere.rikkahub.ui.pages.extensions.mcp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.skills.McpServiceCatalog
import me.rerere.rikkahub.data.skills.McpServiceEntry

class McpServicesVM(
    private val settingsStore: SettingsStore,
) : ViewModel() {

    val enabledServices: StateFlow<Set<String>> =
        settingsStore.settingsFlow
            .map { it.enabledMcpServices }
            .stateIn(
                scope = viewModelScope,
                started = kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000),
                initialValue = settingsStore.settingsFlow.value.enabledMcpServices,
            )

    fun toggleService(service: McpServiceEntry, enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.update { settings ->
                val current = settings.enabledMcpServices
                val updated = if (enabled) current + service.id else current - service.id
                settings.copy(enabledMcpServices = updated)
            }
        }
    }
}
