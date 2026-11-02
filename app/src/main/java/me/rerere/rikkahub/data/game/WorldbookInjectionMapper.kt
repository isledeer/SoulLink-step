package me.rerere.rikkahub.data.game

import com.isledeer.soullink.game.CompiledInjection
import java.util.UUID
import kotlin.uuid.Uuid
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.PromptInjection

/**
 * SoulLink 编译结果 → 上游的 `RegexInjection`。
 *
 * 映射定死：position = AFTER_SYSTEM_PROMPT、role = USER、scanDepth = 4；
 * `alwaysOn` 对应上游的 `constantActive`（常驻不参与关键词匹配）。
 */
fun CompiledInjection.toRegexInjection(worldbookId: String): PromptInjection.RegexInjection =
    PromptInjection.RegexInjection(
        id = stableInjectionId(worldbookId, name),
        name = name,
        enabled = true,
        priority = priority,
        position = InjectionPosition.AFTER_SYSTEM_PROMPT,
        content = content,
        injectDepth = 4,
        role = MessageRole.USER,
        keywords = keywords,
        useRegex = false,
        caseSensitive = false,
        scanDepth = 4,
        constantActive = alwaysOn,
    )

/**
 * 稳定 UUID：同一本世界书 + 同一个条目名，每轮都得到同一个 id。
 * 用 `Uuid.random()` 会导致注入项 id 每轮变化，上游按 id 去重/绑定的逻辑会失效。
 */
fun stableInjectionId(worldbookId: String, name: String): Uuid =
    Uuid.parse(
        UUID.nameUUIDFromBytes("soullink:$worldbookId:$name".toByteArray()).toString()
    )
