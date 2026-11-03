package me.rerere.rikkahub.data.game

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.isledeer.soullink.game.FileKind
import com.isledeer.soullink.game.isEffectivelyEmpty
import com.isledeer.soullink.game.sniffAndImport
import com.isledeer.soullink.game.sniffFileKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.repository.GameRepository
import me.rerere.rikkahub.utils.ImageUtils

sealed interface WorldbookImportResult {
    data class Success(val bookId: String, val entryCount: Int) : WorldbookImportResult
    data class Failed(val message: String) : WorldbookImportResult
}

/** 用户在抽屉里点的是哪一项。只用来做提示，真实类型一律看文件头。 */
enum class WorldbookImportMode { JSON, PNG }

private const val MAX_FILE_BYTES = 5L * 1024 * 1024
private const val HEADER_BYTES = 8

/**
 * 从文件导入世界书：文件头判类型 → 抽 JSON → `sniffAndImport` → 落库。
 *
 * **不看 MIME**：安卓各家文件管理器对 `.json` 上报的 MIME 五花八门，
 * 所以选择器用 `*/*`，类型只看 [sniffFileKind]。
 */
suspend fun importWorldbookFromUri(
    context: Context,
    uri: Uri,
    repository: GameRepository,
    mode: WorldbookImportMode,
): WorldbookImportResult {
    val resolver = context.contentResolver

    val size = runCatching {
        resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
    }.getOrDefault(-1L)
    if (size > MAX_FILE_BYTES) {
        return WorldbookImportResult.Failed("文件超过 5 MB")
    }

    val header = withContext(Dispatchers.IO) {
        runCatching {
            resolver.openInputStream(uri)?.use { stream ->
                val buffer = ByteArray(HEADER_BYTES)
                val read = stream.read(buffer)
                if (read > 0) buffer.copyOf(read) else ByteArray(0)
            } ?: ByteArray(0)
        }.getOrDefault(ByteArray(0))
    }
    val kind = sniffFileKind(header)

    if (kind == FileKind.ZIP) {
        return WorldbookImportResult.Failed("暂不支持 Office 文档，下一版本支持")
    }
    if (mode == WorldbookImportMode.PNG && kind != FileKind.PNG) {
        return WorldbookImportResult.Failed("这不是 PNG 文件")
    }

    val json = withContext(Dispatchers.IO) {
        runCatching {
            if (kind == FileKind.PNG) {
                val base64 = ImageUtils.getTavernCharacterMeta(context, uri).getOrNull()
                    ?: return@runCatching null
                String(Base64.decode(base64, Base64.DEFAULT))
            } else {
                resolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }
        }.getOrNull()
    } ?: return WorldbookImportResult.Failed(
        if (kind == FileKind.PNG) "这张图片里没有角色卡数据" else "读取文件失败"
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
