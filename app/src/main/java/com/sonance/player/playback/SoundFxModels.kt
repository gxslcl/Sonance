package com.sonance.player.playback

import androidx.annotation.VisibleForTesting

/**
 * 音效参数与内置预设。
 *
 * 设计取向：**只用公开 DSP 原理 + 自己调出来的参数**，不引任何第三方音效包、
 * 不扒商业 App 的曲线、不用 GPL / 禁商用的引擎（JamesDSP、ViPER core 都在此列）。
 * 每条预设的参数来源都是 [SoundPreset.ORIGIN_SELF]，发布时能一眼说清没有外部素材。
 *
 * [SoundFxParams] 是 EQ 之外的效果配方。预设的 EQ 是下面 10 段均衡器的初始曲线，
 * 不是另一条会重复叠加的曲线。前置增益在 EQ 之前应用，给后续处理留余量。
 */
data class SoundFxParams(
    /** 虚拟低音强度 0..1。轻量补充低频谐波，实际听感取决于扬声器 */
    val virtualBass: Float = 0f,
    /** 混响湿度 0..0.6。算法混响（Freeverb 式），不加载 IR 文件 */
    val reverbWet: Float = 0f,
    /** 混响衰减 0..1，映射到 comb 反馈量 */
    val reverbDecay: Float = 0.5f,
    /** 立体声宽度 0..2，1 = 原样，>1 展宽，<1 收窄 */
    val stereoWidth: Float = 1f,
    /** 前置增益 -12..0 dB，防止 EQ + 各效果叠加后削顶 */
    val preampDb: Float = 0f,
    /** 峰值保护；不能代替设备本身的扬声器保护 */
    val limiter: Boolean = false,
    val limiterThresholdDb: Float = -1f
) {
    /** 没有实际效果时可以整链旁路，省 CPU */
    val isNeutral: Boolean
        get() = virtualBass <= 0f && reverbWet <= 0f &&
            stereoWidth == 1f && preampDb == 0f && !limiter

    /** 所有来自 UI / 持久化的数值都过这一关，脏参数不允许进音频线程 */
    fun clamp(): SoundFxParams = copy(
        virtualBass = virtualBass.finiteOr(0f).coerceIn(0f, 1f),
        reverbWet = reverbWet.finiteOr(0f).coerceIn(0f, 0.6f),
        reverbDecay = reverbDecay.finiteOr(0.5f).coerceIn(0f, 1f),
        stereoWidth = stereoWidth.finiteOr(1f).coerceIn(0f, 2f),
        preampDb = preampDb.finiteOr(0f).coerceIn(-12f, 0f),
        limiterThresholdDb = limiterThresholdDb.finiteOr(-1f).coerceIn(-12f, 0f)
    )

    companion object {
        val NEUTRAL = SoundFxParams()

        /** 持久化用「逗号分隔」，不引 JSON 库、也不用担心解析异常 */
        fun fromStore(raw: String?): SoundFxParams {
            if (raw.isNullOrBlank()) return NEUTRAL
            val p = raw.split(",")
            if (p.size < 6) return NEUTRAL
            return SoundFxParams(
                virtualBass = p[0].toFloatOrNull() ?: 0f,
                reverbWet = p[1].toFloatOrNull() ?: 0f,
                reverbDecay = p[2].toFloatOrNull() ?: 0.5f,
                stereoWidth = p[3].toFloatOrNull() ?: 1f,
                preampDb = p[4].toFloatOrNull() ?: 0f,
                limiter = p[5] == "1",
                limiterThresholdDb = p.getOrNull(6)?.toFloatOrNull() ?: -1f
            ).clamp()
        }

        fun toStore(p: SoundFxParams): String = with(p.clamp()) {
            listOf(
                virtualBass, reverbWet, reverbDecay, stereoWidth, preampDb,
                if (limiter) "1" else "0", limiterThresholdDb
            ).joinToString(",")
        }
    }
}

private fun Float.finiteOr(fallback: Float): Float = if (isFinite()) this else fallback

enum class SoundPresetCategory(val label: String) {
    ORIGINAL("原声"),
    SONANCE("自然"),
    SPEAKER("外放"),
    SPACE("空间")
}

/**
 * 一套音效 = 10 段 EQ 曲线 + 一组效果参数。
 * [eqDb] 长度恒为 [EQ_BAND_COUNT]（31/62/125/250/500/1k/2k/4k/8k/16k Hz）。
 */
data class SoundPreset(
    val id: String,
    val name: String,
    val category: SoundPresetCategory,
    val description: String,
    /** 卡片上的简短听感提示，不暗示某种耳机或音箱必然有同一频响 */
    val character: String,
    val eqDb: FloatArray,
    val fx: SoundFxParams
) {
    companion object {
        const val ORIGIN_SELF = "自研调音 · 参数来自公开 DSP 原理与听感微调"
    }
}

/**
 * 内置预设以完整音乐而非孤立人声为对象。
 *
 * 调音原则（也是旧版最大的问题）：
 * - **不做"每个频段都动一点"的曲线**。±1 dB 的微调等于没调，用户点下去只听到
 *   整体变小 —— 现在每套预设只改 2~3 个关键频段，方向明确。
 * - **提升与衰减成对出现**：抬低频必然收一点 250 Hz（否则糊），抬存在感必然收一点
 *   低中频（否则闷）。这是调音台上最常用的"推一个、让一个"。
 * - **日常档不叠混响**，空间档只用短混响；外放档专门为小喇叭切掉放不出的超低频。
 * - 具体频段参数是可微调的听感起点，不声称校准到某个目标曲线。
 */
object SoundPresets {

    private const val BANDS = 10

    val OFF = SoundPreset(
        id = "off",
        name = "原声",
        category = SoundPresetCategory.ORIGINAL,
        description = "不做任何处理，保持原始录音",
        character = "忠于录音",
        eqDb = FloatArray(BANDS),
        fx = SoundFxParams.NEUTRAL
    )

    /** 去糊 + 提细节 + 高频泛音展宽：低中频让路，4~16 kHz 空气感打开，乐器分离度明显提升。 */
    val CLEAR = SoundPreset(
        id = "clear",
        name = "清晨 · 通透",
        category = SoundPresetCategory.SONANCE,
        description = "收掉 250 Hz 的混浊，把乐器泛音与人声细节自然打开；声场更开阔通透，久听不干瘪",
        character = "清爽 · 高解析",
        eqDb = floatArrayOf(1.5f, 1.0f, -0.5f, -2.0f, -0.5f, 0.5f, 2.2f, 3.6f, 4.0f, 3.6f),
        fx = SoundFxParams(virtualBass = 0.14f, stereoWidth = 1.20f)
    )

    /** 母带级暖倾斜（Warm Tilt）+ 等响度低频微补：低频温厚包裹，2~8 kHz 柔和退后，夜间小音量也饱满耐听。 */
    val NIGHT = SoundPreset(
        id = "night",
        name = "深夜 · 轻柔",
        category = SoundPresetCategory.SONANCE,
        description = "模拟黑胶与胆机的暖调倾斜曲线，低频温润包裹、高频柔和内敛，夜间或长时间聆听毫不刺耳",
        character = "温暖 · 胆机感",
        eqDb = floatArrayOf(2.2f, 2.8f, 2.2f, 1.2f, 0.4f, -0.4f, -1.6f, -2.6f, -3.2f, -3.5f),
        fx = SoundFxParams(virtualBass = 0.24f, stereoWidth = 1.06f)
    )

    /** Pultec 式人声呼吸感 + 中置声像微聚焦：125 Hz 保住嗓音磁性，250 Hz 挖除伴奏掩蔽，1~4 kHz 顺滑托起咬字。 */
    val VOCAL = SoundPreset(
        id = "vocal",
        name = "人声 · 贴耳",
        category = SoundPresetCategory.SONANCE,
        description = "退开伴奏低中频掩蔽，保留嗓音磁性厚度并顺滑托起咬字与气息；中置微聚焦让歌手走近半步",
        character = "亲近 · 温润毒人声",
        eqDb = floatArrayOf(-1.0f, 0.5f, 1.0f, -2.2f, 1.2f, 2.6f, 3.6f, 2.6f, 1.0f, -0.5f),
        fx = SoundFxParams(stereoWidth = 0.90f, limiter = true)
    )

    /** 62 Hz 核心锤感 + 250 Hz 去糊切口 + 动态谐波下潜：鼓点拳拳到肉、收放干脆，绝不轰头或掩盖人声。 */
    val BASS = SoundPreset(
        id = "bass",
        name = "低频 · 有弹性",
        category = SoundPresetCategory.SONANCE,
        description = "强化 62 Hz 底鼓冲击力与奇偶次低频谐波，同时挖除 250 Hz 塑料箱体轰鸣，下潜深且回弹干净",
        character = "澎湃 · 拳拳到肉",
        eqDb = floatArrayOf(3.8f, 4.8f, 2.6f, -1.8f, -0.4f, 0.6f, 1.5f, 2.2f, 2.0f, 1.5f),
        fx = SoundFxParams(virtualBass = 0.48f, stereoWidth = 1.12f, limiter = true)
    )

    /** 早期横向反射 + 舞台微V均衡 + 适度展宽：像站在 Livehouse 前排，乐器在四周呼吸而人声依然贴面。 */
    val LIVE = SoundPreset(
        id = "live",
        name = "现场 · 微醺",
        category = SoundPresetCategory.SPACE,
        description = "还原 Livehouse 前排的早期横向反射与舞台宽度，底鼓与吉他泛音更具临场呼吸感",
        character = "临场 · 舞台包围",
        eqDb = floatArrayOf(2.0f, 2.6f, 1.0f, -1.5f, 0f, 0.8f, 2.0f, 3.0f, 2.4f, 1.5f),
        fx = SoundFxParams(
            virtualBass = 0.22f, reverbWet = 0.18f, reverbDecay = 0.42f, stereoWidth = 1.24f,
            limiter = true
        )
    )

    /** 影院级极低频氛围 + 宽阔声场与厅堂余韵：适合大编制、原声 OST 与平板/耳机全景聆听。 */
    val CINEMA = SoundPreset(
        id = "cinema",
        name = "影院 · 宽广",
        category = SoundPresetCategory.SPACE,
        description = "声场向两侧深度延展，强化极低频氛围烘托与厅堂自然余韵，器乐与大场面气势感十足",
        character = "恢弘 · 全景环绕",
        eqDb = floatArrayOf(3.6f, 4.4f, 2.2f, -1.5f, -0.4f, 0.6f, 1.5f, 2.6f, 2.8f, 1.8f),
        fx = SoundFxParams(
            virtualBass = 0.36f, reverbWet = 0.26f, reverbDecay = 0.60f, stereoWidth = 1.32f,
            limiter = true
        )
    )

    /**
     * 外放档（专为安卓手机/平板扬声器定制）：
     * 切除 31 Hz 振膜打底破音区，强力推起 250~500 Hz 胸腔共鸣厚度，压住 2~4 kHz 喇叭刺耳谐振峰，
     * 再用动态低频谐波把缺失的基频低音饱满地"脑补"回来。
     */
    val DUAL_SPEAKER = SoundPreset(
        id = "dual_speaker",
        name = "外放 · 饱满",
        category = SoundPresetCategory.SPEAKER,
        description = "专为手机与平板扬声器调校：切除破音超低频、补足中低频胸腔厚度、柔化高频毛刺，并用谐波重建饱满低音",
        character = "外放 · 小音箱感",
        eqDb = floatArrayOf(-4.5f, -1.5f, 1.2f, 3.2f, 3.6f, 1.2f, -1.8f, -1.2f, 1.5f, 2.2f),
        fx = SoundFxParams(virtualBass = 0.65f, stereoWidth = 1.18f, limiter = true)
    )

    val ALL: List<SoundPreset> = listOf(
        OFF, CLEAR, VOCAL, BASS, NIGHT, LIVE, CINEMA, DUAL_SPEAKER
    )

    fun fromId(id: String?): SoundPreset =
        ALL.firstOrNull { it.id == id } ?: OFF

    /**
     * 旧版本把内置参数和用户微调都存成一串数值。只迁移与历史配方完全相同的值，
     * 其他值视为用户调音并原样保留；下次选择预设才会主动恢复新的默认配方。
     *
     * 这里按代际列出：第一代（reverb 当底子）、第二代（收窄但前置增益过重）、
     * 第三代（效果参数过于保守）。三代都要能识别，否则升级后老用户会被判定成
     * "自己调过"，于是永远停留在旧参数上。
     */
    private val legacyFx: Map<String, List<SoundFxParams>> = mapOf(
        "off" to listOf(SoundFxParams.NEUTRAL),
        "clear" to listOf(
            SoundFxParams(stereoWidth = 1.12f, reverbWet = 0.05f, preampDb = -3f, limiter = true),
            SoundFxParams(stereoWidth = 1.04f, preampDb = -1.5f, limiter = true),
            SoundFxParams(stereoWidth = 1.05f)
        ),
        "night" to listOf(
            SoundFxParams(stereoWidth = 0.95f, reverbWet = 0.04f, preampDb = -5f, limiter = true),
            SoundFxParams(preampDb = -1.5f, limiter = true),
            SoundFxParams.NEUTRAL
        ),
        "vocal" to listOf(
            SoundFxParams(reverbWet = 0.07f, preampDb = -3f, limiter = true),
            SoundFxParams(preampDb = -1.8f, limiter = true),
            SoundFxParams(limiter = true)
        ),
        "bass" to listOf(
            SoundFxParams(virtualBass = 0.55f, preampDb = -4f, limiter = true),
            SoundFxParams(preampDb = -2.5f, limiter = true),
            SoundFxParams(limiter = true)
        ),
        "live" to listOf(
            SoundFxParams(reverbWet = 0.22f, reverbDecay = 0.35f, stereoWidth = 1.25f, preampDb = -4f, limiter = true),
            SoundFxParams(reverbWet = 0.10f, reverbDecay = 0.35f, stereoWidth = 1.10f, preampDb = -1.5f, limiter = true),
            SoundFxParams(reverbWet = 0.10f, reverbDecay = 0.38f, stereoWidth = 1.10f, limiter = true)
        ),
        "cinema" to listOf(
            SoundFxParams(reverbWet = 0.30f, reverbDecay = 0.80f, stereoWidth = 1.35f, preampDb = -5f, limiter = true),
            SoundFxParams(reverbWet = 0.16f, reverbDecay = 0.55f, stereoWidth = 1.16f, preampDb = -1.8f, limiter = true),
            SoundFxParams(reverbWet = 0.15f, reverbDecay = 0.60f, stereoWidth = 1.16f, limiter = true)
        ),
        "dual_speaker" to listOf(
            SoundFxParams(virtualBass = 0.7f, reverbWet = 0.06f, reverbDecay = 0.2f, stereoWidth = 1.18f, preampDb = -4f, limiter = true),
            SoundFxParams(virtualBass = 0.35f, preampDb = -1.8f, limiter = true),
            SoundFxParams(virtualBass = 0.55f, limiter = true)
        )
    )

    data class RestoredFx(val params: SoundFxParams, val custom: Boolean)

    fun restoreFx(preset: SoundPreset, raw: String?): RestoredFx {
        val stored = SoundFxParams.fromStore(raw)
        val unchanged = raw.isNullOrBlank() ||
            stored == preset.fx ||
            legacyFx[preset.id]?.any { it == stored } == true
        return if (unchanged) RestoredFx(preset.fx, false) else RestoredFx(stored, true)
    }

    private const val EQ_STORE_PREFIX = "sound:"

    /** 在既有 eq_preset 字段里标记曲线来源，避免重启后错还原成上一次的曲风。 */
    fun eqStoreName(preset: SoundPreset): String = EQ_STORE_PREFIX + preset.id

    fun eqPresetFromStore(raw: String?): SoundPreset? {
        if (raw == null || !raw.startsWith(EQ_STORE_PREFIX)) return null
        val id = raw.removePrefix(EQ_STORE_PREFIX)
        return ALL.firstOrNull { it.id == id }
    }
}

/**
 * 音频线程 ↔ UI 线程的参数总线，沿用 [EqBus] 的 `revision` 单调递增做法：
 * UI 只写这里，处理器只在版本号变化时才重算状态，不必每块 buffer 做浮点幂运算。
 */
object SoundFxBus {
    @Volatile
    var enabled: Boolean = false
        private set

    @Volatile
    var params: SoundFxParams = SoundFxParams.NEUTRAL
        private set

    /** A/B 试听：按住「听原声」时临时旁通，松开立刻回来 */
    @Volatile
    var bypass: Boolean = false
        private set

    @Volatile
    var revision: Long = 0L
        private set

    fun update(enabled: Boolean, params: SoundFxParams) {
        this.enabled = enabled
        this.params = params.clamp()
        revision++
    }

    fun setBypass(value: Boolean) {
        if (bypass == value) return
        bypass = value
        revision++
    }

    @VisibleForTesting
    internal fun reset() {
        enabled = false
        params = SoundFxParams.NEUTRAL
        bypass = false
        revision = 0L
    }
}
