package me.rerere.rikkahub.data.game

import androidx.compose.runtime.mutableStateMapOf
import com.isledeer.soullink.game.ImportedWorld

/**
 * 未保存的世界书草稿。进程级单例：列表页 → 详情页跨导航存活，退出进程即丢（这是期望行为）。
 *
 * key 是临时 id（`draft-<uuid>`），与数据库 id 不冲突。
 */
object WorldbookDraftStore {
    private val drafts = mutableStateMapOf<String, ImportedWorld>()

    fun put(id: String, world: ImportedWorld) {
        drafts[id] = world
    }

    fun get(id: String): ImportedWorld? = drafts[id]

    fun update(id: String, world: ImportedWorld) {
        if (drafts.containsKey(id)) drafts[id] = world
    }

    fun remove(id: String) {
        drafts.remove(id)
    }

    fun isDraft(id: String): Boolean = drafts.containsKey(id)
}
