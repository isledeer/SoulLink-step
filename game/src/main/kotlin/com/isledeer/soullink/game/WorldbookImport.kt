package com.isledeer.soullink.game

import org.json.JSONArray
import org.json.JSONObject

/** 导入时解析出的一条世界书条目（纯数据，落库前再分配 id）。 */
data class ImportedEntry(
    val keys: List<String> = emptyList(),
    val content: String,
    val alwaysOn: Boolean = false,
    val priority: Int = 0,
)

/** 导入结果：标题 + 归一化后的 docJson + 条目。 */
data class ImportedWorld(
    val title: String,
    val docJson: String,
    val entries: List<ImportedEntry> = emptyList(),
)

/** 文件真实类型：不看 MIME，只看文件头。 */
enum class FileKind { PNG, ZIP, TEXT, UNKNOWN }

private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

/**
 * 只读文件头判类型。安卓各家文件管理器对 `.json` 的 MIME 上报不统一
 * （application/json / text/plain / octet-stream / null 都见过），
 * 所以这里彻底不信 MIME。
 *
 * - PNG 魔数 `89 50 4E 47 0D 0A 1A 0A`
 * - ZIP 魔数 `50 4B 03 04`（docx/epub 都是 zip）
 * - 其余按 TEXT 处理
 */
fun sniffFileKind(header: ByteArray): FileKind {
    if (header.isEmpty()) return FileKind.UNKNOWN
    if (header.size >= PNG_MAGIC.size && header.copyOfRange(0, PNG_MAGIC.size).contentEquals(PNG_MAGIC)) {
        return FileKind.PNG
    }
    if (header.size >= ZIP_MAGIC.size && header.copyOfRange(0, ZIP_MAGIC.size).contentEquals(ZIP_MAGIC)) {
        return FileKind.ZIP
    }
    return FileKind.TEXT
}

/** 单条 content 硬上限（字符）。 */
const val IMPORT_MAX_CONTENT_CHARS = 8000

/** 整本条目数上限；超出截断。 */
const val IMPORT_MAX_ENTRIES = 500

/**
 * 靠形状嗅探决定用哪个解析器，**不让用户选**：
 *
 * 1. `spec` = chara_card_v2 / chara_card_v3 → 酒馆角色卡
 * 2. `entries` 是 JSONObject（键是数字）→ 酒馆 World Info
 * 3. `entries` 是 JSONArray → Rikka 风格 Lorebook
 * 4. 有 `meta` 或 `world` 节点 → SoulLink 自己的 docJson
 * 5. 都不匹配 → null（调用方报"无法识别的世界书格式"，不猜）
 */
fun sniffAndImport(json: String, fallbackTitle: String = "导入的世界书"): ImportedWorld? {
    val root = runCatching { JSONObject(json) }.getOrNull() ?: return null

    val spec = root.optString("spec").trim()
    if (spec == "chara_card_v2" || spec == "chara_card_v3") {
        return importTavernCharacter(root, fallbackTitle)
    }

    val entries = root.opt("entries")
    if (entries is JSONObject) return importTavernWorldInfo(root, fallbackTitle)
    if (entries is JSONArray) return importRikkaLorebook(root, fallbackTitle)

    if (root.has("meta") || root.has("world")) return importSoulLinkWorld(root, fallbackTitle)

    return null
}

// ---------------- 四个解析器 ----------------

/** 酒馆角色卡 v2/v3：data 节点 + character_book.entries[] */
fun importTavernCharacter(root: JSONObject, fallbackTitle: String): ImportedWorld? {
    val data = root.optJSONObject("data") ?: root
    val title = data.optString("name").ifBlank { fallbackTitle }

    val rules = data.optString("system_prompt")
        .split('\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    val doc = buildDoc(
        title = title,
        premise = data.optString("description"),
        setting = data.optString("scenario"),
        tone = data.optString("personality"),
        rules = rules,
        openingScene = data.optString("first_mes"),
    )
    val entries = parseTavernEntries(data.optJSONObject("character_book")?.opt("entries"))
    return ImportedWorld(title = title, docJson = doc, entries = entries)
}

/** 酒馆 World Info：entries 是对象，键为数字字符串 */
fun importTavernWorldInfo(root: JSONObject, fallbackTitle: String): ImportedWorld? {
    val entriesObj = root.optJSONObject("entries") ?: return null
    val parsed = mutableListOf<ImportedEntry>()
    val keys = entriesObj.keys().asSequence().toList().sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }
    for (key in keys) {
        val obj = entriesObj.optJSONObject(key) ?: continue
        val content = obj.optString("content")
        if (content.isBlank()) continue
        parsed += ImportedEntry(
            keys = readKeys(obj),
            content = content,
            alwaysOn = obj.optBoolean("constant", false),
            priority = obj.optInt("insertion_order", 0),
        )
    }

    val title = root.optString("name").ifBlank { fallbackTitle }
    val premiseEntry = parsed.firstOrNull { it.alwaysOn }
    val premise = premiseEntry?.content.orEmpty()
    // 提升为 premise 的那条从 entries 里移除，避免"规则段 + 条目"重复注入同一段文字
    val entries = if (premiseEntry == null) {
        parsed
    } else {
        val index = parsed.indexOfFirst { it.content.trim() == premise.trim() }
        if (index < 0) parsed else parsed.toMutableList().also { it.removeAt(index) }
    }
    val doc = buildDoc(title = title, premise = premise, setting = "", tone = "", rules = emptyList(), openingScene = "")
    return ImportedWorld(title = title, docJson = doc, entries = entries)
}

/** Rikka 风格 Lorebook：entries 是数组 */
fun importRikkaLorebook(root: JSONObject, fallbackTitle: String): ImportedWorld? {
    val array = root.optJSONArray("entries") ?: return null
    val parsed = mutableListOf<ImportedEntry>()
    for (index in 0 until array.length()) {
        val obj = array.optJSONObject(index) ?: continue
        val content = obj.optString("content")
        if (content.isBlank()) continue
        parsed += ImportedEntry(
            keys = readKeys(obj),
            content = content,
            alwaysOn = obj.optBoolean("constantActive", obj.optBoolean("constant", false)),
            priority = obj.optInt("priority", 0),
        )
    }

    val title = root.optString("name").ifBlank { fallbackTitle }
    val doc = buildDoc(
        title = title,
        premise = root.optString("description"),
        setting = "",
        tone = "",
        rules = emptyList(),
        openingScene = "",
    )
    return ImportedWorld(title = title, docJson = doc, entries = parsed)
}

/** SoulLink 自己的 docJson：直接吃，条目为空 */
fun importSoulLinkWorld(root: JSONObject, fallbackTitle: String): ImportedWorld? {
    val doc = WorldDoc.parse(root.toString())
    val title = doc.title.ifBlank { fallbackTitle }
    return ImportedWorld(title = title, docJson = root.toString(), entries = emptyList())
}

// ---------------- 工具 ----------------

/** 同时兼容 `keys`（酒馆）与 `keywords`（Rikka），两者都是数组或单个字符串。 */
private fun readKeys(obj: JSONObject): List<String> {
    val array = obj.optJSONArray("keys") ?: obj.optJSONArray("keywords")
    if (array != null) {
        return buildList {
            for (i in 0 until array.length()) {
                val key = array.optString(i).trim()
                if (key.isNotEmpty()) add(key)
            }
        }
    }
    val single = obj.optString("key").trim().ifEmpty { obj.optString("keyword").trim() }
    return if (single.isEmpty()) emptyList() else listOf(single)
}

private fun parseTavernEntries(raw: Any?): List<ImportedEntry> {
    val list = mutableListOf<ImportedEntry>()
    when (raw) {
        is JSONArray -> for (i in 0 until raw.length()) {
            raw.optJSONObject(i)?.let { list += it.toEntry() }
        }

        is JSONObject -> for (key in raw.keys()) {
            raw.optJSONObject(key)?.let { list += it.toEntry() }
        }

        else -> return emptyList()
    }
    return list.filter { it.content.isNotBlank() }
}

private fun JSONObject.toEntry(): ImportedEntry = ImportedEntry(
    keys = readKeys(this),
    content = optString("content"),
    alwaysOn = optBoolean("constant", false),
    priority = optInt("insertion_order", optInt("priority", 0)),
)

private fun buildDoc(
    title: String,
    premise: String,
    setting: String,
    tone: String,
    rules: List<String>,
    openingScene: String,
): String {
    val root = JSONObject()
    root.put("version", 1)
    root.put(
        "meta",
        JSONObject()
            .put("title", title)
            .put("premise", premise),
    )
    root.put(
        "world",
        JSONObject()
            .put("setting", setting)
            .put("tone", tone)
            .put("rules", JSONArray(rules.filter { it.isNotBlank() }))
            .put("opening", JSONObject().put("scene", openingScene).put("situation", "")),
    )
    return root.toString()
}

/** 落地前的统一裁剪：>500 条截断、单条 >8000 字截断、空内容丢弃。返回 (条目, 是否发生截断)。 */
fun normalizeImported(entries: List<ImportedEntry>): Pair<List<ImportedEntry>, Boolean> {
    val cleaned = entries
        .filter { it.content.isNotBlank() }
        .map { entry ->
            if (entry.content.length > IMPORT_MAX_CONTENT_CHARS) {
                entry.copy(content = entry.content.take(IMPORT_MAX_CONTENT_CHARS))
            } else {
                entry
            }
        }
    val truncated = cleaned.size > IMPORT_MAX_ENTRIES
    return (if (truncated) cleaned.take(IMPORT_MAX_ENTRIES) else cleaned) to truncated
}

/** 整本解析出 0 条且 docJson 全空 → 调用方应拒绝落库。 */
fun ImportedWorld.isEffectivelyEmpty(): Boolean {
    if (entries.isNotEmpty()) return false
    val doc = WorldDoc.parse(docJson)
    return doc.title.isBlank() && doc.premise.isBlank() && doc.setting.isBlank() &&
        doc.rules.isEmpty() && doc.npcs.isEmpty() && doc.items.isEmpty()
}
