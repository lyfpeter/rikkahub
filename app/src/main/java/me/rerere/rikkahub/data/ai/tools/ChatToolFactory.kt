package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.memoryId
import me.rerere.rikkahub.data.skills.SkillManager

/** Creates the complete tool set for one generation run, including approval resumption. */
class ChatToolFactory(
    private val memoryRepository: MemoryRepository,
    private val conversationRepository: ConversationRepository,
    private val localTools: LocalTools,
    private val skillManager: SkillManager,
) {
    suspend fun createTools(
        assistant: Assistant,
    ): List<Tool> = buildList {
        if (assistant.enableMemory) {
            addAll(buildMemoryTools(memoryRepository, assistant.memoryId))
        }
        addAll(localTools.getTools(assistant.localTools))
        if (assistant.enableRecentChatsReference) {
            addAll(createConversationTools(conversationRepository, assistant.id))
        }
        if (assistant.enabledSkills.isNotEmpty()) {
            addAll(
                createSkillTools(
                    enabledSkills = assistant.enabledSkills,
                    allSkills = skillManager.listSkills(),
                )
            )
        }
    }
}
