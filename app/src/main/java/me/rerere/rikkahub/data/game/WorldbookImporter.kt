package me.rerere.rikkahub.data.game

import android.content.Context
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.isledeer.soullink.game.isEffectivelyEmpty
import com.isledeer.soullink.game.sniffAndImport
import me.rerere.rikkahub.data.repository.GameRepository
import me.rerere.rikkahub.utils.ImageUtils

sealed interface WorldbookImportResult {
    data class Success(val bookId: String, val entryCount: Int) : WorldbookImportResult
    data class Failed(val message: String) : WorldbookImportResult
}

private const val MAX_FILE_BYTES = 5L * 1024 * 1024

/**
 * 从文件导入世界书：读 → 嗅探 → 落库。转换逻辑全在 `:game`，这里只做 IO 与提示。
 *
 * PNG 走上游 `ImageUtils.getTavernCharacterMeta`（和助手导入同一套抽帧逻辑）。
 */
suspend fun importWorldbookFromUri(
    context: Context,
    uri: Uri,
    repository: GameRepository,
): WorldbookImportResult {
    val resolver = context.contentResolver
    val mime = resolver.getType(uri)

    val size = runCatching {
        resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
    }.getOrDefault(-1L)
    if (size > MAX_FILE_BYTES) {
        return WorldbookImportResult.Failed("文件超过 5 MB")
    }

    val json = withContext(Dispatchers.IO) {
        runCatching {
            if (mime == "image/png") {
                val base64 = ImageUtils.getTavernCharacterMeta(context, uri).getOrElse { return@runCatching null }
                String(Base64.decode(base64, Base64.DEFAULT))
            } else {
                resolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }
        }.getOrNull()
    } ?: return WorldbookImportResult.Failed(
        if (mime == "image/png") "这张图片里没有角色卡数据" else "读取文件失败"
    )

    val fallbackTitle = uri.lastPathSegment
        ?.substringAfterLast('/')
        ?.substringBeforeLast('.')
        ?.takeIf { it.isNotBlank() }
        ?: "导入的世界书"

    val world = sniffAndImport(json, fallbackTitle)
        ?: return WorldbookImportResult.Failed("无法识别的世界书格式")
    if (world.isEffectivelyEmpty()) {
        return WorldbookImportResult.Failed("文件里没有可用内容")
    }

    return runCatching {
        val id = repository.insertImported(world)
        WorldbookImportResult.Success(id, world.entries.size)
    }.getOrElse { WorldbookImportResult.Failed("导入失败：${it.message ?: "未知错误"}") }
}
