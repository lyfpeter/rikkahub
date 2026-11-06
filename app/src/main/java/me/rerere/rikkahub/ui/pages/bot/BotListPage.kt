package me.rerere.rikkahub.ui.pages.bot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.rikkahub.service.LyfclawBot
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun BotListPage(
    onBotSelected: () -> Unit,
    vm: BotListVM = koinViewModel(),
) {
    val bots by vm.bots.collectAsStateWithLifecycle()
    val currentBotLabel by vm.currentBotLabel.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()

    var editingBot by remember { mutableStateOf<LyfclawBot?>(null) }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("Bots") },
                navigationIcon = { BackButton() },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { contentPadding ->
        when {
            loading && bots.isEmpty() -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                }
            }
            error != null && bots.isEmpty() -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = error!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = { vm.fetchBots() }) {
                        Text("Retry")
                    }
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
                            onClick = {
                                vm.selectBot(bot)
                                onBotSelected()
                            },
                            onRename = { editingBot = bot },
                        )
                    }
                }
            }
        }
    }

    // 重命名对话框
    editingBot?.let { bot ->
        var name by remember(bot) { mutableStateOf(bot.displayName.ifBlank { bot.label }) }
        AlertDialog(
            onDismissRequest = { editingBot = null },
            title = { Text("Rename Bot") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Display Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.renameBot(bot, name.trim())
                        editingBot = null
                    },
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editingBot = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun BotItem(
    bot: LyfclawBot,
    isSelected: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
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
                    IconButton(onClick = onRename) {
                        Icon(HugeIcons.PencilEdit01, contentDescription = "Rename")
                    }
                    if (isSelected) {
                        Text(
                            "✓",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            },
        )
    }
}
