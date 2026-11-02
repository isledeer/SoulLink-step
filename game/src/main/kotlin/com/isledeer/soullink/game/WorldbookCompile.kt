package com.isledeer.soullink.game

import org.json.JSONArray

/** 编译后的注入项：与上游 `PromptInjection.RegexInjection` 同构的纯数据。 */
data class CompiledInjection(
    val name: String,
    val content: String,
    val keywords: List<String> = emptyList(),
    val alwaysOn: Boolean = false,
    val priority: Int = 0,
)

/** 编译层入口：世界书条目的一行（不依赖 Room / Android）。 */
data class EntryRow(
    val id: String,
    val keysJson: String = "[]",
    val content: String,
    val alwaysOn: Boolean = false,
    val priority: Int = 0,
)

/** 规则段的固定名字，测试与排障都靠它认。 */
const val RULES_ENTRY_NAME = "__rules__"

/** 规则段优先级最高；常驻设定次之；条目按自身 priority。 */
private const val RULES_PRIORITY = 1000
private const val SETTING_PRIORITY = 900

/** keysJson 解析失败时追加到 name 上的标记（条目仍然保留）。 */
const val BAD_KEYS_SUFFIX = " (bad-keys)"

/**
 * 把世界书编译成一组注入项。
 *
 * 规则（逐条对应单测）：
 * 1. 规则段 = premise + setting + tone + rules，合并成 1 条 `alwaysOn = true`、
 *    `name = "__rules__"`、优先级最高的注入；**永远排在第一位，且不受预算裁剪**。
 * 2. `doc.npcs` / `doc.items` 的 `brief` 各编译成 1 条 `alwaysOn = true` 的常驻注入，
 *    name 为 `npc:<名字>` / `item:<名字>`；同样不受预算裁剪。
 * 3. `entries` 先按 `priority` 降序（同优先级按 id 升序，保证稳定），
 *    累加 `content.length`；一旦超过 `budgetChars` 就**停止**，后面的条目不参与（不是跳过继续）。
 * 4. `content` 为空或去空白后为空的条目直接跳过。
 * 5. `keysJson` 解析失败时降级为 `alwaysOn = false`、`keywords` 为空，条目**不丢弃**，
 *    并在 name 后追加 [BAD_KEYS_SUFFIX] 以便排障。
 *
 * 预算只统计第 3 步的条目；规则段与常驻设定不占预算，避免"预算小就把世界规则砍没"。
 */
fun compileWorldbook(
    doc: WorldDoc,
    entries: List<EntryRow>,
    budgetChars: Int = 1400,
): List<CompiledInjection> {
    val result = mutableListOf<CompiledInjection>()

    // 1) 规则段：恒在首位，恒常驻
    val rules = buildRules(doc)
    if (rules.isNotEmpty()) {
        result += CompiledInjection(
            name = RULES_ENTRY_NAME,
            content = rules,
            keywords = emptyList(),
            alwaysOn = true,
            priority = RULES_PRIORITY,
        )
    }

    // 2) NPC / 道具的初始设定：常驻，不占预算
    doc.npcs.forEach { npc ->
        val brief = npc.brief.trim()
        if (brief.isNotEmpty()) {
            result += CompiledInjection(
                name = "npc:${npc.name}",
                content = brief,
                alwaysOn = true,
                priority = SETTING_PRIORITY,
            )
        }
    }
    doc.items.forEach { item ->
        val brief = item.brief.trim()
        if (brief.isNotEmpty()) {
            result += CompiledInjection(
                name = "item:${item.name}",
                content = brief,
                alwaysOn = true,
                priority = SETTING_PRIORITY,
            )
        }
    }

    // 3) 条目：priority 降序累加，超预算即停止
    var used = 0
    val ordered = entries
        .filter { it.content.isNotBlank() }
        .sortedWith(compareByDescending<EntryRow> { it.priority }.thenBy { it.id })

    for (row in ordered) {
        val cost = row.content.length
        if (used + cost > budgetChars) break
        used += cost

        val parsed = parseKeys(row.keysJson)
        val name = if (parsed.second) row.id else row.id + BAD_KEYS_SUFFIX
        result += CompiledInjection(
            name = name,
            content = row.content,
            keywords = parsed.first,
            alwaysOn = if (parsed.second) row.alwaysOn else false,
            priority = row.priority,
        )
    }

    return result
}

/** premise / setting / tone / rules 拼成规则段；全空则返回空串。 */
private fun buildRules(doc: WorldDoc): String {
    val parts = mutableListOf<String>()
    doc.premise.trim().takeIf { it.isNotEmpty() }?.let { parts += it }
    doc.setting.trim().takeIf { it.isNotEmpty() }?.let { parts += it }
    doc.tone.trim().takeIf { it.isNotEmpty() }?.let { parts += it }
    if (doc.rules.isNotEmpty()) {
        parts += doc.rules
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n") { "- $it" }
    }
    return parts.joinToString("\n")
}

/** 返回 (关键词列表, 是否解析成功)。 */
private fun parseKeys(raw: String): Pair<List<String>, Boolean> {
    if (raw.isBlank()) return emptyList<String>() to true
    return runCatching {
        val array = JSONArray(raw)
        val keys = buildList {
            for (index in 0 until array.length()) {
                val key = array.optString(index).trim()
                if (key.isNotEmpty()) add(key)
            }
        }
        keys to true
    }.getOrElse { emptyList<String>() to false }
}
