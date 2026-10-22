package com.sonance.player.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.coroutines.coroutineContext

/** 一次批量还原的结果汇总 */
data class RestoreResult(
    val converted: Int,
    val skipped: Int,
    val failed: Int,
    val flacCount: Int,
    val mp3Count: Int,
    val bytesIn: Long,
    val bytesOut: Long,
    val totalFiles: Int,
    val failures: List<String>,
    val skipReasons: Map<String, Int>,
    /** 被跳过的文件样本（最多 20 个），用于让用户看清「到底哪些没处理」 */
    val skippedSamples: List<String>,
    val dryRun: Boolean
)

/** 还原过程中的可变统计 */
private class RestoreStats {
    var converted = 0
    var skipped = 0
    var failed = 0
    var flacCount = 0
    var mp3Count = 0
    var bytesIn = 0L
    var bytesOut = 0L
    val failures = mutableListOf<String>()
    val skipReasons = mutableMapOf<String, Int>()
    val skippedSamples = mutableListOf<String>()

    /** 跳过计数，原因取第一个全角冒号之前的部分做归一化 */
    fun skip(reason: String, name: String? = null) {
        skipped++
        val key = reason.substringBefore('：')
        skipReasons[key] = (skipReasons[key] ?: 0) + 1
        if (name != null && skippedSamples.size < 20) skippedSamples += "$name — $reason"
    }

    fun fail(relPath: String, reason: String) {
        failed++
        if (failures.size < 20) failures += "$relPath — $reason"
    }
}

/**
 * 把私有加密容器批量还原为可播放的标准音频。
 *
 * 支持的格式由 [ContainerRestorer] 决定（目前是酷狗 KGM/KGMA/KGG/VPR、
 * 网易云 NCM、酷我 KWM），算法与密钥全部内置 / 从文件头自读取，用户无需配置。
 *
 * 与 [UnplayableFileScanner] 一样走 SAF：从用户授权的 sourceTree 递归收集文件，
 * 还原结果按相对路径镜像写入 outputTree。整条管线流式处理，不会把整个文件读进内存。
 *
 * 本类只负责**跟 Android 打交道**的部分（SAF 遍历、建目录、开输出流、统计）；
 * 真正的「探测 → 解密 → 嗅探 → 组标签 → 泵数据」在 [RestorePlanner] / [RestorePlan] 里，
 * 那部分是纯 JVM 逻辑，有单测覆盖。
 */
class RestoreEngine(private val context: Context) {

    /**
     * 递归遍历 sourceTree，逐个还原并写入 outputTree（保持相对路径）。
     * dryRun = true 时不写盘，只统计。
     */
    suspend fun restore(
        sourceTree: Uri,
        outputTree: Uri?,
        dryRun: Boolean,
        onProgress: (done: Int, total: Int, current: String) -> Unit = { _, _, _ -> }
    ): RestoreResult = withContext(Dispatchers.IO) {

        val stats = RestoreStats()

        // 输出根目录解析失败 -> 整体失败，返回一个带失败记录的零结果
        val outputRoot: DocumentFile? = if (dryRun) {
            null
        } else {
            outputTree?.let { DocumentFile.fromTreeUri(context, it) }
        }
        if (!dryRun && outputRoot == null) {
            return@withContext RestoreResult(
                converted = 0, skipped = 0, failed = 1,
                flacCount = 0, mp3Count = 0, bytesIn = 0L, bytesOut = 0L,
                totalFiles = 0,
                failures = listOf("输出文件夹无法访问"),
                skipReasons = emptyMap(),
                skippedSamples = emptyList(),
                dryRun = dryRun
            )
        }

        val root = DocumentFile.fromTreeUri(context, sourceTree)
            ?: return@withContext RestoreResult(
                converted = 0, skipped = 0, failed = 1,
                flacCount = 0, mp3Count = 0, bytesIn = 0L, bytesOut = 0L,
                totalFiles = 0,
                failures = listOf("源文件夹无法访问"),
                skipReasons = emptyMap(),
                skippedSamples = emptyList(),
                dryRun = dryRun
            )

        // 第一阶段：DFS 收集全部文件（相对路径），排序保证顺序确定
        val files = mutableListOf<Pair<DocumentFile, String>>()
        var visited = 0
        val stack = ArrayDeque<Pair<DocumentFile, String>>()
        stack.addLast(root to "")

        while (stack.isNotEmpty()) {
            coroutineContext.ensureActive()

            if (visited >= MAX_FILES) break

            val (dir, path) = stack.removeLast()
            val children = runCatching { dir.listFiles() }.getOrNull() ?: continue
            for (child in children) {
                visited++
                if (visited >= MAX_FILES) break
                val name = child.name ?: continue
                if (child.isDirectory) {
                    if (stack.size < MAX_FOLDERS) {
                        stack.addLast(child to if (path.isEmpty()) name else "$path/$name")
                    }
                    continue
                }
                files += child to if (path.isEmpty()) name else "$path/$name"
            }
        }
        files.sortBy { it.second }

        val total = files.size
        onProgress(0, total, "")

        // 相对目录路径 -> DocumentFile 缓存（"" = 输出根）
        val dirCache = HashMap<String, DocumentFile>()
        outputRoot?.let { dirCache[""] = it }

        // 输出目录里已有的文件。用来跳过「已经转换过」的源文件 —— 否则重复跑一次
        // 会在输出目录里堆出 "X (1).mp3"、"X (2).mp3" 这种重复成品。
        val existingOutputs: MutableMap<String, OutputFile> =
            if (outputRoot == null) HashMap() else indexOutputFiles(outputRoot)

        var done = 0
        for ((doc, relPath) in files) {
            coroutineContext.ensureActive()
            val name = relPath.substringAfterLast('/')
            try {
                processOne(doc, name, relPath, dryRun, outputRoot, dirCache, existingOutputs, stats)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                stats.fail(relPath, e.message ?: "未知错误")
            }
            done++
            onProgress(done, total, relPath)
        }

        RestoreResult(
            converted = stats.converted,
            skipped = stats.skipped,
            failed = stats.failed,
            flacCount = stats.flacCount,
            mp3Count = stats.mp3Count,
            bytesIn = stats.bytesIn,
            bytesOut = stats.bytesOut,
            totalFiles = total,
            failures = stats.failures.toList(),
            skipReasons = stats.skipReasons.toMap(),
            skippedSamples = stats.skippedSamples.toList(),
            dryRun = dryRun
        )
    }

    /** 处理单个文件：探测 -> 决定是否写 -> 泵数据。失败抛异常由外层统计。 */
    private suspend fun processOne(
        doc: DocumentFile,
        name: String,
        relPath: String,
        dryRun: Boolean,
        outputRoot: DocumentFile?,
        dirCache: MutableMap<String, DocumentFile>,
        existingOutputs: MutableMap<String, OutputFile>,
        stats: RestoreStats
    ) {
        val size = doc.length()
        val input = context.contentResolver.openInputStream(doc.uri)
            ?: throw IOException("无法打开输入流")
        val scopeContext = coroutineContext

        // QMC 没有魔数、密钥挂在尾部，探测前要先单独读一次尾部窗口
        val qmcTail = if (QmcDecoder.looksLikeName(name)) readTail(doc.uri, size) else null

        input.use { stream ->
            val probe = RestorePlanner.plan(stream, size, name, qmcTail)
            if (probe is RestoreProbe.Skip) {
                stats.skip(probe.reason, relPath)
                return
            }
            val plan = (probe as RestoreProbe.Ready).plan

            if (dryRun) {
                // 预演：只统计，不建目录不写文件
                stats.converted++
                if (plan.ext == ".flac") stats.flacCount++ else stats.mp3Count++
                stats.bytesIn += plan.payloadSize
                return
            }

            val outRoot = outputRoot ?: throw IOException("输出文件夹不可用")
            val dirPath = relPath.substringBeforeLast('/', "")
            val outRel = if (dirPath.isEmpty()) plan.outName else "$dirPath/${plan.outName}"

            // 已经转换过：输出目录里已有同名文件、且大小与本次要写的一致 -> 跳过。
            // 这是「重跑一遍不再重复转换」的关键，否则会一直堆出 "X (1).mp3"。
            val prior = existingOutputs[outRel]
            if (prior != null && prior.size == plan.expectedSize) {
                stats.skip("已转换过")
                return
            }

            val parent = resolveDir(outRoot, dirPath, dirCache)
                ?: throw IOException("创建目录失败")
            // 同名但大小对不上 = 上次中途取消留下的半截文件：复用同一个 Uri 覆盖重写，
            // 而不是再 createDocument 出一个 "X (1).mp3"
            val newUri = prior?.uri
                ?: DocumentsContract.createDocument(
                    context.contentResolver, parent.uri, mimeOf(plan.ext), plan.outName
                )
                ?: throw IOException("创建文件失败")
            existingOutputs[outRel] = OutputFile(newUri, plan.expectedSize)
            val output = context.contentResolver.openOutputStream(newUri, "wt")
                ?: throw IOException("打开输出流失败")

            output.use { out -> plan.writeTo(out) { scopeContext.ensureActive() } }

            // 封面落进缓存：刚还原出来的歌立刻有封面，不用等联网匹配
            storeCover(plan.info, plan.cover)

            stats.converted++
            if (plan.ext == ".flac") stats.flacCount++ else stats.mp3Count++
            stats.bytesIn += plan.payloadSize
            stats.bytesOut += plan.written
        }
    }

    /** 把容器里带的封面存进 [CoverCache]，键与在线封面一致，播放管线会自动捡起来 */
    private fun storeCover(info: HeaderInfo, cover: ByteArray?) {
        if (cover == null || cover.isEmpty()) return
        val title = info.title
        val artist = info.artist
        if (title.isBlank() && artist.isBlank()) return
        val key = OnlineCover.keyOf(title, artist) ?: return
        runCatching { CoverCache.store(context, key, cover) }
    }

    private fun mimeOf(ext: String): String = when (ext) {
        ".flac" -> "audio/flac"
        ".ogg" -> "audio/ogg"
        ".m4a" -> "audio/mp4"
        ".wav" -> "audio/wav"
        else -> "audio/mpeg"
    }

    /**
     * 递归收集输出目录里已有的文件（相对路径 -> uri/大小）。
     *
     * 一次遍历，换来后面每个源文件 O(1) 的「是否已转换过」判断 ——
     * 逐个 `findFile` 会是 O(n²) 的目录扫描，几千个文件时非常慢。
     */
    private fun indexOutputFiles(root: DocumentFile): MutableMap<String, OutputFile> {
        val out = HashMap<String, OutputFile>()
        val stack = ArrayDeque<Pair<DocumentFile, String>>()
        stack.addLast(root to "")
        var visited = 0

        while (stack.isNotEmpty()) {
            if (visited >= MAX_FILES) break
            val (dir, path) = stack.removeLast()
            val children = runCatching { dir.listFiles() }.getOrNull() ?: continue
            for (child in children) {
                visited++
                if (visited >= MAX_FILES) break
                val name = child.name ?: continue
                val childPath = if (path.isEmpty()) name else "$path/$name"
                if (child.isDirectory) {
                    if (stack.size < MAX_FOLDERS) stack.addLast(child to childPath)
                } else {
                    out[childPath] = OutputFile(child.uri, child.length())
                }
            }
        }
        return out
    }

    /** 逐级解析 / 创建输出目录链，结果缓存到 dirCache */
    private fun resolveDir(
        root: DocumentFile,
        dirPath: String,
        cache: MutableMap<String, DocumentFile>
    ): DocumentFile? {
        cache[dirPath]?.let { return it }
        var current = root
        var acc = ""
        val parts = dirPath.split('/').filter { it.isNotEmpty() }
        for (part in parts) {
            val key = if (acc.isEmpty()) part else "$acc/$part"
            acc = key
            val cached = cache[key]
            current = cached ?: run {
                val dir = current.findFile(part) ?: current.createDirectory(part)
                if (dir == null) return null
                cache[key] = dir
                dir
            }
        }
        return current
    }

    /** 输出目录里已存在的一个成品文件：Uri + 大小，用于判断「是否已转换过」 */
    private data class OutputFile(val uri: Uri, val size: Long)

    /**
     * 读文件末尾的 [QmcDecoder.TAIL_WINDOW] 字节（不足取全部）。
     *
     * 为什么要单独开一条流：探测用的输入流是顺序读的，已经消费了头部；
     * 而 QMC 的密钥在尾部。SAF 的流基于文件描述符，skip 是真 seek，开销可忽略。
     * 读取失败返回 null —— 上层会让这个文件带着准确原因跳过，而不是整批失败。
     */
    private fun readTail(uri: Uri, size: Long): ByteArray? {
        if (size < 8) return null
        return try {
            val len = minOf(QmcDecoder.TAIL_WINDOW.toLong(), size).toInt()
            val input = context.contentResolver.openInputStream(uri) ?: return null
            input.use { s ->
                var remaining = size - len
                while (remaining > 0) {
                    val n = s.skip(remaining)
                    if (n > 0) {
                        remaining -= n
                    } else {
                        if (s.read() < 0) return@use null
                        remaining--
                    }
                }
                val buf = ByteArray(len)
                var off = 0
                while (off < len) {
                    val n = s.read(buf, off, len - off)
                    if (n < 0) return@use null
                    off += n
                }
                buf
            }
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val MAX_FILES = 40_000
        private const val MAX_FOLDERS = 4_000
    }
}

