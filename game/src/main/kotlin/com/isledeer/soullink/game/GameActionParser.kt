package com.isledeer.soullink.game

import org.json.JSONObject

/**
 * <game>{json}</game> 尾块解析（旧 React 引擎验证过的兜底协议）。
 * 规则：
 * - 多个块取第一个合法 JSON 对象
 * - JSON 非法也照样剥块（动作置空）
 * - 没有完整块时原样返回（宁可漏剥，不吃正文）
 */
object GameActionParser {

    private val BLOCK_REGEX = Regex("<game>[\\s\\S]*?</game>")
    private const val OPEN_TAG = "<game>"
    private const val CLOSE_TAG = "</game>"

    fun extract(content: String): Pair<String, GameAction?> {
        val matches = BLOCK_REGEX.findAll(content).toList()
        if (matches.isEmpty()) return content to null

        var action: GameAction? = null
        for (match in matches) {
            val inner = match.value.removePrefix(OPEN_TAG).removeSuffix(CLOSE_TAG).trim()
            val parsed = runCatching { JSONObject(inner) }.getOrNull() ?: continue
            action = GameAction.fromJson(parsed)
            break
        }

        val clean = content.replace(BLOCK_REGEX, "").trimEnd()
        return clean to action
    }
}
