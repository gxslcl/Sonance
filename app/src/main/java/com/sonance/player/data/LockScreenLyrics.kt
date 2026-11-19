package com.sonance.player.data

import org.json.JSONObject

/**
 * ColorOS / OPlus 原生锁屏歌词。
 *
 * 为什么能做：ColorOS 的锁屏歌词不是私有 SDK，而是**读播放器自己 MediaSession 上的
 * `MediaMetadata["lyricInfo"]`** —— 一段 JSON 字符串。播放器把它写进当前 MediaItem 的
 * metadata，系统 SystemUI 就能把歌词铺到锁屏和 AOD 上；不需要 root、不需要装模块、
 * 也不需要联网（歌词我们本来就有：内嵌标签或本地缓存）。
 *
 * 这段协议是社区从真机行为反推并公开的（见 ColorOS Live Lyrics Bridge 的
 * `PLAYER_INTEGRATION`），要点：
 * - `lyric` 必须是**带 `[mm:ss.SSS]` 时间标签的逐行 LRC**；`<>` 也可，推荐 `[]`；
 * - 行时间不得倒退；没有时间轴就别硬塞（设 `noLyric = true`）；
 * - 只写 extras，宿主原有的 title / artist / 封面等字段必须原样保留；
 * - 换歌时 `sessionGeneration` 单调递增，用来丢弃迟到的旧结果。
 *
 * ⚠️ 只做**逐行**歌词：逐字（`rawLyric`）需要每个字的绝对时间，我们的歌词源（内嵌
 * LRC / 网易云 LRC）大多只有行时间，伪造逐字扫光会比不做更难看。
 */
object LockScreenLyrics {

    /** 写进 `MediaMetadata` extras 的键名，**不能改** —— ColorOS / OPlus 系统认这个名字 */
    const val EXTRA_KEY = "lyricInfo"

    /** vivo OriginOS（原子随身听 / JoviInCar / 原子通道）读取的整段 LRC 歌词键名 */
    const val EXTRA_KEY_VIVO_WHOLE = "ucar.media.metadata.LYRICS_WHOLE"

    /** 通用系统 / 车载 / 第三方状态栏歌词扩展键 */
    const val EXTRA_KEY_GENERIC_LYRIC = "android.media.metadata.LYRIC"

    /** 诊断用：系统/桥接模块看到这个字段就知道歌词是谁写的 */
    private const val PROVIDER = "com.sonance.player"

    /**
     * 从已解析歌词（或纯时间轴行列表）构建标准 `[mm:ss.SSS]` 逐行 LRC 字符串，
     * 供 ColorOS `lyricInfo` 与 vivo 原子随身听 `ucar.media.metadata.LYRICS_WHOLE` 共用。
     */
    fun toLrcText(lyrics: Lyrics?): String? {
        val synced = (lyrics as? Lyrics.Synced)?.lines?.filter { it.text.isNotBlank() }
        return synced?.takeIf { it.isNotEmpty() }?.let { toLrc(it) }
    }

    fun toLrcText(lines: List<LyricLine>): String? {
        val cleaned = lines.filter { it.text.isNotBlank() }
        return if (cleaned.isEmpty()) null else toLrc(cleaned)
    }

    fun buildJson(
        song: Song,
        lyrics: Lyrics?,
        generation: Int
    ): String {
        val lrc = toLrcText(lyrics)
        return buildJsonFromLrc(
            songId = song.id,
            title = song.title,
            artist = song.artist,
            album = song.album,
            durationMs = song.durationMs,
            lrc = lrc,
            generation = generation
        )
    }

    fun buildJsonFromLrc(
        songId: Long,
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        lrc: String?,
        generation: Int
    ): String {
        return JSONObject().apply {
            put("songName", title)
            put("artist", artist)
            if (album.isNotBlank()) put("album", album)
            put("songId", songId.toString())
            // 0 = 标准时间轴
            put("lyricType", 0)
            if (!lrc.isNullOrBlank()) {
                put("lyric", lrc)
                put("noLyric", false)
            } else {
                put("noLyric", true)
            }
            put("provider", PROVIDER)
            put("source", "$PROVIDER-v1")
            // 稳定性键：同一会话里用它拒绝过期 payload
            put("trackKey", "${songId}|${title}|${artist}|${durationMs / 1000}")
            put("sessionGeneration", generation)
        }.toString()
    }

    /**
     * 把解析后的行重新拼成 LRC。
     *
     * 时间格式统一 `mm:ss.SSS`：分和秒都补零，毫秒三位 —— 这是各家用得最稳的写法。
     * 行起点严格不倒序（歌词源里有重复/乱序时排序一次），否则系统解析器可能整段丢掉。
     */
    private fun toLrc(lines: List<LyricLine>): String {
        val sorted = lines.sortedBy { it.timeMs }
        val sb = StringBuilder()
        for (line in sorted) {
            val ms = line.timeMs.coerceAtLeast(0L)
            val totalSec = ms / 1000
            val min = totalSec / 60
            val sec = totalSec % 60
            val milli = ms % 1000
            sb.append('[')
                .append(min.toString().padStart(2, '0')).append(':')
                .append(sec.toString().padStart(2, '0')).append('.')
                .append(milli.toString().padStart(3, '0'))
                .append(']')
                .append(line.text.replace('\n', ' '))
                .append('\n')
        }
        return sb.toString()
    }
}
