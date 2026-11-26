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
    val speakerHpfHz: Float = 0f,
    /** Poweramp 式宽低音搁架增益（90 Hz Low-Shelf，-9..+9 dB） */
    val bassBoostDb: Float = 0f,
    /** Poweramp 式空气高音搁架增益（9.5 kHz High-Shelf，-9..+9 dB） */
    val trebleBoostDb: Float = 0f,
    /** 超重低音引擎强度 0..1（蝰蛇级低频谐振 + 瞬态冲击 + 可选次谐波八度下潜 + 215 Hz 去浊滤波） */
    val viperBass: Float = 0f,
    /** 超重低音谐振中心频率 35..95 Hz */
    val viperBassFreqHz: Float = 52f,
    /** 超重低音模式：0 = 自然重低音，1 = 蝰蛇炮震，2 = 超潜次低音 */
    val viperBassMode: Int = 0,
    /** 高频明晰度与微细节激励 0..1（带通空气谐波微激励 + 瞬态边缘强化 + 7.8 kHz 齿音抑制） */
    val clarity: Float = 0f
) {
    /** 没有实际效果时可以整链旁路，省 CPU */
    val isNeutral: Boolean
        get() = virtualBass <= 0f && reverbWet <= 0f &&
            stereoWidth == 1f && preampDb == 0f && !limiter &&
            crossfeed <= 0f && speakerHpfHz <= 0f &&
            bassBoostDb == 0f && trebleBoostDb == 0f &&
            viperBass <= 0f && clarity <= 0f

    /** 所有来自 UI / 持久化的数值都过这一关，脏参数不允许进音频线程 */
    fun clamp(): SoundFxParams = copy(
        virtualBass = virtualBass.finiteOr(0f).coerceIn(0f, 1f),
        reverbWet = reverbWet.finiteOr(0f).coerceIn(0f, 0.6f),
        reverbDecay = reverbDecay.finiteOr(0.5f).coerceIn(0f, 1f),
        stereoWidth = stereoWidth.finiteOr(1f).coerceIn(0f, 2f),
        preampDb = preampDb.finiteOr(0f).coerceIn(-12f, 0f),
        limiterThresholdDb = limiterThresholdDb.finiteOr(-1f).coerceIn(-12f, 0f),
        crossfeed = crossfeed.finiteOr(0f).coerceIn(0f, 1f),
        speakerHpfHz = speakerHpfHz.finiteOr(0f).let { if (it < 20f) 0f else it.coerceIn(30f, 160f) },
        bassBoostDb = bassBoostDb.finiteOr(0f).coerceIn(-TONE_MAX_DB, TONE_MAX_DB),
        trebleBoostDb = trebleBoostDb.finiteOr(0f).coerceIn(-TONE_MAX_DB, TONE_MAX_DB),
        viperBass = viperBass.finiteOr(0f).coerceIn(0f, 1f),
        viperBassFreqHz = viperBassFreqHz.finiteOr(52f).coerceIn(35f, 95f),
        viperBassMode = viperBassMode.coerceIn(0, 2),
        clarity = clarity.finiteOr(0f).coerceIn(0f, 1f)
    )

    companion object {
        val NEUTRAL = SoundFxParams()

        /** 持久化用「逗号分隔」，兼容旧版 6~14 段与新版 15 段格式 */
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
                speakerHpfHz = p.getOrNull(8)?.toFloatOrNull() ?: 0f,
                bassBoostDb = p.getOrNull(9)?.toFloatOrNull() ?: 0f,
                trebleBoostDb = p.getOrNull(10)?.toFloatOrNull() ?: 0f,
                viperBass = p.getOrNull(11)?.toFloatOrNull() ?: 0f,
                viperBassFreqHz = p.getOrNull(12)?.toFloatOrNull() ?: 52f,
                viperBassMode = p.getOrNull(13)?.toIntOrNull() ?: 0,
                clarity = p.getOrNull(14)?.toFloatOrNull() ?: 0f
            ).clamp()
        }

        fun toStore(p: SoundFxParams): String = with(p.clamp()) {
            listOf(
                virtualBass, reverbWet, reverbDecay, stereoWidth, preampDb,
                if (limiter) "1" else "0", limiterThresholdDb, crossfeed, speakerHpfHz,
                bassBoostDb, trebleBoostDb, viperBass, viperBassFreqHz, viperBassMode, clarity
            ).joinToString(",")
        }
    }
}

/**
 * 为后级效果计算动态峰值余量（dB），由 [SoundChainDsp]、[presetNetCurve] 与
 * [presetToneBands] 共用，保证运行时实际听感与预览图保持同一响度基准。
 */
internal fun effectPreampBudgetDb(params: SoundFxParams, channels: Int = 2): Float {
    if (channels > 2) return 0f
    val widthBudget = if (channels == 2 && params.stereoWidth > 1f) {
        3.0f * log10(params.stereoWidth)
    } else {
        0f
    }
    return widthBudget + params.virtualBass * 0.25f + params.reverbWet * 0.25f +
        params.viperBass * 0.45f + params.clarity * 0.20f
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
 * 预置音效三档整体强度：按声学中性点分项缩放 EQ、高低音搁架与效果强度，
 * 锁定物理高通与安全保护阈值不缩放，确保低档不干瘪、高档爽感拉满且绝不破音。
 */
enum class PresetIntensity(
    val id: String,
    val label: String,
    val shortDesc: String,
    val eqScale: Float,
    val fxScale: Float
) {
    LOW("low", "低", "柔和耐听", 0.65f, 0.65f),
    MEDIUM("medium", "中", "标准推荐", 1.00f, 1.00f),
    HIGH("high", "高", "强力澎湃", 1.35f, 1.32f);

    companion object {
        fun fromId(id: String?): PresetIntensity =
            entries.firstOrNull { it.id == id } ?: MEDIUM
    }
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
    fun scaled(intensity: PresetIntensity): Pair<FloatArray, SoundFxParams> {
        if (id == SoundPresets.OFF.id || intensity == PresetIntensity.MEDIUM) {
            return eqDb.copyOf() to fx.clamp()
        }
        val scaledEq = FloatArray(eqDb.size) { i ->
            (eqDb[i] * intensity.eqScale).coerceIn(EQ_MIN_DB, EQ_MAX_DB)
        }
        val scaledFx = fx.copy(
            virtualBass = fx.virtualBass * intensity.fxScale,
            reverbWet = fx.reverbWet * intensity.fxScale,
            crossfeed = fx.crossfeed * intensity.fxScale,
            stereoWidth = 1f + (fx.stereoWidth - 1f) * intensity.fxScale,
            bassBoostDb = fx.bassBoostDb * intensity.eqScale,
            trebleBoostDb = fx.trebleBoostDb * intensity.eqScale,
            viperBass = fx.viperBass * intensity.fxScale,
            clarity = fx.clarity * intensity.fxScale
        ).clamp()
        return scaledEq to scaledFx
    }

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

    /** 去糊 + 提细节 + 双搁架空气延展：Low-Shelf 轻托下盘，250 Hz 退让去浊，9.5 kHz 空气搁架点亮光泽，瞬间像揭掉一层纱。 */
    val CLEAR = SoundPreset(
        id = "clear",
        name = "清晨 · 通透",
        category = SoundPresetCategory.SONANCE,
        description = "精准收净 250 Hz 浑浊并开启 9.5 kHz 空气搁架与宽展声场，乐器泛音与细节如揭开薄纱般清晰透亮",
        character = "水晶透亮 · 高解析",
        eqDb = floatArrayOf(0.6f, 0.8f, -0.6f, -3.2f, -0.8f, 0.8f, 2.2f, 2.8f, 2.0f, 1.6f),
        fx = SoundFxParams(virtualBass = 0.22f, stereoWidth = 1.30f, bassBoostDb = 1.4f, trebleBoostDb = 3.0f)
    )

    /** Pultec 式贴面毒人声：保住 125 Hz 嗓音磁性、退开 250 Hz 伴奏掩蔽，托起 2 kHz 咬字气息与自然宽阔氛围，不再发挤发虚。 */
    val VOCAL = SoundPreset(
        id = "vocal",
        name = "人声 · 贴耳",
        category = SoundPresetCategory.SONANCE,
        description = "退开伴奏低中频掩蔽，保留嗓音磁性厚度并饱满托起唇齿呼吸与细节；搭配丝滑空气延伸与自然声场更富情感",
        character = "磁性毒嗓 · 贴面清晰",
        eqDb = floatArrayOf(-1.6f, 0.2f, 1.6f, -3.2f, 1.2f, 3.2f, 4.4f, 2.8f, 1.0f, 0.6f),
        fx = SoundFxParams(stereoWidth = 1.10f, bassBoostDb = 0.5f, trebleBoostDb = 1.8f)
    )

    /** Poweramp 式招牌弹跳低音：Low-Shelf 低频气垫 + 62 Hz 核心锤感 + 强化版谐波下潜，切净 250 Hz 防轰头并补足高频清脆。 */
    val BASS = SoundPreset(
        id = "bass",
        name = "低频 · 有弹性",
        category = SoundPresetCategory.SONANCE,
        description = "强力 90 Hz 低音宽搁架叠加强化版谐波合成，打造气垫般澎湃鼓点弹性；切净 250 Hz 轰鸣并点亮高频清脆度",
        character = "气垫澎湃 · 拳拳到肉",
        eqDb = floatArrayOf(0.8f, 2.4f, 0.8f, -3.6f, -0.6f, 1.0f, 1.8f, 2.2f, 1.2f, 0.8f),
        fx = SoundFxParams(virtualBass = 0.74f, stereoWidth = 1.18f, bassBoostDb = 3.2f, trebleBoostDb = 1.8f)
    )

    /** 生理等响度（Loudness）夜间暖调：温润 Low-Shelf 包围低频、柔和抚平 2~8 kHz 刺耳倦怠区并保留微弱泛音，小音量久听也丰满。 */
    val NIGHT = SoundPreset(
        id = "night",
        name = "深夜 · 轻柔",
        category = SoundPresetCategory.SONANCE,
        description = "小音量生理等响度曲线：温暖厚实的低频包裹配合人耳敏感刺耳区柔化，夜间聆听饱满宽松且久听不累",
        character = "暖厚耐听 · 胆机包围",
        eqDb = floatArrayOf(1.0f, 1.8f, 1.4f, 0.4f, 0.2f, -0.2f, -1.8f, -2.8f, -1.8f, -0.6f),
        fx = SoundFxParams(
            virtualBass = 0.36f, stereoWidth = 1.12f, crossfeed = 0.14f,
            bassBoostDb = 2.4f, trebleBoostDb = -1.2f
        )
    )

    /** 哈曼 IE 取向入耳耳塞调音：低频气垫扎实有弹性、打磨 8 kHz 耳道共振刺峰并推开 12k+ 空气泛音，轻度 BS2B 保住开阔声场。 */
    val IN_EAR = SoundPreset(
        id = "in_ear",
        name = "入耳 · 柔顺",
        category = SoundPresetCategory.HEADPHONE,
        description = "参考主流 Hi-Fi 入耳目标曲线调校：饱满低音气垫搭配 8 kHz 齿音去刺与极高频延伸，一耳朵顺滑大气",
        character = "顺滑去刺 · 澎湃耐听",
        eqDb = floatArrayOf(1.2f, 1.4f, 0.0f, -2.6f, 0.2f, 1.6f, 2.2f, 0.4f, -2.2f, 1.6f),
        fx = SoundFxParams(
            virtualBass = 0.36f, stereoWidth = 1.22f, crossfeed = 0.12f,
            bassBoostDb = 2.4f, trebleBoostDb = 2.2f
        )
    )

    /**
     * 旗舰入耳监听调音（听感 + 微细节双向拉满）：
     * - 250 Hz 深度去浊（-3.0 dB）释放封闭耳道内的乐器分离度与空气层次；
     * - 2 kHz / 4 kHz 质感与泛音细节拉升 + 16 kHz 丝滑空气延伸，同时精准压低 8 kHz 耳道刺峰（-1.4 dB）做到高解析零齿音；
     * - 搭配自然模式超重低音引擎（48 Hz 紧致弹跳 + 215 Hz 二次去浊）、柔和 BS2B 脱塞串扰与 128% 宽阔声场，零混响不糊瞬态。
     */
    val IEM_MASTER = SoundPreset(
        id = "iem_master",
        name = "入耳 · 臻境",
        category = SoundPresetCategory.HEADPHONE,
        description = "专为入耳式耳机打造的旗舰高解析调音：深度清理 250 Hz 封闭腔浊度并定向削弱 8 kHz 刺耳齿音峰，联动高频明晰度与微细节激励引擎放大 2~4 kHz 结像质感与空气层次，配合 46 Hz 弹性低频与脱塞声场",
        character = "超解析细节 · 润泽脱塞",
        eqDb = floatArrayOf(1.2f, 1.4f, -0.4f, -3.2f, 0.4f, 1.2f, 2.0f, 2.2f, -1.8f, 2.0f),
        fx = SoundFxParams(
            virtualBass = 0.18f,
            stereoWidth = 1.32f,
            crossfeed = 0.14f,
            bassBoostDb = 1.4f,
            trebleBoostDb = 1.8f,
            viperBass = 0.42f,
            viperBassFreqHz = 46f,
            viperBassMode = 0,
            clarity = 0.62f
        )
    )

    /** 头戴旗舰大耳调音：宽松低频下潜 + 250 Hz 罩腔去浊 + 宽舒 132% 声场与轻近场反射，营造音箱般的脱塞听感。 */
    val OVER_EAR = SoundPreset(
        id = "over_ear",
        name = "头戴 · 厅堂",
        category = SoundPresetCategory.HEADPHONE,
        description = "针对头戴大耳调校：疏通罩腔微浊共振、深挖低频弹性并融合早期空间反射与宽舒声场，消除头中效应",
        character = "宽舒脱塞 · 大耳氛围",
        eqDb = floatArrayOf(1.2f, 1.6f, 0.4f, -2.6f, 0.4f, 1.6f, 2.6f, 2.0f, 1.2f, 1.4f),
        fx = SoundFxParams(
            virtualBass = 0.36f, reverbWet = 0.14f, reverbDecay = 0.38f,
            stereoWidth = 1.32f, crossfeed = 0.20f, bassBoostDb = 2.2f, trebleBoostDb = 2.0f
        )
    )

    /**
     * 手机外放档（沿用 id="dual_speaker" 保持兼容）：
     * 58 Hz 物理高通切除小喇叭破音振膜打底区间，高通前提取强力 2f/3f 谐波把低音“脑补”得饱满有弹性，
     * 推起胸腔厚度与高音清晰度，外放脱胎换骨且开大声不破响。
     */
    val PHONE_SPEAKER = SoundPreset(
        id = "dual_speaker",
        name = "手机 · 外放",
        category = SoundPresetCategory.SPEAKER,
        description = "58 Hz 高通精准防破音并在高通前强化谐波低音合成，补足胸腔厚度与高频明亮度，外放饱满有力",
        character = "澎湃抗破音 · 小音箱感",
        eqDb = floatArrayOf(-5.0f, -1.2f, 1.8f, 3.4f, 4.0f, 1.6f, -2.0f, -1.0f, 1.8f, 2.2f),
        fx = SoundFxParams(
            virtualBass = 0.78f, stereoWidth = 1.28f, limiter = true,
            limiterThresholdDb = -0.4f, speakerHpfHz = 58f, trebleBoostDb = 2.2f
        )
    )

    @Deprecated("使用 PHONE_SPEAKER", ReplaceWith("PHONE_SPEAKER"))
    val DUAL_SPEAKER: SoundPreset
        get() = PHONE_SPEAKER

    /** 平板外放模式：利用多扬声器大音腔，46 Hz 护喇叭下释放充沛低音气垫，142% 宽立体声让桌面声场与清晰人声一同立起。 */
    val TABLET_SPEAKER = SoundPreset(
        id = "tablet_speaker",
        name = "平板 · 立体",
        category = SoundPresetCategory.SPEAKER,
        description = "专为平板多扬声器大音腔调校：低音气垫叠加 142% 宽距环绕声场与明亮人声，桌面外放立体感翻倍",
        character = "大音腔饱满 · 宽距立体声",
        eqDb = floatArrayOf(-2.0f, 1.4f, 1.8f, -1.8f, 0.6f, 1.6f, 2.6f, 2.2f, 2.0f, 1.6f),
        fx = SoundFxParams(
            virtualBass = 0.68f, reverbWet = 0.08f, reverbDecay = 0.28f,
            stereoWidth = 1.42f, speakerHpfHz = 46f,
            bassBoostDb = 1.0f, trebleBoostDb = 1.8f
        )
    )

    /** 车载动感 V 型调音：强力低音气垫与 62 Hz 底鼓穿透行车胎噪路噪，挖净车门 250 Hz 浑浊共振，前排人声鲜明有劲。 */
    val CAR_AUDIO = SoundPreset(
        id = "car_audio",
        name = "车载 · 动感",
        category = SoundPresetCategory.SPEAKER,
        description = "穿透行车胎噪与路噪掩蔽：强力底鼓锤感搭配门板 250 Hz 共振清理与清晰高音，驾驶舱动态冲击力十足",
        character = "抗路噪冲击 · 劲爽前排",
        eqDb = floatArrayOf(1.2f, 3.0f, 1.4f, -3.2f, -0.4f, 1.6f, 2.8f, 2.0f, 1.4f, 1.4f),
        fx = SoundFxParams(
            virtualBass = 0.58f, stereoWidth = 1.24f, limiter = true,
            limiterThresholdDb = -0.4f, crossfeed = 0.08f, speakerHpfHz = 38f,
            bassBoostDb = 2.4f, trebleBoostDb = 2.0f
        )
    )

    /** 现场 Livehouse 调音：释去多余前置压限保留完整打击乐瞬态，搭配 138% 舞台宽度与鲜明早期声学反射。 */
    val LIVE = SoundPreset(
        id = "live",
        name = "现场 · 微醺",
        category = SoundPresetCategory.SPACE,
        description = "释放鼓点瞬态动态并还原 Livehouse 舞台宽度与早期横向反射，底鼓、吉他泛音与现场临场感鲜明跃出",
        character = "临场冲击 · 舞台活生感",
        eqDb = floatArrayOf(1.2f, 2.2f, 0.8f, -2.6f, 0.2f, 1.2f, 2.4f, 3.0f, 2.0f, 1.4f),
        fx = SoundFxParams(
            virtualBass = 0.38f, reverbWet = 0.24f, reverbDecay = 0.46f,
            stereoWidth = 1.38f, bassBoostDb = 1.8f, trebleBoostDb = 1.8f
        )
    )

    /** 全景 360° 环绕：低频稳固锚定中心，高频侧翼大跨度舒展（168%）叠加立体空间反射，开关反差极为强烈。 */
    val PANORAMA = SoundPreset(
        id = "panorama",
        name = "全景 · 环绕",
        category = SoundPresetCategory.SPACE,
        description = "多维度全景声场拓展：低频稳固锚定中心，侧翼大跨度延展叠加立体环绕反射，呈现一耳朵 360° 沉浸包围感",
        character = "360° 宽阔 · 震撼环绕",
        eqDb = floatArrayOf(1.4f, 2.2f, 0.6f, -2.6f, -0.2f, 1.0f, 2.0f, 2.6f, 2.4f, 1.8f),
        fx = SoundFxParams(
            virtualBass = 0.44f, reverbWet = 0.28f, reverbDecay = 0.56f,
            stereoWidth = 1.68f, bassBoostDb = 2.0f, trebleBoostDb = 2.2f
        )
    )

    /** 巨幕影院：深沉 Low-Shelf + 强力谐波营造 IMAX 低音炮氛围，饱满厅堂尾韵与宏阔动态声场气势恢弘。 */
    val CINEMA = SoundPreset(
        id = "cinema",
        name = "影院 · 宽广",
        category = SoundPresetCategory.SPACE,
        description = "IMAX 巨幕低频氛围与宽阔厅堂余韵：无前置压限保留宏大动态起伏，影视原声与大编制交响震撼感拉满",
        character = "巨幕震撼 · 宏阔厅堂",
        eqDb = floatArrayOf(2.0f, 2.6f, 1.2f, -2.6f, -0.4f, 1.0f, 2.0f, 2.6f, 2.0f, 1.4f),
        fx = SoundFxParams(
            virtualBass = 0.54f, reverbWet = 0.34f, reverbDecay = 0.64f,
            stereoWidth = 1.48f, bassBoostDb = 2.8f, trebleBoostDb = 1.8f
        )
    )

    /** 蝰蛇超重低音：独立次低音共振与瞬态锤感引擎 + 215 Hz 去浊净声，低音震撼度超越经典蝰蛇且人声通透不浑浊。 */
    val VIPER_BASS = SoundPreset(
        id = "viper_bass",
        name = "蝰蛇 · 超重低音",
        category = SoundPresetCategory.SONANCE,
        description = "超重低音炮震模式（50 Hz 高能四阶隔离共振 + 鼓点瞬态强化 + 220 Hz 浑浊抑制 + 明晰度解析），下潜拳拳到肉且人声通透贴耳",
        character = "超潜炮震 · 人声零浑浊",
        eqDb = floatArrayOf(1.6f, 2.2f, 0.4f, -3.6f, 0.0f, 1.0f, 1.6f, 2.0f, 1.0f, 1.2f),
        fx = SoundFxParams(
            virtualBass = 0.30f, stereoWidth = 1.22f, bassBoostDb = 2.0f, trebleBoostDb = 1.4f,
            viperBass = 0.82f, viperBassFreqHz = 50f, viperBassMode = 1, clarity = 0.42f
        )
    )

    /** 超潜次低音炮：追踪底鼓/贝斯零交点实时合成真二分频（f/2）次谐波极低频，小耳机也能涌出音箱般物理震感。 */
    val SUB_CANNON = SoundPreset(
        id = "sub_cannon",
        name = "超潜 · 次低音炮",
        category = SoundPresetCategory.SONANCE,
        description = "实时二分频次谐波合成（42 Hz 极低频下潜 + 瞬态动感压限平衡），将鼓点基频延展至超低八度，呈现震撼低音炮体感",
        character = "真次谐波 · 体感超潜",
        eqDb = floatArrayOf(1.8f, 2.0f, 0.2f, -3.4f, 0.0f, 1.0f, 1.4f, 1.8f, 1.0f, 1.0f),
        fx = SoundFxParams(
            virtualBass = 0.32f, stereoWidth = 1.18f, bassBoostDb = 2.0f, trebleBoostDb = 1.2f,
            viperBass = 0.88f, viperBassFreqHz = 42f, viperBassMode = 2, clarity = 0.38f
        )
    )

    /** 清澈动感重低音：电子管温润饱和叠加瞬态弹性提升，耐听且富有张力。 */
    val PUNCH_CLEAR_BASS = SoundPreset(
        id = "punch_clear_bass",
        name = "清澈 · 动感低音",
        category = SoundPresetCategory.SONANCE,
        description = "自然重低音模式（56 Hz 电子管温润染色 + 瞬态鼓点收紧 + 高频微细节激励），兼顾强劲弹跳低频与通透解析",
        character = "紧实弹性 · 耐听有劲",
        eqDb = floatArrayOf(1.2f, 1.8f, 0.4f, -3.4f, 0.2f, 1.2f, 1.8f, 2.0f, 1.0f, 1.4f),
        fx = SoundFxParams(
            virtualBass = 0.22f, stereoWidth = 1.24f, bassBoostDb = 1.6f, trebleBoostDb = 1.6f,
            viperBass = 0.68f, viperBassFreqHz = 56f, viperBassMode = 0, clarity = 0.48f
        )
    )

    val ALL: List<SoundPreset> = listOf(
        OFF,
        EXTREME_HIFI, CLEAR, VOCAL, BASS, VIPER_BASS, SUB_CANNON, PUNCH_CLEAR_BASS, NIGHT,
        IN_EAR, IEM_MASTER, OVER_EAR,
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
            SoundFxParams(virtualBass = 0.12f, stereoWidth = 1.16f),
            SoundFxParams(virtualBass = 0.16f, stereoWidth = 1.22f)
        ),
        "night" to listOf(
            SoundFxParams(stereoWidth = 0.95f, reverbWet = 0.04f, preampDb = -5f, limiter = true),
            SoundFxParams(preampDb = -1.5f, limiter = true),
            SoundFxParams.NEUTRAL,
            SoundFxParams(virtualBass = 0.24f, stereoWidth = 1.06f),
            SoundFxParams(virtualBass = 0.22f, stereoWidth = 1.04f, crossfeed = 0.22f),
            SoundFxParams(virtualBass = 0.28f, stereoWidth = 1.06f, crossfeed = 0.12f)
        ),
        "vocal" to listOf(
            SoundFxParams(reverbWet = 0.07f, preampDb = -3f, limiter = true),
            SoundFxParams(preampDb = -1.8f, limiter = true),
            SoundFxParams(limiter = true),
            SoundFxParams(stereoWidth = 0.90f, limiter = true),
            SoundFxParams(stereoWidth = 0.92f, limiter = true, crossfeed = 0.18f),
            SoundFxParams(stereoWidth = 0.88f, limiter = true, limiterThresholdDb = -0.5f)
        ),
        "bass" to listOf(
            SoundFxParams(virtualBass = 0.55f, preampDb = -4f, limiter = true),
            SoundFxParams(preampDb = -2.5f, limiter = true),
            SoundFxParams(limiter = true),
            SoundFxParams(virtualBass = 0.48f, stereoWidth = 1.12f, limiter = true),
            SoundFxParams(virtualBass = 0.52f, stereoWidth = 1.10f, limiter = true),
            SoundFxParams(virtualBass = 0.62f, stereoWidth = 1.14f, limiter = true, limiterThresholdDb = -0.5f)
        ),
        "in_ear" to listOf(
            SoundFxParams(virtualBass = 0.16f, stereoWidth = 1.08f, crossfeed = 0.35f),
            SoundFxParams(virtualBass = 0.24f, stereoWidth = 1.16f, crossfeed = 0.18f)
        ),
        "over_ear" to listOf(
            SoundFxParams(
                virtualBass = 0.18f, reverbWet = 0.08f, reverbDecay = 0.28f,
                stereoWidth = 1.14f, crossfeed = 0.48f
            ),
            SoundFxParams(
                virtualBass = 0.28f, reverbWet = 0.10f, reverbDecay = 0.34f,
                stereoWidth = 1.24f, crossfeed = 0.25f
            )
        ),
        "live" to listOf(
            SoundFxParams(reverbWet = 0.22f, reverbDecay = 0.35f, stereoWidth = 1.25f, preampDb = -4f, limiter = true),
            SoundFxParams(reverbWet = 0.10f, reverbDecay = 0.35f, stereoWidth = 1.10f, preampDb = -1.5f, limiter = true),
            SoundFxParams(reverbWet = 0.10f, reverbDecay = 0.38f, stereoWidth = 1.10f, limiter = true),
            SoundFxParams(virtualBass = 0.22f, reverbWet = 0.18f, reverbDecay = 0.42f, stereoWidth = 1.24f, limiter = true),
            SoundFxParams(virtualBass = 0.20f, reverbWet = 0.16f, reverbDecay = 0.40f, stereoWidth = 1.22f, limiter = true),
            SoundFxParams(
                virtualBass = 0.28f, reverbWet = 0.20f, reverbDecay = 0.44f, stereoWidth = 1.28f,
                limiter = true, limiterThresholdDb = -0.5f
            )
        ),
        "panorama" to listOf(
            SoundFxParams(
                virtualBass = 0.26f, reverbWet = 0.20f, reverbDecay = 0.52f,
                stereoWidth = 1.48f, limiter = true, crossfeed = 0.15f
            ),
            SoundFxParams(
                virtualBass = 0.34f, reverbWet = 0.22f, reverbDecay = 0.54f,
                stereoWidth = 1.56f, limiter = true, limiterThresholdDb = -0.5f
            )
        ),
        "cinema" to listOf(
            SoundFxParams(reverbWet = 0.30f, reverbDecay = 0.80f, stereoWidth = 1.35f, preampDb = -5f, limiter = true),
            SoundFxParams(reverbWet = 0.16f, reverbDecay = 0.55f, stereoWidth = 1.16f, preampDb = -1.8f, limiter = true),
            SoundFxParams(reverbWet = 0.15f, reverbDecay = 0.60f, stereoWidth = 1.16f, limiter = true),
            SoundFxParams(virtualBass = 0.36f, reverbWet = 0.26f, reverbDecay = 0.60f, stereoWidth = 1.32f, limiter = true),
            SoundFxParams(virtualBass = 0.32f, reverbWet = 0.22f, reverbDecay = 0.56f, stereoWidth = 1.30f, limiter = true),
            SoundFxParams(
                virtualBass = 0.44f, reverbWet = 0.26f, reverbDecay = 0.60f, stereoWidth = 1.38f,
                limiter = true, limiterThresholdDb = -0.5f
            )
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
            ),
            SoundFxParams(virtualBass = 0.65f, stereoWidth = 1.18f, limiter = true)
        ),
        "tablet_speaker" to listOf(
            SoundFxParams(
                virtualBass = 0.56f, reverbWet = 0.06f, reverbDecay = 0.25f,
                stereoWidth = 1.28f, limiter = true, limiterThresholdDb = -1.0f, speakerHpfHz = 75f
            ),
            SoundFxParams(
                virtualBass = 0.62f, reverbWet = 0.06f, reverbDecay = 0.26f,
                stereoWidth = 1.34f, limiter = true, limiterThresholdDb = -0.5f, speakerHpfHz = 48f
            )
        ),
        "car_audio" to listOf(
            SoundFxParams(
                virtualBass = 0.38f, stereoWidth = 1.12f, limiter = true,
                limiterThresholdDb = -1.0f, crossfeed = 0.20f, speakerHpfHz = 42f
            ),
            SoundFxParams(
                virtualBass = 0.48f, stereoWidth = 1.18f, limiter = true,
                limiterThresholdDb = -0.5f, crossfeed = 0.10f, speakerHpfHz = 40f
            )
        ),
        "viper_bass" to listOf(
            SoundFxParams(
                virtualBass = 0.24f, stereoWidth = 1.16f, bassBoostDb = 2.2f, trebleBoostDb = 1.6f,
                viperBass = 0.72f, viperBassFreqHz = 52f, viperBassMode = 1
            )
        ),
        "sub_cannon" to listOf(
            SoundFxParams(
                virtualBass = 0.28f, stereoWidth = 1.14f, bassBoostDb = 2.2f, trebleBoostDb = 1.2f,
                viperBass = 0.82f, viperBassFreqHz = 44f, viperBassMode = 2
            )
        ),
        "punch_clear_bass" to listOf(
            SoundFxParams(
                virtualBass = 0.18f, stereoWidth = 1.20f, bassBoostDb = 1.8f, trebleBoostDb = 1.8f,
                viperBass = 0.56f, viperBassFreqHz = 58f, viperBassMode = 0
            )
        ),
        "iem_master" to listOf(
            SoundFxParams(
                virtualBass = 0.14f, stereoWidth = 1.28f, crossfeed = 0.16f,
                bassBoostDb = 1.6f, trebleBoostDb = 2.6f,
                viperBass = 0.28f, viperBassFreqHz = 48f, viperBassMode = 0
            )
        )
    )

    data class RestoredFx(val params: SoundFxParams, val custom: Boolean)

    fun restoreFx(
        preset: SoundPreset,
        raw: String?,
        intensity: PresetIntensity = PresetIntensity.MEDIUM
    ): RestoredFx {
        val stored = SoundFxParams.fromStore(raw)
        val expected = preset.scaled(intensity).second
        val unchanged = raw.isNullOrBlank() ||
            PresetIntensity.entries.any { preset.scaled(it).second == stored } ||
            legacyFx[preset.id]?.any { it == stored } == true
        return if (unchanged) RestoredFx(expected, false) else RestoredFx(stored, true)
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
