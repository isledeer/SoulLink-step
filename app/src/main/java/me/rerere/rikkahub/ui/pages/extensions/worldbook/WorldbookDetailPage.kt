package me.rerere.rikkahub.ui.pages.extensions.worldbook

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.dp
import com.isledeer.soullink.game.WorldDoc
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

/**
 * 世界书详情：只编辑 title / premise / setting / rules 四个字段。
 * 落库由 `:game` 的 `updateDoc` 负责，UI 不拼 JSON。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorldbookDetailPage(id: String) {
    val vm = koinViewModel<WorldbookVM>()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var title by remember(id) { mutableStateOf("") }
    var premise by remember(id) { mutableStateOf("") }
    var setting by remember(id) { mutableStateOf("") }
    var rulesText by remember(id) { mutableStateOf("") }
    var saved by remember(id) { mutableStateOf(false) }

    LaunchedEffect(id) {
        vm.load(id)?.let { book ->
            val doc = WorldDoc.parse(book.docJson)
            title = book.title
            premise = doc.premise
            setting = doc.setting
            rulesText = doc.rules.joinToString("\n")
            saved = false
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(title.ifBlank { stringResource(R.string.soullink_worldbooks_title) }) },
                navigationIcon = { BackButton() },
                actions = {
                    TextButton(onClick = {
                        vm.save(
                            id = id,
                            title = title,
                            premise = premise,
                            setting = setting,
                            rules = rulesText.split("\n").filter { it.isNotBlank() },
                            onDone = { saved = true },
                        )
                    }) {
                        Text(stringResource(R.string.soullink_action_save))
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
                    text = stringResource(if (saved) R.string.soullink_action_saved else R.string.soullink_action_save),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
