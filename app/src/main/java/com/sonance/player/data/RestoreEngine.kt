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
import java.io.InputStream
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

    /** 跳过计数，原因取第一个全角冒号之前的部分做归一化 */
    fun skip(reason: String) {
        skipped++
        val key = reason.substringBefore('：')
        skipReasons[key] = (skipReasons[key] ?: 0) + 1
    }

    fun fail(relPath: String, reason: String) {
        failed++
        if (failures.size < 20) failures += "$relPath — $reason"
    }
}

/**
 * 把酷狗私有容器（KGM / KGMA / VPR）批量还原为可播放的 flac / mp3。
 *
 * 与 [UnplayableFileScanner] 一样走 SAF：从用户授权的 sourceTree 递归收集文件，
 * 还原结果按相对路径镜像写入 outputTree。整条管线流式处理，不会把整个文件读进内存；
 * dryRun 时只读首个数据块用来嗅探格式，不写盘。
 *
 * 算法见 [PrivateAudioRestorer]：密钥来自每个文件自己的头部，无需用户配置。
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
            dryRun = dryRun
        )
    }

    /** 处理单个文件：读头 -> 校验版本 -> 流式解密 -> （可选）写盘。失败抛异常由外层统计。 */
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
        if (size < PrivateAudioRestorer.HEADER_MIN) {
            stats.skip("文件过小")
            return
        }

        val input = context.contentResolver.openInputStream(doc.uri)
            ?: throw IOException("无法打开输入流")
        input.use { stream ->
            // 只读 44 字节头部，不整读文件
            val header = ByteArray(PrivateAudioRestorer.HEADER_MIN)
            if (!readFully(stream, header, header.size)) throw IOException("读取头部失败")

            val parsed = PrivateAudioRestorer.parseHeader(header)
            if (parsed == null) {
                stats.skip("非受支持的加密容器")
                return
            }
            if (parsed.version != PrivateAudioRestorer.SUPPORTED_VERSION) {
                stats.skip("加密版本 ${parsed.version} 不支持")
                return
            }
            if (parsed.payloadOffset > size) {
                stats.skip("载荷偏移非法")
                return
            }

            val payloadSize = size - parsed.payloadOffset
            skipFully(stream, (parsed.payloadOffset - PrivateAudioRestorer.HEADER_MIN).toLong())

            // 先读第一块用于嗅探格式
            val buffer = ByteArray(CHUNK_SIZE)
            val firstLen = minOf(payloadSize, CHUNK_SIZE.toLong()).toInt()
            if (!readFully(stream, buffer, firstLen)) throw IOException("读取数据失败")
            PrivateAudioRestorer.xorChunk(buffer, firstLen, 0, parsed.key, parsed.isVpr)

            val ext = PrivateAudioRestorer.sniffExtension(buffer)
            // 剥掉加密容器标记（.kgm/.kgma/.vpr/.ncm/…），只按真实内容定后缀
            val outName = PrivateAudioRestorer.outputStem(name) + ext

            if (dryRun) {
                // 预演：只统计，不建目录不写文件
                stats.converted++
                if (ext == ".flac") stats.flacCount++ else stats.mp3Count++
                stats.bytesIn += firstLen
                return
            }

            val outRoot = outputRoot ?: throw IOException("输出文件夹不可用")
            val dirPath = relPath.substringBeforeLast('/', "")
            val outRel = if (dirPath.isEmpty()) outName else "$dirPath/$outName"

            // 已经转换过：输出目录里已有同名文件、且大小与本次要写的载荷一致 -> 跳过。
            // 这是「重跑一遍不再重复转换」的关键，否则会一直堆出 "X (1).mp3"。
            val prior = existingOutputs[outRel]
            if (prior != null && prior.size == payloadSize) {
                stats.skip("已转换过")
                return
            }

            val parent = resolveDir(outRoot, dirPath, dirCache)
                ?: throw IOException("创建目录失败")
            val mime = if (ext == ".flac") "audio/flac" else "audio/mpeg"
            // 同名但大小对不上 = 上次中途取消留下的半截文件：复用同一个 Uri 覆盖重写，
            // 而不是再 createDocument 出一个 "X (1).mp3"
            val newUri = prior?.uri
                ?: DocumentsContract.createDocument(context.contentResolver, parent.uri, mime, outName)
                ?: throw IOException("创建文件失败")
            existingOutputs[outRel] = OutputFile(newUri, payloadSize)
            val output = context.contentResolver.openOutputStream(newUri, "wt")
                ?: throw IOException("打开输出流失败")

            output.use { out ->
                out.write(buffer, 0, firstLen)
                stats.bytesOut += firstLen

                // 继续流式处理剩余块；pos 必须跨块连续递增，掩码才接得上
                var pos = firstLen
                var remaining = payloadSize - firstLen
                while (remaining > 0) {
                    coroutineContext.ensureActive()
                    val want = minOf(remaining, CHUNK_SIZE.toLong()).toInt()
                    if (!readFully(stream, buffer, want)) throw IOException("读取数据失败")
                    PrivateAudioRestorer.xorChunk(buffer, want, pos, parsed.key, parsed.isVpr)
                    out.write(buffer, 0, want)
                    stats.bytesOut += want
                    pos += want
                    remaining -= want
                }
            }

            stats.converted++
            if (ext == ".flac") stats.flacCount++ else stats.mp3Count++
            stats.bytesIn += payloadSize
        }
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

    /** 读满 buffer 的前 count 字节；提前 EOF 返回 false */
    private fun readFully(input: InputStream, buffer: ByteArray, count: Int): Boolean {
        var off = 0
        while (off < count) {
            val n = input.read(buffer, off, count - off)
            if (n < 0) return false
            off += n
        }
        return true
    }

    /** 跳过 count 字节；skip 不生效时退化为逐字节读 */
    private fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                if (input.read() < 0) return
                remaining--
            }
        }
    }

    /** 输出目录里已存在的一个成品文件：Uri + 载荷大小，用于判断「是否已转换过」 */
    private data class OutputFile(val uri: Uri, val size: Long)

    companion object {
        private const val MAX_FILES = 40_000
        private const val MAX_FOLDERS = 4_000
        private const val CHUNK_SIZE = 64 * 1024
    }
}
