package com.sonance.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 律动柱物理引擎。
 *
 * 这里的用例不是"跑一遍不崩"，而是钉住几条**必须成立的不变量**：
 *
 * 1. **帧率无关** —— 这是本次重写的核心目标。老实现按帧衰减，
 *    120Hz 屏上比 60Hz 快一倍；新实现必须做到"迭代同样时长、结果相同"。
 * 2. **不穿透、不反弹** —— 柱子落到目标就停，不会穿到负值再弹回来。
 * 3. **加速下落** —— 每段等长时间里下降的幅度必须递增（重力感），
 *    如果变成等幅就是线性衰减，等于没改。
 * 4. **峰值悬停** —— 悬停期间位置一动不动，之后才开始落，且永不低于柱子。
 */
class VizPhysicsTest {

    private fun viz(
        bars: Int = 1,
        attack: Float = VizPhysics.DEFAULT_ATTACK,
        damping: Float = VizPhysics.DEFAULT_DAMPING,
        gravity: Float = VizPhysics.DEFAULT_GRAVITY,
        holdTicks: Int = VizPhysics.DEFAULT_PEAK_HOLD_TICKS,
        peak: Boolean = true
    ) = VizPhysics(bars, attack, damping, gravity, holdTicks, peak)

    /** 用固定 dt 迭代 [seconds] 秒，返回最终 pos[0] */
    private fun run(p: VizPhysics, target: Float, seconds: Float, dt: Float): Float {
        val t = FloatArray(1) { target }
        val steps = (seconds / dt).toInt()
        repeat(steps) { p.step(t, dt) }
        return p.pos[0]
    }

    // ===== 1. 帧率无关（核心）=====

    @Test
    fun `上升阶段 60Hz 与 120Hz 结果一致`() {
        val a = viz()
        val b = viz()
        val ta = run(a, 1f, 0.3f, 1f / 60f)
        val tb = run(b, 1f, 0.3f, 1f / 120f)
        assertEquals("上升 0.3 秒后两条轨迹必须重合", ta, tb, 1e-4f)
    }

    @Test
    fun `下落阶段 60Hz 与 120Hz 结果一致`() {
        val a = viz()
        val b = viz()
        a.pos[0] = 1f
        b.pos[0] = 1f
        // 0.4 秒时还在空中（按默认参数约 0.65），不会碰到"落地夹紧"这个非线性
        val ta = run(a, 0f, 0.4f, 1f / 60f)
        val tb = run(b, 0f, 0.4f, 1f / 120f)
        assertEquals("下落 0.4 秒后两条轨迹必须重合", ta, tb, 1e-4f)
        assertTrue("用例前提：0.4 秒时应该还在空中，实际 $ta", ta > 0.05f)
    }

    @Test
    fun `上升加下落整段 60Hz 与 120Hz 结果一致`() {
        val a = viz()
        val b = viz()
        val steps60 = (0.5f / (1f / 60f)).toInt()
        val steps120 = (0.5f / (1f / 120f)).toInt()
        val up = FloatArray(1) { 1f }
        val down = FloatArray(1) { 0f }
        repeat(steps60 / 2) { a.step(up, 1f / 60f) }
        repeat(steps120 / 2) { b.step(up, 1f / 120f) }
        repeat(steps60 - steps60 / 2) { a.step(down, 1f / 60f) }
        repeat(steps120 - steps120 / 2) { b.step(down, 1f / 120f) }
        assertEquals(a.pos[0], b.pos[0], 1e-4f)
    }

    @Test
    fun `峰值条位置也与帧率无关`() {
        val a = viz()
        val b = viz()
        val up = FloatArray(1) { 0.9f }
        val down = FloatArray(1) { 0f }
        repeat(30) { a.step(up, 1f / 60f) }
        repeat(60) { b.step(up, 1f / 120f) }
        // 悬停期结束之后再看
        repeat(120) { a.step(down, 1f / 60f) }
        repeat(240) { b.step(down, 1f / 120f) }
        assertEquals("峰值条轨迹也必须重合", a.peak[0], b.peak[0], 1e-4f)
    }

    @Test
    fun `不规则帧间隔与均匀帧间隔结果一致`() {
        // 真机帧间隔是抖动的（16.6 / 8.3 / 33ms 混着来）。
        // 只要总时长相同，结果就该相同 —— 这是"按 dt 积分"相对"按帧"的关键优势。
        val uniform = viz()
        val jitter = viz()
        val t = FloatArray(1) { 1f }
        repeat(18) { uniform.step(t, 1f / 60f) }
        // 同样 0.3 秒，但用一串长短不一的 dt
        val dts = floatArrayOf(0.033f, 0.008f, 0.0167f, 0.05f, 0.0167f, 0.0083f, 0.033f,
            0.0167f, 0.008f, 0.05f, 0.0167f, 0.0166f, 0.008f, 0.033f)
        var acc = 0f
        for (d in dts) { jitter.step(t, d); acc += d }
        // 补齐剩余时长
        val rest = 0.3f - acc
        if (rest > 0f) jitter.step(t, rest)
        assertEquals("总时长相同就该得到相同结果", uniform.pos[0], jitter.pos[0], 1e-3f)
    }

    // ===== 2. 不穿透 / 不反弹 =====

    @Test
    fun `下落不会穿透目标`() {
        val p = viz()
        p.pos[0] = 1f
        val t = FloatArray(1) { 0f }
        repeat(600) { p.step(t, 1f / 60f) }
        assertEquals("最终必须精确停在目标上", 0f, p.pos[0], 1e-6f)
    }

    @Test
    fun `下落过程中不会出现负值`() {
        val p = viz()
        p.pos[0] = 1f
        val t = FloatArray(1) { 0f }
        repeat(600) {
            p.step(t, 1f / 60f)
            assertTrue("pos 不该为负，实际 ${p.pos[0]}", p.pos[0] >= 0f)
        }
    }

    @Test
    fun `落到非零目标就停住`() {
        val p = viz()
        p.pos[0] = 1f
        val t = FloatArray(1) { 0.4f }
        repeat(600) { p.step(t, 1f / 60f) }
        assertEquals(0.4f, p.pos[0], 1e-6f)
    }

    @Test
    fun `上升不会超过目标`() {
        val p = viz()
        val t = FloatArray(1) { 0.7f }
        repeat(600) { p.step(t, 1f / 60f) }
        assertTrue("不该过冲，实际 ${p.pos[0]}", p.pos[0] <= 0.7f + 1e-5f)
        assertEquals(0.7f, p.pos[0], 1e-4f)
    }

    // ===== 3. 加速下落（重力感）=====

    @Test
    fun `下落是加速的而不是线性的`() {
        val p = viz()
        p.pos[0] = 1f
        val t = FloatArray(1) { 0f }
        val dt = 1f / 60f

        fun advance(seconds: Float): Float {
            val start = p.pos[0]
            repeat((seconds / dt).toInt()) { p.step(t, dt) }
            return start - p.pos[0]      // 这一段掉了多少
        }

        val d1 = advance(0.2f)
        val d2 = advance(0.2f)
        val d3 = advance(0.2f)

        assertTrue("第一段掉了 $d1", d1 > 0.02f)
        assertTrue("第二段($d2) 必须比第一段($d1) 掉得多", d2 > d1 * 1.5f)
        assertTrue("第三段($d3) 必须比第二段($d2) 掉得多", d3 > d2 * 1.2f)
    }

    @Test
    fun `重力越大落得越快`() {
        val slow = viz(gravity = 3f)
        val fast = viz(gravity = 12f)
        slow.pos[0] = 1f
        fast.pos[0] = 1f
        val t = FloatArray(1) { 0f }
        repeat(24) { slow.step(t, 1f / 60f); fast.step(t, 1f / 60f) }
        assertTrue("重力大的应该更低：slow=${slow.pos[0]} fast=${fast.pos[0]}",
            fast.pos[0] < slow.pos[0])
    }

    @Test
    fun `阻尼越大落得越慢`() {
        val loose = viz(damping = 1.5f)
        val sticky = viz(damping = 7f)
        loose.pos[0] = 1f
        sticky.pos[0] = 1f
        val t = FloatArray(1) { 0f }
        repeat(24) { loose.step(t, 1f / 60f); sticky.step(t, 1f / 60f) }
        assertTrue("阻尼大的应该更高：loose=${loose.pos[0]} sticky=${sticky.pos[0]}",
            sticky.pos[0] > loose.pos[0])
    }

    @Test
    fun `上升系数越大跟得越紧`() {
        val slow = viz(attack = 0.2f)
        val fast = viz(attack = 0.85f)
        val t = FloatArray(1) { 1f }
        repeat(3) { slow.step(t, 1f / 60f); fast.step(t, 1f / 60f) }
        assertTrue("响应快的应该更高：slow=${slow.pos[0]} fast=${fast.pos[0]}",
            fast.pos[0] > slow.pos[0])
    }

    // ===== 4. 峰值条 =====

    @Test
    fun `峰值悬停期间位置不动`() {
        val p = viz(holdTicks = 30)      // 30 帧 = 0.5 秒
        val up = FloatArray(1) { 0.8f }
        repeat(60) { p.step(up, 1f / 60f) }
        val held = p.peak[0]
        assertTrue("前提：峰值应该已经跟上去了，实际 $held", held > 0.7f)

        val down = FloatArray(1) { 0f }
        // 悬停期内（0.4 秒 < 0.5 秒）峰值一点都不该动
        repeat(24) { p.step(down, 1f / 60f) }
        assertEquals("悬停期间峰值不该移动", held, p.peak[0], 1e-6f)
    }

    @Test
    fun `悬停结束后峰值开始下落`() {
        val p = viz(holdTicks = 12)      // 0.2 秒
        val up = FloatArray(1) { 0.8f }
        repeat(60) { p.step(up, 1f / 60f) }
        val held = p.peak[0]

        val down = FloatArray(1) { 0f }
        repeat(12) { p.step(down, 1f / 60f) }     // 刚好走完悬停
        repeat(30) { p.step(down, 1f / 60f) }     // 再等 0.5 秒
        assertTrue("峰值应该已经开始下落：held=$held now=${p.peak[0]}", p.peak[0] < held - 0.05f)
    }

    @Test
    fun `峰值永远不会低于柱子`() {
        val p = viz(holdTicks = 6)
        val t = FloatArray(1) { 0f }
        var i = 0
        while (i < 400) {
            // 忽高忽低的目标，把峰值逻辑来回折腾
            t[0] = if (i % 40 < 8) 0.9f else 0.05f
            p.step(t, 1f / 60f)
            assertTrue("第 $i 帧：peak=${p.peak[0]} 低于 pos=${p.pos[0]}", p.peak[0] >= p.pos[0] - 1e-6f)
            i++
        }
    }

    @Test
    fun `关掉峰值时不维护峰值数组`() {
        val p = viz(peak = false)
        val t = FloatArray(1) { 0.9f }
        repeat(60) { p.step(t, 1f / 60f) }
        assertEquals("关掉之后峰值数组应保持为 0", 0f, p.peak[0], 1e-6f)
        assertTrue("但柱子照常上升", p.pos[0] > 0.8f)
    }

    // ===== 5. 边界与健壮性 =====

    @Test
    fun `dt 为零时不推进`() {
        val p = viz()
        val t = FloatArray(1) { 1f }
        repeat(10) { p.step(t, 0f) }
        assertEquals(0f, p.pos[0], 1e-6f)
    }

    @Test
    fun `超大 dt 被夹住且不产生非法值`() {
        val p = viz()
        p.pos[0] = 1f
        val t = FloatArray(1) { 0f }
        // 模拟"切后台几分钟再回来"
        p.step(t, 300f)
        assertTrue("不该是 NaN", !p.pos[0].isNaN())
        assertTrue("不该是 Inf", !p.pos[0].isInfinite())
        assertTrue("不该为负", p.pos[0] >= 0f)
        assertTrue("夹住之后最多掉 50ms 的量，不该直接归零", p.pos[0] > 0.5f)
    }

    @Test
    fun `负 dt 不推进`() {
        val p = viz()
        val t = FloatArray(1) { 1f }
        repeat(10) { p.step(t, -1f) }
        assertEquals(0f, p.pos[0], 1e-6f)
    }

    @Test
    fun `重置清空全部状态`() {
        val p = viz()
        val t = FloatArray(1) { 0.9f }
        repeat(60) { p.step(t, 1f / 60f) }
        p.reset()
        assertEquals(0f, p.pos[0], 1e-6f)
        assertEquals(0f, p.peak[0], 1e-6f)
    }

    @Test
    fun `多根柱子互不干扰`() {
        val p = viz(bars = 8)
        // 只有第 3 根有信号
        val t = FloatArray(8)
        t[3] = 0.9f
        repeat(60) { p.step(t, 1f / 60f) }
        assertTrue("第 3 根应该起来了", p.pos[3] > 0.8f)
        var i = 0
        while (i < 8) {
            if (i != 3) assertEquals("第 $i 根不该动", 0f, p.pos[i], 1e-6f)
            i++
        }
    }

    @Test
    fun `target 数组比柱子数短时不越界`() {
        val p = viz(bars = 8)
        val t = FloatArray(3) { 1f }     // 故意短
        repeat(10) { p.step(t, 1f / 60f) }
        assertTrue(p.pos[0] > 0f)
        assertEquals("超出的柱子按 0 处理", 0f, p.pos[7], 1e-6f)
    }

    @Test
    fun `运行期间改参数立即生效`() {
        val p = viz(attack = 0.1f)
        val t = FloatArray(1) { 1f }
        repeat(5) { p.step(t, 1f / 60f) }
        val slow = p.pos[0]
        p.attackFactor = 0.9f
        repeat(5) { p.step(t, 1f / 60f) }
        val fast = p.pos[0]
        assertTrue("改大 attackFactor 之后应该明显更快：$slow → $fast", fast - slow > 0.3f)
    }
}
