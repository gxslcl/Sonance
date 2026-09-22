package com.sonance.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 「点某首歌播放」时进队列的列表该用哪一份。
 *
 * 用户报过这个 bug：「全部歌曲里搜索之后点播放，队列里只有这一首，
 * 别的歌都没了」—— 原因就是拿**筛选后**的列表当了队列。
 *
 * 用 `List<String>` 而不是 `List<Song>`：`Song` 带 `android.net.Uri`，
 * JVM 单测里构造不出来（`Uri.parse` / `Uri.EMPTY` / `Uri.fromFile` 全是 null，
 * 而 `Uri` 的构造器是 private）。这个函数只做"用哪个列表"的选择、
 * 跟元素类型无关，泛型化没有代价。
 */
class PlayQueueTest {

    private val library = listOf("a", "b", "c", "d", "e")
    private val searchResult = listOf("b")

    @Test
    fun `搜索时用完整曲库而不是搜索结果`() {
        // 这是用户报的那个 bug 的正面断言：搜到 1 首、点了它，
        // 队列也应该是整个曲库（从这首开始接着放），而不是只有这一首
        assertEquals(
            library,
            MediaRepository.playQueue(searchResult, library, searching = true)
        )
    }

    @Test
    fun `不搜索时用列表本身`() {
        // 不搜索时列表 == 曲库，行为不变
        assertEquals(
            library,
            MediaRepository.playQueue(library, library, searching = false)
        )
    }

    @Test
    fun `不搜索时不会替换成曲库`() {
        // 专辑 / 艺术家 / 文件夹页传进来的 display 是有意义的上下文，
        // 不能被曲库顶掉 —— 在专辑里点一首歌，队列应该是那张专辑
        val album = listOf("x", "y")
        assertSame(album, MediaRepository.playQueue(album, library, searching = false))
    }

    @Test
    fun `搜索时返回的就是曲库那一个实例`() {
        // 用同一个实例，调用方的 remember 才不会每次都重建
        assertSame(library, MediaRepository.playQueue(searchResult, library, searching = true))
    }

    @Test
    fun `空曲库不会崩`() {
        assertEquals(
            emptyList<String>(),
            MediaRepository.playQueue(emptyList<String>(), emptyList<String>(), searching = true)
        )
        assertEquals(
            emptyList<String>(),
            MediaRepository.playQueue(emptyList<String>(), emptyList<String>(), searching = false)
        )
    }

    @Test
    fun `搜索没命中时队列退回整个曲库`() {
        // 搜到 0 首时列表页显示空态、点不到歌；但万一走到这里，
        // 也不该把队列弄成空 —— 空队列会让播放器停下来
        assertEquals(
            library,
            MediaRepository.playQueue(emptyList<String>(), library, searching = true)
        )
    }
}
