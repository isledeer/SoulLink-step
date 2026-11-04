package com.isledeer.soullink.game

import org.json.JSONArray
import org.json.JSONObject

/** AI 解析出的全局字段，与 WorldDoc 的 openingScene/openingSituation 一一对齐。 */
data class AiWorldDoc(
    val title: String = "",
    val premise: String = "",
    val setting: String = "",
    val tone: String = "",
    val rules: List<String> = emptyList(),
    val openingScene: String = "",
    val openingSituation: String = "",
)

data class AiEntry(
    val keys: List<String> = emptyList(),
    val content: String,
    val alwaysOn: Boolean = false,
    val priority: Int = 0,
)

data class AiWorldbookResult(
    val doc: AiWorldDoc? = null,
    val entries: List<AiEntry> = emptyList(),
)

/** 解析失败时把原始输出带回去，供"修复一轮"使用。 */
data class AiParseFailure(val message: String, val raw: String)

// ---------------- 分块 ----------------

/** 正文超过这个长度才分块。 */
const val AI_CHUNK_THRESHOLD = 6000

/** 单块上限；调用方会按模型上下文自适应收敛到更小值。 */
const val AI_CHUNK_MAX_CHARS = 4000

/** 相邻块重叠字符数。 */
const val AI_CHUNK_OVERLAP = 200

/** 全局段的输入上限（标题结构 + 第 1 块 + 最后 1 块）。 */
const val AI_GLOBAL_MAX_CHARS = 12000

data class TextChunk(val index: Int, val text: String)

private val HEADING = Regex("""^(#{1,6}\s|第[一二三四五六七八九十百零〇\d]+[章节回卷]|Chapter\s+\d+)""")

/**
 * 把文档切成块。
 *
 * - 长度 ≤ [threshold]：整篇一块（调用方只用它跑一段式）
 * - 否则：先按空行分节，标题行作为强边界；单节超过 [maxChars] 再按句末标点贪心打包
 * - 相邻块尾部重叠 [overlap] 字符，避免跨块情节被切断
 */
fun chunkDocument(
    text: String,
    maxChars: Int = AI_CHUNK_MAX_CHARS,
    overlap: Int = AI_CHUNK_OVERLAP,
    threshold: Int = AI_CHUNK_THRESHOLD,
): List<TextChunk> {
    val normalized = text.replace("\r\n", "\n").trim()
    if (normalized.isEmpty()) return emptyList()
    if (normalized.length <= threshold) return listOf(TextChunk(0, normalized))

    val limit = maxChars.coerceAtLeast(500)
    val sections = splitByStructure(normalized)

    val raw = mutableListOf<String>()
    val buffer = StringBuilder()
    for (section in sections) {
        if (buffer.isNotEmpty() && buffer.length + section.length + 1 > limit) {
            raw += buffer.toString()
            buffer.clear()
        }
        if (section.length > limit) {
            raw += splitBySentence(section, limit)
        } else {
            if (buffer.isNotEmpty()) buffer.append('\n')
            buffer.append(section)
        }
    }
    if (buffer.isNotEmpty()) raw += buffer.toString()

    // 重叠：把上一块的尾部接到当前块前面
    return raw.mapIndexed { index, body ->
        val prefix = if (index == 0 || overlap <= 0) {
            ""
        } else {
            val prev = raw[index - 1]
            prev.takeLast(overlap.coerceAtMost(prev.length))
        }
        TextChunk(index, if (prefix.isEmpty()) body else "$prefix\n$body")
    }
}

private fun splitByStructure(text: String): List<String> {
    val sections = mutableListOf<String>()
    val current = StringBuilder()
    text.split('\n').forEach { line ->
        val isHeading = HEADING.containsMatchIn(line)
        if (isHeading && current.isNotEmpty()) {
            sections += current.toString().trim()
            current.clear()
        }
        if (current.isNotEmpty()) current.append('\n')
        current.append(line)
    }
    if (current.isNotEmpty()) sections += current.toString().trim()
    return sections.filter { it.isNotEmpty() }
}

private fun splitBySentence(text: String, limit: Int): List<String> {
    val pieces = mutableListOf<String>()
    val buffer = StringBuilder()
    val sentenceEnd = charArrayOf('。', '！', '？', '；', '\n')
    var start = 0
    for (i in text.indices) {
        if (text[i] in sentenceEnd) {
            val sentence = text.substring(start, i + 1)
            if (buffer.length + sentence.length > limit && buffer.isNotEmpty()) {
                pieces += buffer.toString().trim()
                buffer.clear()
            }
            if (sentence.length > limit) {
                // 极端长句：硬切
                var offset = 0
                while (offset < sentence.length) {
                    val end = minOf(offset + limit, sentence.length)
                    pieces += sentence.substring(offset, end)
                    offset = end
                }
            } else {
                buffer.append(sentence)
            }
            start = i + 1
        }
    }
    if (start < text.length) buffer.append(text.substring(start))
    if (buffer.isNotEmpty()) pieces += buffer.toString().trim()
    return pieces.filter { it.isNotEmpty() }
}

/** 按字符数与块大小估算请求次数：≤阈值 1 次，否则 1(全局) + 块数。 */
fun estimateAiRequests(chars: Int, chunkMax: Int = AI_CHUNK_MAX_CHARS, threshold: Int = AI_CHUNK_THRESHOLD): Int {
    if (chars <= 0) return 0
    if (chars <= threshold) return 1
    val chunks = (chars + chunkMax - 1) / chunkMax
    return chunks + 1
}

// ---------------- 解析 ----------------

/**
 * 容错解析：剥 ``` 围栏、取第一个 `{` 到最后一个 `}`，再按 JSONObject 解析。
 * 失败返回 [AiParseFailure]（带原文），由调用方决定是否回灌修复。
 */
fun parseAiWorldbook(raw: String): Result<AiWorldbookResult> {
    val cleaned = raw
        .replace(Regex("```json", RegexOption.IGNORE_CASE), "")
        .replace("```", "")
        .trim()

    val start = cleaned.indexOf('{')
    val end = cleaned.lastIndexOf('}')
    if (start < 0 || end <= start) {
        return Result.failure(IllegalArgumentException("找不到 JSON 对象"))
    }

    val json = runCatching { JSONObject(cleaned.substring(start, end + 1)) }.getOrElse {
        return Result.failure(IllegalArgumentException("JSON 解析失败：${it.message}"))
    }

    val docObj = json.optJSONObject("doc")
    val doc = docObj?.let { obj ->
        AiWorldDoc(
            title = obj.optString("title"),
            premise = obj.optString("premise"),
            setting = obj.optString("setting"),
            tone = obj.optString("tone"),
            rules = obj.optJSONArray("rules").toStringList(),
            openingScene = obj.optString("openingScene"),
            openingSituation = obj.optString("openingSituation"),
        )
    }

    val entries = buildList {
        val array = json.optJSONArray("entries") ?: JSONArray()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val content = obj.optString("content")
            if (content.isBlank()) continue
            add(
                AiEntry(
                    keys = obj.optJSONArray("keys").toStringList(),
                    content = content,
                    alwaysOn = obj.optBoolean("alwaysOn", false),
                    priority = obj.optInt("priority", 0),
                )
            )
        }
    }

    if (doc == null && entries.isEmpty()) {
        return Result.failure(IllegalArgumentException("JSON 里没有 doc 也没有 entries"))
    }
    return Result.success(AiWorldbookResult(doc = doc, entries = entries))
}

private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return buildList {
        for (i in 0 until length()) {
            val value = optString(i).trim()
            if (value.isNotEmpty()) add(value)
        }
    }
}

/** 把 AI 的 doc 写进 WorldDoc 兼容的 docJson（opening 落到 world.opening）。 */
fun aiDocToDocJson(doc: AiWorldDoc, fallbackTitle: String = "AI 生成的世界书"): String {
    val root = JSONObject()
    root.put("version", 1)
    root.put(
        "meta",
        JSONObject()
            .put("title", doc.title.ifBlank { fallbackTitle })
            .put("premise", doc.premise),
    )
    root.put(
        "world",
        JSONObject()
            .put("setting", doc.setting)
            .put("tone", doc.tone)
            .put("rules", JSONArray(doc.rules.filter { it.isNotBlank() }))
            .put(
                "opening",
                JSONObject()
                    .put("scene", doc.openingScene)
                    .put("situation", doc.openingSituation),
            ),
    )
    return root.toString()
}

// ---------------- Prompt ----------------

object WorldbookAiPrompt {

    val GLOBAL_SYSTEM = """
        你是世界书结构化助手。阅读给定的小说/设定文本，抽取全书级的设定。
        只输出 JSON，不要任何解释、不要 Markdown 代码块之外的文字。
        输出格式：
        {"doc":{"title":"","premise":"","setting":"","tone":"","rules":[""],"openingScene":"","openingSituation":""}}
        - premise：一句话概括世界的前提
        - setting：地理/势力/时代等世界设定，200 字以内
        - tone：整体语调，10 字以内
        - rules：世界硬规则，每条一句，最多 8 条
        - openingScene：开场场景描写，直接可用于开场
        - openingSituation：开场时主角所处的情境，一句话
        文本不足时对应字段留空字符串，不要编造。
    """.trimIndent()

    val ENTRIES_SYSTEM = """
        你是世界书条目抽取助手。阅读给定片段，抽取可被关键词触发的设定条目。
        只输出 JSON：
        {"entries":[{"keys":["触发词"],"content":"条目正文","alwaysOn":false,"priority":0}]}
        - keys：1~4 个触发词，名词优先
        - content：40~200 字
        - alwaysOn：只有"任何场景都成立"的背景常识才为 true
        - priority：0~10，越重要越大
        片段里没有可抽取的设定时输出 {"entries":[]}。
    """.trimIndent()

    fun globalUserPrompt(title: String, head: String, tail: String): String = buildString {
        appendLine("书名/文件名：$title")
        appendLine()
        appendLine("【开头部分】")
        appendLine(head.take(AI_GLOBAL_MAX_CHARS))
        if (tail.isNotBlank() && tail != head) {
            appendLine()
            appendLine("【结尾部分】")
            appendLine(tail)
        }
    }

    fun entriesUserPrompt(chunk: String): String = "【片段】\n$chunk"

    fun repairPrompt(error: String, raw: String): String = buildString {
        appendLine("你上一次的输出无法解析：$error")
        appendLine("上一次的输出是：")
        appendLine(raw.take(2000))
        appendLine()
        appendLine("请只输出合法 JSON，不要任何解释、不要代码块标记。")
    }

    /**
     * 全局段输入：标题结构 + 第 1 块全文 + 最后 1 块全文。
     * 上限 [AI_GLOBAL_MAX_CHARS]，超限截尾保头（opening/rules 都在开头）。
     */
    fun buildGlobalInput(chunks: List<TextChunk>, rawText: String): String {
        if (chunks.isEmpty()) return rawText.take(AI_GLOBAL_MAX_CHARS)
        val head = chunks.first().text
        val tail = if (chunks.size > 1) chunks.last().text else ""
        val structure = rawText.lineSequence()
            .filter { HEADING.containsMatchIn(it) }
            .take(20)
            .joinToString("\n")
        val combined = buildString {
            if (structure.isNotBlank()) {
                appendLine("【标题结构】")
                appendLine(structure)
                appendLine()
            }
            append("【开头部分】\n")
            append(head)
            if (tail.isNotBlank()) {
                append("\n\n【结尾部分】\n")
                append(tail)
            }
        }
        return if (combined.length <= AI_GLOBAL_MAX_CHARS) combined else combined.take(AI_GLOBAL_MAX_CHARS)
    }
}
