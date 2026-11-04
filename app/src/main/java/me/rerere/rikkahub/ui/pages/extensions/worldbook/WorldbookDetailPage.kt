package me.rerere.rikkahub.ui.pages.extensions.worldbook

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.isledeer.soullink.game.ImportedEntry
import com.isledeer.soullink.game.WorldDoc
import me.rerere.rikkahub.R
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.Delete02
import me.rerere.rikkahub.data.game.WorldbookDraftStore
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

/** 详情页里的条目展示模型：草稿没有数据库 id，落库后有。 */
private data class EntryRowUi(
    val id: String?,
    val keys: List<String>,
    val content: String,
    val priority: Int,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorldbookDetailPage(id: String) {
    val vm = koinViewModel<WorldbookVM>()
    val navController = LocalNavController.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val isDraft = WorldbookDraftStore.isDraft(id)

    var title by remember(id) { mutableStateOf("") }
    var premise by remember(id) { mutableStateOf("") }
    var setting by remember(id) { mutableStateOf("") }
    var rulesText by remember(id) { mutableStateOf("") }
    var entries by remember(id) { mutableStateOf<List<EntryRowUi>>(emptyList()) }
    var saved by remember(id) { mutableStateOf(false) }
    var entryToDelete by remember(id) { mutableStateOf<EntryRowUi?>(null) }
    var showLeaveConfirm by remember(id) { mutableStateOf(false) }

    LaunchedEffect(id) {
        if (isDraft) {
            WorldbookDraftStore.get(id)?.let { draft ->
                val doc = WorldDoc.parse(draft.docJson)
                title = draft.title
                premise = doc.premise
                setting = doc.setting
                rulesText = doc.rules.joinToString("\n")
                entries = draft.entries.map {
                    EntryRowUi(id = null, keys = it.keys, content = it.content, priority = it.priority)
                }
            }
        } else {
            vm.load(id)?.let { book ->
                val doc = WorldDoc.parse(book.docJson)
                title = book.title
                premise = doc.premise
                setting = doc.setting
                rulesText = doc.rules.joinToString("\n")
            }
            entries = vm.entriesOf(id).map {
                EntryRowUi(
                    id = it.id,
                    keys = runCatching {
                        org.json.JSONArray(it.keysJson).let { arr ->
                            (0 until arr.length()).map { i -> arr.optString(i) }
                        }
                    }.getOrDefault(emptyList()).filter { key -> key.isNotBlank() },
                    content = it.content,
                    priority = it.priority,
                )
            }
        }
        saved = false
    }

    fun leave() {
        if (isDraft) {
            showLeaveConfirm = true
        } else {
            navController.popBackStack()
        }
    }

    fun persist() {
        val rules = rulesText.split("\n").filter { it.isNotBlank() }
        if (isDraft) {
            val draftEntries = entries.map {
                ImportedEntry(keys = it.keys, content = it.content, priority = it.priority)
            }
            vm.commitDraft(id, title, premise, setting, rules, draftEntries) { newId ->
                saved = true
                navController.navigate(me.rerere.rikkahub.Screen.WorldbookDetail(newId))
            }
        } else {
            vm.save(id, title, premise, setting, rules) { saved = true }
        }
    }

    BackHandler(enabled = isDraft) { leave() }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(title.ifBlank { stringResource(R.string.soullink_worldbooks_title) }) },
                navigationIcon = {
                    FilledTonalIconButton(
                        onClick = { leave() },
                        shapes = IconButtonDefaults.shapes(),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = CustomColors.listItemColors.containerColor
                        ),
                    ) {
                        Icon(HugeIcons.ArrowLeft01, stringResource(R.string.back))
                    }
                },
                actions = {
                    TextButton(onClick = { persist() }) {
                        Text(
                            stringResource(
                                if (isDraft) R.string.soullink_action_create else R.string.soullink_action_save
                            )
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (isDraft) {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(R.string.soullink_draft_banner),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it; saved = false },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.soullink_worldbook_title_hint)) },
                )
            }
            item {
                OutlinedTextField(
                    value = premise,
                    onValueChange = { premise = it; saved = false },
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    label = { Text(stringResource(R.string.soullink_worldbook_premise)) },
                )
            }
            item {
                OutlinedTextField(
                    value = setting,
                    onValueChange = { setting = it; saved = false },
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    label = { Text(stringResource(R.string.soullink_worldbook_setting)) },
                )
            }
            item {
                OutlinedTextField(
                    value = rulesText,
                    onValueChange = { rulesText = it; saved = false },
                    modifier = Modifier.fillMaxWidth().height(160.dp),
                    label = { Text(stringResource(R.string.soullink_worldbook_rules)) },
                    supportingText = { Text(stringResource(R.string.soullink_worldbook_rules_hint)) },
                )
            }

            item {
                Text(
                    text = stringResource(R.string.soullink_entries_count, entries.size),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            items(entries) { row ->
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (row.keys.isEmpty()) {
                                    stringResource(R.string.soullink_entry_always_on)
                                } else {
                                    row.keys.joinToString(" / ")
                                },
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = row.content,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = stringResource(R.string.soullink_entry_priority, row.priority),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { entryToDelete = row }) {
                            Icon(HugeIcons.Delete02, contentDescription = null)
                        }
                    }
                }
            }

            if (saved) {
                item {
                    Text(
                        text = stringResource(R.string.soullink_action_saved),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    entryToDelete?.let { row ->
        AlertDialog(
            onDismissRequest = { entryToDelete = null },
            title = { Text(stringResource(R.string.soullink_entry_delete_title)) },
            text = { Text(stringResource(R.string.soullink_entry_delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    entryToDelete = null
                    val target = row
                    entries = entries.filterNot { it == target }
                    target.id?.let { entryId -> vm.deleteEntry(entryId) {} }
                }) { Text(stringResource(R.string.soullink_action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { entryToDelete = null }) {
                    Text(stringResource(R.string.soullink_action_cancel))
                }
            },
        )
    }

    if (showLeaveConfirm) {
        AlertDialog(
            onDismissRequest = { showLeaveConfirm = false },
            title = { Text(stringResource(R.string.soullink_leave_title)) },
            text = { Text(stringResource(R.string.soullink_leave_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showLeaveConfirm = false
                    persist()
                }) { Text(stringResource(R.string.soullink_leave_save)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showLeaveConfirm = false
                    WorldbookDraftStore.remove(id)
                    navController.popBackStack()
                }) { Text(stringResource(R.string.soullink_leave_discard)) }
            },
        )
    }
}
