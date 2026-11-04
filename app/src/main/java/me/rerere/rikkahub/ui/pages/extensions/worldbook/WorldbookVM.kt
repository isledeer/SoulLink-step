package me.rerere.rikkahub.ui.pages.extensions.worldbook

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.isledeer.soullink.game.ImportedEntry
import com.isledeer.soullink.game.ImportedWorld
import com.isledeer.soullink.game.WorldDoc
import com.isledeer.soullink.game.updateDoc
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.db.entity.WorldEntryEntity
import me.rerere.rikkahub.data.game.WorldbookDraftStore
import me.rerere.rikkahub.data.repository.GameRepository
import java.util.UUID

class WorldbookVM(
    private val repository: GameRepository,
) : ViewModel() {

    val books = repository.observeWorldbooks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ---- 草稿 ----

    /** 新建一本只在内存里的草稿，返回临时 id。 */
    fun newDraft(title: String): String {
        val id = "draft-${UUID.randomUUID()}"
        val name = title.trim().ifBlank { "未命名世界" }
        WorldbookDraftStore.put(
            id,
            ImportedWorld(
                title = name,
                docJson = updateDoc(null, name, "", "", emptyList()),
                entries = emptyList(),
            ),
        )
        return id
    }

    /** 把导入结果登记为草稿，返回临时 id（此时不落库）。 */
    fun registerImported(world: ImportedWorld): String {
        val id = "draft-${UUID.randomUUID()}"
        WorldbookDraftStore.put(id, world)
        return id
    }

    fun draft(id: String): ImportedWorld? = WorldbookDraftStore.get(id)

    fun discardDraft(id: String) = WorldbookDraftStore.remove(id)

    /** 保存草稿（一次性事务），返回真正的 bookId。 */
    fun commitDraft(
        id: String,
        title: String,
        premise: String,
        setting: String,
        rules: List<String>,
        entries: List<ImportedEntry>,
        onDone: (String) -> Unit,
    ) {
        viewModelScope.launch {
            val draft = WorldbookDraftStore.get(id)
            val docJson = updateDoc(draft?.docJson, title, premise, setting, rules)
            val bookId = repository.saveDraft(title, docJson, entries)
            WorldbookDraftStore.remove(id)
            onDone(bookId)
        }
    }

    // ---- 已落库的书 ----

    suspend fun load(id: String) = repository.worldbook(id)

    suspend fun entriesOf(bookId: String): List<WorldEntryEntity> = repository.entriesOf(bookId)

    suspend fun countSessionsUsing(bookId: String): Int = repository.countSessionsUsing(bookId)

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

    fun deleteEntry(entryId: String, onDone: () -> Unit) {
        viewModelScope.launch {
            repository.deleteEntry(entryId)
            onDone()
        }
    }

    fun deleteBook(bookId: String, onDone: () -> Unit) {
        viewModelScope.launch {
            repository.deleteWorldbook(bookId)
            onDone()
        }
    }

    /** 供详情页把「已落库的书」转成与草稿一致的结构。 */
    fun docOf(docJson: String?): WorldDoc = WorldDoc.parse(docJson)
}
