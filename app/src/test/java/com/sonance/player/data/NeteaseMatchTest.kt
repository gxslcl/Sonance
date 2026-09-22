package com.sonance.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用网易云给本地歌补封面 / 歌词时的**匹配与歌词清洗**逻辑。
 *
 * 这块错了不会报错，只会**静默地匹配到另一首歌**：封面贴成别的专辑、
 * 歌词整体对不上。而用户很难判断是"匹配错了"还是"网易云的数据就这样"，
 * 所以各种边界必须在 JVM 上穷举掉。
 *
 * 用例里的数据都来自实测（搜索"晴天"、"500 Miles"、"味道 (live)" 的真实返回）。
 */
class NeteaseMatchTest {

    private fun track(
        id: String,
        title: String,
        artist: String,
        durationMs: Long
    ) = RemoteTrack(id, title, artist, "专辑", durationMs, null, playable = true, vip = false)

    // ===== 归一化 =====

    @Test
    fun `归一化去大小写和标点`() {
        assertEquals("shapeofyou", NeteaseMatch.normalize("Shape of You"))
        assertEquals("shapeofyou", NeteaseMatch.normalize("Shape Of You!"))
        assertEquals("晴天", NeteaseMatch.normalize(" 晴天 "))
    }

    @Test
    fun `归一化去掉本地文件名常见的开头序号`() {
        // 本地文件常常是 `01 晴天` / `01. 晴天` / `[01] 晴天`，
        // 这些前缀会把标题相似度整体拉低，导致本该匹配上的歌被打低分
        assertEquals("晴天", NeteaseMatch.normalize("01 晴天"))
        assertEquals("晴天", NeteaseMatch.normalize("01. 晴天"))
        assertEquals("晴天", NeteaseMatch.normalize("[01] 晴天"))
        assertEquals("晴天", NeteaseMatch.normalize("01 - 晴天"))
    }

    @Test
    fun `数字开头的歌名会被同样地削掉前缀`() {
        // `500 Miles` 的 "500 " 会被当成音轨序号删掉。这是可接受的 ——
        // **两边都走同一个归一化**，比较依然成立；反过来为它加例外规则
        // 反而会让 `01 晴天` 这类真正的前缀漏网
        assertEquals("miles", NeteaseMatch.normalize("500 Miles"))
        // 汉字开头的标题完全不受影响
        assertEquals("晴天", NeteaseMatch.normalize("晴天"))
    }

    // ===== 版本标记 =====

    @Test
    fun `认得出常见的版本标记`() {
        assertEquals("live", NeteaseMatch.versionTag("味道 (Live)"))
        assertEquals("instrumental", NeteaseMatch.versionTag("晴天 伴奏"))
        assertEquals("remix", NeteaseMatch.versionTag("Yellow (Remix)"))
        assertEquals("acoustic", NeteaseMatch.versionTag("Hotel California (Acoustic)"))
        assertNull(NeteaseMatch.versionTag("晴天"))
    }

    @Test
    fun `同义的中英版本写法归到同一组`() {
        // ★ 这条是设计缺陷的回归测试。第一版按字面比较：
        // 本地 `味道 (live)` 的标记是 "live"、线上 `味道 演唱会现场版` 的是 "现场"，
        // 两者不相等 → 互罚 45 分 → 同一个版本却匹配不上。
        // 必须归并到规范组才能对上。
        assertEquals(
            NeteaseMatch.versionTag("味道 (live)"),
            NeteaseMatch.versionTag("味道 演唱会现场版")
        )
        assertEquals(
            NeteaseMatch.versionTag("晴天 伴奏"),
            NeteaseMatch.versionTag("晴天 Instrumental")
        )
        assertEquals(
            NeteaseMatch.versionTag("Yellow 混音版"),
            NeteaseMatch.versionTag("Yellow Remix")
        )
    }

    @Test
    fun `中英写法的同版本能互相匹配`() {
        // 用打分端再验一次：本地 "(live)"、线上 "演唱会现场版"，版本项不该扣分
        val s = NeteaseMatch.score(
            candidateTitle = "味道 演唱会现场版",
            candidateArtist = "张学友",
            candidateDurationMs = 207_000L,
            title = "味道 (live)", artist = "张学友", durationMs = 207_000L
        )
        assertTrue("同版本不该被罚，实际 $s", s >= NeteaseMatch.MIN_SCORE)
    }

    // ===== 打分 =====

    @Test
    fun `标题歌手时长都对得上时得高分`() {
        val s = NeteaseMatch.score("晴天", "周杰伦", 269_000L, "晴天", "周杰伦", 269_000L)
        assertTrue("应该远超阈值，实际 $s", s >= NeteaseMatch.MIN_SCORE)
        assertEquals(200, s)   // 100 标题 + 50 歌手 + 40 时长 + 10 版本
    }

    @Test
    fun `录音室版匹配不到 Live 版`() {
        // ★ 最关键的一条。本地是录音室版、线上是 Live 版：
        // 标题能拿 60 分、歌手 50 分，但时长差 31 秒必须把它压到阈值以下 ——
        // 否则歌词时间轴会整体错位（Live 版前面多一段说话）
        val s = NeteaseMatch.score(
            candidateTitle = "晴天 (Live)",
            candidateArtist = "周杰伦",
            candidateDurationMs = 300_000L,
            title = "晴天", artist = "周杰伦", durationMs = 269_000L
        )
        assertTrue("时长差 31 秒就该被排除，实际得分 $s", s < NeteaseMatch.MIN_SCORE)
    }

    @Test
    fun `本地是 Live 版时优先匹配 Live 版`() {
        // 反过来也要成立：用户本地就是 Live，别给他贴录音室版的歌词
        val s = NeteaseMatch.score(
            candidateTitle = "味道 (Live)",
            candidateArtist = "张学友",
            candidateDurationMs = 207_000L,
            title = "味道 (live)", artist = "张学友", durationMs = 207_000L
        )
        assertTrue("实际 $s", s >= NeteaseMatch.MIN_SCORE)
        // 两边都带 live，版本项给的是 +10 而不是 -45
        assertEquals(200, s)
    }

    @Test
    fun `同名翻唱因为歌手和时长都对不上被排除`() {
        // 实测搜"晴天"第一条就是「晴天(深情版)」by Lucky小爱，时长也差 10 秒
        val s = NeteaseMatch.score(
            candidateTitle = "晴天(深情版)",
            candidateArtist = "Lucky小爱",
            candidateDurationMs = 278_961L,
            title = "晴天", artist = "周杰伦", durationMs = 269_000L
        )
        assertTrue("实际 $s", s < NeteaseMatch.MIN_SCORE)
    }

    @Test
    fun `本地没有歌手标签时靠标题和时长也能匹配`() {
        // 本地文件的 artist 经常是空的 / "未知艺术家"。这时不该扣分，
        // 靠"标题完全相等 + 时长一致"就足以认定
        val s = NeteaseMatch.score("晴天", "周杰伦", 269_000L, "晴天", "", 269_000L)
        assertTrue("实际 $s", s >= NeteaseMatch.MIN_SCORE)
    }

    @Test
    fun `时长未知时不会因为时长项被排除`() {
        // 拿不到时长（0）就不参与判断，不能算成"差得离谱"
        val s = NeteaseMatch.score("晴天", "周杰伦", 269_000L, "晴天", "周杰伦", 0L)
        assertTrue("实际 $s", s >= NeteaseMatch.MIN_SCORE)
    }

    @Test
    fun `标题完全无关时得负分`() {
        val s = NeteaseMatch.score("七里香", "周杰伦", 299_000L, "晴天", "周杰伦", 269_000L)
        assertTrue("实际 $s", s < NeteaseMatch.MIN_SCORE)
    }

    // ===== 挑选 =====

    @Test
    fun `从一堆候选里挑出正确的那条`() {
        // 实测搜「500 Miles The Proclaimers」第一条是个同名翻唱、时长对不上，
        // 直接取第一条就会贴错封面、拿到空歌词
        val candidates = listOf(
            track("1", "500 Miles", "Some Cover Band", 180_000L),
            track("2", "500 Miles (Live)", "The Proclaimers", 260_000L),
            track("3", "500 Miles", "The Proclaimers", 214_000L),
            track("4", "500 Miles", "Another Artist", 214_000L)
        )
        val best = NeteaseMatch.pickBest(candidates, "500 Miles", "The Proclaimers", 214_000L)
        assertEquals("3", best?.remoteId)
    }

    @Test
    fun `一个都认不出来时返回 null 而不是硬塞第一条`() {
        // 宁可"没找到"，也不要贴一张错的封面 —— 后者用户很难发现
        val candidates = listOf(
            track("1", "完全不相干的歌", "别人", 100_000L),
            track("2", "另一首", "另一个人", 200_000L)
        )
        assertNull(NeteaseMatch.pickBest(candidates, "晴天", "周杰伦", 269_000L))
    }

    @Test
    fun `空候选返回 null`() {
        assertNull(NeteaseMatch.pickBest(emptyList(), "晴天", "周杰伦", 269_000L))
    }

    // ===== 语种判断（决定先问哪个源）=====

    @Test
    fun `中日韩文本能被认出来`() {
        assertTrue(hasCjkText("晴天"))
        assertTrue(hasCjkText("夢ならばどれほどよかったでしょう"))
        assertTrue(hasCjkText("봄날"))
        // 标题是英文但歌手是中文，同样算东亚曲目
        assertTrue(hasCjkText("Yellow", "周杰伦"))
    }

    @Test
    fun `纯西文不会被误判`() {
        assertFalse(hasCjkText("Shape of You", "Ed Sheeran"))
        assertFalse(hasCjkText(""))
        // 全角标点不算汉字，别把标点当语种依据
        assertFalse(hasCjkText("Hello！"))
    }

    // ===== 歌词清洗：制作人员名单 =====

    @Test
    fun `摘掉开头的制作人员名单`() {
        // 实测 Lemon 的歌词前四行就是这个，而且带假时间戳（0.0~0.6 秒）
        val lrc = """
            [00:00.000] 作词 : 米津玄師
            [00:00.212] 作曲 : 米津玄師
            [00:00.424] 编曲 : 米津玄師/室屋光一郎
            [00:00.636] 制作人 : 米津玄師
            [00:00.851]夢ならばどれほどよかったでしょう
            [00:06.650]未だにあなたのことを夢にみる
        """.trimIndent()
        val out = stripCreditLines(lrc)
        assertFalse("名单不该留下", out.contains("作词"))
        assertFalse(out.contains("制作人"))
        assertTrue("正文必须保留", out.startsWith("[00:00.851]夢ならば"))
        assertTrue(out.contains("未だにあなたのことを夢にみる"))
    }

    @Test
    fun `只清开头的名单，不动正文中间`() {
        // 万一正文里出现"作词 :"这种写法（极罕见），也不该被删
        val lrc = """
            [00:01.000]第一句
            [00:02.000] 作词 : 某人
            [00:03.000]第三句
        """.trimIndent()
        val out = stripCreditLines(lrc)
        assertTrue("开头不是名单，一个字都不该动", out == lrc)
    }

    @Test
    fun `没有名单时原样返回`() {
        val lrc = "[00:01.000]第一句\n[00:02.000]第二句"
        assertEquals(lrc, stripCreditLines(lrc))
    }

    @Test
    fun `全是名单时返回空`() {
        val lrc = "[00:00.000] 作词 : A\n[00:00.100] 作曲 : B"
        assertTrue(stripCreditLines(lrc).isBlank())
    }

    // ===== 歌词清洗：翻译合并 =====

    @Test
    fun `翻译按时间戳并到原文同一行`() {
        val lrc = "[00:00.851]夢ならばどれほどよかったでしょう\n[00:06.650]未だにあなたのことを夢にみる"
        val tr = "[00:00.851]如果这一切都是梦境该有多好\n[00:06.650]至今仍能与你在梦中相遇"
        val out = mergeTranslation(lrc, tr)
        val lines = out.split('\n')
        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("[00:00.851]夢ならばどれほどよかったでしょう / "))
        assertTrue(lines[0].endsWith("如果这一切都是梦境该有多好"))
        assertTrue(lines[1].endsWith("至今仍能与你在梦中相遇"))
    }

    @Test
    fun `合并后仍然是一行一个时间戳`() {
        // 这是"为什么并到同一行"的核心：解析器按行取时间戳，
        // 译文如果另起一行（无时间戳）会被整行丢掉
        val out = mergeTranslation("[00:01.000]A", "[00:01.000]甲")
        assertEquals(1, out.split('\n').size)
        assertEquals("[00:01.000]A / 甲", out)
    }

    @Test
    fun `时间戳精度不同也能配对`() {
        // 原文 `[00:30.542]` / 译文 `[00:30.54]` 字符串不相等，
        // 按字符串配对会整段对不上，必须解析成毫秒
        val out = mergeTranslation("[00:30.542]A", "[00:30.542]甲")
        assertTrue(out.contains("/ 甲"))
    }

    @Test
    fun `译文与原文相同时不重复贴`() {
        // 有些歌的"翻译"其实就是原文，贴两遍很蠢
        val out = mergeTranslation("[00:01.000]Hello", "[00:01.000]Hello")
        assertEquals("[00:01.000]Hello", out)
    }

    @Test
    fun `没有译文时原样返回`() {
        val lrc = "[00:01.000]A\n[00:02.000]B"
        assertEquals(lrc, mergeTranslation(lrc, ""))
    }

    @Test
    fun `译文里多出来的行不会影响原文`() {
        // 译文行数可能比原文少（实测 Lemon 是 42 vs 46），
        // 也可能是别的歌更多 —— 以原文为准，多出来的译文忽略
        val lrc = "[00:01.000]A\n[00:02.000]B"
        val tr = "[00:01.000]甲\n[00:02.000]乙\n[00:03.000]丙"
        val out = mergeTranslation(lrc, tr)
        assertEquals(2, out.split('\n').size)
        assertFalse("原文里没有的时间戳不该冒出来", out.contains("丙"))
    }

    @Test
    fun `元信息行原样保留`() {
        val lrc = "[ti:歌名]\n[00:01.000]A"
        val tr = "[00:01.000]甲"
        val out = mergeTranslation(lrc, tr)
        assertTrue(out.startsWith("[ti:歌名]"))
        assertTrue(out.contains("[00:01.000]A / 甲"))
    }
}
