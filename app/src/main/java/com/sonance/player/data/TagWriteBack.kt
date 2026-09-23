package com.sonance.player.data

import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/**
 * 把「只存在应用缓存里」的封面 / 歌词**写回音乐文件本身**。
 *
 * ## 为什么需要
 *
 * 封面补全 / 歌词补全拿到的内容都只落在应用自己的缓存里（[CoverCache] /
 * [LyricsCache]）：清一次数据、换一次手机、换一个播放器，就全没了。
 * 这些东西是从网络匹配来的、**无法从文件本身重建**，所以值得写进文件。
 *
 * ## 范围：只处理 MP3
 *
 * 写标签 = 在文件头重写一个 ID3v2 标签，这只对 MP3 成立。
 * WAV 是 RIFF 容器、FLAC 是 metadata block、M4A 是 mp4 盒子，结构完全不同，
 * 硬把 ID3 前置进去会做出"看起来能播但结构非法"的文件。所以非 MP3 一律跳过，
 * 并在结果里如实告知（而不是静默当成功）。
 *
 * ## 元数据必须原样保留
 *
 * 重写时用 [Id3Writer.rebuildTag]：**保留文件里原有的全部帧**（标题 / 歌手 /
 * 专辑 / 曲目号…），只替换 APIC（封面）和 USLT（歌词）。
 * 不能拿 MediaStore 里的字段重写一遍 —— 那里的标题 / 歌手可能是**从文件名猜的**
 * （「张卫健 - 虚虚实实」当标题、歌手「未知艺术家」），覆盖进去等于把对的改成错的。
 *
 * ## 授权
 *
 * - API 30+：[MediaStore.createWriteRequest] 一次请求一批（系统弹一个确认框）。
 * - API 29：写的时候会抛 `RecoverableSecurityException`，带出它自带的授权入口，
 *   授权后重试。
 * - API 28-：走 `WRITE_EXTERNAL_STORAGE`（manifest 里 `maxSdkVersion="28"` 声明过）。
 *
 * ## 安全
 *
 * 每个文件**先备份到应用缓存**再覆盖，写完读回来校验，失败就回滚 ——
 * 这些是用户的音乐文件，写坏一首都是事故。
 */
object TagWriteBack {

    private const val TAG = "SonanceTagWrite"

    /** 一首待写的歌：写什么已经算好了 */
    class Item(
        val song: Song,
        val cover: ByteArray?,
        val lyrics: String?
    ) {
        val uri: Uri get() = song.uri
    }

    /** 结果。`consentSender` 非 null 表示中途要求授权，处理完应重试 */
    class Summary(
        val written: Int,
        val skipped: Int,
        val failed: Int,
        val consentSender: IntentSender?
    )

    /**
     * 纯函数：这次该往文件里补什么。
     *
     * 规则只有一条：**文件里已经有的不动，缓存里也拿不到的不写**。
     * 返回 (要写的封面, 要写的歌词)，两个都 null 表示没什么可写的。
     */
    internal fun decide(
        fileHasCover: Boolean,
        fileHasLyrics: Boolean,
        cacheCover: ByteArray?,
        cacheLyrics: String?
    ): Pair<ByteArray?, String?> {
        val cover = if (fileHasCover) null else cacheCover?.takeIf { it.isNotEmpty() }
        val lyrics = if (fileHasLyrics) null else cacheLyrics?.takeIf { it.isNotBlank() }
        return cover to lyrics
    }

    /**
     * 只处理 MP3：`ID3` 开头，或者裸 MPEG 帧同步（`0xFFEx`）。
     * 其余容器（RIFF/WAV、fLaC、mp4）不动。
     */
    internal fun isMp3(head: ByteArray): Boolean = when {
        head.size < 4 -> false
        head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte() -> true
        head[0] == 0xFF.toByte() && (head[1].toInt() and 0xE0) == 0xE0 -> true
        else -> false
    }

    /**
     * 扫一遍曲库，找出值得写的文件。
     *
     * 每首歌要开一次文件（读头部 + 标签）+ 两次缓存查询，几百首的库在
     * IO 线程上一两秒，放后台跑、结果缓存住（见 ViewModel 的调用）。
     *
     * @param lyricsApiBase 歌词缓存键的一部分（换源后键不同），照用户设置取
     */
    suspend fun findCandidates(
        context: Context,
        songs: List<Song>,
        lyricsApiBase: String
    ): List<Item> = withContext(Dispatchers.IO) {
        val out = ArrayList<Item>()
        for (song in songs) {
            val head = peekHead(context, song.uri) ?: continue
            if (!isMp3(head)) continue
            val (fileCover, fileLyrics) = runCatching { AudioTags.readTags(context, song.uri) }
                .getOrNull() ?: continue
            val cacheCover = (CoverCache.lookup(context, OnlineCover.keyOf(song.title, song.artist))
                as? CoverCache.Lookup.Hit)?.bytes
            val cacheLyrics = (LyricsCache.lookup(
                context, LyricsCache.keyOf(song.title, song.artist, lyricsApiBase)
            ) as? LyricsCache.Lookup.Hit)?.text
            val (addCover, addLyrics) = decide(
                fileCover != null, !fileLyrics.isNullOrBlank(), cacheCover, cacheLyrics
            )
            if (addCover == null && addLyrics == null) continue
            out += Item(song, addCover, addLyrics)
        }
        out
    }

    /** API 30+：一批 URI 一次授权。返回 null 表示这一步不需要（API 29- 走异常带出的入口） */
    fun consentSender(context: Context, uris: List<Uri>): IntentSender? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        if (uris.isEmpty()) return null
        return runCatching {
            MediaStore.createWriteRequest(context.contentResolver, uris).intentSender
        }.getOrNull()
    }

    /**
     * 逐个写。`onProgress(done, total)` 给 UI 显示进度。
     *
     * 遇到「需要授权」（API 29 的 RecoverableSecurityException）会**立刻停下**，
     * 把授权入口带回去 —— 与其半途弹 N 个框，不如一次授权后从头再来，
     * 反正已写完的那几首再写也是幂等的。
     */
    suspend fun write(
        context: Context,
        items: List<Item>,
        onProgress: (Int, Int) -> Unit
    ): Summary = withContext(Dispatchers.IO) {
        var written = 0
        var skipped = 0
        var failed = 0
        var consent: IntentSender? = null
        for ((index, item) in items.withIndex()) {
            onProgress(index, items.size)
            if (consent != null) break
            try {
                if (writeOne(context, item)) written++ else skipped++
            } catch (e: SecurityException) {
                // API 29 的"要授权"长这样。30+ 走 createWriteRequest，一般到不了这里
                val sender = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    (e as? android.app.RecoverableSecurityException)
                        ?.userAction?.actionIntent?.intentSender
                } else {
                    null
                }
                if (sender != null) {
                    consent = sender
                } else {
                    Log.w(TAG, "没有写权限：${item.song.title}")
                    failed++
                }
            } catch (t: Throwable) {
                Log.w(TAG, "写回失败 ${item.song.title}: ${t.javaClass.simpleName}: ${t.message}")
                failed++
            }
        }
        onProgress(items.size, items.size)
        Summary(written, skipped, failed, consent)
    }

    /**
     * 写一个文件：读原字节 → 组新标签 → 备份 → 覆盖 → 读回来校验。
     *
     * @return true = 写了；false = 没什么可写 / 不是 MP3（跳过）
     * @throws IOException 读写失败（外层记失败）
     * @throws SecurityException 没有写权限（外层转授权流程）
     */
    private fun writeOne(context: Context, item: Item): Boolean {
        val original = context.contentResolver.openInputStream(item.uri)?.use { it.readBytes() }
            ?: throw IOException("读不到文件")
        if (!isMp3(original)) return false
        val head = original.copyOfRange(0, minOf(10, original.size))
        val existingSize = Id3Writer.existingTagSize(head)
        val existing = if (existingSize > 0) {
            original.copyOfRange(0, minOf(existingSize, original.size))
        } else {
            null
        }
        val tag = Id3Writer.rebuildTag(existing, item.cover, null, item.lyrics)
            ?: throw IOException("标签结构不支持")
        val newBytes = ByteArrayOutputStream(original.size + tag.size).also {
            Id3Writer.copyWithTag(ByteArrayInputStream(original), it, tag)
        }.toByteArray()
        if (newBytes.size <= tag.size) throw IOException("拼出来的内容为空")

        val backup = File(context.cacheDir, "tagbak-${item.song.id}.bin")
        backup.writeBytes(original)
        try {
            overwrite(context, item.uri, newBytes)
            val now = context.contentResolver.openInputStream(item.uri)?.use { it.readBytes() }
                ?: throw IOException("写完读不回来")
            if (now.size != newBytes.size || !isMp3(now)) throw IOException("写入校验失败")
            backup.delete()
            Log.i(TAG, "已写回：${item.song.title}（${original.size} → ${newBytes.size} 字节）")
            return true
        } catch (t: Throwable) {
            // 文件可能已经被截断 —— 从备份恢复，绝不能留半个文件在那
            runCatching { overwrite(context, item.uri, backup.readBytes()) }
            backup.delete()
            throw t
        }
    }

    /**
     * 整体覆盖一个 MediaStore 条目。
     *
     * `"wt"`（write-truncate）才能把变短的文件也写对；个别提供方不认这个模式，
     * 再退回 `"w"` 重写一遍。
     */
    private fun overwrite(context: Context, uri: Uri, bytes: ByteArray) {
        val modes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) listOf("wt", "w") else listOf("w")
        var lastError: Exception? = null
        for (mode in modes) {
            try {
                val pfd = context.contentResolver.openFileDescriptor(uri, mode) ?: continue
                ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { it.write(bytes) }
                return
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IOException("打不开输出流")
    }

    /** 读文件头（判断容器用），读不到返回 null */
    private fun peekHead(context: Context, uri: Uri): ByteArray? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val head = ByteArray(12)
            var filled = 0
            while (filled < head.size) {
                val n = input.read(head, filled, head.size - filled)
                if (n < 0) break
                filled += n
            }
            if (filled == 0) null else head.copyOf(filled)
        }
    }.getOrNull()
}
