package com.sonance.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ListenStatsTest {

    private val today: LocalDate = LocalDate.of(2026, 11, 17)
    private val zone: ZoneId = ZoneId.of("UTC")
    private val todayEpoch = today.toEpochDay()

    @Test
    fun `codec round trips days and songs`() {
        val days = listOf(ListenDay(todayEpoch - 1, 600L, 2), ListenDay(todayEpoch, 120L, 1))
        val songs = listOf(SongListenStat(5L, "静止", "杨乃文", 3, 1000L))
        assertEquals(days, ListenStatsCodec.decodeDays(ListenStatsCodec.encodeDays(days)))
        assertEquals(songs, ListenStatsCodec.decodeSongs(ListenStatsCodec.encodeSongs(songs)))
        assertTrue(ListenStatsCodec.decodeDays("broken").isEmpty())
    }

    @Test
    fun `week summary totals bars and streak`() {
        val days = listOf(
            ListenDay(todayEpoch - 2, 1800L, 3),
            ListenDay(todayEpoch - 1, 3600L, 5),
            ListenDay(todayEpoch, 600L, 1)
        )
        val s = ListenStatsAggregator.summarize(days, emptyList(), StatsRange.WEEK, today, zone)
        assertEquals(6000L, s.totalSeconds)
        assertEquals(9, s.totalPlays)
        assertEquals(3, s.streakDays)
        assertEquals(7, s.bars.size)
        assertEquals("今日", s.bars.last().label)
        assertTrue(s.bars.last().highlight)
        assertEquals(600L, s.bars.last().seconds)
        assertEquals("1小时40分", s.totalText)
    }

    @Test
    fun `streak survives empty today by counting from yesterday`() {
        val days = listOf(ListenDay(todayEpoch - 1, 60L, 1), ListenDay(todayEpoch - 2, 60L, 1))
        val s = ListenStatsAggregator.summarize(days, emptyList(), StatsRange.WEEK, today, zone)
        assertEquals(2, s.streakDays)
    }

    @Test
    fun `week range excludes older days`() {
        val days = listOf(ListenDay(todayEpoch - 7, 9999L, 9), ListenDay(todayEpoch, 60L, 1))
        val s = ListenStatsAggregator.summarize(days, emptyList(), StatsRange.WEEK, today, zone)
        assertEquals(60L, s.totalSeconds)
        assertEquals(1, s.totalPlays)
    }

    @Test
    fun `month summary buckets 30 days into 6 bars`() {
        val days = (0..29).map { ListenDay(todayEpoch - it, 60L, 1) }
        val s = ListenStatsAggregator.summarize(days, emptyList(), StatsRange.MONTH, today, zone)
        assertEquals(6, s.bars.size)
        assertEquals(1800L, s.totalSeconds)
        assertEquals(300L, s.bars.last().seconds)
    }

    @Test
    fun `top songs and artists filtered by period`() {
        val fromMillis = today.minusDays(6).atStartOfDay(zone).toInstant().toEpochMilli()
        val songs = listOf(
            SongListenStat(1L, "旧歌", "老歌手", 99, fromMillis - 1),
            SongListenStat(2L, "绿岛小夜曲", "赵鹏", 4, fromMillis + 1000),
            SongListenStat(3L, "雨爱", "杨丞琳", 7, fromMillis + 2000),
            SongListenStat(4L, "另一首", "赵鹏", 2, fromMillis + 3000)
        )
        val s = ListenStatsAggregator.summarize(emptyList(), songs, StatsRange.WEEK, today, zone)
        assertEquals(listOf(3L, 2L, 4L), s.topSongs.map { it.songId })
        assertEquals("杨丞琳", s.topArtists.first().first)
        assertEquals(7, s.topArtists.first().second)
        assertEquals("赵鹏", s.topArtists[1].first)
        assertEquals(6, s.topArtists[1].second)
    }

    @Test
    fun `ledger counts forward playback only`() {
        val ledger = ListenLedger()
        ledger.observe(1L, 0L, playing = true)
        ledger.observe(1L, 500L, playing = true)
        ledger.observe(1L, 1000L, playing = true)
        assertEquals(1L, ledger.drainSeconds())
        // 暂停不计
        ledger.observe(1L, 5000L, playing = false)
        ledger.observe(1L, 5500L, playing = true)
        assertEquals(0L, ledger.drainSeconds())
        // 拖动大跳变不计；跳变前的 500ms 余数保留，加上跳变后的 500ms 正好 1 秒
        ledger.observe(1L, 60_000L, playing = true)
        ledger.observe(1L, 60_500L, playing = true)
        assertEquals(1L, ledger.drainSeconds())
        // 切歌重置基准，不拿上一首的进度差记账
        ledger.observe(2L, 100_000L, playing = true)
        ledger.observe(2L, 100_500L, playing = true)
        assertEquals(0L, ledger.drainSeconds())
    }

    @Test
    fun `ledger keeps sub second remainder across drains`() {
        val ledger = ListenLedger()
        ledger.observe(1L, 0L, playing = true)
        ledger.observe(1L, 600L, playing = true)
        assertEquals(0L, ledger.drainSeconds())
        ledger.observe(1L, 1200L, playing = true)
        assertEquals(1L, ledger.drainSeconds())
        ledger.observe(1L, 1600L, playing = true)
        assertEquals(0L, ledger.drainSeconds())
    }

    @Test
    fun `session clock tracks background playback without 500ms ui polling`() {
        val clock = SessionListenClock()
        clock.onState(101L, isPlaying = true, nowElapsedMs = 1_000L)
        // 退后台 4 分钟无任何 ticker 事件，直接切歌或唤醒结算也必须完整计入 240 秒
        clock.onState(102L, isPlaying = true, nowElapsedMs = 241_000L)
        assertEquals(240L, clock.drainSeconds(nowElapsedMs = 241_000L))

        // 暂停期间 10 分钟不计入；播半秒后的余数保留到下次凑满整秒
        clock.onState(102L, isPlaying = false, nowElapsedMs = 241_500L)
        assertEquals(0L, clock.drainSeconds(nowElapsedMs = 841_500L))
        clock.onState(102L, isPlaying = true, nowElapsedMs = 841_500L)
        assertEquals(1L, clock.drainSeconds(nowElapsedMs = 842_000L))
    }
}