package com.sonance.player.playback

import androidx.annotation.VisibleForTesting
import kotlin.math.log10

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
    /** 虚拟低音强度 0..1。在超低频高通保护前提取基频，动态合成二/三次谐波 */
    val virtualBass: Float = 0f,
    /** 混响湿度 0..0.6。改良 Freeverb 早期反射 + 互质抽头长尾声场，不加载外部 IR 文件 */
    val reverbWet: Float = 0f,
    /** 混响衰减 0..1，映射到 comb 反馈量与高频阻尼 */
    val reverbDecay: Float = 0.5f,
    /** 立体声宽度 0..2，1 = 原样，>1 展宽，<1 收窄（210 Hz 以下双级锁定单声道稳固） */
    val stereoWidth: Float = 1f,
    /** 前置增益 -12..0 dB，防止 EQ + 各效果叠加后削顶 */
    val preampDb: Float = 0f,
    /** 峰值保护；软拐点限幅减少削波失真 */
    val limiter: Boolean = false,
    val limiterThresholdDb: Float = -1f,
    /** 双耳交叉馈送 0..1（BS2B 式低通串扰：缓解耳机头中效应，呈现音箱自然定位） */
    val crossfeed: Float = 0f,
    /** 外放高通保护截止频率 Hz：0 表示关闭，启用时 30..160 Hz 切除外放难还原且易破音的超低频 */
    val speakerHpfHz: Float = 0f
) {
    /** 没有实际效果时可以整链旁路，省 CPU */
    val isNeutral: Boolean
        get() = virtualBass <= 0f && reverbWet <= 0f &&
            stereoWidth == 1f && preampDb == 0f && !limiter &&
            crossfeed <= 0f && speakerHpfHz <= 0f

    /** 所有来自 UI / 持久化的数值都过这一关，脏参数不允许进音频线程 */
    fun clamp(): SoundFxParams = copy(
        virtualBass = virtualBass.finiteOr(0f).coerceIn(0f, 1f),
        reverbWet = reverbWet.finiteOr(0f).coerceIn(0f, 0.6f),
        reverbDecay = reverbDecay.finiteOr(0.5f).coerceIn(0f, 1f),
        stereoWidth = stereoWidth.finiteOr(1f).coerceIn(0f, 2f),
        preampDb = preampDb.finiteOr(0f).coerceIn(-12f, 0f),
        limiterThresholdDb = limiterThresholdDb.finiteOr(-1f).coerceIn(-12f, 0f),
        crossfeed = crossfeed.finiteOr(0f).coerceIn(0f, 1f),
        speakerHpfHz = speakerHpfHz.finiteOr(0f).let { if (it < 20f) 0f else it.coerceIn(30f, 160f) }
    )

    companion object {
        val NEUTRAL = SoundFxParams()

        /** 持久化用「逗号分隔」，兼容旧版 6~7 段格式与新版 9 段格式 */
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
                limiterThresholdDb = p.getOrNull(6)?.toFloatOrNull() ?: -1f,
                crossfeed = p.getOrNull(7)?.toFloatOrNull() ?: 0f,
                speakerHpfHz = p.getOrNull(8)?.toFloatOrNull() ?: 0f
            ).clamp()
        }

        fun toStore(p: SoundFxParams): String = with(p.clamp()) {
            listOf(
                virtualBass, reverbWet, reverbDecay, stereoWidth, preampDb,
                if (limiter) "1" else "0", limiterThresholdDb, crossfeed, speakerHpfHz
            ).joinToString(",")
        }
    }
}

/**
 * 为后级效果计算动态峰值余量（dB），由 [SoundChainDsp]、[presetNetCurve] 与
 * [presetToneBands] 共用，保证运行时实际听感与预览图保持同一响度基准。
 *
 * 说明：正常音乐中 Side 分量通常比 Mid 弱 6~12 dB，因此按 `3.0 * log10(width)` 预留，
 * 既能避免宽声场边缘削顶，又不会像旧版 `12 * log10(width)` 那样误压掉 4~5 dB 中频响度。
 */
internal fun effectPreampBudgetDb(params: SoundFxParams, channels: Int = 2): Float {
    if (channels > 2) return 0f
    val widthBudget = if (channels == 2 && params.stereoWidth > 1f) {
        3.0f * log10(params.stereoWidth)
    } else {
        0f
    }
    return widthBudget + params.virtualBass * 0.25f + params.reverbWet * 0.25f
}

private fun Float.finiteOr(fallback: Float): Float = if (isFinite()) this else fallback

enum class SoundPresetCategory(val label: String) {
    ORIGINAL("原声"),
    SONANCE("自然"),
    HEADPHONE("耳机"),
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
 * 调音原则：
 * - **提升与衰减成对出现**（推一个、让一个）：抬低频适当收 250 Hz 防止轰头，
 *   抬存在感适当收低中频保持通透，减少无谓整段下压。
 * - **按发声硬件声学特征区分调校**：入耳耳塞削减 4~8 kHz 耳道共振峰并加柔和 crossfeed；
 *   头戴大耳展开自然横纵声场与真实低频下潜；手机/平板外放启用前级提取低音谐波 + 物理高通护喇叭。
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

    /** 极致音质（高解析参考调音）：疏通 250 Hz 浊度、两端丝滑延展与轻度横向开阔，零混响、零压限、零音染，一耳感受到通透细腻升级。 */
    val EXTREME_HIFI = SoundPreset(
        id = "extreme_hifi",
        name = "极致 · Hi-Fi",
        category = SoundPresetCategory.SONANCE,
        description = "高解析参考调音：轻量清理 250 Hz 微浊并打开极高频延伸与横向解析力，零混响、零压限，同时听出质感与透明度的清晰提升",
        character = "高解析 · 通透微扩",
        eqDb = floatArrayOf(1.6f, 1.2f, 0.2f, -1.6f, -0.4f, 0.5f, 1.3f, 2.0f, 2.2f, 2.6f),
        fx = SoundFxParams(stereoWidth = 1.10f)
    )

    /** 去糊 + 提细节 + 高频泛音展宽：低中频让路，4~16 kHz 空气感打开，乐器分离度与通透度一耳朵拉开差距。 */
    val CLEAR = SoundPreset(
        id = "clear",
        name = "清晨 · 通透",
        category = SoundPresetCategory.SONANCE,
        description = "收掉 250 Hz 的混浊，把乐器泛音与人声细节自然打开；声场更开阔透亮，开关前后清澈度对比鲜明",
        character = "透亮 · 高解析",
        eqDb = floatArrayOf(1.6f, 1.2f, -0.4f, -2.4f, -0.3f, 0.8f, 2.4f, 3.8f, 4.2f, 3.8f),
        fx = SoundFxParams(virtualBass = 0.16f, stereoWidth = 1.22f)
    )

    /** Pultec 式人声呼吸感：125 Hz 保住嗓音磁性，250 Hz 挖除伴奏掩蔽，1~4 kHz 顺滑托起咬字与气息，不靠互馈抹糊声像。 */
    val VOCAL = SoundPreset(
        id = "vocal",
        name = "人声 · 贴耳",
        category = SoundPresetCategory.SONANCE,
        description = "退开伴奏低中频掩蔽，保留嗓音磁性厚度并顺滑托起唇齿呼吸与细节；收束声场焦距让歌手稳稳贴近面前",
        character = "亲近 · 饱满毒人声",
        eqDb = floatArrayOf(-0.8f, 0.4f, 1.2f, -2.4f, 1.2f, 2.8f, 3.8f, 2.8f, 1.2f, 0.4f),
        fx = SoundFxParams(stereoWidth = 0.88f, limiter = true, limiterThresholdDb = -0.5f)
    )

    /** 62 Hz 核心锤感 + 250 Hz 去糊切口 + 74 Hz+ 饱和谐波下潜：鼓点拳拳到肉、收放干脆有弹性，绝不干瘪或发虚。 */
    val BASS = SoundPreset(
        id = "bass",
        name = "低频 · 有弹性",
        category = SoundPresetCategory.SONANCE,
        description = "强化 62 Hz 底鼓冲击力与澎湃谐波下潜，挖除 250 Hz 闷浊共振并补齐明亮度，下盘稳重有弹性、人声依旧通透",
        character = "澎湃 · 拳拳到肉",
        eqDb = floatArrayOf(3.8f, 4.6f, 2.4f, -2.2f, -0.2f, 0.8f, 1.8f, 2.4f, 2.2f, 1.6f),
        fx = SoundFxParams(
            virtualBass = 0.62f, stereoWidth = 1.14f, limiter = true, limiterThresholdDb = -0.5f
        )
    )

    /** 母带级暖倾斜（Warm Tilt）+ 轻柔双耳过渡：低频温厚包裹，高频柔和顺滑不刺耳，柔和却不显闷窄。 */
    val NIGHT = SoundPreset(
        id = "night",
        name = "深夜 · 轻柔",
        category = SoundPresetCategory.SONANCE,
        description = "模拟黑胶与胆机的暖调倾斜曲线，温润低频包围搭配极轻度双耳融合，夜晚聆听惬意饱满不刺耳",
        character = "温暖 · 胆机包围",
        eqDb = floatArrayOf(2.4f, 3.0f, 2.2f, 0.8f, 0.2f, -0.2f, -1.4f, -2.4f, -3.0f, -3.2f),
        fx = SoundFxParams(virtualBass = 0.28f, stereoWidth = 1.06f, crossfeed = 0.12f)
    )

    /** 入耳式耳机优化：饱满下潜 + 轻量抚平 8 kHz 耳道刺耳峰而不压暗高频，搭配柔和 BS2B 缓解头中效应。 */
    val IN_EAR = SoundPreset(
        id = "in_ear",
        name = "入耳 · 柔顺",
        category = SoundPresetCategory.HEADPHONE,
        description = "针对入耳耳塞声学调校：强化低频厚实下潜与极高频空气泛音，轻柔打磨 8 kHz 齿音毛刺并开启适度交叉馈送减轻头中压迫",
        character = "饱满顺滑 · 去刺不闷",
        eqDb = floatArrayOf(2.2f, 1.8f, 0.0f, -1.6f, 0.2f, 1.2f, 1.0f, -0.4f, -1.0f, 2.4f),
        fx = SoundFxParams(virtualBass = 0.24f, stereoWidth = 1.16f, crossfeed = 0.18f)
    )

    /** 头戴式耳机优化：发挥大尺寸单元宽松低潜 + 适度 BS2B 与早期空间反射，拉开脱塞感而不收窄宽声场。 */
    val OVER_EAR = SoundPreset(
        id = "over_ear",
        name = "头戴 · 厅堂",
        category = SoundPresetCategory.HEADPHONE,
        description = "针对头戴大耳调校：疏通 250 Hz 罩腔共振并深挖低频弹性，适度近场双耳融合配合轻早期反射与宽展声场，呈现开放音箱听感",
        character = "脱塞音箱感 · 宽舒饱满",
        eqDb = floatArrayOf(2.6f, 2.2f, 0.6f, -1.6f, 0.2f, 1.6f, 2.4f, 1.8f, 1.4f, 2.0f),
        fx = SoundFxParams(
            virtualBass = 0.28f, reverbWet = 0.10f, reverbDecay = 0.34f,
            stereoWidth = 1.24f, crossfeed = 0.25f
        )
    )

    /**
     * 手机外放档（原「外放 · 饱满」，沿用 id="dual_speaker" 保持兼容，沿用原版听感配方）：
     * 切除 31 Hz 振膜打底破音区，强力推起 250~500 Hz 胸腔共鸣厚度，压住 2~4 kHz 喇叭刺耳谐振峰，
     * 再用动态低频谐波把缺失的基频低音饱满地"脑补"回来。
     */
    val PHONE_SPEAKER = SoundPreset(
        id = "dual_speaker",
        name = "手机 · 外放",
        category = SoundPresetCategory.SPEAKER,
        description = "专为手机与平板扬声器调校：切除破音超低频、补足中低频胸腔厚度、柔化高频毛刺，并用谐波重建饱满低音",
        character = "外放 · 小音箱感",
        eqDb = floatArrayOf(-4.5f, -1.5f, 1.2f, 3.2f, 3.6f, 1.2f, -1.8f, -1.2f, 1.5f, 2.2f),
        fx = SoundFxParams(virtualBass = 0.65f, stereoWidth = 1.18f, limiter = true)
    )

    @Deprecated("使用 PHONE_SPEAKER", ReplaceWith("PHONE_SPEAKER"))
    val DUAL_SPEAKER: SoundPreset
        get() = PHONE_SPEAKER

    /** 平板外放模式：利用平板更大音腔与宽距扬声器，48 Hz 轻保护下释放深沉中低音，大幅展开横向立体声与人声鲜明度。 */
    val TABLET_SPEAKER = SoundPreset(
        id = "tablet_speaker",
        name = "平板 · 立体",
        category = SoundPresetCategory.SPEAKER,
        description = "专为平板多扬声器大音腔调校：48 Hz 温和保护下保留充沛低频冲击，拉开 134% 宽距立体声并托起人声与弦乐明亮度",
        character = "大音腔饱满 · 宽距立体环绕",
        eqDb = floatArrayOf(-2.0f, 2.0f, 2.8f, -1.0f, 0.8f, 1.2f, 2.0f, 2.2f, 2.8f, 3.0f),
        fx = SoundFxParams(
            virtualBass = 0.62f, reverbWet = 0.06f, reverbDecay = 0.26f,
            stereoWidth = 1.34f, limiter = true, limiterThresholdDb = -0.5f, speakerHpfHz = 48f
        )
    )

    /** 汽车音响优化：穿透行车 60~250 Hz 路噪胎噪掩蔽，切掉 40 Hz 以下门板驻波，聚焦前排质感并强化鼓点力度。 */
    val CAR_AUDIO = SoundPreset(
        id = "car_audio",
        name = "车载 · 动感",
        category = SoundPresetCategory.SPEAKER,
        description = "针对汽车驾驶舱声学优化：有力 62 Hz 底鼓穿透路噪掩蔽，清理 250 Hz 门板浑浊驻波并聚焦前排清晰人声",
        character = "动感抗路噪 · 前排声场",
        eqDb = floatArrayOf(1.8f, 4.2f, 2.0f, -2.5f, -0.3f, 1.4f, 2.6f, 1.8f, 1.4f, 1.8f),
        fx = SoundFxParams(
            virtualBass = 0.48f, stereoWidth = 1.18f, limiter = true,
            limiterThresholdDb = -0.5f, crossfeed = 0.10f, speakerHpfHz = 40f
        )
    )

    /** 早期横向反射 + 舞台微V均衡 + 明显展宽：像站在 Livehouse 前排，临场氛围与打击感跃然而出。 */
    val LIVE = SoundPreset(
        id = "live",
        name = "现场 · 微醺",
        category = SoundPresetCategory.SPACE,
        description = "还原 Livehouse 前排的横向声学反射与舞台宽度，底鼓弹性、吉他泛音与临场空气感鲜明拉开",
        character = "临场氛围 · 舞台活生感",
        eqDb = floatArrayOf(2.2f, 2.8f, 1.0f, -1.8f, 0.0f, 1.0f, 2.2f, 3.2f, 2.6f, 1.8f),
        fx = SoundFxParams(
            virtualBass = 0.28f, reverbWet = 0.20f, reverbDecay = 0.44f, stereoWidth = 1.28f,
            limiter = true, limiterThresholdDb = -0.5f
        )
    )

    /** 全景环绕：强力低音锁相展宽 + 立体交叉声学反射（不加跨声道抵消），打造一耳朵 360° 包围环绕的球形空间感。 */
    val PANORAMA = SoundPreset(
        id = "panorama",
        name = "全景 · 环绕",
        category = SoundPresetCategory.SPACE,
        description = "多维度全景声场拓展：低频稳固锚定中心，侧向高频极大舒展并结合立体早期反射环绕耳畔，呈现一耳朵震撼的 360° 包围感",
        character = "360° 立体 · 震撼环绕",
        eqDb = floatArrayOf(2.6f, 3.0f, 1.0f, -1.8f, -0.2f, 0.8f, 1.8f, 2.6f, 3.2f, 2.8f),
        fx = SoundFxParams(
            virtualBass = 0.34f, reverbWet = 0.22f, reverbDecay = 0.54f,
            stereoWidth = 1.56f, limiter = true, limiterThresholdDb = -0.5f
        )
    )

    /** 影院级极低频氛围 + 宏阔声场与厅堂余韵：动态澎湃、巨幕包围感十足。 */
    val CINEMA = SoundPreset(
        id = "cinema",
        name = "影院 · 宽广",
        category = SoundPresetCategory.SPACE,
        description = "声场向两侧深度延展，强化 31~62 Hz 巨幕下潜与饱满厅堂余韵，原声 OST 与大场面气势震撼十足",
        character = "恢弘澎湃 · 巨幕氛围",
        eqDb = floatArrayOf(3.8f, 4.4f, 2.2f, -1.8f, -0.4f, 0.8f, 1.8f, 2.8f, 2.8f, 2.0f),
        fx = SoundFxParams(
            virtualBass = 0.44f, reverbWet = 0.26f, reverbDecay = 0.60f, stereoWidth = 1.38f,
            limiter = true, limiterThresholdDb = -0.5f
        )
    )

    val ALL: List<SoundPreset> = listOf(
        OFF,
        EXTREME_HIFI, CLEAR, VOCAL, BASS, NIGHT,
        IN_EAR, OVER_EAR,
        PHONE_SPEAKER, TABLET_SPEAKER, CAR_AUDIO,
        LIVE, PANORAMA, CINEMA
    )

    fun fromId(id: String?): SoundPreset =
        ALL.firstOrNull { it.id == id } ?: OFF

    /**
     * 旧版本把内置参数和用户微调都存成一串数值。只迁移与历史配方完全相同的值，
     * 其他值视为用户调音并原样保留；下次选择预设才会主动恢复新的默认配方。
     */
    private val legacyFx: Map<String, List<SoundFxParams>> = mapOf(
        "off" to listOf(SoundFxParams.NEUTRAL),
        "extreme_hifi" to listOf(
            SoundFxParams(stereoWidth = 1.04f)
        ),
        "clear" to listOf(
            SoundFxParams(stereoWidth = 1.12f, reverbWet = 0.05f, preampDb = -3f, limiter = true),
            SoundFxParams(stereoWidth = 1.04f, preampDb = -1.5f, limiter = true),
            SoundFxParams(stereoWidth = 1.05f),
            SoundFxParams(virtualBass = 0.14f, stereoWidth = 1.20f),
            SoundFxParams(virtualBass = 0.12f, stereoWidth = 1.16f)
        ),
        "night" to listOf(
            SoundFxParams(stereoWidth = 0.95f, reverbWet = 0.04f, preampDb = -5f, limiter = true),
            SoundFxParams(preampDb = -1.5f, limiter = true),
            SoundFxParams.NEUTRAL,
            SoundFxParams(virtualBass = 0.24f, stereoWidth = 1.06f),
            SoundFxParams(virtualBass = 0.22f, stereoWidth = 1.04f, crossfeed = 0.22f)
        ),
        "vocal" to listOf(
            SoundFxParams(reverbWet = 0.07f, preampDb = -3f, limiter = true),
            SoundFxParams(preampDb = -1.8f, limiter = true),
            SoundFxParams(limiter = true),
            SoundFxParams(stereoWidth = 0.90f, limiter = true),
            SoundFxParams(stereoWidth = 0.92f, limiter = true, crossfeed = 0.18f)
        ),
        "bass" to listOf(
            SoundFxParams(virtualBass = 0.55f, preampDb = -4f, limiter = true),
            SoundFxParams(preampDb = -2.5f, limiter = true),
            SoundFxParams(limiter = true),
            SoundFxParams(virtualBass = 0.48f, stereoWidth = 1.12f, limiter = true),
            SoundFxParams(virtualBass = 0.52f, stereoWidth = 1.10f, limiter = true)
        ),
        "in_ear" to listOf(
            SoundFxParams(virtualBass = 0.16f, stereoWidth = 1.08f, crossfeed = 0.35f)
        ),
        "over_ear" to listOf(
            SoundFxParams(
                virtualBass = 0.18f, reverbWet = 0.08f, reverbDecay = 0.28f,
                stereoWidth = 1.14f, crossfeed = 0.48f
            )
        ),
        "live" to listOf(
            SoundFxParams(reverbWet = 0.22f, reverbDecay = 0.35f, stereoWidth = 1.25f, preampDb = -4f, limiter = true),
            SoundFxParams(reverbWet = 0.10f, reverbDecay = 0.35f, stereoWidth = 1.10f, preampDb = -1.5f, limiter = true),
            SoundFxParams(reverbWet = 0.10f, reverbDecay = 0.38f, stereoWidth = 1.10f, limiter = true),
            SoundFxParams(virtualBass = 0.22f, reverbWet = 0.18f, reverbDecay = 0.42f, stereoWidth = 1.24f, limiter = true),
            SoundFxParams(virtualBass = 0.20f, reverbWet = 0.16f, reverbDecay = 0.40f, stereoWidth = 1.22f, limiter = true)
        ),
        "panorama" to listOf(
            SoundFxParams(
                virtualBass = 0.26f, reverbWet = 0.20f, reverbDecay = 0.52f,
                stereoWidth = 1.48f, limiter = true, crossfeed = 0.15f
            )
        ),
        "cinema" to listOf(
            SoundFxParams(reverbWet = 0.30f, reverbDecay = 0.80f, stereoWidth = 1.35f, preampDb = -5f, limiter = true),
            SoundFxParams(reverbWet = 0.16f, reverbDecay = 0.55f, stereoWidth = 1.16f, preampDb = -1.8f, limiter = true),
            SoundFxParams(reverbWet = 0.15f, reverbDecay = 0.60f, stereoWidth = 1.16f, limiter = true),
            SoundFxParams(virtualBass = 0.36f, reverbWet = 0.26f, reverbDecay = 0.60f, stereoWidth = 1.32f, limiter = true),
            SoundFxParams(virtualBass = 0.32f, reverbWet = 0.22f, reverbDecay = 0.56f, stereoWidth = 1.30f, limiter = true)
        ),
        "dual_speaker" to listOf(
            SoundFxParams(virtualBass = 0.7f, reverbWet = 0.06f, reverbDecay = 0.2f, stereoWidth = 1.18f, preampDb = -4f, limiter = true),
            SoundFxParams(virtualBass = 0.35f, preampDb = -1.8f, limiter = true),
            SoundFxParams(virtualBass = 0.55f, limiter = true),
            SoundFxParams(
                virtualBass = 0.68f, stereoWidth = 1.16f, limiter = true,
                limiterThresholdDb = -1.2f, speakerHpfHz = 115f
            ),
            SoundFxParams(
                virtualBass = 0.70f, stereoWidth = 1.24f, limiter = true,
                limiterThresholdDb = -0.5f, speakerHpfHz = 65f
            )
        ),
        "tablet_speaker" to listOf(
            SoundFxParams(
                virtualBass = 0.56f, reverbWet = 0.06f, reverbDecay = 0.25f,
                stereoWidth = 1.28f, limiter = true, limiterThresholdDb = -1.0f, speakerHpfHz = 75f
            )
        ),
        "car_audio" to listOf(
            SoundFxParams(
                virtualBass = 0.38f, stereoWidth = 1.12f, limiter = true,
                limiterThresholdDb = -1.0f, crossfeed = 0.20f, speakerHpfHz = 42f
            )
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
