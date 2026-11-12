package com.sonance.player.playback

import kotlin.math.exp
import kotlin.math.ln

/**
 * 律动柱的物理状态机。
 *
 * ## 为什么要重写：老实现是「按帧」衰减的
 *
 * 老代码是 `cur + (target - cur) * 0.13f` —— `0.13` 是**每帧**的系数，
 * 于是衰减速度直接跟刷新率挂钩：
 *
 * | 刷新率 | 每秒保留 | 从满高落到 10% |
 * |---|---|---|
 * | 60Hz | `0.87^60 ≈ 2e-4` | 17 帧 = 280ms |
 * | 120Hz | `0.87^120 ≈ 4e-8` | 17 帧 = **140ms** |
 *
 * 同一份代码在 120Hz 屏上衰减快一倍。所以这里所有演化都按 **dt（秒）** 积分。
 *
 * ## 帧率无关是**精确成立**的，不是近似
 *
 * 两段演化都是「可组合」的线性过程 —— 把 `dt` 拆成 `dt/2 + dt/2` 迭代两次，
 * 结果和一次 `dt` **完全相等**（浮点误差量级）：
 *
 * - 上升：`p = T - (T-p₀)·(1-a)^(60·dt)`。两段相乘时指数相加：
 *   `(1-a)^(60·dt₁)·(1-a)^(60·dt₂) = (1-a)^(60·(dt₁+dt₂))` ✓
 * - 下落：常系数线性 ODE `v' = -g - k·v` 的解就是它的**流**，
 *   而流的半群性质保证 `flow(t₁+t₂) = flow(t₂) ∘ flow(t₁)` ✓
 *
 * 所以用的是解析解而不是欧拉积分 —— 欧拉积分会有 O(dt) 的误差，
 * 120Hz 和 60Hz 会走出**不同的轨迹**（那正是要避免的）。
 *
 * ## 零 GC
 *
 * 所有数组构造时分配一次；[step] 内**零分配** —— 没有装箱、没有临时对象、
 * 没有 lambda 捕获。每帧只算 2 次 `exp`（上升一次、下落一次，全柱共用）。
 *
 * ## 线程
 *
 * 只在**一个线程**上调用 [step]（UI 帧回调）。[target] 由 IO 线程写、UI 线程读：
 * FloatArray 单元素写入是原子的，读到撕裂值最多让某根柱子某一帧偏一点，
 * 视觉上不可见 —— 不值得为它加锁。
 */
class VizPhysics(
    val bars: Int,
    /**
     * 上升系数：**每 1/60 秒**逼近目标的比例。
     *
     * 越大越"跟手"；0.9 以上基本就是瞬时贴合了。语义定在 60fps 基准上是为了
     * 和参数面板上的数字直觉一致（"每帧逼近 62%"），但执行时按 dt 换算，
     * 所以 120Hz 下的实际手感是同一个。
     */
    var attackFactor: Float = DEFAULT_ATTACK,
    /** 空气阻尼系数（1/秒）：越大下落越"黏"，自由落体感越弱 */
    var decayDamping: Float = DEFAULT_DAMPING,
    /** 重力加速度（单位/秒²）：越大落得越快 */
    var gravity: Float = DEFAULT_GRAVITY,
    /**
     * 峰值悬停时长，单位是 **60fps 基准的帧数**。
     *
     * 内部换算成秒执行。之所以不按帧递减：120Hz 下 22 帧只有 183ms，
     * 60Hz 下是 367ms —— 帧计数会让悬停时长跟刷新率绑定，和"帧率无关"的目标矛盾。
     */
    var peakHoldTicks: Int = DEFAULT_PEAK_HOLD_TICKS,
    /** 是否维护峰值条。关掉时 [step] 里省掉峰值那一整段运算 */
    var peakEnabled: Boolean = true
) {

    /** 显示高度 0..1 —— 绘制只读这个 */
    val pos = FloatArray(bars)

    /** 峰值条位置 0..1。绘制只读，[peakEnabled] 为 false 时不维护 */
    val peak = FloatArray(bars)

    /** 下落速度（单位/秒，负数表示向下） */
    private val vel = FloatArray(bars)

    /** 峰值条自己的下落速度 */
    private val peakVel = FloatArray(bars)

    /** 峰值悬停剩余时间（秒） */
    private val peakHold = FloatArray(bars)

    // ===== 派生量：只在参数变化时重算，避免每帧做 ln =====

    /** `ln(1 - attackFactor) * 60`。上升系数是 `1 - exp(attackLog * dt)` */
    private var attackLog = 0f
    private var lastAttack = Float.NaN

    /** `peakHoldTicks / 60f` 秒 */
    private var holdSec = 0f
    private var lastHoldTicks = Int.MIN_VALUE

    /**
     * 单帧 dt 上限（秒）。
     *
     * 切后台再回来、或者卡了一大下，会给出几百毫秒的 dt —— 不夹住的话柱子
     * 会瞬间归零（看起来像"闪了一下"）。夹到 50ms 相当于最多丢 6 帧的量。
     */
    private val maxDt = 0.05f

    init {
        refreshDerived()
    }

    /** 清空所有状态（切歌 / 重建时用） */
    fun reset() {
        pos.fill(0f)
        peak.fill(0f)
        vel.fill(0f)
        peakVel.fill(0f)
        peakHold.fill(0f)
    }

    /**
     * 推进一帧。
     *
     * @param target 目标高度 0..1（来自 FFT + AGC），长度应等于 [bars]
     * @param dtSec  距上一帧的秒数
     */
    fun step(target: FloatArray, dtSec: Float) {
        refreshDerived()
        val dt = dtSec.coerceIn(0f, maxDt)
        if (dt <= 0f) return

        // ---- 每帧只算这两次 exp，全柱共用 ----
        val attackK = 1f - exp(attackLog * dt)
        val drag = exp(-decayDamping * dt)

        // 下落解析解的系数（也是全柱共用）
        val invK = 1f / decayDamping
        val gk = gravity * invK
        val fallTerm = (1f - drag) * invK
        // 峰值条用同一个阻尼 k，只是重力常数不同 → 系数复用同一套
        val peakGk = PEAK_FALL_GRAVITY * invK

        val peakOn = peakEnabled
        val n = bars
        var b = 0
        while (b < n) {
            val t = if (b < target.size) target[b] else 0f
            var p = pos[b]
            var v = vel[b]

            if (t >= p) {
                // ── 上升：高响应指数逼近 ──
                // 解析解 `p = T - (T-p₀)·(1-a)^(60·dt)`，可组合 → 帧率无关
                p += (t - p) * attackK
                if (p > 1f) p = 1f
                v = 0f          // 上升阶段速度状态无意义，清掉免得残留到下落段
            } else {
                // ── 下落：重力加速 + 空气阻尼，用常系数线性 ODE 的解析解 ──
                //
                //   v' = -g - k·v
                //   v(t) = (v₀ + g/k)·e^(-kt) - g/k
                //   p(t) = p₀ + (v₀ + g/k)·(1 - e^(-kt))/k - (g/k)·t
                //
                // 比欧拉积分多几次乘除，换来的是**精确的帧率无关**：
                // 欧拉有 O(dt) 误差，60Hz 和 120Hz 会走出不同轨迹。
                val v1 = (v + gk) * drag - gk
                val p1 = p + (v + gk) * fallTerm - gk * dt
                p = p1
                v = v1
                if (p <= t) {
                    // 落到目标就停：不穿透、不反弹
                    p = t
                    v = 0f
                }
            }

            pos[b] = p
            vel[b] = v

            // ── 峰值条：吸附 → 悬停 → 加速落下 ──
            if (peakOn) {
                val pk = peak[b]
                if (p >= pk) {
                    peak[b] = p
                    peakHold[b] = holdSec
                    peakVel[b] = 0f
                } else {
                    // ── 悬停 / 下落 ──
                    //
                    // 悬停结束的时刻**必须**落在真实时间轴上，不能被帧边界量化：
                    // 如果只是"减到 <=0 就从下一帧开始落"，60Hz 和 120Hz 会在
                    // 相差最多一帧的时刻开始下落，峰值位置就对不上了。
                    // 所以这里把"悬停在本帧内结束"多出来的那段时间**结转给下落**。
                    var fallDt = dt
                    if (peakHold[b] > 0f) {
                        val left = peakHold[b] - dt
                        if (left > 0f) {
                            peakHold[b] = left
                            fallDt = 0f          // 整帧都在悬停：峰值一动不动
                        } else {
                            peakHold[b] = 0f
                            fallDt = -left       // 悬停刚结束，本帧剩余的时间用来下落
                        }
                    }

                    if (fallDt > 0f) {
                        // 和主下落同一个解析解，只是重力常数不同。
                        //
                        // ⚠️ 这里**不能**用 `pv += g·dt; np = pk - pv·dt` 那种欧拉积分 ——
                        // 欧拉有 O(dt) 误差，60Hz 和 120Hz 会走出不同轨迹
                        // （单测「峰值条位置也与帧率无关」钉的就是这个，第一版栽在这）。
                        // 用解析解才满足流的半群性质 → 拆成任意多小步结果都相同。
                        //
                        // peakVel 表示**向下的速度**（正数），所以 ODE 是 pv' = g - k·pv。
                        // fallDt 通常等于 dt（系数直接复用）；只有悬停刚结束那一帧
                        // 才需要按 fallDt 重算一次系数 —— 每个峰值一生只发生一次。
                        val d = if (fallDt == dt) drag else exp(-decayDamping * fallDt)
                        val ft = if (fallDt == dt) fallTerm else (1f - d) * invK
                        val pv = peakVel[b]
                        var np = pk - ((pv - peakGk) * ft + peakGk * fallDt)
                        var nv = (pv - peakGk) * d + peakGk
                        if (np <= p) {
                            // 追上柱子就停，不继续往下掉
                            np = p
                            nv = 0f
                        }
                        peak[b] = np
                        peakVel[b] = nv
                    }
                }
            }
            b++
        }
    }

    /** 参数变了就重算派生量。两个比较而已，每帧调也没负担 */
    private fun refreshDerived() {
        if (attackFactor != lastAttack) {
            lastAttack = attackFactor
            // 1 - (1-a)^(dt*60) 用 exp(ln(1-a) · dt · 60) 实现，
            // 于是 ln 只算这一次，每帧只剩一次 exp
            attackLog = ln((1f - attackFactor).coerceIn(1e-4f, 1f)) * 60f
        }
        if (peakHoldTicks != lastHoldTicks) {
            lastHoldTicks = peakHoldTicks
            holdSec = peakHoldTicks.coerceAtLeast(0) / 60f
        }
    }

    companion object {
        /** 每 1/60 秒逼近 62%：约 4 帧到 95%，够跟手又不会硬贴 */
        const val DEFAULT_ATTACK = 0.62f

        /** 空气阻尼 3.4/s（速度时间常数约 0.29 秒） */
        const val DEFAULT_DAMPING = 3.4f

        /** 重力 6.5/s²：从满高落到底约 0.8 秒，且是**加速**的 */
        const val DEFAULT_GRAVITY = 6.5f

        /** 峰值悬停 10 帧（60fps 基准）≈ 167ms：太久会像顶部又叠了一层"顶" */
        const val DEFAULT_PEAK_HOLD_TICKS = 10

        /**
         * 峰值条自己的下落加速度。
         *
         * **不暴露成设置**：它的观感主要取决于悬停时长，加速度给个固定值就够了，
         * 再开一个参数只会让设置面板更难懂。
         */
        private const val PEAK_FALL_GRAVITY = 3.0f
    }
}
