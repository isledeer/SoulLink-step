package me.rerere.rikkahub.ui.pages.extensions.worldbook

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

/**
 * 世界书管理（全局，不带 conversationId）。
 * 入口：设置 → 扩展。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorldbookPage() {
    val navController = LocalNavController.current
    val vm = koinViewModel<WorldbookVM>()
    val books by vm.books.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var showCreate by remember { mutableStateOf(false) }
    var newTitle by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.soullink_worldbooks_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                newTitle = ""
                showCreate = true
            }) {
                Text("+", style = MaterialTheme.typography.headlineSmall)
            }
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (books.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.soullink_worldbooks_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier,
                    )
                }
            } else {
                item {
                    CardGroup(title = { Text(stringResource(R.string.soullink_worldbooks_section)) }) {
                        books.forEach { book ->
                            item(
                                onClick = { navController.navigate(Screen.WorldbookDetail(book.id)) },
                                headlineContent = { Text(book.title) },
                                supportingContent = { Text(book.template) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text(stringResource(R.string.soullink_worldbooks_new)) },
            text = {
                TextField(
                    value = newTitle,
                    onValueChange = { newTitle = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.soullink_worldbook_title_hint)) },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val title = newTitle
                    showCreate = false
                    vm.create(title) { id -> navController.navigate(Screen.WorldbookDetail(id)) }
                }) {
                    Text(stringResource(R.string.soullink_action_create))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreate = false }) {
                    Text(stringResource(R.string.soullink_action_cancel))
                }
            },
        )
    }
}
