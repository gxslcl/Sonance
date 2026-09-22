package com.sonance.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 在线音源的纯逻辑。
 *
 * 重点覆盖四件**一旦错了会静默出错**的事：
 *
 * 1. **在线歌 id 恒为负** —— 本地统计（最近播放 / 播放次数 / 上次播放）和收藏
 *    全靠「负数 = 在线」来挡掉在线歌。哪天真出现了非负值，那些账就会悄悄被污染，
 *    而且没有任何报错。
 * 2. **`fee` → 可播** 的映射 —— 判断反了的话，用户点免费歌被拦、点会员歌报错。
 * 3. **缩略图 URL** —— 拼错了图床会忽略参数、原样返回 1.9 MB 的原图，
 *    界面照常显示，只是每次搜索偷偷跑掉几十 MB 流量。
 * 4. **在线歌映射的淘汰规则** —— 淘汰多了会让队列页凭空少几首，
 *    淘汰少了就是无上限增长。
 */
class OnlineMusicTest {

    // ===== remoteSongId：必须是负数、非 0、稳定 =====

    @Test
    fun `在线歌 id 恒为负数`() {
        // 覆盖各种 hashCode 结果的输入，尤其是能撞出 h = 0 的
        val samples = listOf(
            "netease" to "1",
            "netease" to "186016",
            "netease" to "2652820720",
            "netease" to "",
            "netease" to "a",
            "other" to "1",
            "" to "",
            "netease" to "很大的一个远端 id 字符串用来撞哈希"
        )
        for ((source, remoteId) in samples) {
            val id = remoteSongId(source, remoteId)
            assertTrue(
                "remoteSongId($source, $remoteId) = $id 应该是负数",
                id < 0L
            )
        }
    }

    @Test
    fun `在线歌 id 永远不是 0`() {
        // 0 在上层被当作"没有歌曲"的哨兵（比如 PlayerUiState.mediaId 的默认值）
        val samples = (0..500).map { "netease" to it.toString() }
        for ((source, remoteId) in samples) {
            assertTrue(remoteSongId(source, remoteId) != 0L)
        }
    }

    @Test
    fun `同一首歌的 id 稳定不变`() {
        // 队列 / 收藏 / 封面缓存都按 id 索引，不稳定就会每搜一次多出一份"新歌"
        repeat(3) {
            assertEquals(
                remoteSongId("netease", "186016"),
                remoteSongId("netease", "186016")
            )
        }
    }

    @Test
    fun `不同音源的同一个远端 id 不会撞车`() {
        // 远端 id 只在各自的音源内唯一，主键必须是 (音源, 远端 id)
        assertTrue(
            remoteSongId("netease", "1") != remoteSongId("qqmusic", "1")
        )
    }

    @Test
    fun `不同歌曲的 id 不同`() {
        val ids = listOf("1", "2", "3", "186016", "2652820720").map { remoteSongId("netease", it) }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `isRemoteSongId 认得在线歌、不误伤本地歌`() {
        assertTrue(isRemoteSongId(remoteSongId("netease", "1")))
        assertTrue(isRemoteSongId(-1L))
        assertFalse(isRemoteSongId(0L))
        assertFalse(isRemoteSongId(1L))
        // MediaStore 的行 id 是从 1 开始的整数
        assertFalse(isRemoteSongId(987654321L))
    }

    // ===== fee → 可播 / VIP =====

    @Test
    fun `免费的 fee 值判定为可播`() {
        // 实测：fee=0（免费）、fee=8（低音质免费）的外链能拿到 audio/mpeg
        assertTrue(neteasePlayable(0))
        assertTrue(neteasePlayable(8))
        assertFalse(neteaseVip(0))
        assertFalse(neteaseVip(8))
    }

    @Test
    fun `会员与需购买的 fee 值判定为不可播`() {
        // 实测：fee=1（会员）、fee=4（需购买）的外链 302 到一张 HTML 下载页
        assertFalse(neteasePlayable(1))
        assertFalse(neteasePlayable(4))
        assertTrue(neteaseVip(1))
        assertTrue(neteaseVip(4))
    }

    @Test
    fun `未知 fee 值一律按不可播处理`() {
        // 宁可漏掉一首能播的，也不要让用户点了之后拿到一个 HTML 页面报"格式不支持"
        for (fee in listOf(-1, 2, 3, 5, 6, 7, 9, 100, Int.MAX_VALUE)) {
            assertFalse("fee=$fee 应该判为不可播", neteasePlayable(fee))
        }
    }

    @Test
    fun `可播与 VIP 互斥`() {
        for (fee in -2..20) {
            assertFalse(
                "fee=$fee 不能同时是可播和需要会员",
                neteasePlayable(fee) && neteaseVip(fee)
            )
        }
    }

    // ===== 缩略图 URL =====

    @Test
    fun `缩略图给没有查询串的地址接上 param`() {
        val url = "https://p2.music.126.net/abc==/123.jpg"
        assertEquals("$url?param=200y200", NeteaseSource.thumbUrl(url, 200))
    }

    @Test
    fun `缩略图对已有查询串的地址用 & 连接`() {
        val url = "https://p2.music.126.net/abc==/123.jpg?x=1"
        assertEquals("$url&param=150y150", NeteaseSource.thumbUrl(url, 150))
    }

    @Test
    fun `缩略图尺寸被夹在合理区间`() {
        val url = "https://p2.music.126.net/a.jpg"
        // 太小会糊、太大就失去缩略图的意义
        assertEquals("$url?param=40y40", NeteaseSource.thumbUrl(url, 1))
        assertEquals("$url?param=640y640", NeteaseSource.thumbUrl(url, 99999))
        assertEquals("$url?param=200y200", NeteaseSource.thumbUrl(url, 200))
    }

    @Test
    fun `缩略图对空地址原样返回`() {
        assertEquals("", NeteaseSource.thumbUrl("", 200))
        assertEquals("   ", NeteaseSource.thumbUrl("   ", 200))
    }

    @Test
    fun `默认实现不改造地址`() {
        // 接口的默认实现是"原样返回"，别的音源不覆写也不会出错
        val plain = object : OnlineSource {
            override val id = "plain"
            override val label = "示例音源"
            override suspend fun search(
                context: android.content.Context,
                query: String,
                limit: Int,
                offset: Int
            ): List<RemoteTrack> = emptyList()

            override fun streamUri(track: RemoteTrack) =
                android.net.Uri.parse("https://example.com/a.mp3")
        }
        val url = "https://example.com/a.jpg"
        assertEquals(url, plain.thumbUrl(url, 200))
    }

    // ===== toSong：字段映射 =====
    //
    // ⚠️ `toSong` 本身**没有单测**，这是环境限制不是偷懒：
    // 它要构造 [Song]，而 [Song.uri] 是非空的 `android.net.Uri`；
    // JVM 单测里**所有**拿到 Uri 的途径都返回 null ——
    // `Uri.parse` / `Uri.EMPTY` / `Uri.fromFile` 实测全是 null
    // （AGP 的 `isReturnDefaultValues` 把 android.jar 的桩方法改成了返回默认值），
    // 而 `Uri` 的构造器是 private，也没法自己实现一个。
    // 于是 `Song(uri = null)` 会在 Kotlin 的非空参数检查上直接抛 NPE。
    //
    // 这是本项目第二次撞上它 —— 上一次是 `PlaybackRestore` 因此改成"只处理 id、不碰 Song"。
    // 这里照同样的思路办：把唯一有**真实故障模式**的那条约定抽成常量，钉住它。

    @Test
    fun `在线歌的专辑 id 是负数`() {
        // 撞上 MediaStore 的专辑 id（全为正数）的后果不是报错，而是"静默错封面"：
        // ArtworkLoader 按 albumId 索引缓存，在线歌会显示成某个本地专辑的封面。
        assertTrue("在线歌的 albumId 必须是负数，否则会和本地专辑撞车", REMOTE_ALBUM_ID < 0L)
        // 0 也不行：它在别处是"没有专辑"的哨兵
        assertFalse(REMOTE_ALBUM_ID == 0L)
    }

    @Test
    fun `toSong 用到的 id 与远程 id 规则一致`() {
        // toSong 内部就是 remoteSongId(id, track.remoteId)，这里钉住这条依赖关系 ——
        // 它同时是"本地统计能挡掉在线歌"和"队列能反查到在线歌"的共同前提
        val track = RemoteTrack(
            remoteId = "186016",
            title = "晴天",
            artist = "周杰伦",
            album = "叶惠美",
            durationMs = 269_000L,
            coverUrl = "https://p2.music.126.net/x.jpg",
            playable = false,
            vip = true
        )
        val id = remoteSongId(NeteaseSource.id, track.remoteId)
        assertTrue(isRemoteSongId(id))
        assertTrue(id < 0L)
        assertTrue(id != 0L)
    }

    // ===== 在线歌映射的淘汰规则 =====

    @Test
    fun `队列里的在线歌一定要保留`() {
        // 漏了它们，队列页会凭空少几首 —— 用户看到的是"我明明加进去了怎么没了"
        val inQueue = remoteSongId("netease", "999")
        val keep = onlineSongIdsToKeep(
            resultIds = listOf(remoteSongId("netease", "1")),
            queueIds = listOf(inQueue, 123L),
            currentMediaId = remoteSongId("netease", "1")
        )
        assertTrue("队列里的在线歌必须保留", inQueue in keep)
        assertTrue("队列里的本地歌也顺带保留，无害", 123L in keep)
    }

    @Test
    fun `正在播的那首即使不在队列里也要保留`() {
        // 用户可能刚把它从队列里删掉，但音频还在放 —— 那时播放页仍要能查到它
        val playing = remoteSongId("netease", "555")
        val keep = onlineSongIdsToKeep(
            resultIds = emptyList(),
            queueIds = emptyList(),
            currentMediaId = playing
        )
        assertTrue(playing in keep)
    }

    @Test
    fun `搜索结果的 id 全部保留`() {
        val results = (1..30).map { remoteSongId("netease", it.toString()) }
        val keep = onlineSongIdsToKeep(results, emptyList(), 0L)
        for (id in results) assertTrue(id in keep)
    }

    @Test
    fun `没有在播的歌时不会塞进哨兵 0`() {
        // 0 是"没有歌曲"的哨兵，混进保留集毫无意义
        val keep = onlineSongIdsToKeep(emptyList(), emptyList(), 0L)
        assertTrue(keep.isEmpty())
    }

    @Test
    fun `过期的在线歌不在保留集里`() {
        val stale = remoteSongId("netease", "old")
        val keep = onlineSongIdsToKeep(
            resultIds = listOf(remoteSongId("netease", "new")),
            queueIds = listOf(7L),
            currentMediaId = 7L
        )
        assertFalse("上一轮搜索的歌应该被淘汰掉", stale in keep)
    }


    // ===== 音源清单 =====

    @Test
    fun `音源清单能按 id 查回自己`() {
        for (src in OnlineSources.all) {
            assertEquals(src, OnlineSources.byId(src.id))
            assertTrue(src.id.isNotBlank())
            assertTrue(src.label.isNotBlank())
        }
    }

    @Test
    fun `查不存在的音源返回 null 而不是抛异常`() {
        assertEquals(null, OnlineSources.byId(null))
        assertEquals(null, OnlineSources.byId(""))
        assertEquals(null, OnlineSources.byId("不存在的音源"))
    }

    @Test
    fun `音源 id 不重复`() {
        // id 是写进 Song.source 的持久化标识，重复了会把两个音源的歌混成一锅
        val ids = OnlineSources.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `默认音源是网易云`() {
        // Prefs.onlineSourceId 取不到时回落到清单第一个，它得是能用的那个
        assertEquals("netease", OnlineSources.all.first().id)
    }
}
