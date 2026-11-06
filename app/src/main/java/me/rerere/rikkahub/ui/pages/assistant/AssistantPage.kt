package me.rerere.rikkahub.ui.pages.assistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.rikkahub.R
import me.rerere.rikkahub.service.LyfclawBot
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun AssistantPage(
    onBotSelected: () -> Unit,
    showBack: Boolean = true,
    vm: AssistantVM = koinViewModel(),
) {
    val bots by vm.bots.collectAsStateWithLifecycle()
    val currentBotLabel by vm.currentBotLabel.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()

    var showCreateSheet by remember { mutableStateOf(false) }
    var editingBot by remember { mutableStateOf<LyfclawBot?>(null) }
    var menuBot by remember { mutableStateOf<LyfclawBot?>(null) }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.assistant_page_title)) },
                navigationIcon = { if (showBack) BackButton() },
                actions = {
                    IconButton(onClick = { showCreateSheet = true }) {
                        Icon(HugeIcons.Add01, contentDescription = stringResource(R.string.assistant_page_bot_create))
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { contentPadding ->
        when {
            loading && bots.isEmpty() -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) { CircularProgressIndicator() }
            }
            error != null && bots.isEmpty() -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(error!!, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { vm.fetchBots() }) { Text(stringResource(R.string.assistant_page_bot_retry)) }
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = contentPadding + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(bots, key = { it.label }) { bot ->
                        BotItem(
                            bot = bot,
                            isSelected = bot.label == currentBotLabel,
                            onClick = { vm.selectBot(bot); onBotSelected() },
                            onMore = { menuBot = bot },
                        )
                    }
                }
            }
        }
    }

    // 创建 Bot 表单
    if (showCreateSheet) {
        BotEditSheet(
            title = stringResource(R.string.assistant_page_bot_new_title),
            initialLabel = "",
            initialDisplayName = "",
            initialSystemPrompt = "",
            isCreate = true,
            onDismiss = { showCreateSheet = false },
            onSave = { label, displayName, systemPrompt ->
                vm.createBot(label, displayName, systemPrompt)
                showCreateSheet = false
            },
        )
    }

    // 编辑 Bot（重命名）
    editingBot?.let { bot ->
        BotEditSheet(
            title = stringResource(R.string.assistant_page_bot_edit_title),
            initialLabel = bot.label,
            initialDisplayName = bot.displayName,
            initialSystemPrompt = "",
            isCreate = false,
            onDismiss = { editingBot = null },
            onSave = { _, displayName, systemPrompt ->
                vm.updateBot(bot.label, displayName, systemPrompt.ifBlank { null })
                editingBot = null
            },
        )
    }

    // 长按菜单
    menuBot?.let { bot ->
        DropdownMenu(
            expanded = menuBot != null,
            onDismissRequest = { menuBot = null },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.assistant_page_bot_rename)) },
                onClick = { editingBot = bot; menuBot = null },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.delete)) },
                onClick = { vm.deleteBot(bot.label); menuBot = null },
            )
        }
    }
}

@Composable
private fun BotItem(
    bot: LyfclawBot,
    isSelected: Boolean,
    onClick: () -> Unit,
    onMore: () -> Unit,
) {
    CardGroup {
        item(
            onClick = onClick,
            headlineContent = {
                Text(
                    bot.displayName.ifBlank { bot.label },
                    style = if (isSelected) MaterialTheme.typography.titleSmallEmphasized
                    else MaterialTheme.typography.titleSmall,
                )
            },
            supportingContent = {
                if (bot.displayName.isNotBlank() && bot.displayName != bot.label) {
                    Text(
                        bot.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onMore) {
                        Icon(HugeIcons.MoreVertical, contentDescription = stringResource(R.string.assistant_page_bot_more))
                    }
                    if (isSelected) {
                        Text("✓", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            },
        )
    }
}

@Composable
private fun BotEditSheet(
    title: String,
    initialLabel: String,
    initialDisplayName: String,
    initialSystemPrompt: String,
    isCreate: Boolean,
    onDismiss: () -> Unit,
    onSave: (label: String, displayName: String, systemPrompt: String) -> Unit,
) {
    var label by remember { mutableStateOf(initialLabel) }
    var displayName by remember { mutableStateOf(initialDisplayName) }
    var systemPrompt by remember { mutableStateOf(initialSystemPrompt) }
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)

            if (isCreate) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(R.string.assistant_page_bot_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it },
                label = { Text(stringResource(R.string.assistant_page_bot_display_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = systemPrompt,
                onValueChange = { systemPrompt = it },
                label = { Text(stringResource(R.string.assistant_page_bot_system_prompt)) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 300.dp),
            )

            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                TextButton(
                    onClick = { onSave(label.trim(), displayName.trim(), systemPrompt.trim()) },
                    enabled = if (isCreate) label.isNotBlank() else true,
                ) { Text(stringResource(R.string.save)) }
            }
        }
    }
}