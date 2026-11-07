package com.sonance.player.sync

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * 从机侧的曲目缓存：`cacheDir/group_audio`，LRU 上限约 200MB。
 *
 * 文件名用 key 的 hash，避免主机 mediaId / 特殊字符直接落盘；
 * 下载完成的文件旁边写一个 `.done` 标记，进程被杀后仍能判断完整性。
 */
class TrackCache(private val context: Context) {

    private val root = File(context.cacheDir, "group_audio").apply { mkdirs() }
    private val maxBytes: Long = 200L * 1024L * 1024L

    fun safeName(key: String): String = "k" + (key.hashCode().toLong() and 0xFFFF_FFFFL).toString(16)

    fun fileFor(key: String, ext: String): File =
        File(root, "${safeName(key)}.${ext.ifBlank { "audio" }}")

    fun partFor(key: String, ext: String): File =
        File(root, "${safeName(key)}.part")

    fun doneMarker(key: String): File = File(root, "${safeName(key)}.done")

    /** 已完成且文件存在的缓存；否则 null */
    fun doneFile(key: String, ext: String): File? {
        val file = fileFor(key, ext)
        return file.takeIf { it.exists() && it.length() > 0 && doneMarker(key).exists() }
    }

    fun markDone(key: String, ext: String, part: File): File? {
        val target = fileFor(key, ext)
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) {
            runCatching { part.copyTo(target, overwrite = true) }
            part.delete()
        }
        if (!target.exists()) return null
        runCatching { doneMarker(key).writeText(target.length().toString()) }
        // 传进去的 key 必须保留：刚下完的这一首可能正好把总量顶过上限
        evict(setOf(key))
        return target
    }

    fun uriFor(file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.covers", file)

    /** 超过上限时按最后修改时间从旧到新删（保留正在播放/预取的 key） */
    fun evict(keepKeys: Set<String>) {
        val keep = keepKeys.map { safeName(it) }
        val files = root.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        val candidates = files
            .filter { f ->
                !f.name.startsWith("k") || keep.none { f.name.startsWith(it) }
            }
            .sortedBy { it.lastModified() }
        for (f in candidates) {
            if (total <= maxBytes) break
            val size = f.length()
            if (f.delete()) total -= size
        }
    }

    fun clear() {
        runCatching { root.listFiles()?.forEach { it.delete() } }
    }
}
