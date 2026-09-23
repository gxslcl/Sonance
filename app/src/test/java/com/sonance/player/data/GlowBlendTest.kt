package com.sonance.player.data

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `blendGlowColors` 的契约 —— 「跟手」过渡（播放页上下滑切歌时颜色跟着手指走）的颜色规则。
 *
 * 为什么值得钉住：这段规则以前写成"相邻两页各画一层、按 (1-t) / t 交叉淡化"，
 * 两层都半透明地叠起来之后中点比两端淡一成半，底色从中间透出来 ——
 * 用户看到的就是「切歌时背景会闪一下」。这类缺陷**截图很难抓**（只有几百毫秒，
 * 而且要正好停在过渡中点），所以只能把数学关系钉在单测里：
 * 中点必须是两端真正的中点。
 *
 * ⚠️ 断言里的 `±0.005` 不是随手放的：Compose 的 [Color] 在 sRGB 下按 **8 位**存分量，
 * 传 0.5 进去读回来是 128/255 = 0.50196。要做严格相等就得用 8 位量化过的值，
 * 见「逐分量」那条用例。
 */
class GlowBlendTest {

    private val red = Color(1f, 0f, 0f)
    private val blue = Color(0f, 0f, 1f)
    private val green = Color(0f, 1f, 0f)
    private val a = listOf(red, green, blue)
    private val b = listOf(blue, red, green)

    /** 8 位量化的误差上限（0.5 存回来是 128/255） */
    private val quant = 0.005f

    @Test
    fun `两侧都有色卡时按位置在颜色本身上插值`() {
        val half = blendGlowColors(listOf(red), listOf(blue), 0.5f)
        assertEquals(1, half.size)
        assertEquals(0.5f, half[0].red, quant)
        assertEquals(0.5f, half[0].blue, quant)

        val quarter = blendGlowColors(listOf(red), listOf(blue), 0.25f)
        assertEquals(0.75f, quarter[0].red, quant)
        assertEquals(0.25f, quarter[0].blue, quant)
    }

    /**
     * 这条就是「闪一下」的回归哨兵。
     *
     * 中点必须是两端**真正的中点**。旧实现（两层各按 0.5 叠）算出来是
     * 0.5B + 0.5(0.5A + 0.5·底色) —— 底色占了两成半，中点明显偏暗，
     * 于是切歌切到一半背景亮一下。用 8 位量化过的端点值算，这里的容差可以收到 0.002。
     */
    @Test
    fun `中点的分量必须正好是两端的中点`() {
        val lo = Color(26, 26, 26)      // 0.10196
        val hi = Color(230, 230, 230)   // 0.90196
        val mid = blendGlowColors(listOf(lo), listOf(hi), 0.5f).single()
        val expected = (26f / 255f + 230f / 255f) / 2f
        assertEquals(expected, mid.red, 0.002f)
        assertEquals(expected, mid.green, 0.002f)
        assertEquals(expected, mid.blue, 0.002f)
    }

    @Test
    fun `每个分量都是两端的线性插值`() {
        val mid = blendGlowColors(a, b, 0.5f)
        assertEquals(a.size, mid.size)
        for (i in a.indices) {
            assertEquals((a[i].red + b[i].red) / 2f, mid[i].red, quant)
            assertEquals((a[i].green + b[i].green) / 2f, mid[i].green, quant)
            assertEquals((a[i].blue + b[i].blue) / 2f, mid[i].blue, quant)
        }
    }

    /**
     * 下一页的封面还在取 / 色卡还在算 —— 这是「切歌切到一半背景整个淡掉」的场景。
     * 必须沿用当前这一页的颜色，**绝不能返回空**（返回空 = 那一层什么都不画）。
     */
    @Test
    fun `下一页色卡还没算出来时沿用当前页的颜色，任何进度下都不返回空`() {
        for (t in listOf(0f, 0.2f, 0.5f, 0.8f, 1f)) {
            val out = blendGlowColors(a, emptyList(), t)
            assertEquals("t=$t", a, out)
        }
    }

    /**
     * 落定在 t = 0 时，如果**当前页**的色卡还没算出来，不能拿下一页的颜色顶上 ——
     * 那会让停下来的这一页显示成下一首的颜色（切完歌颜色对不上，同样是"闪"）。
     * 返回空，由调用方用"上一次真正画出来的颜色"兜底。
     */
    @Test
    fun `落定时当前页色卡缺失要返回空，不能显示下一首的颜色`() {
        assertEquals(emptyList<Color>(), blendGlowColors(emptyList(), b, 0f))
        assertEquals(emptyList<Color>(), blendGlowColors(emptyList(), b, -0.3f))
    }

    @Test
    fun `确实在滑向下一页时，当前页色卡缺失可以用下一页的`() {
        assertEquals(b, blendGlowColors(emptyList(), b, 0.3f))
    }

    @Test
    fun `两侧都没有色卡时返回空`() {
        assertEquals(emptyList<Color>(), blendGlowColors(emptyList(), emptyList(), 0f))
        assertEquals(emptyList<Color>(), blendGlowColors(emptyList(), emptyList(), 0.5f))
        assertEquals(emptyList<Color>(), blendGlowColors(emptyList(), emptyList(), 1f))
    }

    @Test
    fun `t 落在两端时直接取那一侧，越界也夹到端点`() {
        assertEquals(a, blendGlowColors(a, b, 0f))
        assertEquals(b, blendGlowColors(a, b, 1f))
        assertEquals(a, blendGlowColors(a, b, -0.2f))
        assertEquals(b, blendGlowColors(a, b, 1.4f))
    }

    /**
     * 端点返回的必须是**同一份实例**：调用方拿它当 `remember` 的 key 去重烘位图，
     * 每次返回新列表会让静止时也在反复重建 8192 像素的位图。
     */
    @Test
    fun `端点返回同一份色卡实例`() {
        assertSame(a, blendGlowColors(a, b, 0f))
        assertSame(b, blendGlowColors(a, b, 1f))
        assertSame(a, blendGlowColors(a, emptyList(), 0.5f))
    }

    @Test
    fun `两侧色卡长度不一致时取更近的一侧，不硬插值`() {
        val three = listOf(red, green, blue)
        val one = listOf(red)
        assertEquals(three, blendGlowColors(three, one, 0.2f))
        assertEquals(one, blendGlowColors(three, one, 0.8f))
        assertTrue(blendGlowColors(three, one, 0.5f).isNotEmpty())
    }
}
