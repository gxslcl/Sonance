package com.sonance.player.playback

import android.os.SystemClock
import android.util.Log
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * 真实音频可视化数据源。
 *
 * 数据来自 [PcmTapProcessor]：直接取自家播放链路上的 PCM，自己做 FFT。
 * 不再使用 `android.media.audiofx.Visualizer` —— 它在 realme/ColorOS（Android 16）上
 * 连应用自己的会话都建不起来（`Cannot initialize Visualizer engine, error: -3`），
 * 而 Android 10+ 又禁止普通应用抓全局输出。
 *
 * 性能约定（重要）：
 * - 本类**不做线程切换**，调用方负责在后台线程调 [poll]（UI 侧用 Dispatchers.Default）。
 * - [poll] 内部有"新数据门控"：PCM 缓冲没更新就直接返回，不跑 FFT。
 *   音频缓冲实际只有 20~40 次/秒更新，这样 FFT 次数从"每帧一次"降到"每个缓冲一次"。
 * - 所有工作区（FFT 数组、窗函数、频段表）构造时分配一次，[poll] 里零分配。
 */
class AudioVisualizer(barCount: Int) {

    val bars = barCount.coerceIn(16, 128)

    /** 已平滑的柱高 0..1，绘制线程直接读；Float 写入是原子的，无需加锁 */
    val levels = FloatArray(bars)

    /** 归一化前的原始柱高，[applyAutoGain] 的输入 */
    private val rawBands = FloatArray(bars)

    /**
     * EMA 平滑后的频段能量。
     *
     * 放在 AGC **之前**：AGC 会做逐频段归一化，先滤掉噪声再归一化，
     * 峰值追踪才不会被单个毛刺顶高 —— 那会让后面几帧整根柱子集体变矮，
     * 看起来像"抽了一下"。
     */
    private val emaBands = FloatArray(bars)

    /**
     * EMA 系数 0..1：越小越平滑、但越迟钝。
     *
     * 0.55 只滤掉 FFT 的逐帧抖动，不会明显拖慢打击感。
     * 低于 0.3 时上升会肉眼可见地变钝（打击感丢失）。
     */
    var emaAlpha: Float = 0.55f

    /**
     * 每根柱子自己的历史峰值，缓慢衰减。
     *
     * 音乐的能量天然集中在低频，所以左边几根柱子总是跳得最凶、右边几乎不动 ——
     * 看起来"重心偏左、不平衡"。用它做**逐频段归一化**，每根柱子的动态范围就被拉平了。
     */
    private val bandPeak = FloatArray(bars)

    /** 是否有真实数据源（PCM 取样器已拿到格式并收到数据） */
    val available: Boolean
        get() = PcmBus.ready

    // ===== FFT 工作区：构造时分配一次 =====
    // 1024 点对 48~96 根柱子足够（512 个 bin），比 2048 点省一半以上运算
    private val fftN = 1024
    private val re = FloatArray(fftN)
    private val im = FloatArray(fftN)
    private val hann = FloatArray(fftN)
    private val twRe = FloatArray(fftN / 2)
    private val twIm = FloatArray(fftN / 2)

    /** 参与映射的 bin 数：高频区基本是空的，砍掉 1/4 */
    private val usable = (fftN / 2 * 3) / 4

    /** 对数压缩的归一化分母，构造时算一次 */
    private val maxLog = ln(1f + usable.toFloat())

    /**
     * 每根柱子对应的 bin 区间起点（长度 bars+1，最后一个是哨兵）。
     * 原来是在 computeBands 里逐帧调 logIndex()，内含 exp/ln，每帧 96 次超越函数；
     * 它只依赖 (bars, usable)，是常量，这里预计算。
     */
    private val bandEdges = IntArray(bars + 1)

    private var lastSeenBuffers = -1L
    private var lastDataAtMs = 0L
    private var lastLogAtMs = 0L

    /** 累计真正跑过的 FFT 次数，用来确认门控是否生效 */
    @Volatile
    var fftCount: Long = 0L
        private set

    init {
        var k = 0
        while (k < fftN / 2) {
            val a = -2.0 * PI * k / fftN
            twRe[k] = cos(a).toFloat()
            twIm[k] = sin(a).toFloat()
            k++
        }
        var i = 0
        while (i < fftN) {
            hann[i] = (0.5 - 0.5 * cos(2.0 * PI * i / (fftN - 1))).toFloat()
            i++
        }
        // 频段边界：先按对数分布算，再量化成**严格递增**的整数边界。
        //
        // 为什么不能直接 toInt()：对数分布下低频端相邻柱子的边界差不到 1 个 bin，
        // toInt() 之后会落在同一个 bin 上 → 这几根柱子高度**完全相同** → 视觉上糊成
        // 一整块，看起来"左边的柱子比右边明显少"。
        // 实测：48 柱时前 6 根都映射到 bin 1（20% 的柱子重复）；
        // 96 柱时前 12 根都是 bin 1（31% 重复），全在左侧。
        //
        // 这里保证每根柱子至少分到 1 个独立 bin。usable(384) 远大于最大柱数(128)，
        // 所以不会把高频端挤没。
        var prev = 0
        i = 0
        while (i <= bars) {
            val raw = logIndex(i, bars, usable)
            val v = maxOf(raw, prev + 1).coerceAtMost(usable)
            bandEdges[i] = v
            prev = v
            i++
        }
        bandEdges[bars] = usable
    }

    /**
     * 频段边界的只读快照，**只给单元测试用**。
     *
     * 它必须满足「严格递增」：一旦相邻两根柱子落在同一个 bin 上，
     * 它们的高度就完全一样，视觉上会糊成一块（表现就是"左边的柱子比右边少"）。
     */
    @androidx.annotation.VisibleForTesting
    internal fun bandEdgesSnapshot(): IntArray = bandEdges.copyOf()

    /**
     * 保留旧签名以免改动 UI —— 新数据源和 audio session 无关，[sessionId] 被忽略。
     * @return 当前是否已能拿到真实数据
     */
    fun start(sessionId: Int): Boolean {
        Log.i(
            TAG,
            "PCM 数据源: ready=${PcmBus.ready} sampleRate=${PcmBus.sampleRate} " +
                "channels=${PcmBus.channels} fftN=$fftN bars=$bars"
        )
        return PcmBus.ready
    }

    fun stop() {
        levels.fill(0f)
    }

    /**
     * 拉取一帧并更新 [levels]。**必须在后台线程调用。**
     *
     * @param level 律动幅度 0.5..1.0（在整套映射之后相乘，等比缩放柱高）
     * @param active 播放中才输出能量；暂停时目标值归零
     * @return true 表示当前有有效数据（柱高可用），false 表示应走 [decayToZero]
     */
    fun poll(level: Float, active: Boolean): Boolean {
        if (!PcmBus.ready) return false

        val seen = PcmBus.buffersSeen
        val now = SystemClock.elapsedRealtime()

        if (seen == lastSeenBuffers) {
            // 没有新 PCM：不跑 FFT，只要还在"活跃窗口"内就沿用上一帧结果。
            // 之前这里会继续跑完整 FFT，导致同一批样本被重复算 3~6 次。
            return now - lastDataAtMs <= 300L
        }
        lastSeenBuffers = seen
        lastDataAtMs = now

        if (PcmBus.ring.readLatest(re, fftN) < fftN) return false

        var i = 0
        while (i < fftN) {
            re[i] *= hann[i]
            im[i] = 0f
            i++
        }
        fft(re, im)
        computeBands(level, active)
        fftCount++

        // 每 ~3 秒打一条，便于确认门控与灵敏度是否正常
        if (now - lastLogAtMs > 3000L) {
            lastLogAtMs = now
            var mx = 0f
            for (v in levels) if (v > mx) mx = v
            Log.i(
                TAG,
                "levels max=$mx fftCount=$fftCount ready=${PcmBus.ready} " +
                    "sr=${PcmBus.sampleRate} ch=${PcmBus.channels}"
            )
        }
        return true
    }

    /**
     * 没有真实数据时把**目标值**拉向 0。
     *
     * 注意它现在只影响目标值 —— 柱子实际怎么落由 `VizPhysics` 按 dt 积分决定，
     * 所以暂停时柱子会走一次自然的自由落体，而不是这里硬压下去。
     * 这里的系数只决定"目标多快归零"，0.86 在 250Hz 轮询下约 30ms 归零，
     * 快到不参与观感，所以保持按次衰减、不改成按时间。
     */
    fun decayToZero() {
        var i = 0
        while (i < levels.size) {
            levels[i] *= 0.86f
            emaBands[i] *= 0.86f
            i++
        }
    }

    /**
     * FFT -> 柱高（**原始目标值，不做平滑**）。
     *
     * 平滑挪到绘制阶段做了：FFT 只有 20~40Hz（跟着音频缓冲走），而绘制是
     * 60~120Hz，如果在这里平滑，两次 FFT 之间画面就是完全静止的、看起来一格一格跳。
     * 交给绘制侧按帧插值，才能把 30Hz 的数据画成连续运动。
     */
    private fun computeBands(level: Float, active: Boolean) {
        val bins = fftN / 2
        val n = levels.size

        var b = 0
        while (b < n) {
            val lo = bandEdges[b]
            val hi = bandEdges[b + 1]
            var sumSq = 0f
            var count = 0
            var k = lo
            while (k < hi && k < bins) {
                val rr = re[k]
                val ii = im[k]
                sumSq += rr * rr + ii * ii
                count++
                k++
            }
            val avg = if (count > 0) sqrt(sumSq / count) * FFT_SCALE else 0f
            // 这里**不再截断**到 1：真实幅度要留给 AGC 区分"刚好到顶"和"远超满格"。
            // 之前在 AGC 前裁剪，任何超过 1 的频段都变成同一个 1.0 → 顶部整片平头。
            var v = ln(1f + avg) / maxLog * BAND_GAIN
            if (!active) v = 0f

            // ★ EMA 平滑：消掉 FFT 逐帧的抖动噪点。
            // 它只影响"目标值"的平滑度；柱子本身的运动由 VizPhysics 负责，
            // 那里是按 dt 积分的，所以这一步不会引入帧率依赖
            // （poll 的调用频率跟着音频缓冲走，与屏幕刷新率无关）。
            val a = emaAlpha
            emaBands[b] += (v - emaBands[b]) * a
            rawBands[b] = emaBands[b]
            b++
        }
        applyAutoGain(rawBands, bandPeak, levels, level)
    }

    /**
     * 逐频段自动增益 + 顶部余量。
     *
     * 旧实现是 `0.88 × (v / 本柱峰值) + 0.12 × v` —— 每根柱子都拿自己的近期峰值当 100%，
     * 于是**每根柱子都会顶到接近 1.0**：随便一首歌就是一片冲顶的平墙。
     *
     * 现在分三件事：
     *
     * 1. **混合参考**：`0.65 × 本柱峰值 + 0.35 × 全频段峰值`。顶端重新变成"事件"：
     *    只有真正最响的频段能到满格，其余按自己相对全频段的能量落到中段。
     * 2. **幂次 1.30**：只压中段、不动峰值，把"接近自己的峰值"从 0.9 压到 0.6 附近。
     * 3. **软顶**：低于 0.52 线性，高于它按 tanh 压向 0.82。满格不再贴着顶栏，
     *    同时 0.82 附近仍有区分度，不会出现一排等高的平头柱。
     *
     * - 峰值**快起慢落**（`0.992` 每帧，约 3 秒半衰），安静之后该频段能重新顶满
     * - 峰值太小时（刚起播 / 该频段一直没声）直接用原始值，避免除零放大噪声
     *
     * 抽成独立函数是为了能单测：它只依赖输入数组，不需要真的跑一遍 FFT。
     *
     * @param level 律动幅度 0.5..1.0，在整套映射之后等比缩放
     */
    @androidx.annotation.VisibleForTesting
    internal fun applyAutoGain(raw: FloatArray, peaks: FloatArray, out: FloatArray, level: Float = 1f) {
        val n = minOf(raw.size, peaks.size, out.size)
        if (n == 0) return

        // 第一遍：推进峰值，同时拿到全频段参考（本轮最大的那个峰值）
        var globalPeak = 0f
        var i = 0
        while (i < n) {
            val v = raw[i]
            val p = peaks[i]
            val peak = if (v > p) v else p * PEAK_DECAY
            peaks[i] = peak
            if (peak > globalPeak) globalPeak = peak
            i++
        }
        val globalValid = globalPeak > MIN_PEAK
        val gain = level.coerceIn(0f, MAX_LEVEL)

        // 第二遍：归一化 → 混合参考 → 压中段 → 软顶
        i = 0
        while (i < n) {
            val v = raw[i].coerceAtLeast(0f)
            val peak = peaks[i]
            val normLocal = if (peak > MIN_PEAK) (v / peak).coerceIn(0f, 1f) else v.coerceAtMost(1f)
            val normGlobal = if (globalValid) (v / globalPeak).coerceIn(0f, 1f) else v.coerceAtMost(1f)
            val norm = LOCAL_PEAK_WEIGHT * normLocal + (1f - LOCAL_PEAK_WEIGHT) * normGlobal
            val shaped = norm.pow(NORM_GAMMA)
            val mixed = NORM_WEIGHT * shaped + (1f - NORM_WEIGHT) * v.coerceAtMost(1f)
            out[i] = displayHeight(mixed) * gain
            i++
        }
    }

    /** 软顶：低于拐点线性，高于拐点用 tanh 收敛到 [CEILING]，保留区分度而不是硬切平。 */
    private fun displayHeight(value: Float): Float {
        val v = value.coerceIn(0f, 1f)
        val soft = if (v <= SOFT_KNEE) {
            v
        } else {
            val t = (v - SOFT_KNEE) / (1f - SOFT_KNEE)
            SOFT_KNEE + (1f - SOFT_KNEE) * tanh(t * KNEE_CURVE) / tanh(KNEE_CURVE)
        }
        return (CEILING * soft).coerceIn(0f, CEILING)
    }

    /** 第 i 根柱子在频率 bin 上的对数起点（只在构造时调用） */
    private fun logIndex(i: Int, total: Int, usable: Int): Int {
        val t = i.toFloat() / total.toFloat()
        val v = 1f + (usable - 1f) * (exp(ln(usable.toFloat()) * t) - 1f) /
            (usable.toFloat() - 1f)
        return v.toInt().coerceIn(1, usable - 1)
    }

    /**
     * 迭代式 radix-2 Cooley-Tukey FFT（原位运算）。
     * 输入实数放在 [re]、虚部清零；输出为复数频谱。旋转因子查表，避免逐级累乘误差。
     */
    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size

        // 位反转置换
        var j = 0
        var i = 1
        while (i < n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
            i++
        }

        // 蝶形运算
        var len = 2
        while (len <= n) {
            val half = len shr 1
            val step = n / len
            var start = 0
            while (start < n) {
                var k = 0
                while (k < half) {
                    val w = k * step
                    val even = start + k
                    val odd = even + half
                    val tRe = re[odd] * twRe[w] - im[odd] * twIm[w]
                    val tIm = re[odd] * twIm[w] + im[odd] * twRe[w]
                    re[odd] = re[even] - tRe
                    im[odd] = im[even] - tIm
                    re[even] += tRe
                    im[even] += tIm
                    k++
                }
                start += len
            }
            len = len shl 1
        }
    }

    companion object {
        private const val TAG = "SonanceViz"

        /**
         * 归一化系数：满幅正弦的峰值 bin ≈ N/2，乘上它之后量级稳定在 ~256，
         * 与旧 Visualizer 字节 FFT（满幅 ≈128）的对数压缩曲线手感一致。
         * 随 [fftN] 变化：1024 点时取 0.5。
         */
        private const val FFT_SCALE = 0.5f

        /** 历史峰值的每帧衰减；0.992 在 30Hz 更新下约 3 秒半衰 */
        private const val PEAK_DECAY = 0.992f

        /** 低于这个峰值就不做归一化，避免除零把噪声放大 */
        private const val MIN_PEAK = 0.06f

        /**
         * 频段能量 → 对数高度的固定增益。
         *
         * 2.4 时 `ln(1+avg)/ln(385)×2.4 = 1` 只对应 avg ≈ 10.9（约 -21 dBFS），
         * 普通音乐的中低频段全都在这条线之上 → 整片柱子直接饱和。
         * 1.6 把饱和点推到 avg ≈ 40（约 -11 dBFS），只有真正的强拍才会接近顶部。
         */
        private const val BAND_GAIN = 1.6f

        /**
         * 归一化里「本柱峰值」的权重，剩下 0.35 交给「全频段峰值」。
         *
         * 纯本柱峰值会让每根柱子都在自己的巅峰时顶到 100%，也就是"一片满"；
         * 纯全频段峰值又会回到左重右轻。这个比例让顶端重新变得稀缺，
         * 同时保留大部分跨频段的平衡。
         */
        private const val LOCAL_PEAK_WEIGHT = 0.65f

        /** 归一化项在最终混合里的权重；其余留给原始幅度，保留"这段整体很轻"的层次感 */
        private const val NORM_WEIGHT = 0.70f

        /** 归一化项的幂次：压中段、不动峰值（1.0 = 原样，越大中段越低） */
        private const val NORM_GAMMA = 1.30f

        /** 软拐点：低于它保持线性，高于它按 tanh 收敛，避免顶部出现一排平头柱 */
        private const val SOFT_KNEE = 0.52f

        /** 满格映射到的最终高度。0.82 让柱顶与顶栏之间留出一条可见的余量 */
        private const val CEILING = 0.82f

        /** 软顶的弯曲程度；越大越快贴到 ceiling */
        private const val KNEE_CURVE = 2f

        /** 律动幅度上限（Prefs 里限制在 0.5..1.0，这里只是兜底） */
        private const val MAX_LEVEL = 1.5f
    }
}
