package me.rerere.rikkahub.data.game

import com.isledeer.soullink.game.CompiledInjection
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.Settings

/**
 * 编译结果 → 上游 `Lorebook`。空结果返回 null，调用方据此走"未绑定"分支。
 *
 * 纯函数：不碰数据库、不碰 Android，JVM 单测可直接覆盖。
 */
fun buildWorldbookLorebook(
    worldbookId: String,
    injections: List<CompiledInjection>,
): Lorebook? {
    if (injections.isEmpty()) return null
    return Lorebook(
        id = stableInjectionId(worldbookId, "worldbook"),
        name = "SoulLink",
        description = "SoulLink 世界书：$worldbookId",
        entries = injections.map { it.toRegexInjection(worldbookId) },
    )
}

/** 把世界书挂到本轮 settings 上（只加不改，返回新实例）。 */
fun Settings.attachWorldbook(book: Lorebook?): Settings =
    if (book == null) this else copy(lorebooks = lorebooks + book)

/** 把世界书绑到助手，让 `PromptInjectionTransformer` 认领它（只加不改）。 */
fun Assistant.attachWorldbook(book: Lorebook?): Assistant =
    if (book == null) this else copy(lorebookIds = lorebookIds + book.id)
