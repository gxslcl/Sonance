package com.sonance.player.data

/** 歌单导入解析出的一个候选条目（还没匹配曲库）。 */
data class ImportCandidate(
    /** 原始展示文本（未匹配提示用） */
    val display: String,
    val title: String? = null,
    val artist: String? = null,
    /** 文件路径或文件名（.m3u / .pls 里的条目） */
    val path: String? = null
)

/**
 * 解析 .m3u / .m3u8 / .pls / 纯文本歌单（纯逻辑，JVM 可测）。
 *
 * - `#EXTINF:时长,歌手 - 歌名` 取逗号后的展示名，下一行非注释行是路径；
 * - .pls 的 `FileN=` / `TitleN=` 成对取；
 * - 其余非注释行按「歌手 - 歌名」或文件名处理。
 */
object PlaylistImportParser {

    fun parse(text: String): List<ImportCandidate> {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.firstOrNull()?.equals("[playlist]", ignoreCase = true) == true) {
            return parsePls(lines)
        }
        val out = mutableListOf<ImportCandidate>()
        var pendingDisplay: String? = null
        for (line in lines) {
            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    pendingDisplay = line.substringAfter(',', "").trim().takeIf { it.isNotEmpty() }
                }
                line.startsWith("#") -> Unit
                else -> {
                    val display = pendingDisplay ?: line
                    pendingDisplay = null
                    out.add(candidateFrom(display, path = line))
                }
            }
        }
        if (pendingDisplay != null) out.add(candidateFrom(pendingDisplay!!, path = null))
        return out
    }

    private fun parsePls(lines: List<String>): List<ImportCandidate> {
        val files = mutableMapOf<Int, String>()
        val titles = mutableMapOf<Int, String>()
        for (line in lines) {
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq).trim()
            val value = line.substring(eq + 1).trim()
            val num = key.filter { it.isDigit() }.toIntOrNull() ?: continue
            when {
                key.startsWith("File", ignoreCase = true) -> files[num] = value
                key.startsWith("Title", ignoreCase = true) -> titles[num] = value
            }
        }
        return files.keys.sorted().mapNotNull { n ->
            val path = files[n]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val display = titles[n]?.takeIf { it.isNotBlank() } ?: path
            candidateFrom(display, path)
        }
    }

    private fun candidateFrom(display: String, path: String?): ImportCandidate {
        val namePart = display.substringAfterLast('/').substringAfterLast('\\')
        val stem = namePart.substringBeforeLast('.', namePart)
        val dash = splitArtistTitle(stem) ?: splitArtistTitle(display)
        return ImportCandidate(
            display = display,
            title = dash?.second ?: stem.takeIf { it.isNotBlank() },
            artist = dash?.first,
            path = path
        )
    }

    /** 「歌手 - 歌名」→ (歌手, 歌名)；没有分隔符返回 null。 */
    fun splitArtistTitle(text: String): Pair<String, String>? {
        for (sep in listOf(" - ", "-")) {
            val i = text.indexOf(sep)
            if (i > 0 && i < text.length - sep.length) {
                val a = text.substring(0, i).trim()
                val t = text.substring(i + sep.length).trim()
                if (a.isNotEmpty() && t.isNotEmpty()) return a to t
            }
        }
        return null
    }

    private val NETEASE_ID_REGEX = Regex("(?:playlist\\?id=|playlist/)(\\d+)")

    /**
     * 从网易云歌单链接里取歌单 id。
     * 支持 `music.163.com/#/playlist?id=`、`y.music.163.com/m/playlist?id=`、
     * `music.163.com/playlist/<id>` 及带分享文案的字符串；取不到返回 null。
     */
    fun neteasePlaylistId(url: String): String? {
        val target = OnlinePlaylistImporter.extractFirstHttpUrl(url) ?: url
        if (!target.contains("163.com", ignoreCase = true) && !target.contains("163cn.tv", ignoreCase = true)) {
            return null
        }
        return NETEASE_ID_REGEX.find(target)?.groupValues?.get(1)
    }
}

/** 在线歌单链接导入的预览结果（确认导入前给用户看的）。 */
data class UrlImportPreview(
    val playlistName: String,
    val matchedLocalIds: List<Long>,
    /** 「本地优先 + 在线补齐」模式下，没命中本地、以在线形态补进来的歌 */
    val onlineEntries: List<StoredOnlineSong>,
    /** 两种都没收进来的条目展示名（仅匹配本地模式下的未命中） */
    val unmatchedDisplays: List<String>
)

data class ImportMatchResult(
    val matchedLocalIds: List<Long>,
    val unmatched: List<ImportCandidate>
)

/** 候选条目 ↔ 本地曲库匹配（纯逻辑）：先按文件名/路径，再按「歌名（+歌手）」模糊命中。 */
object PlaylistImportMatcher {

    fun match(candidates: List<ImportCandidate>, songs: List<Song>): ImportMatchResult {
        val matched = mutableListOf<Long>()
        val unmatched = mutableListOf<ImportCandidate>()
        val seen = HashSet<Long>()
        for (c in candidates) {
            val hit = findSong(c, songs)
            if (hit != null && seen.add(hit.id)) matched.add(hit.id)
            else if (hit == null) unmatched.add(c)
        }
        return ImportMatchResult(matched, unmatched)
    }

    fun findMatchingSong(candidate: ImportCandidate, songs: List<Song>): Song? =
        findSong(candidate, songs)

    private fun findSong(c: ImportCandidate, songs: List<Song>): Song? {
        val path = c.path?.lowercase()
        if (!path.isNullOrBlank()) {
            val byName = songs.firstOrNull { s ->
                path.endsWith(s.displayName.lowercase()) ||
                    s.displayName.lowercase().endsWith(path.substringAfterLast('/'))
            }
            if (byName != null) return byName
        }
        val title = normalize(c.title ?: return null)
        if (title.isEmpty()) return null
        val artist = c.artist?.let { normalize(it) }
        val titleHits = songs.filter { s ->
            val st = normalize(s.title)
            st == title || st.contains(title) || title.contains(st)
        }
        if (titleHits.isEmpty()) return null
        if (artist.isNullOrEmpty()) return titleHits.first()
        return titleHits.firstOrNull { normalize(it.artist).contains(artist) || artist.contains(normalize(it.artist)) }
            ?: titleHits.first()
    }

    private fun normalize(s: String): String =
        s.lowercase().filter { it.isLetterOrDigit() }
}