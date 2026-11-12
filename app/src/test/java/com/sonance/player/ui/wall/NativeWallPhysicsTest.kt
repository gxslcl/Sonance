package com.sonance.player.ui.wall

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 仅推进物理状态，不创建 Bitmap / Canvas，也不启动 Android 或 Compose UI。 */
class NativeWallPhysicsTest {
    private val uptime = 9_000_000_000_000L
    private val dt = 0.016f
    private val silent: (Int, String, Int) -> Unit = { _, _, _ -> }

    private fun engine(drift: Boolean = false) = WallEngine(WallTune().apply {
        this.drift = drift
    }).apply {
        maxY = 1000f
        widthCss = 300f
        heightCss = 100f
    }

    private fun tile() = WallTile(
        WallAlbum("test", "Test", "Artist", false, emptyList(), emptyList()),
        CoverSpec("Test", "Artist", 0f, 60f, 0, 50f, 50f, 0f, false),
        0f, 0f, 100f, 2
    )

    private fun settle(e: WallEngine, start: Long = uptime): Long {
        var now = start
        repeat(1000) {
            if (!e.active(now)) return now
            now += 16L
            e.step(now, dt, silent)
        }
        fail("Physics did not park within 16 seconds")
        return now
    }

    @Test
    fun animationPreservesIntermediateFramesAtLargeUptime() {
        val e = engine()
        var completed = 0
        e.scrollY = 10f
        e.anim = ScrollAnim(10f, 110f, uptime, 1000f) { completed++ }

        assertTrue(e.step(uptime + 250L, dt, silent))
        assertEquals(78.359375f, e.scrollY, 0f)
        assertEquals(0, completed)
        assertTrue(e.active(uptime + 250L))
        e.step(uptime + 500L, dt, silent)
        assertEquals(103.75f, e.scrollY, 0f)
        assertEquals(0, completed)
        assertTrue(e.step(uptime + 1000L, dt, silent))
        assertEquals(110f, e.scrollY, 0f)
        assertNull(e.anim)
        assertEquals(1, completed)
        val parked = settle(e, uptime + 1000L)
        assertEquals(1, completed)
        assertFalse(e.step(parked + 16L, dt, silent))
    }

    @Test
    fun rippleAndIntroExpiryDrawTheirLastFrameAtLargeUptime() {
        val rippleEngine = engine()
        rippleEngine.step(uptime, dt, silent)
        rippleEngine.addRipple(tile(), 0.05f, uptime)
        val ripple = rippleEngine.ripples.single()
        assertEquals(uptime, ripple.t0)
        rippleEngine.step(uptime + 2600L, dt, silent)
        assertSame(ripple, rippleEngine.ripples.single())
        assertTrue(rippleEngine.step(uptime + 2601L, dt, silent))
        assertTrue(rippleEngine.ripples.isEmpty())
        assertFalse(rippleEngine.active(uptime + 2601L))
        assertFalse(rippleEngine.step(uptime + 2617L, dt, silent))

        val introEngine = engine()
        val intro = Intro(150f, 40f, uptime)
        introEngine.intro = intro
        // hypot(300, 100 + 300) = 500; lifetime = 440 + 500 * .38 = 630ms.
        introEngine.step(uptime + 630L, dt, silent)
        assertSame(intro, introEngine.intro)
        assertTrue(introEngine.step(uptime + 631L, dt, silent))
        assertNull(introEngine.intro)
        assertFalse(introEngine.active(uptime + 631L))
        assertFalse(introEngine.step(uptime + 647L, dt, silent))
    }

    @Test
    fun longPressUsesElapsedLongMilliseconds() {
        val e = engine()
        e.markInput(uptime)
        e.pressing = tile()
        e.pressStart = uptime
        assertEquals(uptime, e.lastInput)
        e.step(uptime + 450L, dt, silent)
        assertFalse(e.longFired)
        e.step(uptime + 451L, dt, silent)
        assertTrue(e.longFired)
        e.pressing = null
        assertFalse(e.active(uptime + 451L))
    }

    @Test
    fun idleParksUntilTheExactDriftDeadlineWithoutPolling() {
        val e = engine(drift = true)
        e.markInput(uptime)
        assertTrue(e.step(uptime, dt, silent))
        assertFalse(e.active(uptime))
        assertEquals(8_000L, e.idleDelay(uptime)!!)
        for (elapsed in listOf(1000L, 5000L, 7_999L)) {
            assertFalse(e.active(uptime + elapsed))
            assertFalse(e.step(uptime + elapsed, dt, silent))
            assertEquals(8_000L - elapsed, e.idleDelay(uptime + elapsed)!!)
        }
        assertTrue(e.active(uptime + 8_000L))
        assertTrue(e.driftCond(uptime + 8_000L))
        assertNull(e.idleDelay(uptime + 8_000L))
        assertTrue(e.step(uptime + 8_000L, dt, silent))
        assertEquals(9f * dt, e.scrollY, 0f)
        assertEquals(0f, e.vel, 0f)
        e.tune.drift = false
        assertFalse(e.active(uptime + 8_001L))
        assertNull(e.idleDelay(uptime + 8_001L))
        assertFalse(e.step(uptime + 8_016L, dt, silent))
    }

    @Test
    fun enablingDriftAfterParkingSchedulesDeadlineAndNewInputPostponesIt() {
        val e = engine()
        e.markInput(uptime)
        e.step(uptime, dt, silent)
        assertFalse(e.active(uptime))
        assertNull(e.idleDelay(uptime))
        e.tune.drift = true
        assertFalse(e.active(uptime + 4000L))
        assertEquals(4000L, e.idleDelay(uptime + 4000L)!!)
        e.markInput(uptime + 5000L)
        e.step(uptime + 5000L, dt, silent)
        assertFalse(e.active(uptime + 12_000L))
        assertEquals(1000L, e.idleDelay(uptime + 12_000L)!!)
        e.obscured = true
        assertFalse(e.active(uptime + 17_000L))
        assertNull(e.idleDelay(uptime + 17_000L))
        e.obscured = false
        assertTrue(e.active(uptime + 17_000L))
        assertNull(e.idleDelay(uptime + 17_000L))
    }

    @Test
    fun everyDriftBlockerSuppressesBothMotionAndIdleDeadline() {
        fun blocked(name: String, change: (WallEngine) -> Unit) {
            val e = engine(drift = true)
            e.markInput(uptime)
            e.step(uptime, dt, silent)
            change(e)
            assertNull(name, e.idleDelay(uptime + 1000L))
            assertNull(name, e.idleDelay(uptime + 12_000L))
            assertFalse(name, e.driftCond(uptime + 12_000L))
        }
        blocked("disabled") { it.tune.drift = false }
        blocked("no scroll range") { it.maxY = 0f }
        blocked("dragging") { it.dragging = true }
        blocked("pressing") { it.pressing = tile() }
        blocked("animation") { it.anim = ScrollAnim(0f, 100f, uptime, 1000f) {} }
        blocked("lock") { it.lockPending = true }
        blocked("player") { it.playerOpen = true }
        blocked("panel") { it.obscured = true }
        blocked("busy") { it.busy = true }
        blocked("inertia") { it.vel = 100f }
        blocked("overscroll") { it.scrollY = -0.005f }
    }

    @Test
    fun emptyWallAndExternalBusyLockOrPanelCanPark() {
        val e = engine(drift = true)
        e.maxY = 0f
        e.step(uptime, dt, silent)
        assertFalse(e.active(uptime))
        assertFalse(e.step(uptime + 16L, dt, silent))
        e.maxY = 1000f
        e.busy = true
        e.lockPending = true
        e.obscured = true
        e.forceCurve = 0.8f
        e.dimT = 0.62f
        e.gxT = 0.3f
        e.gyT = -0.2f
        e.tilt = -3f
        val parked = settle(e)
        assertEquals(0.8f, e.curve, 0f)
        assertEquals(0f, e.curveV, 0f)
        assertEquals(e.dimT, e.dim, 0f)
        assertEquals(e.gxT, e.gx, 0f)
        assertEquals(e.gyT, e.gy, 0f)
        assertEquals(0f, e.tilt, 0f)
        assertFalse(e.active(parked))
        assertFalse(e.step(parked + 16L, dt, silent))
    }

    @Test
    fun curveAtZeroVelocityExtremumStillSettlesToTarget() {
        val e = engine()
        e.step(uptime, dt, silent)
        e.curve = 0.8f
        e.curveV = 0f
        assertTrue(e.active(uptime))
        val parked = settle(e)
        assertEquals(e.tune.rest, e.curve, 0f)
        assertEquals(0f, e.curveV, 0f)
        assertFalse(e.step(parked + 16L, dt, silent))
    }

    @Test
    fun subpixelCurveAndTileSettleStillDrawOneFinalFrame() {
        val e = engine()
        e.step(uptime, dt, silent)
        e.curve += 0.00001f
        assertTrue(e.active(uptime))
        assertTrue(e.step(uptime + 16L, dt, silent))
        assertEquals(e.tune.rest, e.curve, 0f)
        assertFalse(e.active(uptime + 16L))
        assertFalse(e.step(uptime + 32L, dt, silent))

        val t = tile()
        t.ps = 0.99999f
        t.rx = 0.001f
        t.ry = -0.001f
        e.releaseTile(t)
        assertTrue(e.active(uptime + 32L))
        assertTrue(e.step(uptime + 48L, dt, silent))
        assertTrue(e.activeTiles.isEmpty())
        assertFalse(t.active)
        assertEquals(1f, t.ps, 0f)
        assertEquals(0f, t.rx, 0f)
        assertEquals(0f, t.ry, 0f)
        assertEquals(0f, t.psv, 0f)
        assertEquals(0f, t.rxv, 0f)
        assertEquals(0f, t.ryv, 0f)
        assertFalse(e.active(uptime + 48L))
        assertFalse(e.step(uptime + 64L, dt, silent))
    }

    @Test
    fun pressAndReleaseSpringsParkInFiniteTime() {
        val e = engine()
        val t = tile()
        e.pressTile(t, 0.8f, -0.6f)
        val pressed = settle(e)
        assertEquals(e.tune.pressScale, t.ps, 0f)
        assertEquals(t.rxt, t.rx, 0f)
        assertEquals(t.ryt, t.ry, 0f)
        e.releaseTile(t)
        val released = settle(e, pressed)
        assertEquals(1f, t.ps, 0f)
        assertEquals(0f, t.rx, 0f)
        assertEquals(0f, t.ry, 0f)
        assertFalse(e.step(released + 16L, dt, silent))
    }

    // 故意保留旧数组公式作为测试 oracle，验证生产无分配版本的子步运算逐值相同。
    private fun legacySpring(x: Float, v: Float, target: Float, k: Float, z: Float, dt: Float): FloatArray {
        var state = floatArrayOf(x, v)
        val n = ceil(dt / 0.004f).toInt().coerceAtLeast(1)
        val h = dt / n
        repeat(n) {
            val cc = 2f * sqrt(k) * z
            val a = k * (target - state[0]) - cc * state[1]
            val nv = state[1] + a * h
            state = floatArrayOf(state[0] + nv * h, nv)
        }
        return state
    }

    @Test
    fun allocationFreeSpringsMatchLegacySubstepNumerics() {
        for (frameDt in listOf(0.001f, 0.008f, 0.016f, 0.033f, 0.05f)) {
            val e = engine()
            e.scrollY = -20f
            e.vel = -100f
            e.curve = 0.7f
            e.curveV = 1.2f
            val t = tile()
            t.ps = 0.8f; t.psv = -0.3f; t.pst = 0.95f
            t.rx = 4f; t.rxv = 2f; t.rxt = -2f
            t.ry = -5f; t.ryv = 1f; t.ryt = 3f
            t.active = true
            e.activeTiles.add(t)
            val scroll = legacySpring(e.scrollY, e.vel * exp(-frameDt * 16f), 0f, 170f, 1f, frameDt)
            val target = e.tune.rest
            val curve = legacySpring(e.curve, e.curveV, target, e.tune.springK, e.tune.zeta, frameDt)
            val scale = legacySpring(t.ps, t.psv, t.pst, 420f, 0.5f, frameDt)
            val rx = legacySpring(t.rx, t.rxv, t.rxt, 300f, 0.55f, frameDt)
            val ry = legacySpring(t.ry, t.ryv, t.ryt, 300f, 0.55f, frameDt)
            e.step(uptime, frameDt, silent)
            assertEquals(scroll[0], e.scrollY, 0f)
            assertEquals(scroll[1], e.vel, 0f)
            assertEquals(curve[0], e.curve, 0f)
            assertEquals(curve[1], e.curveV, 0f)
            assertEquals(scale[0], t.ps, 0f)
            assertEquals(scale[1], t.psv, 0f)
            assertEquals(rx[0], t.rx, 0f)
            assertEquals(rx[1], t.rxv, 0f)
            assertEquals(ry[0], t.ry, 0f)
            assertEquals(ry[1], t.ryv, 0f)
        }
    }

    @Test
    fun tinyOverscrollAtEitherEndReturnsExactlyToBounds() {
        for (position in listOf(-0.005f, 1000.005f)) {
            val e = engine()
            e.step(uptime, dt, silent)
            e.scrollY = position
            assertTrue(e.active(uptime))
            assertTrue(e.step(uptime + 16L, dt, silent))
            assertEquals(if (position < 0f) 0f else e.maxY, e.scrollY, 0f)
            assertEquals(0f, e.vel, 0f)
            assertFalse(e.active(uptime + 16L))
            assertFalse(e.step(uptime + 32L, dt, silent))
        }
    }

    @Test
    fun outwardOverscrollVelocityReboundsAndParksAtBothEnds() {
        for ((position, velocity) in listOf(-120f to -1500f, 1120f to 1500f)) {
            val e = engine()
            e.scrollY = position
            e.vel = velocity
            e.userMotion = true
            val parked = settle(e)
            assertEquals(if (position < 0f) 0f else e.maxY, e.scrollY, 0f)
            assertEquals(0f, e.vel, 0f)
            assertFalse(e.userMotion)
            assertFalse(e.step(parked + 16L, dt, silent))
        }
    }

    @Test
    fun inertialBoundaryImpactEmitsL3OnlyOnce() {
        val e = engine()
        e.scrollY = 995f
        e.vel = 1000f
        e.userMotion = true
        var impacts = 0
        val haptic: (Int, String, Int) -> Unit = { level, _, _ -> if (level == 3) impacts++ }
        assertTrue(e.step(uptime, dt, haptic))
        assertEquals(1, impacts)
        repeat(600) { e.step(uptime + (it + 1) * 16L, dt, haptic) }
        assertEquals(1, impacts)
        assertEquals(e.maxY, e.scrollY, 0f)
        assertFalse(e.active(uptime + 9600L))

        // 已由拖动越界的输入侧触发过，松手回弹不能再补一发。
        e.scrollY = -0.005f
        e.vel = -500f
        repeat(600) { e.step(uptime + 10_000L + it * 16L, dt, haptic) }
        assertEquals(1, impacts)
    }

    @Test
    fun l1IsSameFrameRateLimitedAndNeverReplaysSkippedEdges() {
        val e = engine()
        e.heightCss = 0f
        e.blockEdges = FloatArray(8) { (it + 1) * 10f }
        e.dragging = true
        val ticks = mutableListOf<Long>()
        var now = uptime
        val haptic: (Int, String, Int) -> Unit = { level, reason, duration ->
            assertEquals(1, level)
            assertEquals("滚动", reason)
            assertTrue(duration in 6..15)
            ticks.add(now - uptime)
        }
        fun drag(elapsed: Long, position: Float) {
            now = uptime + elapsed
            e.lastDragTime = now
            e.scrollY = position
            e.vel = 1000f
            e.step(now, dt, haptic)
        }
        drag(0L, 0f)
        assertTrue(ticks.isEmpty())
        drag(1L, 11f)
        assertEquals(listOf(1L), ticks)
        drag(16L, 21f)
        drag(35L, 31f) // 距上次仅 34ms。
        assertEquals(3, e.edgeIdx)
        assertEquals(listOf(1L), ticks)
        drag(36L, 61f) // 恰好 35ms，一帧跨三条 edge 也只响一次。
        assertEquals(listOf(1L, 36L), ticks)
        drag(50L, 81f)
        assertEquals(8, e.edgeIdx)
        drag(100L, 81f) // 限频窗口已过，但没有新 edge，不能补播。
        assertEquals(listOf(1L, 36L), ticks)
        drag(101L, 21f) // 反向跨多格。
        assertEquals(listOf(1L, 36L, 101L), ticks)
    }

    @Test
    fun scrollAnimationAlsoEmitsL1InItsCrossingFrame() {
        val e = engine()
        e.heightCss = 0f
        e.blockEdges = FloatArray(51) { it * 10f }
        e.anim = ScrollAnim(0f, 500f, uptime, 1000f) {}
        val reasons = mutableListOf<String>()
        val haptic: (Int, String, Int) -> Unit = { level, reason, _ ->
            assertEquals(1, level)
            reasons.add(reason)
        }
        e.step(uptime, dt, haptic)
        assertTrue(reasons.isEmpty())
        e.step(uptime + 16L, dt, haptic)
        assertEquals(listOf("滚筒"), reasons)
        e.step(uptime + 32L, dt, haptic)
        assertEquals(1, reasons.size)
        e.step(uptime + 64L, dt, haptic)
        assertEquals(2, reasons.size)
    }

    @Test
    fun dragPauseDecaysOnlyLightingVelocityAfter80ms() {
        val e = engine()
        e.dragging = true
        e.scrollY = 300f
        e.vel = 1000f
        e.lastDragTime = uptime
        e.step(uptime + 80L, dt, silent)
        assertEquals(1000f, e.vel, 0f)
        e.step(uptime + 81L, dt, silent)
        assertTrue(e.vel > 0f && e.vel < 1000f)
        repeat(100) {
            e.step(uptime + 97L + it * 16L, dt, silent)
            assertEquals(300f, e.scrollY, 0f)
        }
        assertEquals(0f, e.vel, 0f)
        e.lastDragTime = uptime + 2000L
        e.vel = 1200f
        e.step(uptime + 2016L, dt, silent)
        assertEquals(1200f, e.vel, 0f)
        assertEquals(300f, e.scrollY, 0f)
        e.vel = 0f
        e.dragging = false
        val parked = settle(e, uptime + 2016L)
        assertFalse(e.step(parked + 16L, dt, silent))
    }
}
