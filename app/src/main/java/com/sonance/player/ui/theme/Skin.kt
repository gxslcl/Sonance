package com.sonance.player.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * 一套皮肤的全部设计令牌。深色 / 浅色皮肤共用同一份结构。
 */
@Immutable
data class Skin(
    val id: String,
    val name: String,
    val tagline: String,
    val isLight: Boolean,
    // 背景层次
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val surface3: Color,
    // 描边
    val border: Color,
    val border2: Color,
    // 文字
    val text: Color,
    val textDim: Color,
    val textMuted: Color,
    // 强调色
    val accent: Color,
    val accentDim: Color,
    val onAccent: Color,
    val accentGlow: Color,
    // 状态色
    val danger: Color,
    // 无封面时的占位渐变
    val gradients: List<List<Color>>,
) {
    /**
     * 依据种子取一组稳定的占位渐变。
     *
     * 只有 3 组固定渐变时，同一套皮肤下的专辑看起来几乎一模一样 —— 这里在
     * 命中基础渐变后再按种子做 ±14° 的色相偏移，等效把可区分度放大到
     * 3 组 × 28 档 ≈ 84 种，列表里就不会"全都一样"了。
     */
    fun gradientFor(seed: String): List<Color> {
        if (gradients.isEmpty()) return listOf(surface3, surface2)
        val h = seedHash(seed)
        val base = gradients[h % gradients.size]
        val shift = ((h ushr 7) % 29) - 14f
        if (shift == 0f) return base
        return base.map { shiftHue(it, shift) }
    }

    companion object {
        /** 稳定的字符串散列（跨进程/跨启动一致） */
        fun seedHash(seed: String): Int {
            var h = 7
            for (c in seed) h = (h * 31 + c.code) and 0x7fffffff
            return h
        }

        /**
         * 在 HSV 空间里旋转色相，保持明度/饱和度。
         * HSV 数组每次新建 —— 它是极小的临时对象，而共享一个静态数组会在
         * 多线程（组合、预览、后台预热）同时调用时出现竞态。
         */
        private fun shiftHue(c: Color, deg: Float): Color {
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(c.toArgb(), hsv)
            hsv[0] = (hsv[0] + deg + 360f) % 360f
            return Color(
                android.graphics.Color.HSVToColor(
                    (c.alpha * 255f).toInt().coerceIn(0, 255),
                    hsv
                )
            )
        }
    }
}

private fun c(hex: Long) = Color(hex)

/** 14 套皮肤：6 套深色 + 8 套浅色 */
object Skins {

    /** 1 · 午夜琥珀 —— 原型默认 */
    val MidnightAmber = Skin(
        id = "midnight_amber",
        name = "午夜琥珀",
        tagline = "原型默认 · 暗金",
        isLight = false,
        bg = c(0xFF121212), surface = c(0xFF1A1A1A), surface2 = c(0xFF222222), surface3 = c(0xFF2A2A2A),
        border = c(0xFF2A2A2A), border2 = c(0xFF333333),
        text = c(0xFFEDE8E0), textDim = c(0xFF9A958E), textMuted = c(0xFF5A5550),
        accent = c(0xFFE8A838), accentDim = c(0xFFB8860B), onAccent = c(0xFF121212),
        accentGlow = c(0x2EE8A838), danger = c(0xFFE05555),
        gradients = listOf(
            listOf(c(0xFF3A2A10), c(0xFF1A1408)),
            listOf(c(0xFF2E2416), c(0xFF15120C)),
            listOf(c(0xFF3D3117), c(0xFF201A0F)),
        )
    )

    /** 2 · 石墨黑夜 */
    val Graphite = Skin(
        id = "graphite",
        name = "石墨黑夜",
        tagline = "极简中性 · 冷灰",
        isLight = false,
        bg = c(0xFF0D0D0F), surface = c(0xFF161618), surface2 = c(0xFF1E1E21), surface3 = c(0xFF27272B),
        border = c(0xFF26262A), border2 = c(0xFF33333A),
        text = c(0xFFF2F2F4), textDim = c(0xFF9E9EA7), textMuted = c(0xFF63636C),
        accent = c(0xFF8EA2FF), accentDim = c(0xFF5A6ED6), onAccent = c(0xFF0D0D0F),
        accentGlow = c(0x2E8EA2FF), danger = c(0xFFFF5A6E),
        gradients = listOf(
            listOf(c(0xFF242A44), c(0xFF14161F)),
            listOf(c(0xFF1F2438), c(0xFF101218)),
            listOf(c(0xFF2A2F3D), c(0xFF16181E)),
        )
    )

    /** 3 · 深海静默 */
    val Ocean = Skin(
        id = "ocean",
        name = "深海静默",
        tagline = "海蓝 · 深夜",
        isLight = false,
        bg = c(0xFF08131F), surface = c(0xFF0E1E2E), surface2 = c(0xFF14283C), surface3 = c(0xFF1C344C),
        border = c(0xFF17304A), border2 = c(0xFF204264),
        text = c(0xFFE6F2FA), textDim = c(0xFF87A8C2), textMuted = c(0xFF4E6E88),
        accent = c(0xFF3FC1F5), accentDim = c(0xFF1E8CBE), onAccent = c(0xFF04121C),
        accentGlow = c(0x2E3FC1F5), danger = c(0xFFFF6B6B),
        gradients = listOf(
            listOf(c(0xFF123A55), c(0xFF08151F)),
            listOf(c(0xFF0E2E46), c(0xFF061019)),
            listOf(c(0xFF164A6B), c(0xFF0A1C29)),
        )
    )

    /** 4 · 霓虹紫夜 */
    val NeonViolet = Skin(
        id = "neon_violet",
        name = "霓虹紫夜",
        tagline = "赛博 · 电紫",
        isLight = false,
        bg = c(0xFF0F0718), surface = c(0xFF180C24), surface2 = c(0xFF211134), surface3 = c(0xFF2C1745),
        border = c(0xFF2A1542), border2 = c(0xFF3A1E58),
        text = c(0xFFF0E8FA), textDim = c(0xFFA78FC4), textMuted = c(0xFF6B5288),
        accent = c(0xFFB478FF), accentDim = c(0xFF7C46C9), onAccent = c(0xFF0F0718),
        accentGlow = c(0x2EB478FF), danger = c(0xFFFF5C8A),
        gradients = listOf(
            listOf(c(0xFF3A1B5E), c(0xFF150A22)),
            listOf(c(0xFF2C1450), c(0xFF120A1C)),
            listOf(c(0xFF4A1F6E), c(0xFF1B0D2A)),
        )
    )

    /** 5 · 松林幽谷 */
    val Forest = Skin(
        id = "forest",
        name = "松林幽谷",
        tagline = "自然 · 森绿",
        isLight = false,
        bg = c(0xFF0A1210), surface = c(0xFF0F1B17), surface2 = c(0xFF152520), surface3 = c(0xFF1D332C),
        border = c(0xFF1A2F28), border2 = c(0xFF264439),
        text = c(0xFFE8F3EC), textDim = c(0xFF8AA99A), textMuted = c(0xFF537165),
        accent = c(0xFF4ADE80), accentDim = c(0xFF2A9D5C), onAccent = c(0xFF04140B),
        accentGlow = c(0x2E4ADE80), danger = c(0xFFE86A6A),
        gradients = listOf(
            listOf(c(0xFF17402D), c(0xFF08130E)),
            listOf(c(0xFF1E4A33), c(0xFF0A1711)),
            listOf(c(0xFF123A2A), c(0xFF07110D)),
        )
    )

    /** 6 · 绯红夜幕 */
    val Crimson = Skin(
        id = "crimson",
        name = "绯红夜幕",
        tagline = "热烈 · 酒红",
        isLight = false,
        bg = c(0xFF140A0C), surface = c(0xFF1E1013), surface2 = c(0xFF29151A), surface3 = c(0xFF371C22),
        border = c(0xFF331A20), border2 = c(0xFF47242C),
        text = c(0xFFF7EAEC), textDim = c(0xFFC2949B), textMuted = c(0xFF7E5A62),
        accent = c(0xFFFF5A6E), accentDim = c(0xFFC13A4C), onAccent = c(0xFF140A0C),
        accentGlow = c(0x2EFF5A6E), danger = c(0xFFFF9A5A),
        gradients = listOf(
            listOf(c(0xFF4A1720), c(0xFF160A0D)),
            listOf(c(0xFF3A121A), c(0xFF120809)),
            listOf(c(0xFF5A1C28), c(0xFF1A0C10)),
        )
    )

    /** 7 · 象牙白昼 */
    val Ivory = Skin(
        id = "ivory",
        name = "象牙白昼",
        tagline = "明亮 · 暖米",
        isLight = true,
        bg = c(0xFFF7F4EF), surface = c(0xFFFFFFFF), surface2 = c(0xFFF1ECE4), surface3 = c(0xFFE5DFD4),
        border = c(0xFFE7E1D6), border2 = c(0xFFD6CFC2),
        text = c(0xFF1E1B16), textDim = c(0xFF6B6459), textMuted = c(0xFF9C958A),
        accent = c(0xFFC8791A), accentDim = c(0xFF9A5B10), onAccent = c(0xFFFFFFFF),
        accentGlow = c(0x33C8791A), danger = c(0xFFD64545),
        gradients = listOf(
            listOf(c(0xFFF3DFC0), c(0xFFE8D3B0)),
            listOf(c(0xFFEDE0CC), c(0xFFDCCDB4)),
            listOf(c(0xFFF6E8D2), c(0xFFE3D2B8)),
        )
    )

    /** 8 · 樱花浅粉 */
    val Sakura = Skin(
        id = "sakura",
        name = "樱花浅粉",
        tagline = "温柔 · 浅粉",
        isLight = true,
        bg = c(0xFFFDF6F7), surface = c(0xFFFFFFFF), surface2 = c(0xFFF9EDF0), surface3 = c(0xFFF0DFE4),
        border = c(0xFFF0E2E6), border2 = c(0xFFE2CCD3),
        text = c(0xFF2A1E22), textDim = c(0xFF7A6270), textMuted = c(0xFFA99AA2),
        accent = c(0xFFE0568A), accentDim = c(0xFFB23D6C), onAccent = c(0xFFFFFFFF),
        accentGlow = c(0x33E0568A), danger = c(0xFFD94B4B),
        gradients = listOf(
            listOf(c(0xFFFBDCE6), c(0xFFF2C2D3)),
            listOf(c(0xFFF6D8E2), c(0xFFEBBFCE)),
            listOf(c(0xFFFCE4EC), c(0xFFF0C9D8)),
        )
    )

    /** 9 · 薄荷清晨 */
    val Mint = Skin(
        id = "mint",
        name = "薄荷清晨",
        tagline = "清爽 · 浅绿",
        isLight = true,
        bg = c(0xFFF2F8F6), surface = c(0xFFFFFFFF), surface2 = c(0xFFE8F2EF), surface3 = c(0xFFD7E8E3),
        border = c(0xFFE0EDE9), border2 = c(0xFFC6DED7),
        text = c(0xFF152420), textDim = c(0xFF5A7570), textMuted = c(0xFF93AAA5),
        accent = c(0xFF0E9F6E), accentDim = c(0xFF0A7A54), onAccent = c(0xFFFFFFFF),
        accentGlow = c(0x330E9F6E), danger = c(0xFFD2483F),
        gradients = listOf(
            listOf(c(0xFFCDE9DF), c(0xFFB2DCCD)),
            listOf(c(0xFFD9EDE6), c(0xFFBEDDD3)),
            listOf(c(0xFFC4E4D8), c(0xFFA8D6C6)),
        )
    )

    /** 10 · 雾霭蓝 */
    val Mist = Skin(
        id = "mist",
        name = "雾霭蓝",
        tagline = "清冷 · 雾蓝",
        isLight = true,
        bg = c(0xFFF3F6FA), surface = c(0xFFFFFFFF), surface2 = c(0xFFE9EFF6), surface3 = c(0xFFDAE3EE),
        border = c(0xFFE1E8F0), border2 = c(0xFFC9D5E4),
        text = c(0xFF17202B), textDim = c(0xFF5A6879), textMuted = c(0xFF93A1B2),
        accent = c(0xFF2E6DB4), accentDim = c(0xFF1F5490), onAccent = c(0xFFFFFFFF),
        accentGlow = c(0x332E6DB4), danger = c(0xFFD1453D),
        gradients = listOf(
            listOf(c(0xFFDCE8F6), c(0xFFBCCFE8)),
            listOf(c(0xFFD2E0F0), c(0xFFB3C8E3)),
            listOf(c(0xFFE4EEF9), c(0xFFC6D8EC)),
        )
    )

    /** 11 · 素白极简 */
    val Cloud = Skin(
        id = "cloud",
        name = "素白极简",
        tagline = "中性 · 极简",
        isLight = true,
        bg = c(0xFFF5F6F8), surface = c(0xFFFFFFFF), surface2 = c(0xFFECEEF1), surface3 = c(0xFFDFE2E7),
        border = c(0xFFE4E7EB), border2 = c(0xFFCFD4DB),
        text = c(0xFF1B1E23), textDim = c(0xFF5F656F), textMuted = c(0xFF969CA6),
        accent = c(0xFF4A63E7), accentDim = c(0xFF3649B8), onAccent = c(0xFFFFFFFF),
        accentGlow = c(0x334A63E7), danger = c(0xFFD1453D),
        gradients = listOf(
            listOf(c(0xFFE3E6EC), c(0xFFC9CFD9)),
            listOf(c(0xFFDADEE6), c(0xFFC1C7D3)),
            listOf(c(0xFFEAEDF2), c(0xFFD2D8E0)),
        )
    )

    /** 12 · 薰衣草田 */
    val Lavender = Skin(
        id = "lavender",
        name = "薰衣草田",
        tagline = "柔和 · 浅紫",
        isLight = true,
        bg = c(0xFFF8F6FC), surface = c(0xFFFFFFFF), surface2 = c(0xFFEFEAF8), surface3 = c(0xFFE1D9F0),
        border = c(0xFFE9E3F5), border2 = c(0xFFD5CBE9),
        text = c(0xFF1F1A2B), textDim = c(0xFF615878), textMuted = c(0xFF9C93B2),
        accent = c(0xFF7A5AD0), accentDim = c(0xFF5E41AB), onAccent = c(0xFFFFFFFF),
        accentGlow = c(0x337A5AD0), danger = c(0xFFD1453D),
        gradients = listOf(
            listOf(c(0xFFE7DEF8), c(0xFFCFC0EE)),
            listOf(c(0xFFDED4F4), c(0xFFC4B3E8)),
            listOf(c(0xFFEDE6FA), c(0xFFD8CCF1)),
        )
    )

    /** 13 · 珊瑚暖阳 */
    val Coral = Skin(
        id = "coral",
        name = "珊瑚暖阳",
        tagline = "暖阳 · 珊瑚",
        isLight = true,
        bg = c(0xFFFDF6F2), surface = c(0xFFFFFFFF), surface2 = c(0xFFFAEBE3), surface3 = c(0xFFF2DBCD),
        border = c(0xFFF6E4DA), border2 = c(0xFFE8CBBA),
        text = c(0xFF2B1C14), textDim = c(0xFF77584A), textMuted = c(0xFFB0968A),
        accent = c(0xFFDE6239), accentDim = c(0xFFB44A28), onAccent = c(0xFFFFFFFF),
        accentGlow = c(0x33DE6239), danger = c(0xFFC9403A),
        gradients = listOf(
            listOf(c(0xFFFBDDCC), c(0xFFF3C3AA)),
            listOf(c(0xFFF8D5C4), c(0xFFEFB99D)),
            listOf(c(0xFFFCE4D6), c(0xFFF5CDB6)),
        )
    )

    /** 14 · 陶土暖棕 */
    val Clay = Skin(
        id = "clay",
        name = "陶土暖棕",
        tagline = "质朴 · 陶土",
        isLight = true,
        bg = c(0xFFF9F4F0), surface = c(0xFFFFFFFF), surface2 = c(0xFFF2E7E0), surface3 = c(0xFFE5D5C9),
        border = c(0xFFEFE1D8), border2 = c(0xFFDCC6B8),
        text = c(0xFF26190F), textDim = c(0xFF6D5442), textMuted = c(0xFFA8917F),
        accent = c(0xFF9C5B3C), accentDim = c(0xFF7A432A), onAccent = c(0xFFFFFFFF),
        accentGlow = c(0x339C5B3C), danger = c(0xFFC9403A),
        gradients = listOf(
            listOf(c(0xFFEDD9C8), c(0xFFDCC0AA)),
            listOf(c(0xFFE6CEBB), c(0xFFD3B49C)),
            listOf(c(0xFFF2E2D4), c(0xFFE0C8B4)),
        )
    )

    val all: List<Skin> = listOf(
        // ---- 深色 ----
        MidnightAmber, Graphite, Ocean, NeonViolet, Forest, Crimson,
        // ---- 浅色 ----
        Ivory, Sakura, Mint, Mist, Cloud, Lavender, Coral, Clay
    )

    fun byId(id: String?): Skin = all.firstOrNull { it.id == id } ?: MidnightAmber
}

val LocalSkin = staticCompositionLocalOf { Skins.MidnightAmber }
