package com.sonance.player.data.netease

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import kotlin.coroutines.coroutineContext

/** 免 Shizuku 本机缓存扫描与独立目录转换的纯规则层（可直接跑 JVM 单测）。 */
object LocalCacheRules {

    const val STANDALONE_FOLDER_NAME = "本机缓存转换"
    const val STANDALONE_DISPLAY_PATH = "Music/$STANDALONE_FOLDER_NAME"

    private val EXCLUDED_SEGMENTS = listOf(
        "/$STANDALONE_FOLDER_NAME",
        "\\$STANDALONE_FOLDER_NAME",
        "/山水有清音下载",
        "\\山水有清音下载",
        "netease_cache",
        "local_cache_work",
        "cover_cache",
        ".thumbnails"
    )

    private val CACHE_DIR_HINTS = listOf(
        "/cache", "/temp", "/playingcache", "/music3", "/uc", "/nmsf", "/audiocache"
    )

    private val SONG_ID_LEADING = Regex("""^(\d{5,12})(?:[_\-.].*)?$""")
    private val FRAGMENT_SUFFIX = Regex("""^(.*)-([0-9]{1,5})$""")

    fun isExcludedPath(path: String): Boolean {
        val norm = path.replace('\\', '/').lowercase()
        return EXCLUDED_SEGMENTS.any { seg ->
            norm.contains(seg.replace('\\', '/').lowercase())
        }
    }

    fun looksLikeCacheDirectory(path: String): Boolean {
        val norm = path.replace('\\', '/').lowercase()
        return CACHE_DIR_HINTS.any { norm.contains(it) }
    }

    fun looksLikeCacheFileName(fileName: String): Boolean {
        val dot = fileName.lastIndexOf('.')
        val stem = if (dot > 0) fileName.substring(0, dot) else fileName
        val ext = if (dot > 0) fileName.substring(dot + 1).lowercase() else ""
        if (ext == "uc" || ext == "uc!" || ext == "nmsf" || ext == "ncm" || ext == "vcc") return true
        return SONG_ID_LEADING.matches(stem)
    }

    /**
     * 判断扫描到的本地文件是否纳入「本机缓存转换」候选：
     * - 已知加密缓存（NCM / NMSF / KGM / QMC）>= 64KB 一律纳入；
     * - 明文音频仅当位于缓存目录、或文件名呈歌曲 ID 缓存形态且 >= 256KB 时纳入，
     *   避免把用户自己已下载在 Download 里的普通成品歌误当成待转换缓存。
     */
    fun shouldIncludeScannedFile(
        kind: NcKind,
        fileName: String,
        folderPathHint: String,
        sizeBytes: Long
    ): Boolean {
        if (isExcludedPath(folderPathHint) || isExcludedPath(fileName)) return false
        return when (kind) {
            NcKind.NCM, NcKind.NMS, NcKind.KGM, NcKind.QMC -> sizeBytes >= 64L * 1024L
            NcKind.PLAIN -> sizeBytes >= 256L * 1024L &&
                (looksLikeCacheDirectory(folderPathHint) || looksLikeCacheFileName(fileName))
            NcKind.MDL, NcKind.QQCACHE, NcKind.UNKNOWN -> false
        }
    }

    fun extractNeteaseSongId(displayName: String, kind: NcKind): String? {
        if (kind != NcKind.NMS && kind != NcKind.PLAIN) return null
        val match = SONG_ID_LEADING.matchEntire(displayName.trim()) ?: return null
        return match.groupValues[1]
    }

    /**
     * 试听残曲最短阈值（秒）：
     * 播放块缓存（NMSF / PLAIN）沿用 150 秒；完整加密下载容器（NCM / KGM / QMC）仅拦截 <45 秒试听段，
     * 避免误杀 1~2 分钟的纯音乐或短间奏。
     */
    fun trialThresholdSeconds(kind: NcKind): Double = when (kind) {
        NcKind.NMS, NcKind.PLAIN -> NcDuration.TRIAL_MIN_SECONDS
        else -> 45.0
    }

    fun isSuspectTrialForLocal(kind: NcKind, sizeBytes: Long, plainExt: String): Boolean {
        if (kind == NcKind.NCM || kind == NcKind.KGM || kind == NcKind.QMC) return false
        return NeteaseCacheParser.isSuspectTrial(sizeBytes, plainExt)
    }

    /** 仅针对加密/分片候选用作显示与分组的轻量清理。 */
    fun uniqueFileName(desired: String, existingNames: Set<String>): String {
        if (desired !in existingNames) return desired
        val dot = desired.lastIndexOf('.')
        val stem = if (dot > 0) desired.substring(0, dot) else desired
        val ext = if (dot > 0) desired.substring(dot) else ""
        var n = 1
        while (true) {
            val candidate = "$stem ($n)$ext"
            if (candidate !in existingNames) return candidate
            n++
        }
    }

    fun parseFragmentKey(stem: String): Pair<String, Int>? {
        val m = FRAGMENT_SUFFIX.matchEntire(stem) ?: return null
        val idx = m.groupValues[2].toIntOrNull() ?: return null
        return m.groupValues[1] to idx
    }
}

enum class LocalSourceKind(val label: String) {
    SAF_TREE("已选文件夹"),
    PUBLIC_CACHE("公开存储目录"),
    SONANCE_CACHE("本应用缓存")
}

data class LocalCacheCandidate(
    val entry: NeteaseCacheEntry,
    val sourceKind: LocalSourceKind,
    val sourceLabel: String
)

data class LocalCacheScanReport(
    val candidates: List<LocalCacheCandidate>,
    val scannedSources: List<String>,
    val androidDataBlocked: Boolean
)

data class LocalCacheConvertSummary(
    val converted: Int,
    val skipped: Int,
    val failed: Int,
    val trials: Int,
    val incomplete: Int,
    val doneKeys: List<String>,
    val outputs: List<String>,
    val failures: List<String>,
    val standaloneFolderCreated: Boolean,
    val standaloneFolderPath: String = LocalCacheRules.STANDALONE_DISPLAY_PATH
)

/**
 * 免 Shizuku 本机缓存转换器。
 *
 * 特点：
 * 1. **无需 Shizuku / Root**：直接扫描本机可读存储目录、应用自身缓存与用户通过系统文件夹授权器（SAF）
 *    挑选的本地音乐/下载目录（如 Download/netease/cloudmusic）；
 * 2. **结合已登录网易云会话**：对以歌曲 ID 命名的本地缓存，自动调用含登录凭据的 [NcMetaFetcher]
 *    补全真实歌名、歌手、专辑与封面；
 * 3. **延迟创建独立输出文件夹**：转换成功至少 1 首完整曲目后，才会在系统音乐目录下新建独立文件夹
 *    `Music/本机缓存转换/` 保存成品，并通知曲库重扫；全跳过或失败绝不产生空目录。
 */
class LocalCacheConverter(context: Context) {

    private val appContext = context.applicationContext
    private val metaCache = HashMap<String, NcMeta?>()
    private val workRoot = File(appContext.cacheDir, "local_cache_work")
    private val doneFile = File(appContext.filesDir, "local_cache_converted.txt")

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
            if (!tmp.renameTo(doneFile)) {
                doneFile.writeText(text)
                tmp.delete()
            }
        }
    }

    suspend fun scan(safTreeUri: Uri?, safTreeLabel: String?): LocalCacheScanReport =
        withContext(Dispatchers.IO) {
            val scannedSources = mutableListOf<String>()
            val collected = ArrayList<LocalCacheCandidate>()
            val seenKeys = HashSet<String>()

            // 1) 检查系统 Android/data 私有目录是否直接可读（Android 11+ 普通权限被 FUSE 拦截，此处如实报告，绝不绕过）
            val androidDataCache = File("/storage/emulated/0/Android/data/com.netease.cloudmusic/files/Cache")
            val androidDataBlocked = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !canListDir(androidDataCache)

            // 2) 本机可读目录：应用自身缓存 + 未受限的公开/遗留缓存目录
            val dirTargets = buildList {
                appContext.externalCacheDir?.let { add(it to LocalSourceKind.SONANCE_CACHE) }
                add(appContext.cacheDir to LocalSourceKind.SONANCE_CACHE)
                val extRoot = runCatching { Environment.getExternalStorageDirectory() }.getOrNull()
                if (extRoot != null) {
                    listOf(
                        "netease/cloudmusic/Cache",
                        "netease/cloudmusic/Music",
                        "Download/netease/cloudmusic",
                        "Download/netease/cloudmusic/Music",
                        "Music/netease",
                        "kgmusic/Temp",
                        "kgmusic/Download",
                        "qqmusic/song",
                        "Android/data/com.netease.cloudmusic/files/Cache"
                    ).forEach { rel ->
                        add(File(extRoot, rel) to LocalSourceKind.PUBLIC_CACHE)
                    }
                }
            }

            for ((dir, kind) in dirTargets) {
                coroutineContext.ensureActive()
                if (!canListDir(dir)) continue
                scannedSources += dir.name
                val rawFiles = collectFileTree(dir)
                if (rawFiles.isEmpty()) continue
                val built = NeteaseCacheParser.buildEntries(rawFiles)
                for (entry in built) {
                    val trial = LocalCacheRules.isSuspectTrialForLocal(entry.kind, entry.sizeBytes, entry.plainExt)
                    val fixed = entry.copy(suspectTrial = trial)
                    if (fixed.suspectTrial) continue
                    val folderHint = fixed.fragments.firstOrNull()?.substringBeforeLast('/', "") ?: dir.absolutePath
                    if (!LocalCacheRules.shouldIncludeScannedFile(
                            fixed.kind,
                            fixed.fragments.firstOrNull()?.substringAfterLast('/') ?: fixed.displayName,
                            folderHint,
                            fixed.sizeBytes
                        )
                    ) continue
                    if (seenKeys.add(fixed.key)) {
                        collected += LocalCacheCandidate(fixed, kind, dir.name)
                    }
                }
            }

            // 3) 用户通过系统选择器授权的本机文件夹（免 Shizuku 读取 Download/netease 等目录的核心通道）
            if (safTreeUri != null) {
                val treeDoc = runCatching { DocumentFile.fromTreeUri(appContext, safTreeUri) }.getOrNull()
                if (treeDoc != null && treeDoc.canRead()) {
                    val label = safTreeLabel?.takeIf { it.isNotBlank() } ?: (treeDoc.name ?: "已选文件夹")
                    scannedSources += label
                    val safCandidates = collectSafCandidates(treeDoc, label)
                    for (cand in safCandidates) {
                        if (seenKeys.add(cand.entry.key)) {
                            collected += cand
                        }
                    }
                }
            }

            LocalCacheScanReport(
                candidates = collected.sortedBy { it.entry.displayName.lowercase() },
                scannedSources = scannedSources.distinct(),
                androidDataBlocked = androidDataBlocked
            )
        }

    private fun canListDir(dir: File): Boolean = runCatching {
        dir.exists() && dir.isDirectory && dir.canRead() && (dir.listFiles() != null)
    }.getOrDefault(false)

    private fun collectFileTree(root: File, depth: Int = 0): List<NcFile> {
        if (depth > 5 || LocalCacheRules.isExcludedPath(root.absolutePath)) return emptyList()
        val children = runCatching { root.listFiles() }.getOrNull() ?: return emptyList()
        val out = ArrayList<NcFile>()
        for (child in children) {
            if (child.isDirectory) {
                out += collectFileTree(child, depth + 1)
                continue
            }
            if (!child.isFile || !child.canRead()) continue
            val len = child.length()
            if (len < 64L * 1024L) continue
            if (LocalCacheRules.isExcludedPath(child.absolutePath)) continue
            val head = readHeaderBytes { child.inputStream() }
            val (kind, ext) = NeteaseCacheParser.classify(head, child.name)
            val stem = child.name.substringBeforeLast('.', child.name)
            if (kind != NcKind.UNKNOWN || LocalCacheRules.parseFragmentKey(stem) != null) {
                out += NcFile(child.absolutePath, len, kind, ext)
            }
        }
        return out
    }

    private fun collectSafCandidates(root: DocumentFile, treeLabel: String): List<LocalCacheCandidate> {
        data class SafDoc(
            val parentRel: String,
            val name: String,
            val uriStr: String,
            val size: Long,
            val kind: NcKind,
            val plainExt: String
        )

        val docs = ArrayList<SafDoc>()
        fun walk(dir: DocumentFile, rel: String, depth: Int) {
            if (depth > 6 || LocalCacheRules.isExcludedPath(rel)) return
            val children = runCatching { dir.listFiles() }.getOrNull() ?: return
            for (child in children) {
                val name = child.name ?: continue
                val childRel = if (rel.isEmpty()) name else "$rel/$name"
                if (LocalCacheRules.isExcludedPath(childRel)) continue
                if (child.isDirectory) {
                    walk(child, childRel, depth + 1)
                } else if (child.isFile) {
                    val size = child.length()
                    if (size < 64L * 1024L) continue
                    val head = readHeaderBytes { appContext.contentResolver.openInputStream(child.uri) }
                    val (kind, plainExt) = NeteaseCacheParser.classify(head, name)
                    val stem = name.substringBeforeLast('.', name)
                    if (kind != NcKind.UNKNOWN || LocalCacheRules.parseFragmentKey(stem) != null) {
                        docs += SafDoc(rel, name, child.uri.toString(), size, kind, plainExt)
                    }
                }
            }
        }
        walk(root, "", 0)

        // 先按同目录同前缀归并分片（<前缀>-<片号>）
        data class Frag(val doc: SafDoc, val prefix: String, val index: Int)
        val fragCandidates = mutableListOf<Frag>()
        val result = ArrayList<LocalCacheCandidate>()

        for (d in docs) {
            val dot = d.name.lastIndexOf('.')
            val stem = if (dot > 0) d.name.substring(0, dot) else d.name
            val frag = LocalCacheRules.parseFragmentKey(stem)
            if (frag != null) {
                fragCandidates += Frag(d, frag.first, frag.second)
            } else {
                addSingleSafDoc(d.name, d.parentRel, d.uriStr, d.size, d.kind, d.plainExt, treeLabel, result)
            }
        }

        val groups = fragCandidates.groupBy { it.doc.parentRel to it.prefix }
        for ((groupKey, list) in groups) {
            if (list.size >= 2) {
                val sorted = list.sortedBy { it.index }
                val first = sorted.first().doc
                val totalSize = sorted.sumOf { it.doc.size }
                val trial = LocalCacheRules.isSuspectTrialForLocal(first.kind, totalSize, first.plainExt)
                if (!trial && LocalCacheRules.shouldIncludeScannedFile(
                        first.kind,
                        first.name,
                        first.parentRel.ifBlank { treeLabel },
                        totalSize
                    )
                ) {
                    val entry = NeteaseCacheEntry(
                        key = first.uriStr,
                        displayName = groupKey.second.ifBlank { "未命名缓存" },
                        sizeBytes = totalSize,
                        kind = first.kind,
                        plainExt = first.plainExt,
                        fragments = sorted.map { it.doc.uriStr },
                        fragmentCount = sorted.size,
                        suspectTrial = false
                    )
                    result += LocalCacheCandidate(entry, LocalSourceKind.SAF_TREE, treeLabel)
                }
            } else {
                val d = list.first().doc
                addSingleSafDoc(d.name, d.parentRel, d.uriStr, d.size, d.kind, d.plainExt, treeLabel, result)
            }
        }
        return result
    }

    private fun addSingleSafDoc(
        name: String,
        parentRel: String,
        uriStr: String,
        size: Long,
        kind: NcKind,
        plainExt: String,
        treeLabel: String,
        out: MutableList<LocalCacheCandidate>
    ) {
        val trial = LocalCacheRules.isSuspectTrialForLocal(kind, size, plainExt)
        if (trial) return
        val folderHint = parentRel.ifBlank { treeLabel }
        if (!LocalCacheRules.shouldIncludeScannedFile(kind, name, folderHint, size)) return
        val dot = name.lastIndexOf('.')
        val stem = (if (dot > 0) name.substring(0, dot) else name).removeSuffix(".vcc")
            .ifBlank { "未命名缓存" }
        val entry = NeteaseCacheEntry(
            key = uriStr,
            displayName = stem,
            sizeBytes = size,
            kind = kind,
            plainExt = plainExt,
            fragments = listOf(uriStr),
            fragmentCount = 1,
            suspectTrial = false
        )
        out += LocalCacheCandidate(entry, LocalSourceKind.SAF_TREE, treeLabel)
    }

    private inline fun readHeaderBytes(open: () -> InputStream?): ByteArray = runCatching {
        open()?.use { inp ->
            val buf = ByteArray(12)
            var off = 0
            while (off < buf.size) {
                val n = inp.read(buf, off, buf.size - off)
                if (n <= 0) break
                off += n
            }
            if (off == buf.size) buf else buf.copyOf(off)
        } ?: ByteArray(0)
    }.getOrDefault(ByteArray(0))

    suspend fun convertAll(
        items: List<LocalCacheCandidate>,
        onProgress: (Int, Int, String) -> Unit = { _, _, _ -> }
    ): LocalCacheConvertSummary = withContext(Dispatchers.IO) {
        val existing = queryStandaloneExisting()
        val doneMap = loadDoneMap().toMutableMap()

        var converted = 0
        var skipped = 0
        var failed = 0
        var trials = 0
        var incomplete = 0
        val doneKeys = mutableListOf<String>()
        val outputs = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val beforeExisted = existing.isNotEmpty()

        items.forEachIndexed { index, item ->
            coroutineContext.ensureActive()
            val entry = item.entry
            onProgress(index, items.size, entry.displayName)

            val knownOut = doneMap[entry.key]
            if (knownOut != null && existing.containsKey(knownOut)) {
                skipped++
                onProgress(index + 1, items.size, entry.displayName)
                return@forEachIndexed
            }

            val outcome = runCatching { convertOne(entry, existing, doneMap) }
                .getOrElse { NcConvertOutcome.Failed(it.message ?: "处理异常") }

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
            onProgress(index + 1, items.size, entry.displayName)
        }

        saveDoneMap(doneMap)
        LocalCacheConvertSummary(
            converted = converted,
            skipped = skipped,
            failed = failed,
            trials = trials,
            incomplete = incomplete,
            doneKeys = doneKeys,
            outputs = outputs,
            failures = failures,
            standaloneFolderCreated = converted > 0 && !beforeExisted
        )
    }

    private suspend fun convertOne(
        entry: NeteaseCacheEntry,
        existing: MutableMap<String, Long>,
        doneMap: MutableMap<String, String>
    ): NcConvertOutcome {
        val keyHash = Integer.toHexString(entry.key.hashCode()) + "-" + entry.key.length
        val workDir = File(workRoot, keyHash)
        return try {
            workDir.deleteRecursively()
            workDir.mkdirs()

            val src = materializeEntry(entry, workDir)
            val songId = LocalCacheRules.extractNeteaseSongId(entry.displayName, entry.kind)
            val minTrial = LocalCacheRules.trialThresholdSeconds(entry.kind)
            val containerExt = NcCacheDecodeSupport.containerExtFor(entry)

            val prepared = when (entry.kind) {
                NcKind.NCM, NcKind.NMS, NcKind.KGM -> {
                    val dec = File(workDir, "dec.bin")
                    NcCacheDecodeSupport.prepareEncrypted(
                        context = appContext,
                        src = src,
                        decFile = dec,
                        displayName = entry.displayName,
                        containerExt = containerExt,
                        tail = null,
                        songId = songId,
                        metaCache = metaCache,
                        scopeContext = coroutineContext,
                        trialMinSeconds = minTrial,
                        legacyShouldSkip = { outName, expectedSize, enrichable ->
                            val priorSize = existing[outName]
                            if (priorSize != null && priorSize == expectedSize && !enrichable) {
                                doneMap[entry.key] = outName
                                true
                            } else false
                        },
                        finalShouldSkip = { outName, finalSize ->
                            val priorSize = existing[outName]
                            if (priorSize != null && priorSize == finalSize) {
                                doneMap[entry.key] = outName
                                true
                            } else false
                        }
                    )
                }
                NcKind.QMC -> {
                    val dec = File(workDir, "dec.bin")
                    NcCacheDecodeSupport.prepareEncrypted(
                        context = appContext,
                        src = src,
                        decFile = dec,
                        displayName = entry.displayName,
                        containerExt = containerExt,
                        tail = NcCacheDecodeSupport.readTail(src),
                        songId = songId,
                        metaCache = metaCache,
                        scopeContext = coroutineContext,
                        trialMinSeconds = minTrial,
                        legacyShouldSkip = { outName, expectedSize, enrichable ->
                            val priorSize = existing[outName]
                            if (priorSize != null && priorSize == expectedSize && !enrichable) {
                                doneMap[entry.key] = outName
                                true
                            } else false
                        },
                        finalShouldSkip = { outName, finalSize ->
                            val priorSize = existing[outName]
                            if (priorSize != null && priorSize == finalSize) {
                                doneMap[entry.key] = outName
                                true
                            } else false
                        }
                    )
                }
                NcKind.PLAIN -> {
                    NcCacheDecodeSupport.preparePlain(
                        context = appContext,
                        src = src,
                        displayName = entry.displayName,
                        plainExt = entry.plainExt,
                        songId = songId,
                        metaCache = metaCache,
                        trialMinSeconds = minTrial,
                        shouldSkip = { outName, sizeBytes ->
                            val priorSize = existing[outName]
                            if (priorSize != null && priorSize == sizeBytes) {
                                doneMap[entry.key] = outName
                                true
                            } else false
                        }
                    )
                }
                else -> NcCacheDecodeSupport.PreparedAudio.Skipped("不支持的类型")
            }

            when (prepared) {
                is NcCacheDecodeSupport.PreparedAudio.Skipped ->
                    NcConvertOutcome.Skipped(prepared.reason, trial = prepared.trial, incomplete = prepared.incomplete)
                is NcCacheDecodeSupport.PreparedAudio.Ready -> {
                    // 注意：只有到达此处（验证完整且非试听残曲），才新建并写入独立目录
                    writeToStandaloneFolder(prepared, existing)
                }
            }
        } finally {
            workDir.deleteRecursively()
        }
    }

    private fun materializeEntry(entry: NeteaseCacheEntry, workDir: File): File {
        val dst = File(workDir, "src.bin")
        dst.outputStream().use { out ->
            for (frag in entry.fragments) {
                openSourceStream(frag)?.use { inp -> inp.copyTo(out) }
                    ?: error("无法读取本机源文件：$frag")
            }
        }
        if (dst.length() <= 0L) error("本机缓存文件为空或已失效")
        return dst
    }

    private fun openSourceStream(pathOrUri: String): InputStream? = runCatching {
        if (pathOrUri.startsWith("content://")) {
            appContext.contentResolver.openInputStream(Uri.parse(pathOrUri))
        } else {
            File(pathOrUri).inputStream()
        }
    }.getOrNull()

    /** 查询独立输出文件夹内已有文件名与大小（不触发新建目录）。 */
    private fun queryStandaloneExisting(): MutableMap<String, Long> {
        val map = HashMap<String, Long>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cols = arrayOf(
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.RELATIVE_PATH
            )
            val sel = "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?"
            val args = arrayOf("%${LocalCacheRules.STANDALONE_FOLDER_NAME}%")
            runCatching {
                appContext.contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cols, sel, args, null
                )?.use { c ->
                    val nameIdx = c.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                    val sizeIdx = c.getColumnIndex(MediaStore.Audio.Media.SIZE)
                    val pathIdx = c.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
                    while (c.moveToNext()) {
                        val rel = if (pathIdx >= 0) c.getString(pathIdx).orEmpty() else ""
                        if (!rel.trimEnd('/').endsWith(LocalCacheRules.STANDALONE_FOLDER_NAME)) continue
                        val name = if (nameIdx >= 0) c.getString(nameIdx) else null
                        val size = if (sizeIdx >= 0) c.getLong(sizeIdx) else 0L
                        if (!name.isNullOrBlank()) map[name] = size
                    }
                }
            }
        } else {
            val dir = legacyStandaloneDir()
            if (dir.exists() && dir.isDirectory) {
                dir.listFiles()?.forEach { f ->
                    if (f.isFile) map[f.name] = f.length()
                }
            }
        }
        return map
    }

    private fun writeToStandaloneFolder(
        prepared: NcCacheDecodeSupport.PreparedAudio.Ready,
        existing: MutableMap<String, Long>
    ): NcConvertOutcome {
        val fileName = LocalCacheRules.uniqueFileName(prepared.outName, existing.keys)
        val mime = NcCacheDecodeSupport.mimeOf(prepared.ext)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = appContext.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Audio.Media.MIME_TYPE, mime)
                put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/${LocalCacheRules.STANDALONE_FOLDER_NAME}")
                put(MediaStore.Audio.Media.IS_MUSIC, 1)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
                if (prepared.info.title.isNotBlank()) put(MediaStore.Audio.Media.TITLE, prepared.info.title)
                if (prepared.info.artist.isNotBlank()) put(MediaStore.Audio.Media.ARTIST, prepared.info.artist)
                if (prepared.info.album.isNotBlank()) put(MediaStore.Audio.Media.ALBUM, prepared.info.album)
            }
            val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                ?: return NcConvertOutcome.Failed("新建独立目录输出文件失败")
            return try {
                resolver.openOutputStream(uri)?.use { out ->
                    prepared.tempFile.inputStream().use { it.copyTo(out) }
                } ?: run {
                    resolver.delete(uri, null, null)
                    return NcConvertOutcome.Failed("打开独立目录输出流失败")
                }
                val doneValues = ContentValues().apply {
                    put(MediaStore.Audio.Media.IS_PENDING, 0)
                    put(MediaStore.Audio.Media.SIZE, prepared.finalSize)
                }
                resolver.update(uri, doneValues, null, null)
                existing[fileName] = prepared.finalSize
                NcConvertOutcome.Done(fileName, prepared.ext, prepared.finalSize)
            } catch (t: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                NcConvertOutcome.Failed(t.message ?: "写入失败")
            }
        } else {
            val dir = legacyStandaloneDir()
            if (!dir.exists() && !dir.mkdirs()) {
                return NcConvertOutcome.Failed("无法新建独立文件夹「${dir.absolutePath}」")
            }
            val outFile = File(dir, fileName)
            val tmpFile = File(dir, "$fileName.tmp")
            return try {
                tmpFile.outputStream().use { out ->
                    prepared.tempFile.inputStream().use { it.copyTo(out) }
                }
                if (!tmpFile.renameTo(outFile)) {
                    tmpFile.copyTo(outFile, overwrite = true)
                    tmpFile.delete()
                }
                existing[fileName] = outFile.length()
                MediaScannerConnection.scanFile(appContext, arrayOf(outFile.absolutePath), arrayOf(mime), null)
                NcConvertOutcome.Done(fileName, prepared.ext, outFile.length())
            } catch (t: Throwable) {
                tmpFile.delete()
                NcConvertOutcome.Failed(t.message ?: "写入失败")
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyStandaloneDir(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
        LocalCacheRules.STANDALONE_FOLDER_NAME
    )
}
