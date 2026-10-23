package com.sonance.player.data.bili

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 扫描结果。区分「没装 Shizuku」「装了但没运行」「没授权」——三者给的引导完全不同 */
sealed interface BiliScanResult {
    data class Ok(val entries: List<BiliEntry>) : BiliScanResult
    data object NoShizuku : BiliScanResult
    data object NotRunning : BiliScanResult
    data object NoPermission : BiliScanResult
    data class Failed(val message: String) : BiliScanResult
}

/** 已经落到本地、可以直接给播放器用的一个 MP4 */
data class BiliMedia(val file: File)

/**
 * B 站离线缓存的读取入口。
 *
 * 全部操作都通过 shell 完成（见 [ShizukuShell]），这里负责三件事：
 * ① 扫出所有 `entry.json` 并解析成 [BiliEntry]；
 * ② 用户点播放时把 `video.m4s` / `audio.m4s` 拷到应用私有缓存；
 * ③ 管理这份缓存（按大小清理，别把用户手机塞满）。
 *
 * 为什么必须先拷贝：`MergingMediaSource` 需要两个能随机 seek 的文件源，
 * 而 shell 只能给我们一条顺序的管道流。拷一份到本地是代价最小、行为最稳的做法
 * （24MB 的样本实测不到 1 秒）。
 */
class BiliRepository(context: Context) {

    private val appContext = context.applicationContext

    /**
     * 合并产物放**外部私有目录**（`Android/data/<自己的包>/cache/bili`）。
     *
     * 不用内部 `cacheDir` 的两个理由：视频动辄几百 MB，外部存储更宽裕；
     * 外部目录还能用文件管理器 / adb 直接查看，排查「为什么这条播不了」时
     * 可以把产物拉出来用 ffprobe 验结构，内部目录只能靠打日志猜。
     */
    private val cacheRoot = File(
        appContext.externalCacheDir ?: appContext.cacheDir,
        "bili"
    )

    /** 缓存上限：超过就按最久未播放淘汰。视频动辄几百 MB，2GB 是个不激进的上限 */
    private val maxCacheBytes = 2L * 1024 * 1024 * 1024

    suspend fun scan(): BiliScanResult = withContext(Dispatchers.IO) {
        when {
            !ShizukuShell.isInstalled(appContext) -> BiliScanResult.NoShizuku
            !ShizukuShell.isRunning() -> BiliScanResult.NotRunning
            !ShizukuShell.hasPermission() -> BiliScanResult.NoPermission
            else -> {
                val raw = ShizukuShell.exec(SCAN_SCRIPT)
                    ?: return@withContext BiliScanResult.Failed("读取缓存目录失败")
                val entries = parseScanOutput(raw)
                if (entries.isEmpty() && raw.isBlank()) {
                    BiliScanResult.Failed("缓存目录为空或不可读")
                } else {
                    BiliScanResult.Ok(entries)
                }
            }
        }
    }

    /**
     * 把某条缓存变成**一个可播放的 MP4**。
     *
     * 流程：把两条 m4s 拷到本地 → remux 合并 → 删掉中间文件。
     * 合并而不是直接播的原因见 [BiliRemuxer] 的注释（fMP4 没有索引，
     * 两条轨没法直接并给播放器）。
     *
     * @param onProgress 0f~1f。复制占前 80%，合并占后 20%。
     */
    suspend fun prepare(
        entry: BiliEntry,
        onProgress: (Float) -> Unit = {}
    ): Result<BiliMedia> = withContext(Dispatchers.IO) {
        runCatching {
            require(entry.audioPath != null) { "这条缓存没有独立音轨" }

            val dir = File(cacheRoot, entry.key)
            val merged = File(dir, "playable.mp4")
            // 完成标记：只有 muxer 正常收尾才会写。
            // 光看文件大小不够 —— 中途失败留下的空壳 mp4 也有几百字节，
            // 会被误判成"已缓存"，然后每次点播都拿着坏文件报错。
            val done = File(dir, ".done")

            // 已经合并过、且是同一版容器方案就直接用（重复点播不该再等一次）。
            // 比对 [MUX_VERSION] 是必须的：容器方案改过一次（补 ctts + 交错），
            // 旧产物放久了会被永远复用，用户永远看不到修复。
            if (merged.length() > 0 &&
                runCatching { done.readText().trim() }.getOrNull() == MUX_VERSION
            ) {
                dir.setLastModified(System.currentTimeMillis())
                return@runCatching BiliMedia(merged)
            }
            done.delete()

            val videoFile = File(dir, "video.m4s")
            val audioFile = File(dir, "audio.m4s")

            dir.mkdirs()
            videoFile.delete()
            audioFile.delete()

            val total = entry.sizeBytes.takeIf { it > 0 } ?: 0L

            // 先按「entry.json 目录/type_tag/audio.m4s」来；老版本缓存把 m4s
            // 直接放在 entry.json 旁边，所以失败后用同级路径再试一次
            val audioOk = ShizukuShell.copyTo(entry.audioPath.orEmpty(), audioFile) { done ->
                if (total > 0) onProgress((done.toFloat() / total * 0.8f).coerceIn(0f, 0.8f))
            } || ShizukuShell.copyTo("${entry.dir}/audio.m4s", audioFile) { done ->
                if (total > 0) onProgress((done.toFloat() / total * 0.8f).coerceIn(0f, 0.8f))
            }
            if (!audioOk) {
                dir.deleteRecursively()
                error("音频轨复制失败，缓存可能不完整")
            }

            val audioBytes = audioFile.length()
            val videoOk = ShizukuShell.copyTo(entry.videoPath, videoFile) { done ->
                if (total > 0) {
                    onProgress(((audioBytes + done).toFloat() / total * 0.8f).coerceIn(0f, 0.8f))
                } else {
                    onProgress(0.4f)
                }
            } || ShizukuShell.copyTo("${entry.dir}/video.m4s", videoFile) { done ->
                if (total > 0) {
                    onProgress(((audioBytes + done).toFloat() / total * 0.8f).coerceIn(0f, 0.8f))
                } else {
                    onProgress(0.4f)
                }
            }
            if (!videoOk) {
                dir.deleteRecursively()
                error("视频轨复制失败，缓存可能不完整")
            }

            onProgress(0.82f)
            val remuxed = BiliRemuxer.remux(videoFile, audioFile, merged, entry.durationMs) { p ->
                // 合并阶段占最后 18%
                onProgress((0.82f + p * 0.18f).coerceIn(0.82f, 1f))
            }
            if (!remuxed) {
                dir.deleteRecursively()
                error("容器解析失败：这条缓存的音视频轨无法合成，可能下载不完整")
            }

            // 中间文件没用了，留着要多占一倍空间
            videoFile.delete()
            audioFile.delete()
            done.writeText(MUX_VERSION)

            onProgress(1f)
            dir.setLastModified(System.currentTimeMillis())
            trimCache()
            BiliMedia(merged)
        }
    }

    /** 已缓存到本地的占用大小 */
    fun cachedBytes(): Long = cacheRoot.walkTopDown()
        .filter { it.isFile }
        .sumOf { it.length() }

    suspend fun clearLocalCache() = withContext(Dispatchers.IO) {
        runCatching { cacheRoot.deleteRecursively() }
    }

    /**
     * 按总大小淘汰：从最久未播放的开始删。
     *
     * 只删缓存目录里我们自己的拷贝，**绝不碰 B 站的原目录** ——
     * 那边是用户的离线缓存，删了等于在 B 站里重新下载一遍。
     */
    private fun trimCache() {
        val dirs = cacheRoot.listFiles()?.filter { it.isDirectory } ?: return
        var total = dirs.sumOf { dir -> dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
        if (total <= maxCacheBytes) return
        dirs.sortedBy { it.lastModified() }.forEach { dir ->
            if (total <= maxCacheBytes) return@forEach
            val size = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            if (dir.deleteRecursively()) total -= size
        }
    }

    private fun parseScanOutput(raw: String): List<BiliEntry> {
        val entries = mutableListOf<BiliEntry>()
        var header: String? = null
        val json = StringBuilder()

        fun flush() {
            val h = header ?: return
            if (json.isNotBlank()) {
                BiliEntryParser.parse(h, json.toString())?.let(entries::add)
            }
            header = null
            json.setLength(0)
        }

        raw.lineSequence().forEach { line ->
            if (line.startsWith("###")) {
                flush()
                header = line.removePrefix("###")
            } else if (header != null && line.isNotBlank()) {
                json.append(line)
            }
        }
        flush()

        // 同一个视频在 HD 版和手机版里各缓存一份时，按 key 去重
        return entries
            .distinctBy { it.key }
            .sortedByDescending { it.createdAt }
    }

    private companion object {
        /**
         * 容器方案的版本标记。**改动 [BiliMp4Writer] 的写出策略后必须升这个号**，
         * 否则用户已经存在的 `playable.mp4` 会被一直复用，修复永远到不了设备上。
         */
        const val MUX_VERSION = "v5-audiofix"

        /**
         * 一次扫完两个包名的缓存目录。
         *
         * 输出约定：`###<entry.json 路径>` 一行，紧跟 entry.json 原文（单行）。
         *
         * ⚠️ 这里刻意**不在 shell 里做任何额外处理**：早期版本每条都要 `sed` 取
         * type_tag、`ls` 列文件，300 个条目就是 900 次 fork，进页面要等 25 秒以上。
         * 现在路径全交给 Kotlin 按 `type_tag` 拼，shell 只负责 find + cat，
         * 同样一批缓存两秒左右扫完。
         */
        val SCAN_SCRIPT = """
            for pkg in tv.danmaku.bilibilihd tv.danmaku.bili; do
              root="/sdcard/Android/data/${'$'}pkg/download"
              [ -d "${'$'}root" ] || continue
              for f in $(find "${'$'}root" -maxdepth 3 -name entry.json 2>/dev/null); do
                echo "###${'$'}f"
                cat "${'$'}f" 2>/dev/null
                echo
              done
            done
        """.trimIndent()
    }
}
