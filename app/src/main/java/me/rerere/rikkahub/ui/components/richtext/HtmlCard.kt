package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.isledeer.soullink.game.stripDangerousHtml
import me.rerere.rikkahub.R

/** 超过这个大小的块直接显示源码，不起 WebView。 */
const val HTML_CARD_MAX_BYTES = 200 * 1024

/**
 * 消息正文里的 ```html 块 → 真实网页卡片。
 *
 * 只负责外壳（标题栏 / 查看源码 / 全屏），沙箱本体在 [SandboxWebView]。
 * 渲染失败（3 秒量不到高度、主文档报错）时降级成代码块，不留白屏。
 */
@Composable
fun HtmlCard(
    code: String,
    modifier: Modifier = Modifier,
) {
    if (code.toByteArray().size > HTML_CARD_MAX_BYTES) {
        HtmlCardFallback(code = code, reason = stringResource(R.string.soullink_html_too_large))
        return
    }

    var showSource by remember(code) { mutableStateOf(false) }
    var showFullscreen by remember(code) { mutableStateOf(false) }
    var renderFailed by remember(code) { mutableStateOf(false) }

    if (renderFailed || showSource) {
        HtmlCardFallback(
            code = code,
            reason = if (renderFailed) stringResource(R.string.soullink_html_render_failed) else null,
        )
        return
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column {
            Box(modifier = Modifier.fillMaxWidth()) {
                SandboxWebView(
                    html = stripDangerousHtml(code),
                    modifier = Modifier.fillMaxWidth(),
                    onFailed = { renderFailed = true },
                )
                Row(
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                ) {
                    TextButton(onClick = { showSource = true }) {
                        Text(stringResource(R.string.soullink_html_view_source), style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(onClick = { showFullscreen = true }) {
                        Text(stringResource(R.string.soullink_html_fullscreen), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }

    if (showFullscreen) {
        Dialog(onDismissRequest = { showFullscreen = false }) {
            Surface(modifier = Modifier.fillMaxSize()) {
                Box {
                    SandboxWebView(
                        html = stripDangerousHtml(code),
                        modifier = Modifier.fillMaxSize(),
                        minHeight = 200.dp,
                        maxHeight = 4000.dp,
                        onFailed = { showFullscreen = false; renderFailed = true },
                    )
                }
            }
        }
    }
}

@Composable
private fun HtmlCardFallback(code: String, reason: String?) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (reason != null) {
            Text(
                text = reason,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        HighlightCodeBlock(
            code = code,
            language = "html",
            modifier = Modifier.fillMaxWidth(),
            completeCodeBlock = true,
        )
    }
}
