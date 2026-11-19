package com.sonance.player.data

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

/** 某一天的听歌统计。[epochDay] 是距 1970-01-01 的天数，范围计算不用解析字符串。 */
data class ListenDay(val epochDay: Long, val seconds: Long, val plays: Int)

/** 单曲累计收听记录（Top 榜单用；独立于曲库，在线歌也能上榜）。 */
data class SongListenStat(
    val songId: Long,
    val title: String,
    val artist: String,
    val plays: Int,
    val lastAt: Long
)

enum class StatsRange(val label: String) { WEEK("本周"), MONTH("本月"), ALL("全部") }

/** 柱状图的一根柱子。 */
data class StatsBar(val label: String, val seconds: Long, val highlight: Boolean = false)

data class StatsSummary(
    val totalSeconds: Long,
    val totalPlays: Int,
    val streakDays: Int,
    val bars: List<StatsBar>,
    val topSongs: List<SongListenStat>,
    val topArtists: List<Pair<String, Int>>
) {
    val totalText: String get() {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        return if (h > 0) "${h}小时${m}分" else "${m}分钟"
    }
}

object ListenStatsCodec {
    fun encodeDays(list: List<ListenDay>): String {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("d", it.epochDay).put("s", it.seconds).put("p", it.plays))
        }
        return arr.toString()
    }

    fun decodeDays(raw: String?): List<ListenDay> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val d = o.optLong("d", -1L)
                    if (d < 0) continue
                    add(ListenDay(d, o.optLong("s").coerceAtLeast(0L), o.optInt("p").coerceAtLeast(0)))
                }
            }
        }.getOrDefault(emptyList())
    }

    fun encodeSongs(list: List<SongListenStat>): String {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.songId)
                    .put("t", it.title)
                    .put("a", it.artist)
                    .put("p", it.plays)
                    .put("at", it.lastAt)
            )
        }
        return arr.toString()
    }

    fun decodeSongs(raw: String?): List<SongListenStat> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(
                        SongListenStat(
                            songId = o.optLong("id"),
                            title = o.optString("t"),
                            artist = o.optString("a"),
                            plays = o.optInt("p").coerceAtLeast(0),
                            lastAt = o.optLong("at")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }
}

/** 纯聚合逻辑：按周/月/全部汇总天数与单曲记录，JVM 单测用固定日期钉住。 */
object ListenStatsAggregator {

    fun summarize(
        days: List<ListenDay>,
        songs: List<SongListenStat>,
        range: StatsRange,
        today: LocalDate,
        zone: ZoneId = ZoneId.systemDefault()
    ): StatsSummary {
        val todayEpoch = today.toEpochDay()
        val fromEpoch = when (range) {
            StatsRange.WEEK -> todayEpoch - 6
            StatsRange.MONTH -> todayEpoch - 29
            StatsRange.ALL -> days.minOfOrNull { it.epochDay } ?: todayEpoch
        }
        val inRange = days.filter { it.epochDay in fromEpoch..todayEpoch }
        val totalSeconds = inRange.sumOf { it.seconds }
        val totalPlays = inRange.sumOf { it.plays }

        val bars = when (range) {
            StatsRange.WEEK -> (0..6).map { i ->
                val epoch = todayEpoch - 6 + i
                val date = LocalDate.ofEpochDay(epoch)
                val label = if (i == 6) "今日" else when (date.dayOfWeek.value) {
                    1 -> "周一"; 2 -> "周二"; 3 -> "周三"; 4 -> "周四"
                    5 -> "周五"; 6 -> "周六"; else -> "周日"
                }
                StatsBar(label, inRange.firstOrNull { it.epochDay == epoch }?.seconds ?: 0L, i == 6)
            }
            StatsRange.MONTH -> (0..5).map { b ->
                val start = todayEpoch - 29 + b * 5
                val end = minOf(start + 4, todayEpoch)
                val sec = inRange.filter { it.epochDay in start..end }.sumOf { it.seconds }
                val d = LocalDate.ofEpochDay(start)
                StatsBar("${d.monthValue}/${d.dayOfMonth}", sec, b == 5)
            }
            StatsRange.ALL -> {
                val firstMonth = LocalDate.ofEpochDay(fromEpoch).withDayOfMonth(1)
                val months = java.time.temporal.ChronoUnit.MONTHS.between(firstMonth, today.withDayOfMonth(1)).toInt() + 1
                val shown = months.coerceAtMost(12)
                ((months - shown) until months).map { m ->
                    val month = firstMonth.plusMonths(m.toLong())
                    val next = month.plusMonths(1)
                    val sec = inRange.filter {
                        val d = LocalDate.ofEpochDay(it.epochDay)
                        !d.isBefore(month) && d.isBefore(next)
                    }.sumOf { it.seconds }
                    StatsBar("${month.monthValue}月", sec, m == months - 1)
                }
            }
        }

        var streak = 0
        var cursor = todayEpoch
        if (inRange.none { it.epochDay == todayEpoch && it.seconds > 0 }) cursor -= 1
        val dayMap = days.associateBy { it.epochDay }
        while ((dayMap[cursor]?.seconds ?: 0L) > 0L) {
            streak++
            cursor--
        }

        val fromMillis = LocalDate.ofEpochDay(fromEpoch).atStartOfDay(zone).toInstant().toEpochMilli()
        val periodSongs = songs.filter { it.lastAt >= fromMillis && it.plays > 0 }
        val topSongs = periodSongs.sortedByDescending { it.plays }.take(5)
        val topArtists = periodSongs
            .filter { it.artist.isNotBlank() }
            .groupBy { it.artist }
            .map { (artist, list) -> artist to list.sumOf { it.plays } }
            .sortedByDescending { it.second }
            .take(4)

        return StatsSummary(totalSeconds, totalPlays, streak, bars, topSongs, topArtists)
    }
}

/**
 * 会话内「真实出声时长」记账（纯逻辑，可单测）。
 *
 * 只累计**播放中**的**前向**进度：暂停不计；拖动进度条（大跳变）不计；
 * 切歌时重置基准。毫秒在内部累计，[drainSeconds] 只取整秒、余数留到下次，
 * 避免 500ms 一跳的 ticker 每次都被整除截没。
 */
class ListenLedger(private val maxStepMs: Long = 2500L) {
    private var songId = -1L
    private var lastPos = -1L
    private var pendingMs = 0L

    fun observe(id: Long, positionMs: Long, playing: Boolean) {
        if (id != songId) {
            songId = id
            lastPos = positionMs
            return
        }
        val delta = positionMs - lastPos
        lastPos = positionMs
        if (playing && delta in 1..maxStepMs) pendingMs += delta
    }

    fun drainSeconds(): Long {
        val s = pendingMs / 1000
        pendingMs %= 1000
        return s
    }

    fun reset() {
        songId = -1L
        lastPos = -1L
        pendingMs = 0L
    }
}

/**
 * 基于单调时钟的事件驱动听歌计时器（不依赖前台 500ms UI 轮询）：
 * 只在播放状态或曲目切换时根据经过的 `elapsedRealtime` 结算真实播放时长，
 * 应用退居后台 / 暂停时零定时唤醒，统计时长仍完全准确。
 */
class SessionListenClock(private val maxSegmentMs: Long = 12L * 3600_000L) {
    private var activeSongId: Long = -1L
    private var playingSinceElapsedMs: Long = -1L
    private var pendingMs: Long = 0L

    fun onState(songId: Long, isPlaying: Boolean, nowElapsedMs: Long) {
        if (activeSongId != -1L && activeSongId != 0L && playingSinceElapsedMs >= 0L) {
            pendingMs += (nowElapsedMs - playingSinceElapsedMs).coerceIn(0L, maxSegmentMs)
        }
        activeSongId = songId
        playingSinceElapsedMs = if (isPlaying && songId != -1L && songId != 0L) nowElapsedMs else -1L
    }

    fun drainSeconds(nowElapsedMs: Long): Long {
        if (activeSongId != -1L && activeSongId != 0L && playingSinceElapsedMs >= 0L) {
            pendingMs += (nowElapsedMs - playingSinceElapsedMs).coerceIn(0L, maxSegmentMs)
            playingSinceElapsedMs = nowElapsedMs
        }
        val s = pendingMs / 1000L
        pendingMs %= 1000L
        return s
    }

    fun reset() {
        activeSongId = -1L
        playingSinceElapsedMs = -1L
        pendingMs = 0L
    }
}

/** 听歌统计持久化（JSON-in-SP）。天数保留 400 天、单曲保留 600 条，防止无限膨胀。 */
class ListenStatsStore(private val sp: SharedPreferences) {

    fun days(): List<ListenDay> = ListenStatsCodec.decodeDays(sp.getString(KEY_DAYS, null))

    fun songStats(): List<SongListenStat> = ListenStatsCodec.decodeSongs(sp.getString(KEY_SONGS, null))

    fun addSeconds(epochDay: Long, seconds: Long) {
        if (seconds <= 0L) return
        val list = days().toMutableList()
        val i = list.indexOfFirst { it.epochDay == epochDay }
        if (i >= 0) list[i] = list[i].copy(seconds = list[i].seconds + seconds)
        else list.add(ListenDay(epochDay, seconds, 0))
        writeDays(list)
    }

    fun recordPlay(songId: Long, title: String, artist: String, atMillis: Long, epochDay: Long) {
        val songs = songStats().toMutableList()
        val i = songs.indexOfFirst { it.songId == songId }
        if (i >= 0) {
            songs[i] = songs[i].copy(
                title = title.ifBlank { songs[i].title },
                artist = artist.ifBlank { songs[i].artist },
                plays = songs[i].plays + 1,
                lastAt = atMillis
            )
        } else {
            songs.add(SongListenStat(songId, title, artist, 1, atMillis))
        }
        songs.sortByDescending { it.lastAt }
        sp.edit().putString(KEY_SONGS, ListenStatsCodec.encodeSongs(songs.take(MAX_SONGS))).apply()

        val list = days().toMutableList()
        val j = list.indexOfFirst { it.epochDay == epochDay }
        if (j >= 0) list[j] = list[j].copy(plays = list[j].plays + 1)
        else list.add(ListenDay(epochDay, 0L, 1))
        writeDays(list)
    }

    fun summary(
        range: StatsRange,
        today: LocalDate = LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault()
    ): StatsSummary = ListenStatsAggregator.summarize(days(), songStats(), range, today, zone)

    private fun writeDays(list: MutableList<ListenDay>) {
        list.sortBy { it.epochDay }
        while (list.size > MAX_DAYS) list.removeAt(0)
        sp.edit().putString(KEY_DAYS, ListenStatsCodec.encodeDays(list)).apply()
    }

    companion object {
        private const val KEY_DAYS = "listen_days"
        private const val KEY_SONGS = "listen_song_stats"
        private const val MAX_DAYS = 400
        private const val MAX_SONGS = 600
    }
}