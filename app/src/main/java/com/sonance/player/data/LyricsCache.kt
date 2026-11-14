package com.sonance.player.data

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * 在线歌词的本地缓存。
 *
 * 目的很明确：**同一首歌只联网一次**。拿到歌词后落盘，之后每次打开歌词页
 * 直接读本地文件，不再打接口 —— 既省流量，也避免被公开实例限流。
 *
 * 文件名用「歌名+歌手」的 SHA-1，而不是 MediaStore 的曲目 id：
 * 重新扫描后 id 会变，而歌名歌手不变，这样缓存能跨扫描复用。
 *
 * 两种状态都会落盘：
 * - 有歌词 → 文件内容是 LRC 文本
 * - **确认没有** → 空文件（哨兵），下次不再白跑一次网络请求
 */
object LyricsCache {

    private const val TAG = "SonanceLyrics"
    private const val DIR = "lyrics_cache"
    private const val CUSTOM_DIR = "custom_lyrics"
    private const val EXT = ".lrc"
    private const val DELETED_EXT = ".deleted"
    private const val MAX_FILES = 500

    /** 按 songId 保存的用户手动操作覆盖状态（优先于内嵌歌词与在线缓存） */
    sealed interface Override {
        data class Imported(val text: String) : Override
        data object Deleted : Override
        data object None : Override
    }

    /** 缓存查询结果 */
    sealed interface Lookup {
        /** 有歌词 */
        data class Hit(val text: String) : Lookup

        /** 之前查过，确认没有（别再发请求了） */
        data object KnownMiss : Lookup

        /** 没查过 */
        data object Unknown : Lookup
    }

    /**
     * 用「歌名 + 歌手 + 服务地址」算稳定 key。
     *
     * 为什么要带服务地址：换了歌词源（比如从公开实例换成自建）之后，
     * 旧源的结果不应该被复用 —— 否则用户会看到"换了地址但歌词没变"。
     * 带进 key 就等于自动按源分命名空间。
     *
     * 歌名歌手都空时返回 null（不缓存）。
     */
    fun keyOf(title: String, artist: String, apiBase: String = ""): String? {
        val t = title.trim().lowercase()
        val a = artist.trim().lowercase()
        if (t.isEmpty() && a.isEmpty()) return null
        val salt = apiBase.trim().lowercase()
        return sha1("$salt|$t|$a")
    }

    fun lookup(context: Context, key: String?): Lookup {
        if (key == null) return Lookup.Unknown
        val f = fileOf(context, key)
        if (!f.exists()) return Lookup.Unknown
        return try {
            val text = f.readText()
            if (text.isBlank()) Lookup.KnownMiss else Lookup.Hit(text)
        } catch (t: Throwable) {
            Log.w(TAG, "读歌词缓存失败: ${t.message}")
            Lookup.Unknown
        }
    }

    fun store(context: Context, key: String?, text: String?) {
        if (key == null) return
        try {
            val dir = dirOf(context)
            if (!dir.exists() && !dir.mkdirs()) return
            // text == null 表示"确认没有"，写空文件当哨兵
            fileOf(context, key).writeText(text.orEmpty())
            trim(dir)
        } catch (t: Throwable) {
            Log.w(TAG, "写歌词缓存失败: ${t.message}")
        }
    }

    /** 删掉某个 key 的缓存（包括 KnownMiss 哨兵），用于"拿到错误歌词后重搜" */
    fun remove(context: Context, key: String?) {
        if (key == null) return
        runCatching { fileOf(context, key).delete() }
            .onFailure { Log.w(TAG, "删歌词缓存失败: ${it.message}") }
    }

    fun count(context: Context): Int =
        runCatching { dirOf(context).listFiles()?.size ?: 0 }.getOrDefault(0)

    fun clear(context: Context) {
        runCatching { dirOf(context).listFiles()?.forEach { it.delete() } }
    }

    /** 缓存体积（KB），设置页展示用 */
    fun sizeKb(context: Context): Long = runCatching {
        dirOf(context).listFiles()?.sumOf { it.length() }?.div(1024) ?: 0L
    }.getOrDefault(0L)

    fun lookupOverride(context: Context, songId: Long): Override {
        if (songId == 0L) return Override.None
        if (deletedMarkerOf(context, songId).exists()) return Override.Deleted
        val imported = customFileOf(context, songId)
        if (imported.exists()) {
            val text = runCatching { imported.readText() }.getOrNull()
            if (!text.isNullOrBlank()) return Override.Imported(text)
        }
        return Override.None
    }

    fun saveImported(context: Context, songId: Long, text: String) {
        if (songId == 0L || text.isBlank()) return
        runCatching {
            val dir = customDirOf(context)
            if (!dir.exists() && !dir.mkdirs()) return
            deletedMarkerOf(context, songId).delete()
            customFileOf(context, songId).writeText(text)
        }.onFailure { Log.w(TAG, "保存导入歌词失败: ${it.message}") }
    }

    fun markDeleted(context: Context, songId: Long) {
        if (songId == 0L) return
        runCatching {
            val dir = customDirOf(context)
            if (!dir.exists() && !dir.mkdirs()) return
            customFileOf(context, songId).delete()
            deletedMarkerOf(context, songId).writeText("1")
        }.onFailure { Log.w(TAG, "标记删除歌词失败: ${it.message}") }
    }

    fun clearOverride(context: Context, songId: Long) {
        if (songId == 0L) return
        runCatching {
            customFileOf(context, songId).delete()
            deletedMarkerOf(context, songId).delete()
        }.onFailure { Log.w(TAG, "清理歌词覆盖标记失败: ${it.message}") }
    }

    /* ============================================================ */

    private fun dirOf(context: Context) = File(context.filesDir, DIR)

    private fun fileOf(context: Context, key: String) = File(dirOf(context), "$key$EXT")

    private fun customDirOf(context: Context) = File(context.filesDir, CUSTOM_DIR)

    private fun customFileOf(context: Context, songId: Long) = File(customDirOf(context), "song_$songId$EXT")

    private fun deletedMarkerOf(context: Context, songId: Long) = File(customDirOf(context), "song_$songId$DELETED_EXT")

    /** 超过上限就按最后修改时间删掉最旧的一批，避免无限增长 */
    private fun trim(dir: File) {
        val files = dir.listFiles() ?: return
        if (files.size <= MAX_FILES) return
        files.sortedBy { it.lastModified() }
            .take(files.size - MAX_FILES)
            .forEach { runCatching { it.delete() } }
    }

    private fun sha1(s: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
