package com.sonance.player.playback

import android.os.SystemClock
import android.util.Log
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

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
     * @return true 表示当前有有效数据（柱高可用），false 表示应走 [decayToZero]
     */
    fun poll(sensitivity: Float, active: Boolean): Boolean {
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
        computeBands(sensitivity, active)
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

    /** 没有真实数据时让柱子自然落下 */
    fun decayToZero() {
        var i = 0
        while (i < levels.size) {
            levels[i] *= 0.86f
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
    private fun computeBands(sensitivity: Float, active: Boolean) {
        val bins = fftN / 2
        val n = levels.size
        val gain = 2.4f * sensitivity

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
            var v = ln(1f + avg) / maxLog * gain
            if (v > 1f) v = 1f
            if (!active) v = 0f
            levels[b] = v
            b++
        }
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
    }
}
