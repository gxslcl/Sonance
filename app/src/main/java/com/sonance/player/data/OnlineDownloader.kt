package com.sonance.player.data

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 「播放并下载」：把正在播的在线歌存一份到本地。
 *
 * ## 存的是哪条流
 *
 * **正在播的那条**（标准音质 128kbps MP3），不是官方下载接口给的高音质版本 ——
 * 那些端点要登录 + 会员，这里刻意不碰。所以存下来的音质和你听到的一致。
 *
 * ## 字节从哪来：二次 GET，不复用播放流
 *
 * 复用播放流（Media3 `SimpleCache` 边播边写）能省一半流量，但有三个坑：
 * 缓存存的不是普通文件（分片 + 元数据 DB，要读回来重新拼）、
 * 用户跳歌会导致缓存里只有**半首歌**（导出的文件中途截断，得靠比对
 * `Content-Length` 才能识别）、缓存淘汰还可能把数据清掉。
 *
 * 二次 GET 的代价是流量翻倍 —— 但实测一首 4 分钟的歌是 4.26 MB、
 * 下载耗时 884 ms，这个代价换来的是"产出就是干净的 mp3"，值。
 *
 * ## 落盘位置：公开音乐目录下的一个独立子目录
 *
 * `Music/<FOLDER_NAME>/`。这样**扫描之后「文件夹」页会自然多出一个分类**
 * （用户明确要的），而且文件是真正的本地歌 —— 能被搜索、收藏、排序。
 *
 * - API 29+：走 MediaStore，**不需要任何权限**
 * - API 24~28：直接写文件，需要 `WRITE_EXTERNAL_STORAGE`（已在 manifest 里
 *   用 `maxSdkVersion="28"` 声明，只在老系统上请求）
 */
object OnlineDownloader {

    private const val TAG = "SonanceDownload"

    /** 公开音乐目录下的子目录名。改它会让已下载的歌下次扫描后"换一个文件夹" */
    const val FOLDER_NAME = "山水有清音下载"

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 30_000

    /** 跟随重定向的跳数上限，防止环 */
    private const val MAX_REDIRECTS = 5

    private const val UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"

    sealed interface Outcome {
        /** 存好了 */
        data class Saved(val fileName: String) : Outcome

        /** 这首歌拿不到音频（会员歌 / 版权下架）—— 取流接口给的是 HTML 页面 */
        data object Unavailable : Outcome

        /** 网络、写盘、权限等原因失败 */
        data object Failed : Outcome

        /** 已经下过了，跳过 */
        data object Skipped : Outcome
    }

    /**
     * 下载键。用 `音源:远端id` —— 它就在 `Song` 里，判断重复几乎零成本。
     *
     * 不用 `song.id`（负数哈希）是因为那个值依赖哈希实现；键要写进
     * SharedPreferences 长期保存，用原始字段更稳。
     */
    fun downloadKeyOf(source: String, remoteId: String?): String? =
        if (remoteId.isNullOrBlank()) null else "$source:$remoteId"

    /**
     * 落盘用的文件名：`歌手 - 歌名.mp3`。
     *
     * 歌手为空时只留歌名。**必须清洗** —— 歌名里带 `/`、`:`、`?`、`*` 的很多
     * （`AC/DC`、`Love Me Do?`），直接当文件名会创建失败或落到别的目录去。
     */
    fun fileNameFor(title: String, artist: String): String {
        val t = sanitize(title).ifBlank { "未知歌曲" }
        val a = sanitize(artist)
        return (if (a.isBlank()) t else "$a - $t") + ".mp3"
    }

    /**
     * 清掉文件名里不能用的字符。
     *
     * 覆盖 FAT/exFAT 和 ext4 的非法字符集，另外去掉首尾的点和空格 ——
     * Windows 上以点或空格结尾的文件名是非法的（外接卡插到电脑上就看出问题了）。
     */
    private fun sanitize(raw: String): String {
        val cleaned = raw
            .replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_")
            .trim()
            .trimEnd('.', ' ')
        // 单段文件名上限通常 255 字节，中文按 UTF-8 占 3 字节 —— 保守取 60 个字符
        return if (cleaned.length > 60) cleaned.take(60).trim() else cleaned
    }

    /**
     * 下载一首歌。
     *
     * @param streamUrl  取流地址（[OnlineSource.streamUrl] 的结果）
     * @param coverBytes 要嵌进 ID3 的封面字节；null 就不嵌
     * @param lyrics     要嵌进 ID3 的歌词（LRC 原文）；null / 空白就不嵌
     */
    suspend fun download(
        context: Context,
        source: String,
        remoteId: String,
        title: String,
        artist: String,
        album: String,
        streamUrl: String,
        coverBytes: ByteArray?,
        lyrics: String? = null
    ): Outcome = withContext(Dispatchers.IO) {
        val fileName = fileNameFor(title, artist)
        val temp = File(context.cacheDir, "dl-${System.nanoTime()}.part")

        try {
            // ---- 1) 下载到缓存目录的临时文件 ----
            val conn = openFollowingRedirects(streamUrl) ?: return@withContext Outcome.Failed
            try {
                val code = conn.responseCode
                if (code !in 200..299) {
                    Log.w(TAG, "取流 HTTP $code（多半是会员歌）")
                    return@withContext Outcome.Unavailable
                }
                // ★ 会员歌的重定向目标是**一张 HTML 下载页**（200 + text/html），
                // 不是 403。只看状态码会把它当成功，存下来一个"能播但全是噪音"的文件
                val type = conn.contentType.orEmpty().lowercase()
                if (!type.startsWith("audio/")) {
                    Log.w(TAG, "取流返回的不是音频（$type），按不可用处理")
                    return@withContext Outcome.Unavailable
                }
                val expected = conn.contentLengthLong
                var written = 0L
                conn.inputStream.use { input ->
                    FileOutputStream(temp).use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            written += n
                        }
                    }
                }
                // 长度对不上就是下载被截断了（连接断了、或服务端提前关流）。
                // 存一个半首歌比不存更糟 —— 用户以为下好了，播到一半没了
                if (expected > 0 && written != expected) {
                    Log.w(TAG, "下载不完整：$written / $expected 字节")
                    return@withContext Outcome.Failed
                }
                if (written <= 0L) return@withContext Outcome.Failed
            } finally {
                runCatching { conn.disconnect() }
            }

            // ---- 2) 写标签 + 落到目标位置（流式，不把整首歌读进内存）----
            // 标题 / 歌手 / 专辑 / 封面 / 歌词一次写全：这样存下来的文件
            // 在**任何**播放器里都是完整的一首歌，而不是"文件名当标题、歌手未知"。
            // 封面和歌词都来自下载时顺手取的那份，不额外发请求。
            val tag = Id3Writer.buildTag(title, artist, album, coverBytes, lyrics = lyrics)
            val opened = openTarget(context, fileName) ?: return@withContext Outcome.Failed
            try {
                temp.inputStream().use { input ->
                    opened.stream.use { out ->
                        Id3Writer.copyWithTag(input, out, tag)
                    }
                }
            } catch (t: Throwable) {
                opened.onFailure()
                throw t
            }
            opened.onSuccess(context, fileName)

            Log.i(TAG, "已保存：$fileName")
            Outcome.Saved(fileName)
        } catch (t: Throwable) {
            Log.w(TAG, "下载失败：${t.javaClass.simpleName}: ${t.message}")
            Outcome.Failed
        } finally {
            runCatching { temp.delete() }
        }
    }

    // ===== HTTP：必须自己跟随重定向 =====

    /**
     * 发 GET 并**手动**跟随重定向，返回最终那个连接（调用方负责 disconnect）。
     *
     * ## 为什么不设 `instanceFollowRedirects = true`
     *
     * `HttpURLConnection` 即使开了这个开关，也**只跟随同协议**的重定向。
     * 而网易云的取流接口恰好是 `https://music.163.com/...` → 302 →
     * `http://m701.music.126.net/...`（明文 http）。跟着跟着就停了，
     * 拿到的会是一个 302 空响应 —— 这正是之前播放链路栽过的那个坑，
     * 当时表现为"所有在线歌都播不了"。
     *
     * 这里手动跟，顺便把跳数限死防环。
     */
    private fun openFollowingRedirects(startUrl: String): HttpURLConnection? {
        var current = startUrl
        var hop = 0
        while (hop < MAX_REDIRECTS) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Referer", "https://music.163.com/")
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc.isNullOrBlank()) return null
                // Location 可能是相对路径，按当前 URL 解析
                current = runCatching {
                    if (loc.startsWith("http", ignoreCase = true)) loc
                    else URL(URL(current), loc).toString()
                }.getOrNull() ?: return null
                hop++
                continue
            }
            return conn
        }
        Log.w(TAG, "重定向超过 $MAX_REDIRECTS 跳，放弃")
        return null
    }

    // ===== 落盘：两套 API =====

    /** 一个已经打开的落盘目标。写完之后要调 [onSuccess] / [onFailure] */
    private class Target(
        val stream: OutputStream,
        /** 写完之后的收尾：把文件变成"可见" */
        private val finish: (Context, String) -> Unit,
        /** 失败时的清理：把半截文件删掉 */
        private val abort: () -> Unit
    ) {
        fun onSuccess(context: Context, fileName: String) = finish(context, fileName)
        fun onFailure() = abort()
    }

    /**
     * 打开落盘目标。
     *
     * 两套 API 的差别只在"怎么让文件被系统索引到"：
     * - API 29+：MediaStore 插入时带 `IS_PENDING=1`，写完置 0 —— 系统自动索引，
     *   而且**不需要任何权限**（应用写自己的媒体文件是允许的）
     * - API 24~28：直接写文件，写完调 `MediaScannerConnection.scanFile` 让它进媒体库
     */
    private fun openTarget(context: Context, fileName: String): Target? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
                put(
                    MediaStore.Audio.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_MUSIC}/$FOLDER_NAME"
                )
                // 写完之前标记为 pending，避免别的应用/扫描器读到半截文件
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val uri: Uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                ?: return@runCatching null
            val out = resolver.openOutputStream(uri) ?: run {
                resolver.delete(uri, null, null)
                return@runCatching null
            }
            Target(
                stream = out,
                finish = { ctx, _ ->
                    val done = ContentValues().apply {
                        put(MediaStore.Audio.Media.IS_PENDING, 0)
                    }
                    runCatching { ctx.contentResolver.update(uri, done, null, null) }
                },
                abort = { runCatching { context.contentResolver.delete(uri, null, null) } }
            )
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                FOLDER_NAME
            )
            if (!dir.exists() && !dir.mkdirs()) return@runCatching null
            val file = File(dir, fileName)
            Target(
                stream = FileOutputStream(file),
                finish = { ctx, name ->
                    // 直接写文件不会自动进媒体库，要显式扫一次，
                    // 否则"文件夹"分类要等下次整盘扫描才出现
                    runCatching {
                        MediaScannerConnection.scanFile(
                            ctx, arrayOf(File(dir, name).absolutePath), null, null
                        )
                    }
                },
                abort = { runCatching { file.delete() } }
            )
        }
    }.getOrNull()

    // ===== 网络条件 =====

    /**
     * 现在是不是 Wi-Fi / 以太网。
     *
     * 拿不到网络信息时**返回 false**（保守）—— 用户既然勾了"只在 Wi-Fi 下下载"，
     * 判断不出来时就不该下，而不是赌一把。
     */
    fun isOnUnmeteredNetwork(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }
}
