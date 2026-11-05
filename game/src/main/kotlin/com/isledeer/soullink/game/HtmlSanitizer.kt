package com.isledeer.soullink.game

/** `<script>…</script>` 与 `<script/>` 一并剥离；大小写不敏感，跨行也吃。 */
private val SCRIPT_BLOCK = Regex("""<script\b[^>]*>[\s\S]*?</script\s*>""", RegexOption.IGNORE_CASE)
private val SCRIPT_TAG = Regex("""</?script\b[^>]*>""", RegexOption.IGNORE_CASE)

/** 事件属性（onerror/onload…）在 CSP 之外再剥一层，纯字符串兜底。 */
private val EVENT_ATTR = Regex("""\son[a-z]+\s*=\s*("[^"]*"|'[^']*'|[^\s>]+)""", RegexOption.IGNORE_CASE)

/**
 * 去掉可执行的 HTML：script 块、残留 script 标签、内联事件属性。
 *
 * 注意：这只是第二道保险，第一道是 [buildCspHtml] 注入的 CSP（`default-src 'none'`），
 * 字符串处理永远不可能穷尽浏览器解析器的花样。
 */
fun stripDangerousHtml(html: String): String = html
    .replace(SCRIPT_BLOCK, "")
    .replace(SCRIPT_TAG, "")
    .replace(EVENT_ATTR, "")

/** 在文档最前面注入 CSP。CSP 必须早于任何内容，否则不生效。 */
fun buildCspHtml(html: String): String {
    val csp = """<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; img-src data:; font-src data:;">"""
    val head = csp + """<meta name="viewport" content="width=device-width, initial-scale=1">"""
    return when {
        html.contains("<head>", ignoreCase = true) ->
            html.replaceFirst(Regex("<head>", RegexOption.IGNORE_CASE), "<head>$head")

        html.contains("<html", ignoreCase = true) ->
            html.replaceFirst(Regex("(<html[^>]*>)", RegexOption.IGNORE_CASE), "$1<head>$head</head>")

        else -> "<head>$head</head>$html"
    }
}
