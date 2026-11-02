package com.isledeer.soullink.game

import org.json.JSONArray
import org.json.JSONObject

/**
 * 只更新四个基础字段，其余结构（entities / opening / template 等）原样保留。
 *
 * 纯函数：UI 层不准自己拼 JSON。
 * 解析失败时按空文档处理（宁可重建，不要让详情页打不开）。
 */
fun updateDoc(
    raw: String?,
    title: String,
    premise: String,
    setting: String,
    rules: List<String>,
): String {
    val root = runCatching {
        if (raw.isNullOrBlank()) JSONObject() else JSONObject(raw)
    }.getOrElse { JSONObject() }

    if (!root.has("version")) root.put("version", 1)

    val meta = root.optJSONObject("meta") ?: JSONObject()
    meta.put("title", title)
    meta.put("premise", premise)
    root.put("meta", meta)

    val world = root.optJSONObject("world") ?: JSONObject()
    world.put("setting", setting)
    world.put(
        "rules",
        JSONArray(rules.map { it.trim() }.filter { it.isNotEmpty() }),
    )
    root.put("world", world)

    return root.toString()
}
