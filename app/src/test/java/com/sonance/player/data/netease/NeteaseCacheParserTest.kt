package com.sonance.player.data.netease

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网易云缓存扫描解析的纯函数单测。
 *
 * 没有实机，缓存目录的真实形态只能靠枚举：这里把「整文件 NCM / 明文整文件 /
 * 明文分片组 / 杂文件 / 空结果 / 路径带空格引号 / magic 读不出来」全部钉死。
 */
class NeteaseCacheParserTest {

    // ===== 魔数分类 =====

    @Test
    fun `NCM 魔数被认出来`() {
        val (kind, ext) = NeteaseCacheParser.classify(NeteaseCacheParser.hexToBytes("4354454e4644414d"))
        assertEquals(NcKind.NCM, kind)
        assertEquals("", ext)
    }

    @Test
    fun `nmsf 魔数（网易云现行缓存）被认出来`() {
        // 真机实测：明文 m4a 整体 XOR 0xA3，见 NmsDecoderTest
        val (kind, ext) = NeteaseCacheParser.classify(
            NeteaseCacheParser.hexToBytes("a3a3a3bfc5d7dad3ee97e283a3a3a1a3")
        )
        assertEquals(NcKind.NMS, kind)
        assertEquals("", ext)
    }

    @Test
    fun `明文音频的各种魔数都能认出并给出后缀`() {
        fun kindOf(hex: String) = NeteaseCacheParser.classify(NeteaseCacheParser.hexToBytes(hex))

        assertEquals(NcKind.PLAIN to ".mp3", kindOf("49443303"))          // ID3
        assertEquals(NcKind.PLAIN to ".mp3", kindOf("fffb9064"))          // MPEG 帧同步
        assertEquals(NcKind.PLAIN to ".mp3", kindOf("fff3" + "0000"))     // MPEG 2.5
        assertEquals(NcKind.PLAIN to ".flac", kindOf("664c6143" + "0000")) // fLaC
        assertEquals(NcKind.PLAIN to ".ogg", kindOf("4f676753" + "0000")) // OggS
        assertEquals(NcKind.PLAIN to ".wav", kindOf("52494646" + "0000")) // RIFF
        // m4a：前 4 字节是 box 长度，ftyp 在第 4~7 字节
        assertEquals(NcKind.PLAIN to ".m4a", kindOf("0000001866747970"))
    }

    @Test
    fun `认不出的魔数一律 UNKNOWN（json 图片 数据库 残片）`() {
        fun kindOf(hex: String) = NeteaseCacheParser.classify(NeteaseCacheParser.hexToBytes(hex))

        assertEquals(NcKind.UNKNOWN to "", kindOf("7b226d6574"))   // {"met… json
        assertEquals(NcKind.UNKNOWN to "", kindOf("ffd8ffe1"))     // JPEG（0xFFD8 不撞 MPEG 同步）
        assertEquals(NcKind.UNKNOWN to "", kindOf("53514c69"))     // SQLite
        assertEquals(NcKind.UNKNOWN to "", kindOf(""))             // od 失败，没读到 magic
    }

    @Test
    fun `hexToBytes 对异常输入兜底`() {
        assertEquals(0, NeteaseCacheParser.hexToBytes("").size)
        assertEquals(0, NeteaseCacheParser.hexToBytes("abc").size)      // 奇数位
        assertEquals(0, NeteaseCacheParser.hexToBytes("zzzz").size)     // 非 hex
        assertEquals(4, NeteaseCacheParser.hexToBytes(" FF FB 90 64 ").size) // 带空格 + 大写
        assertEquals(0xfb.toByte(), NeteaseCacheParser.hexToBytes("fffb9064")[1])
    }

    // ===== 歌曲 ID 提取（在线补全名称 / 封面的依据） =====

    private val NMS_MAGIC = "a3a3a3bfc5d7dad3ee97e283a3a3a1a3"

    @Test
    fun `NMSF 文件名第一段是歌曲 ID 能被提取`() {
        val entries = NeteaseCacheParser.buildEntriesFromScan(
            line(
                9_200_000,
                "/sdcard/Android/data/com.netease.cloudmusic/files/Cache/Music3/0a/422094253_256003_abcdef0123_0.nmsf",
                NMS_MAGIC
            )
        )
        assertEquals(1, entries.size)
        assertEquals(NcKind.NMS, entries[0].kind)
        assertEquals("422094253", NeteaseCacheParser.songIdOf(entries[0]))
    }

    @Test
    fun `非 NMS 条目提取不到歌曲 ID`() {
        // NCM 自带元数据，不需要也不应该查
        val ncm = NeteaseCacheParser.buildEntriesFromScan(
            line(9_200_000, "/cache/x.ncm", "4354454e4644414d")
        )
        assertNull(NeteaseCacheParser.songIdOf(ncm[0]))
        // 明文分片：文件名是哈希或真名，宁可不补也不张冠李戴
        val plain = NeteaseCacheParser.buildEntriesFromScan(
            line(9_200_000, "/cache/1234567890abcdef.m4a", "0000001866747970")
        )
        assertNull(NeteaseCacheParser.songIdOf(plain[0]))
    }

    @Test
    fun `第一段不是合理位数的纯数字就当没有 ID`() {
        fun idOf(name: String): String? = NeteaseCacheParser.songIdOf(
            NeteaseCacheParser.buildEntriesFromScan(
                line(9_200_000, "/cache/$name.nmsf", NMS_MAGIC)
            )[0]
        )
        assertNull(idOf("notanid_256003_abcdef0123_0"))       // 非纯数字
        assertNull(idOf("123_256003_abcdef0123_0"))           // 太短
        assertNull(idOf("12345678901234_256003_abcdef0123_0")) // 太长
        assertEquals("422094253", idOf("422094253_256003_abcdef0123_0"))
    }

    // ===== 扫描输出解析 =====

    private fun line(size: Long, path: String, hex: String) = "$size\t$path\t$hex"

    @Test
    fun `普通行解析出大小 路径 魔数`() {
        val files = NeteaseCacheParser.parseScan(
            line(1048576, "/sdcard/Android/data/com.netease.cloudmusic/cache/x.ncm", "4354454e4644414d")
        )
        assertEquals(1, files.size)
        with(files[0]) {
            assertEquals("/sdcard/Android/data/com.netease.cloudmusic/cache/x.ncm", path)
            assertEquals(1048576L, size)
            assertEquals(NcKind.NCM, kind)
        }
    }

    @Test
    fun `路径里的空格和单引号不会错位`() {
        val path = "/sdcard/Android/data/com.netease.cloudmusic/cache/Cluster/0/my 'song' name - 1.m4a"
        val files = NeteaseCacheParser.parseScan(line(2048, path, "0000001866747970"))
        assertEquals(path, files.single().path)
    }

    @Test
    fun `wc 输出的前导空格被容忍 hex 缺失按 UNKNOWN 处理`() {
        val files = NeteaseCacheParser.parseScan(
            " 4096\t/a/b.m4a\t\n" +          // hex 为空
                "4096\t/a/c.m4a"             // 少一列，整行丢弃
        )
        assertEquals(1, files.size)
        assertEquals("/a/b.m4a", files.single().path)
        assertEquals(NcKind.UNKNOWN, files.single().kind)
    }

    @Test
    fun `大小为 0 或非法的行直接丢弃`() {
        val files = NeteaseCacheParser.parseScan(
            line(0, "/a/zero.m4a", "0000001866747970") + "\n" +
                line(-5, "/a/neg.m4a", "0000001866747970") + "\n" +
                "abc\t/a/garbage.m4a\t0000001866747970\n" +
                "no-tabs-here\n" +
                "\n" +
                line(100, "/a/ok.m4a", "0000001866747970")
        )
        assertEquals(listOf("/a/ok.m4a"), files.map { it.path })
    }

    @Test
    fun `同一路径重复出现只保留一条`() {
        val files = NeteaseCacheParser.parseScan(
            line(100, "/a/dup.m4a", "0000001866747970") + "\n" +
                line(100, "/a/dup.m4a", "0000001866747970")
        )
        assertEquals(1, files.size)
    }

    // ===== 条目归并（分片分组） =====

    private val M4A = "0000001866747970"
    private val NCM = "4354454e4644414d"
    private val JSON = "7b226d6574"

    @Test
    fun `同目录同前缀的分片归并成一组且按片号数值排序`() {
        val entries = NeteaseCacheParser.buildEntries(
            listOf(
                NcFile("/cache/Cluster/0/abc123-10.m4a", 10L, NcKind.PLAIN, ".m4a"),
                NcFile("/cache/Cluster/0/abc123-2.m4a", 2L, NcKind.PLAIN, ".m4a"),
                NcFile("/cache/Cluster/0/abc123-1.m4a", 1L, NcKind.PLAIN, ".m4a")
            )
        )
        assertEquals(1, entries.size)
        val e = entries.single()
        assertEquals("/cache/Cluster/0/abc123-1.m4a", e.key) // key = 第一片
        assertEquals("abc123", e.displayName)
        assertEquals(13L, e.sizeBytes)                       // 组大小 = 各片之和
        assertEquals(3, e.fragmentCount)
        assertEquals(
            listOf(
                "/cache/Cluster/0/abc123-1.m4a",
                "/cache/Cluster/0/abc123-2.m4a",
                "/cache/Cluster/0/abc123-10.m4a"             // -10 排在 -2 后面（数值序）
            ),
            e.fragments
        )
        assertEquals(true, e.isFragmentGroup)
        assertEquals("分片 ×3 · M4A 明文 · 疑似残曲", e.typeLabel)
    }

    @Test
    fun `单片不成组_完整单文件保持原名`() {
        val entries = NeteaseCacheParser.buildEntries(
            listOf(
                NcFile("/cache/x/abc-0.m4a", 100L, NcKind.PLAIN, ".m4a"),  // 只有 -0，没兄弟
                NcFile("/cache/y/song.ncm", 500L, NcKind.NCM, "")
            )
        )
        assertEquals(2, entries.size)
        val single = entries.first { it.kind == NcKind.PLAIN }
        assertEquals(1, single.fragmentCount)
        assertEquals("abc-0", single.displayName) // 名字不动
        assertEquals(false, single.isFragmentGroup)
    }

    @Test
    fun `不同目录的同前缀不归并`() {
        val entries = NeteaseCacheParser.buildEntries(
            listOf(
                NcFile("/cache/Cluster/0/abc-0.m4a", 10L, NcKind.PLAIN, ".m4a"),
                NcFile("/cache/Cluster/1/abc-1.m4a", 20L, NcKind.PLAIN, ".m4a")
            )
        )
        assertEquals(2, entries.size)
    }

    @Test
    fun `魔数认不出的杂文件整批丢弃`() {
        val entries = NeteaseCacheParser.buildEntries(
            listOf(
                NcFile("/cache/a.json", 900L, NcKind.UNKNOWN, ""),
                NcFile("/cache/db.sqlite", 300L, NcKind.UNKNOWN, ""),
                NcFile("/cache/part-0.m4a", 100L, NcKind.PLAIN, ".m4a"),
                NcFile("/cache/part-1.m4a", 100L, NcKind.PLAIN, ".m4a")
            )
        )
        assertEquals(1, entries.size)
        assertEquals("part", entries.single().displayName)
    }

    @Test
    fun `第一片认不出的分片组整组丢弃`() {
        val entries = NeteaseCacheParser.buildEntries(
            listOf(
                NcFile("/cache/junk-0.bin", 100L, NcKind.UNKNOWN, ""),
                NcFile("/cache/junk-1.bin", 100L, NcKind.UNKNOWN, "")
            )
        )
        assertEquals(0, entries.size)
    }

    @Test
    fun `条目按大小降序排_最大的排最前`() {
        val entries = NeteaseCacheParser.buildEntries(
            listOf(
                NcFile("/cache/small.mp3", 10L, NcKind.PLAIN, ".mp3"),
                NcFile("/cache/big.ncm", 999L, NcKind.NCM, ""),
                NcFile("/cache/mid-0.m4a", 30L, NcKind.PLAIN, ".m4a"),
                NcFile("/cache/mid-1.m4a", 40L, NcKind.PLAIN, ".m4a")
            )
        )
        assertEquals(listOf("big", "mid", "small"), entries.map { it.displayName })
    }

    // ===== 端到端：扫描原文 -> 条目 =====

    @Test
    fun `端到端混合场景_ncm_明文分片_杂文件_空行都能处理`() {
        val raw = buildString {
            append(line(4096L, "/cache/users/0/Cluster/0/-123456-5.2gbps-8494049-0.m4a", M4A)); append("\n")
            append(line(4096L, "/cache/users/0/Cluster/0/-123456-5.2gbps-8494049-1.m4a", M4A)); append("\n")
            append(line(9999L, "/cache/users/0/Cluster/1/718803-5.2gbps-6553500.ncm", NCM)); append("\n")
            append(line(2048L, "/cache/meta/list.json", JSON)); append("\n")
            append(line(0L, "/cache/empty.tmp", "")); append("\n")
            append("\n")
        }
        val entries = NeteaseCacheParser.buildEntriesFromScan(raw)
        assertEquals(2, entries.size)

        // 最大的在前：NCM(9999) > 分片组(8192)
        assertEquals(NcKind.NCM, entries[0].kind)
        assertEquals("718803-5.2gbps-6553500", entries[0].displayName)

        assertEquals(2, entries[1].fragmentCount)
        assertEquals("-123456-5.2gbps-8494049", entries[1].displayName)
        assertEquals(".m4a", entries[1].plainExt)
    }

    @Test
    fun `空扫描结果产出空条目列表`() {
        assertEquals(0, NeteaseCacheParser.buildEntriesFromScan("").size)
        assertEquals(0, NeteaseCacheParser.buildEntriesFromScan("\n\n").size)
    }

    // ===== 本地缓存序列化 =====

    @Test
    fun `codec 往返不丢信息`() {
        val e = NeteaseCacheEntry(
            key = "/cache/Cluster/0/abc-0.m4a",
            displayName = "my 'song' 名字",
            sizeBytes = 12345L,
            kind = NcKind.PLAIN,
            plainExt = ".m4a",
            fragments = listOf("/cache/Cluster/0/abc-0.m4a", "/cache/Cluster/0/abc-1 .m4a"),
            fragmentCount = 2
        )
        val back = NeteaseCacheCodec.fromLine(NeteaseCacheCodec.toLine(e))
        assertEquals(e, back)
    }

    @Test
    fun `codec 对坏行返回 null`() {
        assertNull(NeteaseCacheCodec.fromLine("too\tfew\tfields"))
        assertNull(NeteaseCacheCodec.fromLine("k\tn\tnotanumber\tPLAIN\t.m4a\t/p"))
        assertNull(NeteaseCacheCodec.fromLine("k\tn\t100\tBADKIND\t.m4a\t/p"))
        assertNull(NeteaseCacheCodec.fromLine("\tn\t100\tPLAIN\t.m4a\t/p")) // 空 key
        assertNull(NeteaseCacheCodec.fromLine("k\tn\t100\tPLAIN\t.m4a\t")) // 空分片
    }

    @Test
    fun `codec 旧格式行（无残曲列）兼容读取`() {
        val line = "k\tn\t123\tPLAIN\t.m4a\t/p/a.m4a"
        val e = NeteaseCacheCodec.fromLine(line)!!
        assertEquals(false, e.suspectTrial)
        // 新格式往返：残曲标记不丢
        val trial = NeteaseCacheEntry(
            key = "k", displayName = "n", sizeBytes = 320_000, kind = NcKind.NMS,
            plainExt = "", fragments = listOf("p"), fragmentCount = 1, suspectTrial = true
        )
        assertEquals(trial, NeteaseCacheCodec.fromLine(NeteaseCacheCodec.toLine(trial)))
    }

    // ===== 试听残曲预判 =====

    @Test
    fun `真机实测的残曲大小档全部命中预判`() {
        // 真机 9 条残曲的实际大小：320KB 块 ×1 与 ×4
        for (s in longArrayOf(320_001, 320_003, 320_006, 320_010, 320_016, 320_023)) {
            assertEquals(true, NeteaseCacheParser.isSuspectTrial(s))
        }
        for (s in longArrayOf(1_280_020, 1_280_074, 1_280_094)) {
            assertEquals(true, NeteaseCacheParser.isSuspectTrial(s))
        }
    }

    @Test
    fun `完整歌曲大小不会被预判误伤`() {
        // 真机完整歌的最小档从 2.5MB 起
        for (s in longArrayOf(2_500_000, 3_238_400, 5_120_000, 9_199_897, 11_148_263)) {
            assertEquals(false, NeteaseCacheParser.isSuspectTrial(s))
        }
    }

    @Test
    fun `低码率明文容器豁免大小预判_波点 ogg 短歌不误标`() {
        // ogg/opus 48~96kbps：2.4MB 以内也可能是完整歌，不做大小预判
        for (s in longArrayOf(900_000, 1_600_000, 2_300_000)) {
            assertEquals(false, NeteaseCacheParser.isSuspectTrial(s, ".ogg"))
            assertEquals(false, NeteaseCacheParser.isSuspectTrial(s, ".flac"))
        }
        // mp3 等其余明文照旧
        assertEquals(true, NeteaseCacheParser.isSuspectTrial(1_600_000, ".mp3"))
        assertEquals(true, NeteaseCacheParser.isSuspectTrial(1_600_000, ""))
    }

    @Test
    fun `扫描归并时按大小打残曲标记`() {
        val raw = buildString {
            // 真机形态：nmsf 缓存文件
            append("320003\t/sdcard/Cache/Music3/1/422094253_256003_md5_0.nmsf\t")
            append(NeteaseCacheParser.hexToBytes("a3a3a3bfc5d7dad3").joinToString("") { "%02x".format(it) })
            append("\n")
            append("9199897\t/sdcard/Cache/Music3/1/1842025914_256001_md5_0.nmsf\t")
            append(NeteaseCacheParser.hexToBytes("a3a3a3bfc5d7dad3").joinToString("") { "%02x".format(it) })
            append("\n")
        }
        val entries = NeteaseCacheParser.buildEntriesFromScan(raw)
        assertEquals(2, entries.size)
        val trial = entries.first { it.sizeBytes == 320_003L }
        val full = entries.first { it.sizeBytes == 9_199_897L }
        assertEquals(true, trial.suspectTrial)
        assertEquals(false, full.suspectTrial)
        assertEquals(true, trial.typeLabel.contains("疑似残曲"))
        assertEquals(false, full.typeLabel.contains("疑似残曲"))
    }

    // ===== 多 App 缓存分类（酷狗 KGM / QQ 音乐 QMC / 汽水 MDL） =====

    /** 酷狗 KGM 容器的 16 字节魔数（见 PrivateAudioRestorer.KGM_MAGIC_HEX） */
    private val KGM_MAGIC_HEX = "7cd532eb86027f4ba8afa68e0fff9914"

    @Test
    fun `酷狗 KGM 魔数被认出来`() {
        val (kind, ext) = NeteaseCacheParser.classify(NeteaseCacheParser.hexToBytes(KGM_MAGIC_HEX))
        assertEquals(NcKind.KGM, kind)
        assertEquals("", ext)
    }

    @Test
    fun `KGG 新版加密魔数不在支持列表_靠扩展名兜底成 KGM`() {
        val (kind, _) = NeteaseCacheParser.classify(NeteaseCacheParser.hexToBytes(JSON), "晴天.kgg")
        assertEquals(NcKind.KGM, kind)
        val (kind2, _) = NeteaseCacheParser.classify(NeteaseCacheParser.hexToBytes(JSON), "晴天.kgma")
        assertEquals(NcKind.KGM, kind2)
    }

    @Test
    fun `QMC 家族没有魔数_按文件名扩展名识别`() {
        fun kindOf(name: String) =
            NeteaseCacheParser.classify(NeteaseCacheParser.hexToBytes(JSON), name).first
        assertEquals(NcKind.QMC, kindOf("晴天.mflac"))
        assertEquals(NcKind.QMC, kindOf("晴天.mflac0"))
        assertEquals(NcKind.QMC, kindOf("晴天.mgg1"))
        assertEquals(NcKind.QMC, kindOf("晴天.qmcflac"))
        assertEquals(NcKind.QMC, kindOf("晴天.qmc0"))
        assertEquals(NcKind.QMC, kindOf("晴天.tkm"))
    }

    @Test
    fun `汽水 MDL 按扩展名识别为暂不支持类别`() {
        val (kind, ext) = NeteaseCacheParser.classify(NeteaseCacheParser.hexToBytes(JSON), "a1b2c3.mdl")
        assertEquals(NcKind.MDL, kind)
        assertEquals("", ext)
    }

    @Test
    fun `明文魔数优先于扩展名_标准音频不会被误判成加密`() {
        // 名字带 .mflac 但内容是 ID3 mp3：按魔数走明文
        assertEquals(
            NcKind.PLAIN to ".mp3",
            NeteaseCacheParser.classify(NeteaseCacheParser.hexToBytes("49443303"), "x.mflac")
        )
    }

    @Test
    fun `杂文件即使带陌生扩展名也不会被当成加密容器`() {
        fun kindOf(name: String) =
            NeteaseCacheParser.classify(NeteaseCacheParser.hexToBytes(JSON), name).first
        assertEquals(NcKind.UNKNOWN, kindOf("cover.json"))
        assertEquals(NcKind.UNKNOWN, kindOf("db.sqlite"))
        assertEquals(NcKind.UNKNOWN, kindOf("video.mp4"))
    }

    @Test
    fun `parseScan 把文件名传给分类器_mflac 条目带 QMC 类别`() {
        val files = NeteaseCacheParser.parseScan(
            line(5_242_880, "/sdcard/qqmusic/song/晴天 - 周杰伦.mflac", JSON)
        )
        assertEquals(1, files.size)
        assertEquals(NcKind.QMC, files.single().kind)
    }

    @Test
    fun `QMC 条目归并后保留类别与真实文件名`() {
        val entries = NeteaseCacheParser.buildEntries(
            listOf(
                NcFile("/sdcard/qqmusic/song/晴天 - 周杰伦.mflac", 5_242_880L, NcKind.QMC, ""),
                NcFile("/sdcard/kgmusic/download/告白气球 - 周杰伦.kgm", 6_291_456L, NcKind.KGM, "")
            )
        )
        assertEquals(2, entries.size)
        val qmc = entries.first { it.kind == NcKind.QMC }
        assertEquals("晴天 - 周杰伦", qmc.displayName)
        assertEquals(false, qmc.suspectTrial)
        assertEquals("QMC 加密", qmc.typeLabel)
        val kgm = entries.first { it.kind == NcKind.KGM }
        assertEquals("KGM 加密", kgm.typeLabel)
    }

    @Test
    fun `MDL 条目的类型标签如实标注暂不支持`() {
        val entries = NeteaseCacheParser.buildEntries(
            listOf(NcFile("/sdcard/Android/data/com.luna.music/cache/music/x.mdl", 3_000_000L, NcKind.MDL, ""))
        )
        assertEquals(1, entries.size)
        assertEquals("MDL 加密 · 暂不支持解密", entries.single().typeLabel)
    }

    @Test
    fun `codec 往返兼容新增的三种类别`() {
        for (kind in listOf(NcKind.KGM, NcKind.QMC, NcKind.MDL)) {
            val e = NeteaseCacheEntry(
                key = "/cache/a.bin", displayName = "a", sizeBytes = 100L,
                kind = kind, plainExt = "", fragments = listOf("/cache/a.bin"), fragmentCount = 1
            )
            assertEquals(e, NeteaseCacheCodec.fromLine(NeteaseCacheCodec.toLine(e)))
        }
    }

    @Test
    fun `扫描脚本包含所选目标的根目录且不含未选目标`() {
        val script = NeteaseCacheRepository.buildScanScript(setOf(SniffApp.KUGOU, SniffApp.LUNA))
        assertTrue(script.contains("/sdcard/kgmusic"))
        assertTrue(script.contains("com.kugou.android/cache"))
        assertTrue(script.contains("com.luna.music/cache"))
        assertTrue(script.contains("com.ss.android.music/cache"))
        assertFalse(script.contains("com.netease.cloudmusic"))
        assertFalse(script.contains("com.tencent.qqmusic"))
        // shell 变量必须原样输出（由 shell 端展开），不能被 Kotlin 字符串模板吃掉
        assertTrue(script.contains("\$root"))
        assertTrue(script.contains("\"\$f\""))
        // 汇合点：while read 逐文件报大小与魔数
        assertTrue(script.contains("od -An -tx1 -N12"))
    }

    @Test
    fun `扫描脚本根目录列表全选时去重且逐行换行`() {
        val script = NeteaseCacheRepository.buildScanScript(SniffApp.entries.toSet())
        // 每个根目录一行带续行反斜杠（最后一行跟 ; do）
        assertTrue(script.contains("\"/sdcard/Android/data/com.netease.cloudmusic/cache\" \\"))
        assertTrue(script.contains("; do"))
    }

    // ===== 真机实测形态回归（QQ 封面图误判 / 波点明文缓存 / 概念版根目录） =====

    @Test
    fun `WEBP 封面图不会被误判成 wav`() {
        // RIFF....WEBP：QQ音乐 download_cache 的封面图（真机实测头 12 字节）
        val webp = NeteaseCacheParser.hexToBytes("52494646" + "d6a10900" + "57454250")
        assertEquals(NcKind.UNKNOWN, NeteaseCacheParser.classify(webp).first)
        assertEquals(
            NcKind.UNKNOWN,
            NeteaseCacheParser.classify(webp, "1794693713029").first
        )
    }

    @Test
    fun `RIFF 容器 12 字节起校验 WAVE 标记`() {
        val wave = NeteaseCacheParser.hexToBytes("52494646" + "fc7d1a00" + "57415645")
        assertEquals(NcKind.PLAIN to ".wav", NeteaseCacheParser.classify(wave))
        // 不足 12 字节区分不了（旧版扫描只报 8 字节），维持旧行为按 wav 放行
        assertEquals(
            NcKind.PLAIN to ".wav",
            NeteaseCacheParser.classify(NeteaseCacheParser.hexToBytes("524946460000"))
        )
    }

    @Test
    fun `扫描脚本读 12 字节头部`() {
        val script = NeteaseCacheRepository.buildScanScript(setOf(SniffApp.TENCENT))
        assertTrue(script.contains("od -An -tx1 -N12"))
        assertFalse(script.contains("-N8 "))
    }

    @Test
    fun `波点 vcc 缓存按明文魔数识别且展示名剥掉 vcc 中缀`() {
        // 真机实测：<歌曲id>.<码率>.vcc.song 头部是明文 ID3 mp3，.vcc.dat 是 OggS
        val head = NeteaseCacheParser.hexToBytes("49443303")
        assertEquals(NcKind.PLAIN to ".mp3", NeteaseCacheParser.classify(head, "551684.128.vcc.song"))
        assertEquals(
            NcKind.PLAIN to ".ogg",
            NeteaseCacheParser.classify(NeteaseCacheParser.hexToBytes("4f6767530002"), "1131986.100.vcc.dat")
        )
        val entries = NeteaseCacheParser.buildEntries(
            listOf(NcFile("/sdcard/Android/data/cn.wenyu.bodian/files/playCache/551684.128.vcc.song", 3_971_266L, NcKind.PLAIN, ".mp3"))
        )
        assertEquals(1, entries.size)
        assertEquals("551684.128", entries.single().displayName)
        assertEquals(NcKind.PLAIN, entries.single().kind)
    }

    @Test
    fun `非波点的 vcc 结尾不受清洗影响`() {
        // 只有完整的「.vcc 中缀」才剥；普通名字原样保留
        val entries = NeteaseCacheParser.buildEntries(
            listOf(NcFile("/cache/abc.vcc", 4_000_000L, NcKind.UNKNOWN, ""))
        )
        // UNKNOWN 不会成条目；用明文验证清洗只发生在波点形态上
        val plain = NeteaseCacheParser.buildEntries(
            listOf(NcFile("/cache/moon.mp3", 4_000_000L, NcKind.PLAIN, ".mp3"))
        )
        assertEquals("moon", plain.single().displayName)
    }

    @Test
    fun `酷狗概念版与波点的根目录进扫描脚本`() {
        val script = NeteaseCacheRepository.buildScanScript(setOf(SniffApp.KUGOU, SniffApp.BODIAN))
        assertTrue(script.contains("com.kugou.android.lite/cache"))
        assertTrue(script.contains("com.kugou.android.lite/files"))
        assertTrue(script.contains("cn.wenyu.bodian/files/playCache"))
        assertTrue(script.contains("/sdcard/kgmusic"))
        assertFalse(script.contains("com.netease.cloudmusic"))
    }

    @Test
    fun `QQ 分块仓库 bin 按魔数识别且豁免残曲预判`() {
        // 真机实测：cache/files/*.bin 头 4 字节 01 01 02 02
        val head = NeteaseCacheParser.hexToBytes("0101020241000000410000000401")
        assertEquals(NcKind.QQCACHE, NeteaseCacheParser.classify(head, "5.bin").first)
        val entries = NeteaseCacheParser.buildEntries(
            listOf(
                // 600KB（低于 2.4MB 残曲线）也必须成条目：分块仓库不是单曲
                NcFile("/sdcard/Android/data/com.tencent.qqmusic/cache/files/1.bin", 600_731L, NcKind.QQCACHE, ""),
                NcFile("/sdcard/Android/data/com.tencent.qqmusic/cache/files/5.bin", 19_973_750L, NcKind.QQCACHE, "")
            )
        )
        assertEquals(2, entries.size)
        entries.forEach {
            assertEquals(false, it.suspectTrial)
            assertEquals("QQ 分块缓存 · 暂不支持解密", it.typeLabel)
        }
        assertEquals("1", entries.first { it.sizeBytes == 600_731L }.displayName)
    }
}
