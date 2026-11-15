package com.sonance.player.data.netease

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.sonance.player.data.bili.ShizukuShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/** 扫描结果。区分「没装 Shizuku」「装了但没运行」「没授权」——三者给的引导完全不同 */
sealed interface NeteaseScanResult {
    data class Ok(val entries: List<NeteaseCacheEntry>) : NeteaseScanResult
    data object NoShizuku : NeteaseScanResult
    data object NotRunning : NeteaseScanResult
    data object NoPermission : NeteaseScanResult
    data class Failed(val message: String) : NeteaseScanResult
}

/** 单条转换的结果 */
sealed interface NcConvertOutcome {
    /** 转换成功；outName 是写进输出文件夹的成品名 */
    data class Done(val outName: String, val ext: String, val bytesOut: Long) : NcConvertOutcome

    /** 有意跳过（已转换过 / 无法识别 / 试听残曲 / 缓存不完整 / 内容不是音频） */
    data class Skipped(
        val reason: String,
        val trial: Boolean = false,
        /** 跳过里属于「缓存不完整」（块没下完，播放到一半会报 2008）的数量单独统计 */
        val incomplete: Boolean = false
    ) : NcConvertOutcome

    /** 出错 */
    data class Failed(val reason: String) : NcConvertOutcome
}

/** 一次批量转换的汇总 */
data class NcConvertSummary(
    val converted: Int,
    val skipped: Int,
    val failed: Int,
    /** 转换成功的条目 key，供列表打「已转换」标记 */
    val doneKeys: List<String>,
    val outputs: List<String>,
    val failures: List<String>,
    /** 跳过里属于「试听残曲」的数量（单独展示，和已转换过 / 无法识别区分开） */
    val trials: Int = 0,
    /** 跳过里属于「缓存不完整」的数量（块没下完，转换前过滤，播到一半会报 2008） */
    val incomplete: Int = 0
)

/**
 * 网易云音乐本机缓存的嗅探与转换入口。
 *
 * 与 B 站模块同一套路：所有操作都通过 shell 完成（见 [com.sonance.player.data.bili.ShizukuShell]），
 * 解密与转换全部在本机完成、不碰账号；唯一的联网动作是 NMSF 转换时
 * 按缓存文件名里的歌曲 ID 调公开接口补全歌名 / 歌手 / 专辑 / 封面
 * （见 [NcMetaFetcher]），查询失败自动退回无标签转换。
 *
 * 三步走：
 * ① [scan] —— 一条 shell 把所选目标 App（网易云 / 酷狗 / QQ 音乐 / 汽水 / 波点，
 *    见 [SniffApp]）候选根目录下的大文件（>256KB）连同**头 12 字节魔数**
 *    一起报回来，Kotlin 侧按魔数 / 扩展名分类、按「同目录同前缀-片号」归并分片组；
 * ② 拉取 —— 优先 shell 内部 `cp`（~250MB/s），不可用退回 stdout 管道；
 *    分片逐片拷回本地再拼成完整文件；
 * ③ [convertAll] —— 加密容器（NCM/NMSF/KGM/QMC）走 [RestorePlanner] 解密管线
 *    （与「私有格式还原」同一套代码），明文文件直接流复制，
 *    成品统一写进用户选的输出文件夹；汽水 MDL 无公开解密方案，只识别不转换。
 */
class NeteaseCacheRepository(context: Context) {

    private val appContext = context.applicationContext

    /** 歌曲详情的本次运行缓存：同一批缓存里同一首歌不会查两次（转换失败的 key 会在下次重查） */
    private val metaCache = HashMap<String, NcMeta?>()

    /** 拉取的中间产物放外部私有目录：几百 MB 的文件、shell 才写得进去（见 B 站模块同名注释） */
    private val pullRoot = File(
        appContext.externalCacheDir ?: appContext.cacheDir,
        "netease_cache"
    )

    /** 扫描结果的本地副本（内部目录，几十 KB） */
    private val metaFile = File(appContext.cacheDir, "netease_cache_entries.txt")

    /**
     * 已成功转换的记录：源条目 key -> 成品名（内部目录持久化）。
     * 增量转换的依据：下次转换时这些源直接跳过，只处理新出现的缓存；
     * 成品名同时用来核对输出目录里成品是否还在（被用户删掉就会重新转换）。
     */
    private val doneFile = File(appContext.filesDir, "netease_converted.txt")

    /** 产物目录是否在外部私有目录 —— 决定 shell `cp` 快路是否可用 */
    private val shellCanWrite = appContext.externalCacheDir != null

    suspend fun scan(apps: Set<SniffApp>): NeteaseScanResult = withContext(Dispatchers.IO) {
        when {
            !ShizukuShell.isInstalled(appContext) -> NeteaseScanResult.NoShizuku
            !ShizukuShell.isRunning() -> NeteaseScanResult.NotRunning
            !ShizukuShell.hasPermission() -> NeteaseScanResult.NoPermission
            apps.isEmpty() -> NeteaseScanResult.Failed("请先选择至少一个嗅探目标")
            else -> {
                val raw = ShizukuShell.exec(buildScanScript(apps))
                    ?: return@withContext NeteaseScanResult.Failed("读取缓存目录失败")
                // 疑似试听残曲在扫描阶段直接过滤：不进列表、不写缓存副本、不参与转换
                //（转换时的时长裁决仍保留，兜住大小预判漏掉的漏网之鱼）
                val entries = NeteaseCacheParser.buildEntriesFromScan(raw)
                    .filterNot { it.suspectTrial }
                saveCache(entries)
                NeteaseScanResult.Ok(entries)
            }
        }
    }

    /** 上次扫描结果，进设置页先显示、不用每次都等几秒的 shell 扫描（旧副本里的残曲条目一并滤掉） */
    fun loadCached(): List<NeteaseCacheEntry>? = runCatching {
        if (!metaFile.exists()) return null
        val list = metaFile.readLines().mapNotNull { NeteaseCacheCodec.fromLine(it) }
            .filterNot { it.suspectTrial }
        if (list.isEmpty()) return null
        list
    }.getOrNull()

    /** 清掉扫描列表：删除本地缓存副本。只影响列表展示，不动已转换记录与输出成品 */
    fun clearCached() {
        runCatching { metaFile.delete() }
    }

    private fun saveCache(entries: List<NeteaseCacheEntry>) {
        runCatching {
            val text = entries.joinToString("\n") { NeteaseCacheCodec.toLine(it) } + "\n"
            val tmp = File(metaFile.parentFile, metaFile.name + ".tmp")
            tmp.writeText(text)
            if (tmp.renameTo(metaFile)) Unit else {
                metaFile.writeText(text)
                tmp.delete()
            }
        }
    }

    /** 已转换记录，key -> 成品名；给设置页列表打「已转换」标记用 */
    fun loadDoneMap(): Map<String, String> = runCatching {
        if (!doneFile.exists()) return@runCatching emptyMap()
        doneFile.readLines().mapNotNull { line ->
            val i = line.indexOf('\t')
            if (i <= 0) null else line.substring(0, i) to line.substring(i + 1)
        }.toMap()
    }.getOrDefault(emptyMap())

    private fun saveDoneMap(map: Map<String, String>) {
        runCatching {
            val text = map.entries.joinToString("\n") { "${it.key}\t${it.value}" } + "\n"
            val tmp = File(doneFile.parentFile, doneFile.name + ".tmp")
            tmp.writeText(text)
            if (tmp.renameTo(doneFile)) Unit else {
                doneFile.writeText(text)
                tmp.delete()
            }
        }
    }

    /**
     * 批量转换（**增量**）：上次已成功转换、且成品仍在输出目录里的源条目
     * 在拉源之前直接跳过（不拉源、不解密），只会真正处理新出现的缓存。
     * 写盘与取消逻辑与 [com.sonance.player.data.RestoreEngine] 对齐：
     * 输出目录里已有同名且同大小的成品 → 跳过；同名不同大小 → 覆盖重写。
     *
     * @param onProgress (done, total, 当前条目显示名)
     */
    suspend fun convertAll(
        entries: List<NeteaseCacheEntry>,
        outputTree: Uri,
        onProgress: (Int, Int, String) -> Unit = { _, _, _ -> }
    ): NcConvertSummary = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(appContext, outputTree)
            ?: return@withContext NcConvertSummary(
                converted = 0, skipped = 0, failed = 1,
                doneKeys = emptyList(), outputs = emptyList(),
                failures = listOf("输出文件夹无法访问")
            )

        val existing = indexOutputs(root)
        val doneMap = loadDoneMap().toMutableMap()
        var converted = 0
        var skipped = 0
        var trials = 0
        var incomplete = 0
        var failed = 0
        val doneKeys = mutableListOf<String>()
        val outputs = mutableListOf<String>()
        val failures = mutableListOf<String>()

        entries.forEachIndexed { index, entry ->
            coroutineContext.ensureActive()
            onProgress(index, entries.size, entry.displayName)
            // 增量跳过：这条源转过且成品还在输出目录里 → 不拉源不解密，直接过。
            // 成品已被用户删掉时（名字不在 existing 里）会重新转换补回
            val knownOut = doneMap[entry.key]
            if (knownOut != null && existing.containsKey(knownOut)) {
                skipped++
                onProgress(index + 1, entries.size, entry.displayName)
                return@forEachIndexed
            }
            val outcome = runCatching { convertOne(entry, root, existing, doneMap) }
                .getOrElse { NcConvertOutcome.Failed(it.message ?: "未知错误") }
            when (outcome) {
                is NcConvertOutcome.Done -> {
                    converted++
                    doneKeys += entry.key
                    outputs += outcome.outName
                    doneMap[entry.key] = outcome.outName
                }
                is NcConvertOutcome.Skipped -> {
                    skipped++
                    if (outcome.trial) trials++
                    if (outcome.incomplete) incomplete++
                }
                is NcConvertOutcome.Failed -> {
                    failed++
                    if (failures.size < 20) failures += "${entry.displayName} — ${outcome.reason}"
                }
            }
            onProgress(index + 1, entries.size, entry.displayName)
        }
        saveDoneMap(doneMap)

        NcConvertSummary(converted, skipped, failed, doneKeys, outputs, failures, trials, incomplete)
    }

    /** 单条转换：拉到本地 → 按类别分派。中间产物无论成败都清掉 */
    private suspend fun convertOne(
        entry: NeteaseCacheEntry,
        root: DocumentFile,
        existing: MutableMap<String, Pair<Uri, Long>>,
        doneMap: MutableMap<String, String>
    ): NcConvertOutcome {
        val workDir = File(pullRoot, workKey(entry.key))
        try {
            workDir.deleteRecursively()
            workDir.mkdirs()
            val src = pullEntry(entry, workDir)
            val containerExt = NcCacheDecodeSupport.containerExtFor(entry)
            return when (entry.kind) {
                NcKind.NCM, NcKind.NMS, NcKind.KGM ->
                    convertEncrypted(src, entry, root, existing, doneMap, containerExt)
                NcKind.QMC -> convertEncrypted(
                    src, entry, root, existing, doneMap,
                    containerExt,
                    tail = NcCacheDecodeSupport.readTail(src)
                )
                NcKind.MDL -> NcConvertOutcome.Skipped("汽水 MDL 加密暂无公开解密方案")
                NcKind.QQCACHE -> NcConvertOutcome.Skipped(
                    "QQ 在线播放缓存为分块仓库，音频密钥由服务端下发，无法本地解密（只有「下载」的歌可转换）"
                )
                NcKind.PLAIN -> convertPlain(src, entry, root, existing, doneMap)
                NcKind.UNKNOWN -> NcConvertOutcome.Skipped("无法识别")
            }
        } finally {
            workDir.deleteRecursively()
        }
    }

    /**
     * 把条目对应的**完整源文件**拉到本地：单文件直接拷；分片组逐片拷回后拼接。
     *
     * NCM 是自包含容器，直接走解密管线；明文分片拼完才是合法音频，
     * 边拷边喂给解码器是不行的（SAF 输入流也不支持分段）
     */
    private suspend fun pullEntry(entry: NeteaseCacheEntry, workDir: File): File {
        val dst = File(workDir, "src.bin")
        if (entry.fragments.size == 1) {
            copyFromShell(entry.fragments[0], dst)
        } else {
            val parts = entry.fragments.mapIndexed { i, _ -> File(workDir, "frag_$i") }
            entry.fragments.forEachIndexed { i, remote -> copyFromShell(remote, parts[i]) }
            dst.outputStream().use { out ->
                parts.forEach { part -> part.inputStream().use { it.copyTo(out) } }
            }
            parts.forEach { it.delete() }
        }
        if (dst.length() <= 0L) error("缓存文件拉取失败，可能已被网易云音乐清理")
        return dst
    }

    private suspend fun copyFromShell(srcPath: String, dst: File) = withContext(Dispatchers.IO) {
        dst.parentFile?.mkdirs()
        dst.delete()
        val ok = if (shellCanWrite && ShizukuShell.copyLocal(srcPath, dst.absolutePath)) {
            dst.length() > 0
        } else {
            ShizukuShell.copyTo(srcPath, dst)
        }
        if (!ok) error("复制缓存文件失败：$srcPath")
    }

    /** 加密容器（NCM / NMSF / KGM / QMC）：委托共用管线解密补全并写入 SAF 输出文件夹 */
    private suspend fun convertEncrypted(
        src: File,
        entry: NeteaseCacheEntry,
        root: DocumentFile,
        existing: MutableMap<String, Pair<Uri, Long>>,
        doneMap: MutableMap<String, String>,
        containerExt: String,
        tail: ByteArray? = null
    ): NcConvertOutcome {
        val dec = File(src.parentFile, "dec.bin")
        val prepared = NcCacheDecodeSupport.prepareEncrypted(
            context = appContext,
            src = src,
            decFile = dec,
            displayName = entry.displayName,
            containerExt = containerExt,
            tail = tail,
            songId = NeteaseCacheParser.songIdOf(entry),
            metaCache = metaCache,
            scopeContext = coroutineContext,
            legacyShouldSkip = { outName, expectedSize, enrichable ->
                val prior = existing[outName]
                if (prior != null && prior.second == expectedSize && !enrichable) {
                    doneMap[entry.key] = outName
                    true
                } else false
            },
            finalShouldSkip = { outName, finalSize ->
                val prior = existing[outName]
                if (prior != null && prior.second == finalSize) {
                    doneMap[entry.key] = outName
                    true
                } else false
            }
        )
        return writePreparedToSaf(prepared, root, existing)
    }

    /** 明文：先验时长与完整性，再复制到输出文件夹。 */
    private suspend fun convertPlain(
        src: File,
        entry: NeteaseCacheEntry,
        root: DocumentFile,
        existing: MutableMap<String, Pair<Uri, Long>>,
        doneMap: MutableMap<String, String>
    ): NcConvertOutcome {
        val prepared = NcCacheDecodeSupport.preparePlain(
            context = appContext,
            src = src,
            displayName = entry.displayName,
            plainExt = entry.plainExt,
            songId = null,
            metaCache = metaCache,
            shouldSkip = { outName, sizeBytes ->
                val prior = existing[outName]
                if (prior != null && prior.second == sizeBytes) {
                    doneMap[entry.key] = outName
                    true
                } else false
            }
        )
        return writePreparedToSaf(prepared, root, existing)
    }

    private fun writePreparedToSaf(
        prepared: NcCacheDecodeSupport.PreparedAudio,
        root: DocumentFile,
        existing: MutableMap<String, Pair<Uri, Long>>
    ): NcConvertOutcome {
        when (prepared) {
            is NcCacheDecodeSupport.PreparedAudio.Skipped ->
                return NcConvertOutcome.Skipped(prepared.reason, trial = prepared.trial, incomplete = prepared.incomplete)
            is NcCacheDecodeSupport.PreparedAudio.Ready -> Unit
        }
        val dec = prepared.tempFile
        try {
            val prior = existing[prepared.outName]
            val uri = prior?.first
                ?: DocumentsContract.createDocument(
                    appContext.contentResolver,
                    root.uri,
                    NcCacheDecodeSupport.mimeOf(prepared.ext),
                    prepared.outName
                )
                ?: return NcConvertOutcome.Failed("创建输出文件失败")
            existing[prepared.outName] = uri to prepared.finalSize
            appContext.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                dec.inputStream().use { it.copyTo(out) }
            } ?: return NcConvertOutcome.Failed("打开输出流失败")
            return NcConvertOutcome.Done(prepared.outName, prepared.ext, prepared.finalSize)
        } finally {
            if (dec.name == "dec.bin") dec.delete()
        }
    }

    /** 输出根目录（平铺）里已有的成品：名字 -> (uri, 大小)，用来跳过已转换项 */
    private fun indexOutputs(root: DocumentFile): MutableMap<String, Pair<Uri, Long>> {
        val map = HashMap<String, Pair<Uri, Long>>()
        runCatching { root.listFiles() }.getOrNull()?.forEach { f ->
            val name = f.name ?: return@forEach
            if (!f.isDirectory) map[name] = f.uri to f.length()
        }
        return map
    }

    private fun workKey(key: String): String =
        Integer.toHexString(key.hashCode()) + "-" + key.length

    companion object {
        /**
         * 按所选目标 App 生成扫描脚本：把每个 App 的候选根目录拼进同一条
         * `for root in …` 循环（目录不存在会被 `[ -d ]` 跳过）。
         *
         * 只扫各 App 的 cache / files 与公共下载目录：各 App 的「下载」目录里
         * 标准音频不需要还原，混进来反而添乱。
         *
         * 输出约定：每行 `<大小>\t<路径>\t<头12字节hex>`（12 字节是为了区分
         * RIFF 容器里的 wav 与 WEBP 封面图），路径放中间列
         * 防止带空格 / 引号的文件名错位；`while read` 而不是 `for f in $(find)`
         * 同样是为了空格安全。find + od 逐文件 fork，几百个文件几秒内扫完。
         */
        internal fun buildScanScript(apps: Set<SniffApp>): String {
            val roots = apps.flatMap { it.roots }.distinct()
                .joinToString(" \\\n") { "              \"$it\"" }
            return """
                for root in \
            $roots; do
                  [ -d "${'$'}root" ] || continue
                  find "${'$'}root" -type f -size +256k 2>/dev/null
                done | while IFS= read -r f; do
                  printf '%s\t%s\t' "${'$'}(wc -c < "${'$'}f" 2>/dev/null | tr -d ' \n')" "${'$'}f"
                  od -An -tx1 -N12 "${'$'}f" 2>/dev/null | tr -d ' \n'
                  printf '\n'
                done
            """.trimIndent()
        }
    }
}
