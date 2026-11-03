package me.rerere.rikkahub.ui.pages.extensions.worldbook

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.repository.GameRepository

class WorldbookVM(
    private val repository: GameRepository,
) : ViewModel() {

    val books = repository.observeWorldbooks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun create(title: String, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            onCreated(repository.createWorldbook(title))
        }
    }

    suspend fun load(id: String) = repository.worldbook(id)

    fun save(
        id: String,
        title: String,
        premise: String,
        setting: String,
        rules: List<String>,
        onDone: () -> Unit,
    ) {
        viewModelScope.launch {
            repository.saveWorldDoc(id, title, premise, setting, rules)
            onDone()
        }
    }
}
