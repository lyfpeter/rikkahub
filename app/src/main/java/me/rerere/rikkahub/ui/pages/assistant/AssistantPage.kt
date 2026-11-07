package me.rerere.rikkahub.ui.pages.assistant

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.mutableStateListOf
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
import me.rerere.hugeicons.stroke.UserGroup
import me.rerere.rikkahub.R
import me.rerere.rikkahub.service.LyfclawBot
import me.rerere.rikkahub.service.LyfclawRuntime
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import me.rerere.ui.components.SelectTextField
import org.koin.androidx.compose.koinViewModel
import kotlin.uuid.Uuid

@Composable
fun AssistantPage(
    onBotSelected: () -> Unit,
    onGroupCreated: (Uuid) -> Unit = {},
    showBack: Boolean = true,
    vm: AssistantVM = koinViewModel(),
) {
    val bots by vm.bots.collectAsStateWithLifecycle()
    val currentBotLabel by vm.currentBotLabel.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val runtimes by vm.runtimes.collectAsStateWithLifecycle()

    var showCreateSheet by remember { mutableStateOf(false) }
    var showCreateGroupSheet by remember { mutableStateOf(false) }
    var editingBot by remember { mutableStateOf<LyfclawBot?>(null) }
    var menuBot by remember { mutableStateOf<LyfclawBot?>(null) }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.assistant_page_title)) },
                navigationIcon = { if (showBack) BackButton() },
                actions = {
                    // 至少 2 个 bot 才能成群
                    if (bots.size >= 2) {
                        IconButton(onClick = { showCreateGroupSheet = true }) {
                            Icon(
                                HugeIcons.UserGroup,
                                contentDescription = stringResource(R.string.group_chat_new),
                            )
                        }
                    }
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
            initialDisplayName = "",
            initialSystemPrompt = "",
            initialRuntime = "auto",
            availableRuntimes = runtimes,
            isCreate = true,
            onDismiss = { showCreateSheet = false },
            onSave = { displayName, systemPrompt, runtime ->
                vm.createBot(displayName, systemPrompt, runtime)
                showCreateSheet = false
            },
        )
    }

    // 群创建表单（多选 bot）
    if (showCreateGroupSheet) {
        GroupCreateSheet(
            bots = bots,
            onDismiss = { showCreateGroupSheet = false },
            onConfirm = { labels ->
                showCreateGroupSheet = false
                vm.createGroupConversation(labels, onCreated = onGroupCreated)
            },
        )
    }

    // 编辑 Bot（重命名 / 编辑角色 / 切换运行时）
    editingBot?.let { bot ->
        BotEditSheet(
            title = stringResource(R.string.assistant_page_bot_edit_title),
            initialDisplayName = bot.displayName,
            initialSystemPrompt = "",
            initialRuntime = bot.runtime.ifBlank { "auto" },
            availableRuntimes = runtimes,
            isCreate = false,
            onDismiss = { editingBot = null },
            onSave = { displayName, systemPrompt, runtime ->
                vm.updateBot(bot.label, displayName, systemPrompt.ifBlank { null }, runtime)
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
                // 标识（bot.label）已由后端生成、对内不可读，不再展示。
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
    initialDisplayName: String,
    initialSystemPrompt: String,
    initialRuntime: String,
    availableRuntimes: List<LyfclawRuntime>,
    isCreate: Boolean,
    onDismiss: () -> Unit,
    onSave: (displayName: String, systemPrompt: String, runtime: String) -> Unit,
) {
    var displayName by remember { mutableStateOf(initialDisplayName) }
    var systemPrompt by remember { mutableStateOf(initialSystemPrompt) }
    var runtime by remember { mutableStateOf(initialRuntime) }
    val sheetState = rememberModalBottomSheetState()

    // 候选 = 后端白名单；若当前值不在其中（如白名单收紧后的旧值），追加一项保底显示，
    // 否则下拉会「显示不出当前值」。
    val runtimeOptions = remember(availableRuntimes, initialRuntime) {
        if (initialRuntime.isNotBlank() && availableRuntimes.none { it.id == initialRuntime }) {
            availableRuntimes + LyfclawRuntime(id = initialRuntime, label = "$initialRuntime (unsupported)")
        } else {
            availableRuntimes
        }
    }

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

            SelectTextField(
                value = runtimeOptions.firstOrNull { it.id == runtime }?.label ?: runtime,
                options = runtimeOptions,
                onOptionSelected = { runtime = it.id },
                readOnly = true,
                label = { Text(stringResource(R.string.assistant_page_bot_runtime)) },
                optionToString = { it.label },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                TextButton(
                    onClick = { onSave(displayName.trim(), systemPrompt.trim(), runtime) },
                    enabled = if (isCreate) displayName.isNotBlank() else true,
                ) { Text(stringResource(R.string.save)) }
            }
        }
    }
}

@Composable
private fun GroupCreateSheet(
    bots: List<LyfclawBot>,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val selected = remember { mutableStateListOf<String>() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.group_chat_select_members), style = MaterialTheme.typography.titleLarge)

            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(bots, key = { it.label }) { bot ->
                    val isSelected = bot.label in selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (isSelected) selected.remove(bot.label) else selected.add(bot.label)
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Checkbox(checked = isSelected, onCheckedChange = null)
                        Text(bot.displayName.ifBlank { bot.label })
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                TextButton(
                    onClick = { onConfirm(selected.toList()) },
                    enabled = selected.size >= 2,
                ) { Text(stringResource(R.string.group_chat_create)) }
            }
        }
    }
}