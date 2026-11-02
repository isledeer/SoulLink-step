package com.isledeer.soullink.game

import org.json.JSONArray
import org.json.JSONObject

/** 世界书文档的强类型视图（docJson → WorldDoc） */
data class WorldDoc(
    val title: String = "",
    val premise: String = "",
    val setting: String = "",
    val rules: List<String> = emptyList(),
    val tone: String = "",
    val openingScene: String = "",
    val openingSituation: String = "",
    val npcs: List<WorldNpc> = emptyList(),
    val items: List<WorldItem> = emptyList(),
) {
    companion object {
        fun parse(raw: String?): WorldDoc {
            if (raw.isNullOrBlank()) return WorldDoc()
            return runCatching {
                val root = JSONObject(raw)
                val meta = root.optJSONObject("meta") ?: JSONObject()
                val world = root.optJSONObject("world") ?: JSONObject()
                val opening = world.optJSONObject("opening") ?: JSONObject()
                val rules = mutableListOf<String>()
                world.optJSONArray("rules")?.let { array ->
                    for (i in 0 until array.length()) {
                        val rule = array.optString(i)
                        if (rule.isNotBlank()) rules.add(rule)
                    }
                }
                val npcs = mutableListOf<WorldNpc>()
                root.optJSONObject("entities")?.optJSONArray("npcs")?.let { array ->
                    for (i in 0 until array.length()) {
                        val obj = array.optJSONObject(i) ?: continue
                        val name = obj.optString("name")
                        if (name.isBlank()) continue
                        npcs.add(
                            WorldNpc(
                                id = obj.optString("id").ifBlank { name },
                                name = name,
                                role = obj.optString("role"),
                                brief = obj.optString("brief"),
                                initialRelation = obj.optInt("initialRelation", 0),
                            )
                        )
                    }
                }
                val items = mutableListOf<WorldItem>()
                root.optJSONObject("entities")?.optJSONArray("items")?.let { array ->
                    for (i in 0 until array.length()) {
                        val obj = array.optJSONObject(i) ?: continue
                        val name = obj.optString("name")
                        if (name.isBlank()) continue
                        items.add(
                            WorldItem(
                                id = obj.optString("id").ifBlank { name },
                                name = name,
                                type = obj.optString("type").ifBlank { "key_item" },
                                brief = obj.optString("brief"),
                            )
                        )
                    }
                }
                WorldDoc(
                    title = meta.optString("title"),
                    premise = meta.optString("premise"),
                    setting = world.optString("setting"),
                    rules = rules,
                    tone = world.optString("tone"),
                    openingScene = opening.optString("scene"),
                    openingSituation = opening.optString("situation"),
                    npcs = npcs,
                    items = items,
                )
            }.getOrElse { WorldDoc() }
        }
    }
}

data class WorldNpc(
    val id: String,
    val name: String,
    val role: String = "",
    val brief: String = "",
    val initialRelation: Int = 0,
)

data class WorldItem(
    val id: String,
    val name: String,
    val type: String = "key_item",
    val brief: String = "",
)

/** 世界书模板与 AI 填充 */
object WorldbookTemplates {

    val templates: List<Pair<String, String>> = listOf(
        "cultivation" to "修真",
        "cyberpunk" to "赛博朋克",
        "fantasy" to "西幻",
        "custom" to "空白",
    )

    fun label(template: String): String =
        templates.firstOrNull { it.first == template }?.second ?: "空白"

    /** 空世界书文档（新建时用） */
    fun blank(template: String, title: String): String {
        val root = JSONObject()
        root.put("version", 1)
        root.put("template", template)
        root.put(
            "meta",
            JSONObject()
                .put("title", title)
                .put("premise", "")
                .put("rating", "general")
        )
        root.put(
            "world",
            JSONObject()
                .put("setting", "")
                .put("rules", JSONArray())
                .put("tone", "")
                .put(
                    "opening",
                    JSONObject().put("scene", "").put("situation", "")
                )
        )
        root.put(
            "stats",
            JSONObject()
                .put("relations", JSONObject().put("label", "好感度").put("min", 0).put("max", 100))
                .put("custom", JSONArray())
        )
        root.put(
            "entities",
            JSONObject()
                .put("npcs", JSONArray())
                .put("locations", JSONArray())
                .put("items", JSONArray())
        )
        root.put("entries", JSONArray())
        return root.toString()
    }

    /** AI 填充提示词（要求模型只输出 doc JSON） */
    fun fillPrompt(template: String, premise: String): String = listOf(
        "你是游戏世界观设计师。根据模板「${label(template)}」和一句前提「$premise」，生成完整的世界书 JSON。",
        "只输出 JSON，不要解释，不要代码块。",
        "字段结构：",
        "{\"version\":1,\"template\":\"$template\",\"meta\":{\"title\":\"世界名\",\"premise\":\"$premise\",\"rating\":\"general\"},",
        "\"world\":{\"setting\":\"时代、地理、社会结构，200-400字\",\"rules\":[\"硬规则1\",\"硬规则2\"],\"tone\":\"叙事风格与人称\",",
        "\"opening\":{\"scene\":\"起始场景\",\"situation\":\"开场事件\"}},",
        "\"stats\":{\"relations\":{\"label\":\"好感度\",\"min\":0,\"max\":100},\"custom\":[]},",
        "\"entities\":{\"npcs\":[{\"id\":\"npc_xxx\",\"name\":\"\",\"role\":\"\",\"brief\":\"\",\"initialRelation\":0}],",
        "\"locations\":[],\"items\":[{\"id\":\"item_xxx\",\"name\":\"\",\"type\":\"key_item\",\"brief\":\"\"}]},",
        "\"entries\":[{\"keys\":[\"关键词\"],\"content\":\"设定条目\",\"alwaysOn\":false,\"priority\":0}]}",
        "要求：npcs 至少 2 个、entries 至少 3 条；所有 id 用英文小写加下划线；内容用中文。",
    ).joinToString("\n")

    /** 从 doc 生成账本初始状态（NPC 初始关系 + 开场场景） */
    fun buildInitialState(doc: WorldDoc): String {
        val relations = JSONObject()
        doc.npcs.forEach { npc -> relations.put(npc.id, npc.initialRelation) }
        return JSONObject()
            .put("relations", relations)
            .put("inventory", JSONArray())
            .put("scene", doc.openingScene)
            .put("flags", JSONObject())
            .put("stats", JSONObject())
            .toString()
    }

    /** 用表单字段更新 docJson（保留 entities / stats 等未知字段） */
    fun updateDoc(
        original: String,
        title: String,
        premise: String,
        setting: String,
        rules: List<String>,
        tone: String,
        openingScene: String,
        openingSituation: String,
    ): String {
        val root = runCatching { JSONObject(original) }.getOrElse { JSONObject() }
        root.put("version", 1)

        val meta = root.optJSONObject("meta") ?: JSONObject()
        meta.put("title", title)
        meta.put("premise", premise)
        root.put("meta", meta)

        val world = root.optJSONObject("world") ?: JSONObject()
        world.put("setting", setting)
        val rulesArray = JSONArray()
        rules.forEach { rulesArray.put(it) }
        world.put("rules", rulesArray)
        world.put("tone", tone)
        val opening = world.optJSONObject("opening") ?: JSONObject()
        opening.put("scene", openingScene)
        opening.put("situation", openingSituation)
        world.put("opening", opening)
        root.put("world", world)

        return root.toString()
    }
}

/** 世界书条目激活与提示词拼装 */
object WorldbookPrompt {

    /** 关键词激活：alwaysOn 必选，其余按 keys 命中 + priority 排序，受字符预算约束 */
    fun activate(
        entries: List<WorldEntry>,
        contextText: String,
        budgetChars: Int = 1400,
    ): List<WorldEntry> {
        if (entries.isEmpty()) return emptyList()
        val text = contextText.lowercase()
        val selected = mutableListOf<WorldEntry>()
        var used = 0

        val alwaysOn = entries.filter { it.alwaysOn }
        val matched = entries.filter { !it.alwaysOn && entryMatches(it, text) }
            .sortedByDescending { it.priority }

        (alwaysOn + matched).forEach { entry ->
            if (used + entry.content.length <= budgetChars || selected.isEmpty()) {
                selected.add(entry)
                used += entry.content.length
            }
        }
        return selected
    }

    private fun entryMatches(entry: WorldEntry, text: String): Boolean {
        return runCatching {
            val keys = JSONArray(entry.keysJson)
            (0 until keys.length()).any { i ->
                val key = keys.optString(i).trim().lowercase()
                key.isNotBlank() && text.contains(key)
            }
        }.getOrDefault(false)
    }

    /** 拼世界段（拼在 system prompt 前面；状态快照 + 输出契约也在这里） */
    fun buildSystemSection(
        doc: WorldDoc,
        activeEntries: List<WorldEntry>,
        state: GameState?,
        useTool: Boolean,
    ): String {
        val parts = mutableListOf<String>()
        parts.add("【世界】${doc.title}")
        if (doc.setting.isNotBlank()) parts.add(doc.setting)
        if (doc.rules.isNotEmpty()) {
            parts.add("【硬规则】")
            doc.rules.forEach { rule -> parts.add("- $rule") }
        }
        if (doc.tone.isNotBlank()) parts.add("【叙事基调】${doc.tone}")
        if (doc.openingSituation.isNotBlank()) parts.add("【开场】${doc.openingSituation}")
        if (activeEntries.isNotEmpty()) {
            parts.add("【世界设定】")
            activeEntries.forEach { entry -> parts.add(entry.content) }
        }
        state?.let { snapshot ->
            val relations = if (snapshot.relations.isEmpty()) "无" else snapshot.relations.entries.joinToString("、") { (key, value) -> "$key=$value" }
            val inventory = if (snapshot.inventory.isEmpty()) "空" else snapshot.inventory.values.joinToString("、") { "${it.name}x${it.quantity}" }
            parts.add("【当前状态】关系：$relations；背包：$inventory；场景：${snapshot.scene.ifBlank { "未定" }}；时间：${snapshot.worldTime ?: "未定"}")
        }
        parts.add(
            if (useTool) {
                "【输出契约】每轮都必须调用 update_game_state 记录状态变化（无变化传空对象）；choices 给出 4 个用户此刻能做的具体动作。"
            } else {
                "【输出契约】在回复正文结束后另起一行输出 <game>{\"relation\":{\"target\":\"self\",\"delta\":0},\"choices\":[\"选项一\",\"选项二\",\"选项三\",\"选项四\"]}</game>，不要在正文提到这个标记。"
            }
        )
        return parts.joinToString("\n")
    }
}