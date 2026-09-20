package com.sonance.player.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** 扫描到的一个不可播放文件 */
data class UnplayableFile(
    val name: String,
    val uri: Uri,
    val sizeBytes: Long,
    val extension: String,
    /** 加密格式对应的来源平台；普通不支持格式为 null */
    val source: String?,
    val folder: String
) {
    val isLocked: Boolean get() = source != null
}

data class UnplayableScanResult(
    val locked: List<UnplayableFile>,
    val plainUnsupported: List<UnplayableFile>,
    val foldersScanned: Int,
    val truncated: Boolean
) {
    val totalCount: Int get() = locked.size + plainUnsupported.size
    val totalBytes: Long get() = locked.sumOf { it.sizeBytes } + plainUnsupported.sumOf { it.sizeBytes }
}

/**
 * 通过存储访问框架（SAF）在用户指定的目录里查找不可播放的文件。
 *
 * 之所以走 SAF 而不是直接遍历文件系统：Android 10 以后普通应用无法自由读取
 * 共享存储中的任意文件，MediaStore 又**不会索引** .kgm / .ncm 这类未知扩展名
 * （实测：放进 Music 目录触发扫描后仍然查不到），所以只能由用户授权一个目录。
 */
class UnplayableFileScanner(private val context: Context) {

    suspend fun scan(
        treeUri: Uri,
        onProgress: (foldersScanned: Int) -> Unit = {}
    ): UnplayableScanResult = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: return@withContext UnplayableScanResult(emptyList(), emptyList(), 0, false)

        val locked = mutableListOf<UnplayableFile>()
        val plain = mutableListOf<UnplayableFile>()
        var folders = 0
        var visited = 0
        var truncated = false

        val stack = ArrayDeque<Pair<DocumentFile, String>>()
        stack.addLast(root to (root.name ?: "根目录"))

        while (stack.isNotEmpty()) {
            coroutineContext.ensureActive()

            if (visited >= MAX_FILES) {
                truncated = true
                break
            }

            val (dir, path) = stack.removeLast()
            folders++
            onProgress(folders)

            val children = runCatching { dir.listFiles() }.getOrNull() ?: continue
            for (child in children) {
                visited++
                if (visited >= MAX_FILES) {
                    truncated = true
                    break
                }
                if (child.isDirectory) {
                    if (stack.size < MAX_FOLDERS) {
                        stack.addLast(child to "$path/${child.name ?: "?"}")
                    } else {
                        truncated = true
                    }
                    continue
                }
                val name = child.name ?: continue
                val ext = LockedFormat.extensionOf(name)
                if (ext.isEmpty()) continue

                when {
                    LockedFormat.isLocked(name) -> locked += UnplayableFile(
                        name = name,
                        uri = child.uri,
                        sizeBytes = child.length(),
                        extension = ext,
                        source = LockedFormat.sourceOf(name),
                        folder = path
                    )

                    LockedFormat.isPlainUnsupported(name) -> plain += UnplayableFile(
                        name = name,
                        uri = child.uri,
                        sizeBytes = child.length(),
                        extension = ext,
                        source = null,
                        folder = path
                    )
                }
            }
        }

        UnplayableScanResult(
            locked = locked.sortedBy { it.name.lowercase() },
            plainUnsupported = plain.sortedBy { it.name.lowercase() },
            foldersScanned = folders,
            truncated = truncated
        )
    }

    companion object {
        private const val MAX_FILES = 40_000
        private const val MAX_FOLDERS = 4_000
    }
}
