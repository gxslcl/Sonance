package com.sonance.player.data.bili

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
 * 当前正在播放的缓存条目。
 *
 * 由播放页在落定页变化时写入、退出播放页时清空（同进程内共享）。
 * 删除流程据此**拒绝删除正在播放的那一条**：播放器正握着转存出来的
 * `playable.mp4`，页内还维护着上一首/下一首的播放列表，把底下的源文件抽走
 * 会让后续 seek、切页直接失败。提示「先退出播放页再删」是代价最小也最安全的做法。
 */
object BiliPlaybackState {
    @Volatile
    var activeKey: String? = null
}

/** 单条删除失败的原因，够上层拼出「哪几条、为什么」 */
data class BiliDeleteFailure(
    val key: String,
    val title: String,
    val reason: String
)

/** 一批删除的结果：成功的 key 和逐条失败原因，失败不影响其余条目 */
data class BiliDeleteReport(
    val deletedKeys: List<String> = emptyList(),
    val failures: List<BiliDeleteFailure> = emptyList()
) {
    val successCount: Int get() = deletedKeys.size
    val failureCount: Int get() = failures.size
}

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

    /** 扫描结果的本地副本（内部目录，几百 KB） */
    private val metaFile = File(appContext.cacheDir, "bili_entries.txt")

    /**
     * 产物目录是不是在**外部**私有目录。
     *
     * 只有外部目录 shell 才写得进去，也才能走 `cp` 那条快路 ——
     * 退化到内部目录（没插存储的机型）时只能退回管道复制。
     */
    private val shellCanWrite = appContext.externalCacheDir != null

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
                    lastEntries = entries
                    saveCache(entries)
                    BiliScanResult.Ok(entries)
                }
            }
        }
    }

    /**
     * 上次扫描结果，用于**进页面先出列表、再后台刷新**。
     *
     * 扫 300 多个条目要把每个 entry.json 都 `cat` 一遍，实测 2~8 秒。
     * 每次切到这一页都等这么久，体验上就是「点了没反应」。
     * 缓存落在内部目录（几百 KB），读出来几十毫秒。
     */
    fun loadCached(): List<BiliEntry>? = runCatching {
        if (!metaFile.exists()) return null
        val list = metaFile.readLines().mapNotNull { BiliEntryCodec.fromLine(it) }
        if (list.isEmpty()) return null
        if (lastEntries.isEmpty()) lastEntries = list
        list
    }.getOrNull()

    private fun saveCache(entries: List<BiliEntry>) {
        runCatching {
            val tmp = File(metaFile.parentFile, metaFile.name + ".tmp")
            tmp.writeText(entries.joinToString("\n") { BiliEntryCodec.toLine(it) } + "\n")
            if (tmp.renameTo(metaFile)) Unit else {
                // 有些机型 rename 跨不了，退一步直接覆盖写
                metaFile.writeText(entries.joinToString("\n") { BiliEntryCodec.toLine(it) } + "\n")
                tmp.delete()
            }
        }
    }

    /** 播放页按 key 取条目（上一首/下一首要用） */
    fun entryByKey(key: String): BiliEntry? = lastEntries.firstOrNull { it.key == key }

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

            // 两条轨**同时**起 cp。串行的话要等音频读完才开始读视频，
            // 哪怕单条很快，两个 400MB 加起来还是肉眼可见。
            var audioOk = false
            var videoOk = false
            coroutineScope {
                val audio = async(Dispatchers.IO) {
                    copyTrack(entry.audioPath.orEmpty(), audioFile) ||
                        // 老版本缓存把 m4s 直接放在 entry.json 旁边，同级再试一次
                        copyTrack("${entry.dir}/audio.m4s", audioFile)
                }
                val video = async(Dispatchers.IO) {
                    copyTrack(entry.videoPath, videoFile) ||
                        copyTrack("${entry.dir}/video.m4s", videoFile)
                }

                // cp 不报进度，轮询两个目标文件的大小折算
                val poller = launch(Dispatchers.IO) {
                    while (isActive) {
                        val bytes = runCatching {
                            audioFile.length() + videoFile.length()
                        }.getOrDefault(0L)
                        if (total > 0) {
                            onProgress((bytes.toFloat() / total * 0.8f).coerceIn(0f, 0.8f))
                        }
                        delay(150)
                    }
                }

                audioOk = audio.await()
                videoOk = video.await()
                poller.cancel()
            }

            if (!audioOk || !videoOk) {
                dir.deleteRecursively()
                error("缓存文件复制失败，可能已被 B 站清理")
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

    /**
     * 拷一条轨。
     *
     * 优先 shell 内部 `cp`（数据不经过我们的进程），目标目录 shell 写不进去
     * 时退回管道复制。两条路对上层等价，只是快慢不同。
     */
    private suspend fun copyTrack(srcPath: String, dst: File): Boolean =
        withContext(Dispatchers.IO) {
            if (srcPath.isBlank()) return@withContext false
            dst.parentFile?.mkdirs()
            if (shellCanWrite && ShizukuShell.copyLocal(srcPath, dst.absolutePath)) {
                dst.length() > 0
            } else {
                ShizukuShell.copyTo(srcPath, dst)
            }
        }

    /** 已缓存到本地的占用大小 */
    fun cachedBytes(): Long = cacheRoot.walkTopDown()
        .filter { it.isFile }
        .sumOf { it.length() }

    /**
     * 真正删掉若干条缓存（**不是只从列表移除**）。
     *
     * 每条按这个顺序处理，任何一条失败都不影响其余：
     * 1. 先删本应用转存的目录 `externalCacheDir/bili/<key>/` ——
     *    里面有 `playable.mp4`、完成标记 `.done`，以及上次合成中断留下的
     *    `video.m4s` / `audio.m4s`。先删它，失败就整体放弃这条（免得源目录删了、
     *    派生文件变成孤儿还不在列表里）；
     * 2. 再通过 Shizuku 以 shell 身份 `rm -rf` B 站原缓存目录（见 [deleteRemote]）；
     * 3. 只有两步都确认删干净了，才把 key 从内存索引和 [metaFile] 里摘掉。
     *
     * @param allEntries 当前列表的全部条目，用于判断目录是否被别的条目共享
     */
    suspend fun deleteEntries(
        targets: List<BiliEntry>,
        allEntries: List<BiliEntry>
    ): BiliDeleteReport = withContext(Dispatchers.IO) {
        val deleted = mutableListOf<String>()
        val failures = mutableListOf<BiliDeleteFailure>()

        targets.distinctBy { it.key }.forEach { entry ->
            if (BiliPlaybackState.activeKey == entry.key) {
                failures += BiliDeleteFailure(entry.key, entry.displayTitle, "正在播放，先退出播放页再删除")
                return@forEach
            }

            // 1) 派生缓存（我们自己的目录，直接删，不需要 shell）
            val localDir = File(cacheRoot, entry.key)
            localDir.deleteRecursively()
            if (localDir.exists()) {
                failures += BiliDeleteFailure(
                    entry.key, entry.displayTitle,
                    "应用内转存缓存清理失败：${localDir.absolutePath}"
                )
                return@forEach
            }

            // 2) B 站原缓存
            val remoteOk = runCatching { deleteRemote(entry, allEntries) }.getOrDefault(false)
            if (!remoteOk) {
                Log.w(TAG, "删除失败: key=${entry.key} dir=${entry.dir}")
                failures += BiliDeleteFailure(
                    entry.key, entry.displayTitle,
                    "B 站缓存目录未能完全删除（Shizuku 可能已失效，或文件被占用）"
                )
                return@forEach
            }

            Log.i(TAG, "已删除: key=${entry.key} dir=${entry.dir}")
            deleted += entry.key
        }

        if (deleted.isNotEmpty()) {
            lastEntries = lastEntries.filterNot { it.key in deleted }
            saveCache(lastEntries)
        }
        BiliDeleteReport(deleted, failures)
    }

    /**
     * 删 B 站原缓存目录。
     *
     * 常规情况下 `entry.dir` 就是这一分 P 的独立目录
     * （`.../download/<avid>/c_<cid>/`），里面是 entry.json、danmaku.xml、
     * 字幕/封面、`<type_tag>/video.m4s`、`<type_tag>/audio.m4s`、
     * index.json，以及下载中断留下的临时文件——整目录递归删除，不留孤儿。
     *
     * 极端情况：两条扫描结果落在同一个目录（同一份 entry.json 解析出多条），
     * 这时只能删本条目自己的清晰度子目录，`entry.json` / `danmaku.xml` 等
     * 共享文件必须留着；若连独立子目录都没有，就放弃并报失败。
     */
    private fun deleteRemote(entry: BiliEntry, allEntries: List<BiliEntry>): Boolean {
        val qualityDir = entry.videoPath.substringBeforeLast('/', "")
        val sharesDir = allEntries.any { it.key != entry.key && it.dir == entry.dir }
        if (sharesDir) {
            if (qualityDir.isBlank() || qualityDir == entry.dir) {
                Log.w(TAG, "目录被其他条目共享且没有独立清晰度子目录，放弃删除: ${entry.dir}")
                return false
            }
            return ShizukuShell.deleteTree(listOf(qualityDir))
        }
        return ShizukuShell.deleteTree(listOf(entry.dir))
    }

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
        /** logcat 过滤用：`adb logcat -s SonanceBili` */
        const val TAG = "SonanceBili"

        /**
         * 容器方案的版本标记。**改动 [BiliMp4Writer] 的写出策略后必须升这个号**，
         * 否则用户已经存在的 `playable.mp4` 会被一直复用，修复永远到不了设备上。
         */
        const val MUX_VERSION = "v5-audiofix"

        /**
         * 最近一次扫描到的条目，供播放页取上下首。
         *
         * 只在同一进程内共享，所以不需要跨进程序列化 —— 播放页和列表页
         * 本来就跑在一个进程里。空的话播放页拿不到上下首，退化成「单条播放」，
         * 不会崩。
         */
        @Volatile
        var lastEntries: List<BiliEntry> = emptyList()

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
