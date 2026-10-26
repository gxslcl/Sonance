package com.sonance.player.data.netease

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.sonance.player.data.CoverCache
import com.sonance.player.data.HeaderInfo
import com.sonance.player.data.Mp4TagWriter
import com.sonance.player.data.OnlineCover
import com.sonance.player.data.RestorePlan
import com.sonance.player.data.RestorePlanner
import com.sonance.player.data.RestoreProbe
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
 * ① [scan] —— 一条 shell 把四个候选根目录下的大文件（>256KB）连同**头 8 字节魔数**
 *    一起报回来，Kotlin 侧按魔数分类、按「同目录同前缀-片号」归并分片组；
 * ② 拉取 —— 优先 shell 内部 `cp`（~250MB/s），不可用退回 stdout 管道；
 *    分片逐片拷回本地再拼成完整文件；
 * ③ [convertAll] —— NCM 走 [RestorePlanner] 解密管线（与「私有格式还原」同一套代码），
 *    明文文件直接流复制，成品统一写进用户选的输出文件夹。
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

    suspend fun scan(): NeteaseScanResult = withContext(Dispatchers.IO) {
        when {
            !ShizukuShell.isInstalled(appContext) -> NeteaseScanResult.NoShizuku
            !ShizukuShell.isRunning() -> NeteaseScanResult.NotRunning
            !ShizukuShell.hasPermission() -> NeteaseScanResult.NoPermission
            else -> {
                val raw = ShizukuShell.exec(SCAN_SCRIPT)
                    ?: return@withContext NeteaseScanResult.Failed("读取缓存目录失败")
                val entries = NeteaseCacheParser.buildEntriesFromScan(raw)
                saveCache(entries)
                NeteaseScanResult.Ok(entries)
            }
        }
    }

    /** 上次扫描结果，进设置页先显示、不用每次都等几秒的 shell 扫描 */
    fun loadCached(): List<NeteaseCacheEntry>? = runCatching {
        if (!metaFile.exists()) return null
        val list = metaFile.readLines().mapNotNull { NeteaseCacheCodec.fromLine(it) }
        if (list.isEmpty()) return null
        list
    }.getOrNull()

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
            return when (entry.kind) {
                NcKind.NCM -> convertEncrypted(src, entry, root, existing, doneMap, ".ncm")
                NcKind.NMS -> convertEncrypted(src, entry, root, existing, doneMap, ".nmsf")
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

    /** 加密容器（NCM / NMSF）：走「私有格式还原」的探测 + 解密 + 标签组装管线 */
    private suspend fun convertEncrypted(
        src: File,
        entry: NeteaseCacheEntry,
        root: DocumentFile,
        existing: MutableMap<String, Pair<Uri, Long>>,
        doneMap: MutableMap<String, String>,
        containerExt: String
    ): NcConvertOutcome {
        val scopeContext = coroutineContext
        // plan 持有流引用、writeTo 还要继续读，整个「探测→写盘」必须包在同一个 use 里
        src.inputStream().use { stream ->
            val probe = RestorePlanner.plan(stream, src.length(), "${entry.displayName}$containerExt")
            val plan = when (probe) {
                is RestoreProbe.Ready -> probe.plan
                is RestoreProbe.Skip -> return NcConvertOutcome.Skipped(probe.reason)
            }

            // 在线补全的可行性：容器没带标题（NMSF 必然如此）、文件名里有歌曲 ID
            val songId = NeteaseCacheParser.songIdOf(entry)
            val enrichable = songId != null && plan.info.title.isBlank()

            // 旧版产物按旧规则（原名 + 原大小）能对上就直接跳过——
            // 但 NMSF 这类可升级条目放行，让这次运行把它们补上名称与封面
            val legacyPrior = existing[plan.outName]
            if (legacyPrior != null && legacyPrior.second == plan.expectedSize && !enrichable) {
                doneMap[entry.key] = plan.outName
                return NcConvertOutcome.Skipped("已转换过")
            }

            // 按歌曲 ID 查详情接口补全歌名 / 歌手 / 专辑 / 封面（零歧义，不碰账号）。
            // 失败返回 null：转换照旧，成品退回哈希文件名。
            val meta = if (songId != null) metaCache.getOrPut(songId) { NcMetaFetcher.fetch(songId) } else null

            // 歌手取值优先级：**原文件自带标签 → 在线接口 → 歌名「歌名 - 歌手」拆分**兜底。
            // 在线某个字段为空时**不覆盖**原文件自带的值（空值覆盖会把好数据冲掉）。
            val fileArtist = plan.info.artist
            if (meta != null) {
                val artist = listOf(fileArtist, meta.artist).firstOrNull { it.isNotBlank() }
                    ?: splitArtistFromTitle(meta.title)
                plan.applyMeta(
                    newInfo = plan.info.withMetadata(NcMeta(meta.title, artist, meta.album, meta.cover)),
                    newCover = meta.cover,
                    outStem = NcMetaFetcher.outputStem(
                        NcMeta(meta.title, artist, meta.album, meta.cover)
                    )
                )
            } else if (fileArtist.isBlank()) {
                // 在线没查到：歌名若是「歌名 - 歌手」拼接形态，拆出歌手兜底写进标签
                val artist = splitArtistFromTitle(plan.info.title)
                if (artist.isNotBlank()) {
                    plan.applyMeta(
                        newInfo = plan.info.withMetadata(
                            NcMeta(plan.info.title, artist, plan.info.album, plan.cover)
                        ),
                        newCover = null,
                        outStem = null
                    )
                }
            }

            // 先解密到本地临时文件，探测**真实音频时长**：网易云按块缓存，
            // 试听 / 没播完的歌解出来是个几十秒的残段，一律不进曲库。
            // 拦在写输出文件夹之前，残曲不会留下任何痕迹。
            val dec = File(src.parentFile, "dec.bin")
            dec.outputStream().use { out ->
                plan.writeTo(out) { scopeContext.ensureActive() }
            }
            val secs = NcDuration.probeSeconds(dec)
            if (secs != null && secs < NcDuration.TRIAL_MIN_SECONDS) {
                dec.delete()
                return NcConvertOutcome.Skipped("试听残曲（约 ${fmtSecs(secs)}）", trial = true)
            }

            // m4a 数据完整性：块缓存没下完的歌，moov 声明的还是整首时长（能骗过
            // 上面的阈值），mdat 却只有前半 —— 转出去播到一半就报 2008。
            // 这里对账采样表，把这类源拦在写输出之前（之后缓存补全了会正常转换）。
            val integrity = NcDuration.completeness(dec)
            if (integrity != null && !integrity.complete) {
                dec.delete()
                return NcConvertOutcome.Skipped(
                    "缓存不完整（约 ${fmtSecs(integrity.playableSeconds)}/${fmtSecs(integrity.declaredSeconds)}）",
                    incomplete = true
                )
            }

            // m4a 的元数据住在 moov 里，前置字节没地方放 —— 就地补写。
            // 写坏的风险由「临时文件先验证再替换」兜住，失败就退回无标签成品
            if (plan.ext == ".m4a") {
                Mp4TagWriter.tag(dec, plan.info, plan.cover) { f -> NcDuration.probeSeconds(f) != null }
            }

            // 跳过判定用**最终成品大小**（m4a 打完标签会变大）：同名同大小才算已转换。
            // 旧版同名产物大小对不上 → 复用其 uri 覆盖升级，不会产生重复文件
            val finalSize = dec.length()
            val prior = existing[plan.outName]
            if (prior != null && prior.second == finalSize) {
                doneMap[entry.key] = plan.outName
                dec.delete()
                return NcConvertOutcome.Skipped("已转换过")
            }

            val uri = prior?.first
                ?: DocumentsContract.createDocument(
                    appContext.contentResolver, root.uri, mimeOf(plan.ext), plan.outName
                )
                ?: return NcConvertOutcome.Failed("创建输出文件失败")
            existing[plan.outName] = uri to finalSize

            val bytesOut = dec.length()
            appContext.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                dec.inputStream().use { it.copyTo(out) }
            } ?: run { dec.delete(); return NcConvertOutcome.Failed("打开输出流失败") }
            dec.delete()

            storeCover(plan.info, plan.cover)
            return NcConvertOutcome.Done(plan.outName, plan.ext, bytesOut)
        }
    }

    /**
     * 在线查到的元数据覆盖进 [HeaderInfo]（其余字段保持容器自带的值）。
     * **空值不覆盖** —— 接口偶尔有字段缺失，别把原文件自带的值冲掉。
     */
    private fun HeaderInfo.withMetadata(meta: NcMeta): HeaderInfo {
        val extra = buildMap {
            if (meta.title.isNotBlank()) put("title", meta.title)
            if (meta.artist.isNotBlank()) put("artist", meta.artist)
            if (meta.album.isNotBlank()) put("album", meta.album)
        }
        if (extra.isEmpty()) return this
        return copy(metadata = metadata + extra)
    }

    /** 歌名是「歌名 - 歌手」拼接形态时，拆出最后一个「 - 」后面的歌手兜底 */
    private fun splitArtistFromTitle(title: String): String {
        val t = title.trim()
        val i = t.lastIndexOf(" - ")
        if (i <= 0 || i + 3 >= t.length) return ""
        return t.substring(i + 3).trim()
    }

    /** 明文：先验时长再复制到输出文件夹。文件名本身就是哈希，输出名 = stem + 魔数判出的后缀 */
    private fun convertPlain(
        src: File,
        entry: NeteaseCacheEntry,
        root: DocumentFile,
        existing: MutableMap<String, Pair<Uri, Long>>,
        doneMap: MutableMap<String, String>
    ): NcConvertOutcome {
        val ext = entry.plainExt.ifBlank { ".mp3" }
        val outName = "${sanitizeName(entry.displayName)}$ext"

        val prior = existing[outName]
        if (prior != null && prior.second == entry.sizeBytes) {
            doneMap[entry.key] = outName
            return NcConvertOutcome.Skipped("已转换过")
        }

        // 明文源本身就是音频，直接在本地副本上验时长，残曲不用碰输出文件夹
        val secs = NcDuration.probeSeconds(src)
        if (secs != null && secs < NcDuration.TRIAL_MIN_SECONDS) {
            return NcConvertOutcome.Skipped("试听残曲（约 ${fmtSecs(secs)}）", trial = true)
        }

        // 明文 m4a 同样做数据完整性对账（其余格式探测不了，返回 null 放行）
        val integrity = NcDuration.completeness(src)
        if (integrity != null && !integrity.complete) {
            return NcConvertOutcome.Skipped(
                "缓存不完整（约 ${fmtSecs(integrity.playableSeconds)}/${fmtSecs(integrity.declaredSeconds)}）",
                incomplete = true
            )
        }

        val uri = prior?.first
            ?: DocumentsContract.createDocument(
                appContext.contentResolver, root.uri, mimeOf(ext), outName
            )
            ?: return NcConvertOutcome.Failed("创建输出文件失败")
        existing[outName] = uri to entry.sizeBytes

        appContext.contentResolver.openOutputStream(uri, "wt")?.use { out ->
            src.inputStream().use { it.copyTo(out) }
        } ?: return NcConvertOutcome.Failed("打开输出流失败")

        return NcConvertOutcome.Done(outName, ext, src.length())
    }

    /** 把容器里带的封面存进 [CoverCache]，键与在线封面一致，播放管线会自动捡起来 */
    private fun storeCover(info: HeaderInfo, cover: ByteArray?) {
        if (cover == null || cover.isEmpty()) return
        if (info.title.isBlank() && info.artist.isBlank()) return
        val key = OnlineCover.keyOf(info.title, info.artist) ?: return
        runCatching { CoverCache.store(appContext, key, cover) }
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

    private fun mimeOf(ext: String): String = when (ext) {
        ".flac" -> "audio/flac"
        ".ogg" -> "audio/ogg"
        ".m4a" -> "audio/mp4"
        ".wav" -> "audio/wav"
        else -> "audio/mpeg"
    }

    private fun sanitizeName(name: String): String =
        name.replace('/', '_').replace('\\', '_').ifBlank { "未命名缓存" }

    private fun fmtSecs(secs: Double): String = "%.0f".format(secs)

    private fun workKey(key: String): String =
        Integer.toHexString(key.hashCode()) + "-" + key.length

    private companion object {
        /**
         * 一次扫完网易云主版本 / Lite 版本的缓存目录。
         *
         * 只扫 cache 与 files：网易云的**下载**目录里本来就是标准 mp3/flac，
         * 媒体库扫描就能收，不需要也不应该混进这里。
         *
         * 输出约定：每行 `<大小>\t<路径>\t<头8字节hex>`，路径放中间列
         * 防止带空格 / 引号的文件名错位；`while read` 而不是 `for f in $(find)`
         * 同样是为了空格安全。find + od 逐文件 fork，几百个文件几秒内扫完。
         */
        val SCAN_SCRIPT = """
            for root in \
              "/sdcard/Android/data/com.netease.cloudmusic/cache" \
              "/sdcard/Android/data/com.netease.cloudmusic/files" \
              "/sdcard/Android/data/com.netease.cloudmusic.lite/cache" \
              "/sdcard/Android/data/com.netease.cloudmusic.lite/files"; do
              [ -d "${'$'}root" ] || continue
              find "${'$'}root" -type f -size +256k 2>/dev/null
            done | while IFS= read -r f; do
              printf '%s\t%s\t' "${'$'}(wc -c < "${'$'}f" 2>/dev/null | tr -d ' \n')" "${'$'}f"
              od -An -tx1 -N8 "${'$'}f" 2>/dev/null | tr -d ' \n'
              printf '\n'
            done
        """.trimIndent()
    }
}
