package com.sonance.player.data

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `blendGlowSample` 的契约 —— 除了颜色，还要给出这份颜色的**可信度**。
 *
 * ## 为什么值得单独钉住可信度
 *
 * 它就是「切歌闪一下」第三次复发的修法本身。前两版都只管颜色：
 * - 两层按 `1-t` / `t` 叠加 → 中点比两端淡一成半（底色透出来）；
 * - 改成颜色插值 → 中点准了，但缺一侧色卡时只能拿另一侧**顶住**，
 *   于是从切歌开始到色卡到位之间屏幕**一动不动**，然后一帧跳到插值结果。
 *
 * 可信度让「缺失」表现为**逐渐变淡**：每一帧都在变，没有「长时间不动
 * 然后突然跳」的那个台阶。
 *
 * ⚠️ 这些关系**截图抓不到**（只有几百毫秒，且要正好停在色卡到位那一帧），
 * 只能把数学关系钉在单测里。
 */
class GlowBlendSampleTest {

    private val red = Color(1f, 0f, 0f)
    private val green = Color(0f, 1f, 0f)
    private val blue = Color(0f, 0f, 1f)
    private val a = listOf(red, green, blue)
    private val b = listOf(blue, red, green)

    /** 与 `GlowBlend.kt` 里 MISS_FLOOR 对应：色卡缺失时强度淡到的下限 */
    private val floor = 0.55f
    private val eps = 0.005f

    @Test
    fun `两侧色卡都在时可信度满值`() {
        for (t in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            assertEquals("t=$t", 1f, blendGlowSample(a, b, t).readiness, eps)
        }
    }

    /**
     * 本轮修的那下闪，核心断言就在这里。
     *
     * 可信度**恒为 1** 就是修复前的行为：色卡没到位时颜色被顶住不动，
     * 到位瞬间一帧跳到插值结果 —— 跳变量约 30% 色差、一帧完成。
     */
    @Test
    fun `下一首色卡没算出来时可信度随滑动下降`() {
        val t0 = blendGlowSample(a, emptyList(), 0f)
        val half = blendGlowSample(a, emptyList(), 0.5f)
        val t1 = blendGlowSample(a, emptyList(), 1f)

        assertEquals(1f, t0.readiness, eps)
        assertTrue("t=0.5 时可信度必须已经开始下降", half.readiness < 1f - eps)
        assertTrue(
            "可信度必须随 t 单调下降",
            t0.readiness > half.readiness && half.readiness > t1.readiness
        )
        assertEquals(floor, t1.readiness, eps)

        // 颜色仍然是当前这首 —— 只是变淡，不是换成别的颜色
        assertEquals(a, half.colors)
    }

    @Test
    fun `可信度不会淡到零`() {
        // 淡到 0 等于背景整个掉回纯色，那是另一种闪（颜色消失又出现）
        for (t in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val r = blendGlowSample(a, emptyList(), t).readiness
            assertTrue("t=$t 时可信度 $r 不应低于下限", r >= floor - eps)
            assertTrue("t=$t 时可信度 $r 不应超过 1", r <= 1f + eps)
        }
    }

    @Test
    fun `当前这首色卡没算出来时按滑动位置逐步显现`() {
        val quarter = blendGlowSample(emptyList(), b, 0.25f)
        assertEquals(0.25f, quarter.readiness, eps)
        assertEquals(b, quarter.colors)

        val full = blendGlowSample(emptyList(), b, 1f)
        assertEquals(1f, full.readiness, eps)
    }

    @Test
    fun `落定那一刻不能显示成下一首的颜色`() {
        // t=0 表示还停在当前这首：此时只有下一首有色卡，必须整个不画，
        // 交给调用方「沿用上一次真正画出来的颜色」兜底
        val s = blendGlowSample(emptyList(), b, 0f)
        assertTrue(s.colors.isEmpty())
        assertEquals(0f, s.readiness, eps)
    }

    @Test
    fun `两侧都没有色卡时整个不画`() {
        val s = blendGlowSample(emptyList(), emptyList(), 0.5f)
        assertTrue(s.colors.isEmpty())
        assertEquals(0f, s.readiness, eps)
    }

    @Test
    fun `颜色插值部分与旧实现完全一致`() {
        // 可信度是新加的维度，颜色规则本身不许变 —— 两侧都有时逐点等价
        for (t in listOf(0f, 0.3f, 0.5f, 0.7f, 1f)) {
            assertEquals(blendGlowColors(a, b, t), blendGlowSample(a, b, t).colors)
        }
    }
}
