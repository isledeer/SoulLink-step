package me.rerere.rikkahub.ui.components.richtext

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.isledeer.soullink.game.buildCspHtml
import kotlinx.coroutines.delay
import java.io.ByteArrayInputStream

/**
 * 沙箱 WebView：不联网、不读本地文件、不注入任何 JS 桥。
 *
 * 安全设定（缺一不可）：
 * - `loadDataWithBaseURL(null, …)`：不透明 origin，页面拿不到任何同源资源
 * - `allowFileAccess` / `allowContentAccess` / `*FromFileURLs` 全关
 * - `shouldInterceptRequest`：除主文档外一律 204，物理断网
 * - 文档头部注入 CSP `default-src 'none'`，即使漏了 script 也执行不了
 *
 * 高度：`onPageFinished` 里量 `document.body.scrollHeight`，夹在 [minHeight]–[maxHeight]；
 * 3 秒没量到或加载失败 → 调 [onFailed]，由调用方降级成代码块。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SandboxWebView(
    html: String,
    modifier: Modifier = Modifier,
    minHeight: Dp = 120.dp,
    maxHeight: Dp = 600.dp,
    onFailed: () -> Unit = {},
) {
    val density = LocalDensity.current
    val safeHtml = remember(html) { buildCspHtml(html) }

    var measured by remember(html) { mutableStateOf(false) }
    var failed by remember(html) { mutableStateOf(false) }
    var height by remember(html) { mutableStateOf(minHeight) }

    if (failed) {
        LaunchedEffect(Unit) { onFailed() }
        return
    }

    // 3 秒兜底：没量到高度就当失败，别让用户盯着白块
    LaunchedEffect(html) {
        delay(3000)
        if (!measured) failed = true
    }

    AndroidView(
        modifier = modifier.height(height),
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.allowFileAccessFromFileURLs = false
                settings.allowUniversalAccessFromFileURLs = false
                settings.blockNetworkLoads = true
                settings.loadsImagesAutomatically = true
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                setBackgroundColor(android.graphics.Color.TRANSPARENT)

                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): WebResourceResponse? {
                        // 主文档必须放行，否则卡片本身就加载不出来
                        if (request.isForMainFrame) return null
                        return WebResourceResponse(
                            "text/plain",
                            "utf-8",
                            204,
                            "No Content",
                            emptyMap(),
                            ByteArrayInputStream(ByteArray(0)),
                        )
                    }

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: android.webkit.WebResourceError,
                    ) {
                        if (request.isForMainFrame) failed = true
                    }

                    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                        // 主文档加载即视为成功，避免子资源 204 触发 error 回调
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        view.evaluateJavascript("document.body.scrollHeight") { raw ->
                            val px = raw?.trim('"')?.toFloatOrNull() ?: return@evaluateJavascript
                            val dp = with(density) { px.toDp() }
                            height = dp.coerceIn(minHeight, maxHeight)
                            measured = true
                        }
                    }
                }

                loadDataWithBaseURL(null, safeHtml, "text/html", "utf-8", null)
            }
        },
        update = { view ->
            // html 变化时重新加载（流式阶段不会走到这里，调用方只在块闭合后渲染）
            if (view.url == null) {
                view.loadDataWithBaseURL(null, safeHtml, "text/html", "utf-8", null)
            }
        },
    )
}
