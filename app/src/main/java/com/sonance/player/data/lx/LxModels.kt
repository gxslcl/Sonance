package com.sonance.player.data.lx

import androidx.compose.runtime.Immutable
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToLong

/**
 * 洛雪自定义音源支持的曲库平台。
 *
 * 与内置 [com.sonance.player.data.NeteaseSource] 及 MusicFree 插件隔离：
 * 写进 [com.sonance.player.data.Song.source] 时统一加 `"lx:"` 前缀（如 `"lx:kw"`）。
 */
enum class LxPlatform(
    val id: String,
    val shortLabel: String,
    val displayName: String
) {
    KW("kw", "酷我", "酷我音乐"),
    TX("tx", "QQ", "QQ音乐"),
    KG("kg", "酷狗", "酷狗音乐"),
    WY("wy", "网易云", "网易云音乐");

    val sourceId: String get() = "lx:$id"

    companion object {
        val ALL: List<LxPlatform> = entries.toList()

        fun byId(id: String?): LxPlatform? =
            entries.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) }

        fun sourceIdFor(platformId: String): String = "lx:${platformId.trim().lowercase(Locale.ROOT)}"

        fun platformIdFromSource(sourceId: String?): String? {
            if (sourceId == null || !sourceId.startsWith("lx:")) return null
            return sourceId.removePrefix("lx:").trim().takeIf { it.isNotEmpty() }
        }
    }
}

/**
 * 洛雪音源请求的音质档位（标准协议字符串：`128k` / `320k` / `flac` / `flac24bit`）。
 */
enum class LxQuality(
    val id: String,
    val title: String,
    val badge: String,
    val format: String
) {
    STANDARD("128k", "标准音质 (128k)", "128k", "mp3"),
    HIGH("320k", "极高音质 (320k)", "320k", "mp3"),
    LOSSLESS("flac", "无损音质 (FLAC)", "无损", "flac"),
    HIRES("flac24bit", "Hi-Res 母带 (24bit)", "Hi-Res", "flac");

    companion object {
        /** 从高到低的顺序，用于向下逐级回退。 */
        val DESCENDING_IDS: List<String> = listOf("flac24bit", "flac", "320k", "128k")

        fun byId(id: String?): LxQuality =
            entries.firstOrNull { it.id == id?.trim() } ?: HIGH

        /**
         * 根据用户请求的档位和脚本声明的 `qualitys` 生成实际尝试顺序：
         * 先试不超过请求档位的最高支持档位，并按高→低依次降级；
         * 如果脚本未声明任何不高于请求档位的选项，则回退到脚本支持列表中最低可用的档位。
         */
        fun resolveCandidates(requestedId: String, supported: List<String>): List<String> {
            val validSupported = supported
                .map { it.trim() }
                .filter { it in DESCENDING_IDS }
                .distinct()
            val pool = validSupported.ifEmpty { listOf("320k", "128k") }
            val reqIdx = DESCENDING_IDS.indexOf(requestedId.trim())
                .let { if (it >= 0) it else DESCENDING_IDS.indexOf("320k") }

            val atOrBelow = DESCENDING_IDS.drop(reqIdx).filter { it in pool }
            if (atOrBelow.isNotEmpty()) return atOrBelow
            return DESCENDING_IDS.reversed().filter { it in pool }.ifEmpty { listOf("128k") }
        }

        fun formatFor(qualityId: String, url: String? = null): String {
            val cleanUrl = url.orEmpty().substringBefore('?').lowercase(Locale.ROOT)
            if (cleanUrl.endsWith(".flac")) return "flac"
            if (cleanUrl.endsWith(".mp3") || cleanUrl.endsWith(".m4a") || cleanUrl.endsWith(".aac")) {
                return "mp3"
            }
            return byId(qualityId).format
        }
    }
}

/** 洛雪脚本头部注释元信息（`@name` / `@version` / `@author` / `@description` / `@homepage`）。 */
@Immutable
data class LxScriptMeta(
    val name: String,
    val version: String,
    val author: String,
    val description: String,
    val homepage: String
)

/** 单个平台在洛雪脚本 `inited` 回调里声明的能力。 */
@Immutable
data class LxPlatformCapability(
    val name: String,
    val type: String,
    val actions: List<String>,
    val qualitys: List<String>
) {
    val supportsMusicUrl: Boolean
        get() = type.equals("music", ignoreCase = true) &&
            (actions.isEmpty() || actions.any { it.equals("musicUrl", ignoreCase = true) })
}

/** 已导入的洛雪自定义音源脚本记录。 */
@Immutable
data class LxScriptRecord(
    val id: String,
    val meta: LxScriptMeta,
    val fileName: String,
    val addedAtMs: Long,
    val platforms: Map<String, LxPlatformCapability>,
    val apiVersion: String = "2.0.0",
    val lastError: String? = null
) {
    /** 当前脚本与内置多平台曲库引擎交集的可用平台（按固定展示顺序）。 */
    fun supportedCatalogPlatforms(): List<LxPlatform> =
        LxPlatform.ALL.filter { platforms[it.id]?.supportsMusicUrl == true }

    fun displayVersionLine(): String = buildString {
        if (meta.version.isNotBlank()) append("v${meta.version.removePrefix("v")}")
        if (meta.author.isNotBlank()) {
            if (isNotEmpty()) append(" · ")
            append(meta.author)
        }
        val caps = supportedCatalogPlatforms()
        if (caps.isNotEmpty()) {
            if (isNotEmpty()) append(" · ")
            append(caps.joinToString("/") { it.shortLabel })
        }
    }.ifBlank { meta.description }
}

/** 排行榜条目。 */
@Immutable
data class LxBoard(
    val id: String,
    val name: String,
    val platform: String,
    val coverUrl: String? = null,
    val intro: String = "",
    val period: String = ""
)

/** 排行榜分组（例如 酷我「官方榜」「特色榜」）。 */
@Immutable
data class LxTopListGroup(
    val title: String,
    val boards: List<LxBoard>
)

/** 歌单分类标签。 */
@Immutable
data class LxSheetTag(
    val id: String,
    val name: String,
    val digest: String = "",
    val group: String = ""
)

/** 洛雪多平台歌单摘要（榜单外的所有精选 / 搜索歌单）。 */
@Immutable
data class LxSheetSummary(
    val id: String,
    val platform: String,
    val name: String,
    val coverUrl: String? = null,
    val creatorName: String = "",
    val trackCount: Int = 0,
    val desc: String = ""
)

/** 洛雪歌单分页结果。 */
data class LxSheetPage(
    val sheets: List<LxSheetSummary>,
    val total: Int = -1
) {
    fun hasMore(offset: Int, limit: Int): Boolean = when {
        total < 0 -> sheets.size >= limit
        else -> offset + sheets.size < total
    }
}

/** 平台歌词抓取结果（原始 LRC 与可选译文 LRC）。 */
data class LxLyricData(
    val lrc: String,
    val tlyric: String = ""
)

/** 洛雪在线页搜索对象切换（单曲 / 歌单）。 */
enum class LxSearchMode { SONG, SHEET }

/** 打开洛雪榜单或精选歌单详情页时传递的目标信息。 */
@Immutable
data class LxSheetDetailTarget(
    val id: String,
    val platform: String,
    val title: String,
    val subtitle: String = "",
    val coverUrl: String? = null,
    val isBoard: Boolean = false,
    val boardPeriod: String = ""
)

/** 供脚本 `musicUrl` 调用的曲目信息（从搜索 / 歌单 / 恢复队列携带而来）。 */
data class LxTrackHint(
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long
)

/**
 * 洛雪曲目的 `remoteId` 编解码器。
 *
 * 使用波浪号 `~` 分隔紧凑字段，保证：
 * - 不含 `/` `:` `?` 等文件路径或 URL 危险字符（兼容 `downloadKeyOf` 与收藏存档）
 * - 冷启动仅凭 `StoredOnlineSong` 也能还原出 QQ `mediaMid` / 酷狗 `albumId`
 */
object LxRemoteId {
    private const val SEP = '~'

    fun encodeKw(rid: String): String = rid.trim()

    fun encodeWy(id: String): String = id.trim()

    fun encodeTx(
        songmid: String,
        mediaMid: String = "",
        albumMid: String = "",
        songId: String = ""
    ): String {
        val mid = songmid.trim()
        val mMid = mediaMid.trim().let { if (it == mid) "" else it }
        val aMid = albumMid.trim()
        val sId = songId.trim().let { if (it == mid) "" else it }
        if (mMid.isEmpty() && aMid.isEmpty() && sId.isEmpty()) return mid
        return listOf(mid, mMid, aMid, sId).joinToString(SEP.toString())
    }

    fun encodeKg(hash: String, albumId: String = ""): String {
        val h = hash.trim()
        val aid = albumId.trim()
        return if (aid.isEmpty() || aid == "0") h else "$h$SEP$aid"
    }

    data class TxIds(
        val songmid: String,
        val mediaMid: String,
        val albumMid: String,
        val songId: String
    )

    data class KgIds(
        val hash: String,
        val albumId: String
    )

    fun parseTx(remoteId: String): TxIds {
        val parts = remoteId.split(SEP)
        val mid = parts.getOrElse(0) { "" }.trim()
        val mMid = parts.getOrElse(1) { "" }.trim().ifBlank { mid }
        val aMid = parts.getOrElse(2) { "" }.trim()
        val sId = parts.getOrElse(3) { "" }.trim().ifBlank { mid }
        return TxIds(songmid = mid, mediaMid = mMid, albumMid = aMid, songId = sId)
    }

    fun parseKg(remoteId: String): KgIds {
        val parts = remoteId.split(SEP)
        val hash = parts.getOrElse(0) { "" }.trim()
        val albumId = parts.getOrElse(1) { "" }.trim()
        return KgIds(hash = hash, albumId = albumId)
    }

    /** 构造传入 `globalThis.lx` `musicUrl` 的标准 `musicInfo` JSONObject。 */
    fun buildMusicInfoJson(
        platform: String,
        remoteId: String,
        title: String,
        artist: String,
        album: String,
        durationMs: Long
    ): JSONObject = JSONObject().apply {
        val sec = (durationMs / 1000L).coerceAtLeast(0L).toInt()
        put("source", platform)
        put("name", title)
        put("singer", artist)
        put("albumName", album)
        put("interval", sec)
        when (platform) {
            "kw" -> {
                val id = remoteId.trim()
                put("songmid", id)
                put("id", id)
                put("rid", id)
            }
            "wy" -> {
                val id = remoteId.trim()
                put("songmid", id)
                put("id", id)
            }
            "tx" -> {
                val ids = parseTx(remoteId)
                put("songmid", ids.songmid)
                put("id", ids.songId)
                put("strMediaMid", ids.mediaMid)
                put("mediaId", ids.mediaMid)
                if (ids.albumMid.isNotEmpty()) put("albumMid", ids.albumMid)
            }
            "kg" -> {
                val ids = parseKg(remoteId)
                put("hash", ids.hash)
                put("id", ids.hash)
                put("songmid", ids.hash)
                if (ids.albumId.isNotEmpty()) put("albumId", ids.albumId)
            }
            else -> {
                put("songmid", remoteId.trim())
                put("id", remoteId.trim())
            }
        }
        val outer = this
        put(
            "meta",
            JSONObject().apply {
                put("songId", outer.optString("songmid"))
                put("name", title)
                put("singer", artist)
                put("albumName", album)
                put("interval", sec)
                if (outer.has("hash")) put("hash", outer.optString("hash"))
                if (outer.has("albumId")) put("albumId", outer.optString("albumId"))
                if (outer.has("strMediaMid")) put("strMediaMid", outer.optString("strMediaMid"))
            }
        )
    }
}

private val META_TAG_REGEX =
    Regex("^\\s*(?:/\\*+!?|\\*+|//)?\\s*@(name|version|author|description|homepage)\\b[:\\s]*(.*?)\\s*(?:\\*/)?\\s*$", RegexOption.IGNORE_CASE)

/**
 * 解析洛雪脚本开头的 JSDoc 元信息（`@name`、`@version`、`@author`、`@description`、`@homepage`）。
 */
fun parseLxScriptMeta(rawScript: String, fallbackName: String): LxScriptMeta {
    val cleanFallback = fallbackName.removeSuffix(".js").removeSuffix(".JS").trim().ifBlank { "未命名洛雪音源" }
    val header = rawScript.removePrefix("\uFEFF").take(12_288)
    val fields = HashMap<String, String>()
    var linesScanned = 0
    for (line in header.lineSequence()) {
        if (++linesScanned > 160) break
        val m = META_TAG_REGEX.matchEntire(line) ?: continue
        val key = m.groupValues[1].lowercase(Locale.ROOT)
        val value = m.groupValues[2].trim()
        if (value.isNotEmpty() && !fields.containsKey(key)) {
            fields[key] = value
        }
    }
    return LxScriptMeta(
        name = fields["name"].orEmpty().ifBlank { cleanFallback },
        version = fields["version"].orEmpty(),
        author = fields["author"].orEmpty(),
        description = fields["description"].orEmpty(),
        homepage = fields["homepage"].orEmpty()
    )
}

private val DEC_ENTITY_REGEX = Regex("&#(\\d+);")
private val HEX_ENTITY_REGEX = Regex("&#x([0-9a-fA-F]+);")

/** 纯 Kotlin 解码酷我 / QQ 音乐接口常见的 HTML 字符实体。 */
fun decodeHtmlEntities(raw: String): String {
    if (raw.isEmpty() || '&' !in raw) return raw
    var s = raw
        .replace("&amp;", "&")
        .replace("&apos;", "'")
        .replace("&#39;", "'")
        .replace("&quot;", "\"")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&nbsp;", " ")
    s = DEC_ENTITY_REGEX.replace(s) { m ->
        val code = m.groupValues[1].toIntOrNull()
        if (code != null && code in 1..0x10FFFF) String(Character.toChars(code)) else m.value
    }
    s = HEX_ENTITY_REGEX.replace(s) { m ->
        val code = m.groupValues[1].toIntOrNull(16)
        if (code != null && code in 1..0x10FFFF) String(Character.toChars(code)) else m.value
    }
    return s
}

/** 将酷我 `lrclist` 里的秒数字符串（如 `"2.25"` / `"134.5"`）格式化成 `[mm:ss.xx]`。 */
fun formatKuwoLrcTimestamp(timeSecStr: String): String? {
    val sec = timeSecStr.trim().toDoubleOrNull() ?: return null
    if (sec.isNaN() || sec < 0.0) return null
    val totalMs = (sec * 1000.0).roundToLong().coerceAtLeast(0L)
    val minutes = totalMs / 60_000L
    val seconds = (totalMs % 60_000L) / 1000L
    val centis = (totalMs % 1000L) / 10L
    return String.format(Locale.US, "[%02d:%02d.%02d]", minutes, seconds, centis)
}

/** 将酷我 `lrclist`（(秒数字符串, 歌词正文) 列表）组装成标准 LRC 文本。 */
fun formatKuwoLrcList(items: List<Pair<String, String>>): String {
    if (items.isEmpty()) return ""
    val sb = StringBuilder(items.size * 32)
    for ((timeStr, rawLine) in items) {
        val tag = formatKuwoLrcTimestamp(timeStr) ?: continue
        val text = decodeHtmlEntities(rawLine).trim()
        sb.append(tag).append(text).append('\n')
    }
    return sb.toString().trimEnd()
}

/** 规范化酷我封面（优先拼出 500px 高清图；补全 `https://`）。 */
fun normalizeKuwoCoverUrl(raw: String?): String? {
    val t = raw?.trim().orEmpty()
    if (t.isEmpty() || t == "null") return null
    if (t.startsWith("http://", ignoreCase = true) || t.startsWith("https://", ignoreCase = true)) {
        return t
            .replace("http://", "https://", ignoreCase = true)
            .replace("/star/albumcover/120/", "/star/albumcover/500/")
            .replace("/star/albumcover/240/", "/star/albumcover/500/")
    }
    val slash = t.indexOf('/')
    if (slash > 0 && t.substring(0, slash).all { it.isDigit() }) {
        return "https://img4.kuwo.cn/star/albumcover/500/${t.substring(slash + 1)}"
    }
    return "https://img4.kuwo.cn/star/albumcover/500/${t.trimStart('/')}"
}

/** 规范化酷狗封面（把 `{size}` 模板替换成 `480`）。 */
fun normalizeKugouCoverUrl(raw: String?, size: Int = 480): String? {
    val t = raw?.trim().orEmpty()
    if (t.isEmpty() || t == "null") return null
    val sized = t.replace("{size}", size.toString())
    return if (sized.startsWith("http://", ignoreCase = true) || sized.startsWith("https://", ignoreCase = true)) {
        sized
    } else {
        null
    }
}

/** 根据 QQ 专辑 mid 拼接高清专辑封面。 */
fun normalizeQqAlbumCoverUrl(albumMid: String?): String? {
    val mid = albumMid?.trim().orEmpty()
    if (mid.isEmpty() || mid == "null") return null
    return "https://y.gtimg.cn/music/photo_new/T002R800x800M000$mid.jpg"
}

/** 剥除 JSONP 包装（如 `playlistinfoCallback({...})`），返回内部纯 JSON 文本。 */
fun stripJsonpCallback(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.startsWith("{") || trimmed.startsWith("[")) return trimmed
    val openParen = trimmed.indexOf('(')
    val closeParen = trimmed.lastIndexOf(')')
    if (openParen in 1 until closeParen) {
        return trimmed.substring(openParen + 1, closeParen).trim()
    }
    return trimmed
}
