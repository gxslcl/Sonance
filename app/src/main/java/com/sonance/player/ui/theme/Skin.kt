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
    /**
     * 这套皮肤**自带**的玻璃质感；null = 普通皮肤。
     *
     * 「毛玻璃 / 液态玻璃」光靠颜色令牌表达不出来 —— 它得让歌词页、律动柱页、
     * 悬浮迷你播放条真的去糊一层封面。所以带玻璃的皮肤在**被选中的那一刻**
     * 会把这几处的玻璃样式一起切到这一档（见 ViewModel.changeSkin）：
     * 选主题 = 一次点完整套观感，不用再去三个地方各调一次。
     */
    val glass: GlassStyle? = null,
    /**
     * 沉浸皮肤：主界面没有自己的底色，背景就是当前播放封面的液态玻璃
     * （模糊 40 / 透出 100，见 SonanceRoot.Shell），文字颜色按封面明暗逐歌自适应。
     */
    val immersive: Boolean = false,
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

/** 28 套皮肤：17 套深色（含液态玻璃）+ 11 套浅色（含霜华玻璃） */
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

    /** 15 · 霓虹青柠 —— 高饱和酸绿 */
    val NeonLime = Skin(
        id = "neon_lime",
        name = "霓虹青柠",
        tagline = "高饱和 · 酸绿",
        isLight = false,
        bg = c(0xFF0A1004), surface = c(0xFF121A09), surface2 = c(0xFF18240D), surface3 = c(0xFF223313),
        border = c(0xFF253612), border2 = c(0xFF354D1C),
        text = c(0xFFF4FFE2), textDim = c(0xFFA9C88C), textMuted = c(0xFF6D8A56),
        accent = c(0xFFC9FF33), accentDim = c(0xFF8FD015), onAccent = c(0xFF0A1004),
        accentGlow = c(0x2EC9FF33), danger = c(0xFFFF5470),
        gradients = listOf(
            listOf(c(0xFF2C4A0E), c(0xFF0C1405)),
            listOf(c(0xFF395C12), c(0xFF101A07)),
            listOf(c(0xFF1F380A), c(0xFF080F04)),
        )
    )

    /** 16 · 赛博洋红 */
    val CyberMagenta = Skin(
        id = "cyber_magenta",
        name = "赛博洋红",
        tagline = "高饱和 · 电粉",
        isLight = false,
        bg = c(0xFF120516), surface = c(0xFF1D0A24), surface2 = c(0xFF270E32), surface3 = c(0xFF341444),
        border = c(0xFF31123F), border2 = c(0xFF451A58),
        text = c(0xFFFDEAFB), textDim = c(0xFFD292C7), textMuted = c(0xFF8C5C85),
        accent = c(0xFFFF2FD0), accentDim = c(0xFFC2149F), onAccent = c(0xFF120516),
        accentGlow = c(0x2EFF2FD0), danger = c(0xFFFF6B6B),
        gradients = listOf(
            listOf(c(0xFF5A0A4E), c(0xFF160619)),
            listOf(c(0xFF42103C), c(0xFF110512)),
            listOf(c(0xFF6B0F5C), c(0xFF1A0720)),
        )
    )

    /** 17 · 电光冰蓝 */
    val ElectricCyan = Skin(
        id = "electric_cyan",
        name = "电光冰蓝",
        tagline = "高饱和 · 电蓝",
        isLight = false,
        bg = c(0xFF030B18), surface = c(0xFF07152B), surface2 = c(0xFF0B1E3C), surface3 = c(0xFF122A50),
        border = c(0xFF12294A), border2 = c(0xFF1B3A66),
        text = c(0xFFE4F5FF), textDim = c(0xFF87AACA), textMuted = c(0xFF537692),
        accent = c(0xFF18E0FF), accentDim = c(0xFF0BA9CC), onAccent = c(0xFF031018),
        accentGlow = c(0x2E18E0FF), danger = c(0xFFFF5C7A),
        gradients = listOf(
            listOf(c(0xFF0B3A5C), c(0xFF061320)),
            listOf(c(0xFF0E4A72), c(0xFF081A2B)),
            listOf(c(0xFF082E4A), c(0xFF040E18)),
        )
    )

    /** 18 · 落日熔金 */
    val SunsetBlaze = Skin(
        id = "sunset_blaze",
        name = "落日熔金",
        tagline = "高饱和 · 炽橙",
        isLight = false,
        bg = c(0xFF1A0803), surface = c(0xFF271009), surface2 = c(0xFF34170D), surface3 = c(0xFF461F12),
        border = c(0xFF421D11), border2 = c(0xFF5A281A),
        text = c(0xFFFFF1E4), textDim = c(0xFFDBA483), textMuted = c(0xFF91614A),
        accent = c(0xFFFF7A18), accentDim = c(0xFFD9530A), onAccent = c(0xFF1A0803),
        accentGlow = c(0x2EFF7A18), danger = c(0xFFFF4D6A),
        gradients = listOf(
            listOf(c(0xFF5C1D06), c(0xFF1C0A04)),
            listOf(c(0xFF451305), c(0xFF140703)),
            listOf(c(0xFF6E2608), c(0xFF210C05)),
        )
    )

    /** 19 · 热带果漾 */
    val Tropical = Skin(
        id = "tropical",
        name = "热带果漾",
        tagline = "鲜明 · 暖果",
        isLight = true,
        bg = c(0xFFFFFBF2), surface = c(0xFFFFFFFF), surface2 = c(0xFFFFF1DA), surface3 = c(0xFFFDE4C4),
        border = c(0xFFFAE8CE), border2 = c(0xFFF4D4A8),
        text = c(0xFF2A1A06), textDim = c(0xFF7C5C2C), textMuted = c(0xFFAB9165),
        accent = c(0xFFFF6B35), accentDim = c(0xFFE04E1F), onAccent = c(0xFFFFFFFF),
        accentGlow = c(0x33FF6B35), danger = c(0xFFE0382F),
        gradients = listOf(
            listOf(c(0xFFFFE0A8), c(0xFFFFC178)),
            listOf(c(0xFFFFD9C0), c(0xFFFBB98E)),
            listOf(c(0xFFFFEBC8), c(0xFFFFCE96)),
        )
    )

    /** 20 · 晴空柠檬 */
    val SkyLemon = Skin(
        id = "sky_lemon",
        name = "晴空柠檬",
        tagline = "鲜明 · 天蓝柠檬",
        isLight = true,
        bg = c(0xFFF6FCFF), surface = c(0xFFFFFFFF), surface2 = c(0xFFE6F6FF), surface3 = c(0xFFCFEBFA),
        border = c(0xFFD8ECFA), border2 = c(0xFFB2D9F0),
        text = c(0xFF0C1E29), textDim = c(0xFF4F7285), textMuted = c(0xFF89A6B8),
        accent = c(0xFF00B8FF), accentDim = c(0xFF0089C4), onAccent = c(0xFFFFFFFF),
        accentGlow = c(0x3300B8FF), danger = c(0xFFE5392F),
        gradients = listOf(
            listOf(c(0xFFB8E8FF), c(0xFF8FD4F5)),
            listOf(c(0xFFCDEFFD), c(0xFFA2DCF2)),
            listOf(c(0xFFA9E0FA), c(0xFF7FCCEE)),
        )
    )

    /** 21 · 霜华玻璃 —— 自带毛玻璃（选中时三处玻璃背景一起切到「强」）*/
    val FrostedGlass = Skin(
        id = "frosted_glass",
        name = "霜华玻璃",
        tagline = "毛玻璃 · 冷白",
        isLight = true,
        bg = c(0xFFEDF1F7), surface = c(0xFFF8FAFD), surface2 = c(0xFFE5EBF4), surface3 = c(0xFFD7E0EE),
        border = c(0xFFDBE3EF), border2 = c(0xFFC4D1E2),
        text = c(0xFF17202B), textDim = c(0xFF5A6879), textMuted = c(0xFF92A0B2),
        accent = c(0xFF3A7BD5), accentDim = c(0xFF2A5FA8), onAccent = c(0xFFFFFFFF),
        accentGlow = c(0x333A7BD5), danger = c(0xFFD1453D),
        gradients = listOf(
            listOf(c(0xFFD9E6F5), c(0xFFBFD2E8)),
            listOf(c(0xFFCFDFF1), c(0xFFB4CAE4)),
            listOf(c(0xFFE2EDF8), c(0xFFC8DAEE)),
        ),
        glass = GlassStyle.STRONG
    )

    /** 22 · 液态玻璃 —— 自带液态玻璃（深色底 + 高光折射）*/
    val LiquidGlass = Skin(
        id = "liquid_glass",
        name = "液态玻璃",
        tagline = "液态玻璃 · 深蓝",
        isLight = false,
        bg = c(0xFF080D16), surface = c(0xFF111A27), surface2 = c(0xFF192533), surface3 = c(0xFF243141),
        border = c(0xFF22303F), border2 = c(0xFF2F4155),
        text = c(0xFFEAF3FF), textDim = c(0xFF9BB0C8), textMuted = c(0xFF647A93),
        accent = c(0xFF5AC8FA), accentDim = c(0xFF2E9BD8), onAccent = c(0xFF04121C),
        accentGlow = c(0x2E5AC8FA), danger = c(0xFFFF6B7A),
        gradients = listOf(
            listOf(c(0xFF1B3550), c(0xFF0A1420)),
            listOf(c(0xFF152C44), c(0xFF08111B)),
            listOf(c(0xFF22405E), c(0xFF0C1926)),
        ),
        glass = GlassStyle.LIQUID
    )

    /** 23 · 沉浸 —— 主界面即封面：液态玻璃铺底，字色随封面明暗逐歌自适应 */
    val Immersive = Skin(
        id = "immersive",
        name = "沉浸",
        tagline = "封面铺底 · 随歌换妆",
        isLight = false,
        // 底色只是遮罩基调（中性深灰，压什么封面都不偏色）；surface 全部带透明度，
        // 叠在糊开的封面上才是「玻璃卡片」的观感
        bg = c(0xFF17181C), surface = c(0xCC22242A), surface2 = c(0xCC2A2C33), surface3 = c(0xB332343C),
        border = c(0x40FFFFFF), border2 = c(0x29FFFFFF),
        text = c(0xFFF2F2F3), textDim = c(0xFFB9B9C0), textMuted = c(0xFF8B8B93),
        accent = c(0xFF4ECDC4), accentDim = c(0xFF2FA69E), onAccent = c(0xFF0A0F0E),
        accentGlow = c(0x2E4ECDC4), danger = c(0xFFFF6B7A),
        gradients = listOf(listOf(c(0xFF2A2C34), c(0xFF17181C))),
        immersive = true
    )

    /**
     * 24-28 · 五色沉浸系列 —— 与「沉浸」同一路径的封面玻璃皮肤，但各自带主题色：
     * - 主界面铺当前播放封面的液态玻璃，scrimColor = bg（主题色深调遮罩），
     *   整页氛围 = 封面 × 主题色调；字色/图标色为该主题的协调浅色，
     *   ensureReadable 仅在压不住时兜底提亮
     * - surface 家族全部半透明：叠在玻璃上是主题色调玻璃卡片
     * - glass = LIQUID：选中时歌词页 / 律动柱 / 迷你条三处一起切液态玻璃
     */
    /** 24 · 提香红 —— 典雅 · 艺术（Titian Red #D34947）*/
    val TitianRed = Skin(
        id = "titian_red",
        name = "提香红",
        tagline = "封面铺底 · 绯红玻璃",
        isLight = false,
        bg = c(0xFF201010), surface = c(0xCC2A1A18), surface2 = c(0xCC33201D), surface3 = c(0xB33D2723),
        border = c(0x40FFFFFF), border2 = c(0x29FFFFFF),
        text = c(0xFFF7ECEA), textDim = c(0xFFD9C2BE), textMuted = c(0xFFA68D89),
        accent = c(0xFFE0615E), accentDim = c(0xFFB23E3B), onAccent = c(0xFFFFF4F2),
        accentGlow = c(0x2EE0615E), danger = c(0xFFFF7A5A),
        gradients = listOf(
            listOf(c(0xFF4A1C1B), c(0xFF150A0A)),
            listOf(c(0xFF3B1514), c(0xFF110707)),
            listOf(c(0xFF5A2322), c(0xFF180C0B)),
        ),
        glass = GlassStyle.LIQUID,
        immersive = true
    )

    /** 25 · 莱姆绿 —— 生机 · 自然（Lime Green #6ECC54）*/
    val LimeGreen = Skin(
        id = "lime_green",
        name = "莱姆绿",
        tagline = "封面铺底 · 青翠玻璃",
        isLight = false,
        bg = c(0xFF101A0C), surface = c(0xCC1A2814), surface2 = c(0xCC22331A), surface3 = c(0xB32C4121),
        border = c(0x40FFFFFF), border2 = c(0x29FFFFFF),
        text = c(0xFFF1FAEA), textDim = c(0xFFCFE2C4), textMuted = c(0xFF97AC8B),
        accent = c(0xFF7DD863), accentDim = c(0xFF4FA836), onAccent = c(0xFF08120A),
        accentGlow = c(0x2E7DD863), danger = c(0xFFE05555),
        gradients = listOf(
            listOf(c(0xFF2C4A16), c(0xFF0C1405)),
            listOf(c(0xFF223A12), c(0xFF090F04)),
            listOf(c(0xFF3A5C1E), c(0xFF0F1A08)),
        ),
        glass = GlassStyle.LIQUID,
        immersive = true
    )

    /** 26 · 马尔斯绿 —— 奢华 · 神秘（Marrs Green #018B8D）*/
    val MarrsGreen = Skin(
        id = "marrs_green",
        name = "马尔斯绿",
        tagline = "封面铺底 · 幽青玻璃",
        isLight = false,
        bg = c(0xFF0A1A1B), surface = c(0xCC14292B), surface2 = c(0xCC1B3436), surface3 = c(0xB3224244),
        border = c(0x40FFFFFF), border2 = c(0x29FFFFFF),
        text = c(0xFFE9F7F6), textDim = c(0xFFC2DEDC), textMuted = c(0xFF86A5A3),
        accent = c(0xFF22BCB7), accentDim = c(0xFF018B8D), onAccent = c(0xFF041414),
        accentGlow = c(0x2E22BCB7), danger = c(0xFFFF6B6B),
        gradients = listOf(
            listOf(c(0xFF0E3A3B), c(0xFF051313)),
            listOf(c(0xFF0A2E2F), c(0xFF040F0F)),
            listOf(c(0xFF124A4A), c(0xFF061818)),
        ),
        glass = GlassStyle.LIQUID,
        immersive = true
    )

    /** 27 · 克莱因蓝 —— 希望 · 自由（Klein Blue #002FA7）*/
    val KleinBlue = Skin(
        id = "klein_blue",
        name = "克莱因蓝",
        tagline = "封面铺底 · 深蓝玻璃",
        isLight = false,
        bg = c(0xFF0A1128), surface = c(0xCC161F45), surface2 = c(0xCC1D2856), surface3 = c(0xB3253370),
        border = c(0x40FFFFFF), border2 = c(0x29FFFFFF),
        text = c(0xFFEDF1FF), textDim = c(0xFFC4CDEE), textMuted = c(0xFF8B97C4),
        accent = c(0xFF4A6EE8), accentDim = c(0xFF002FA7), onAccent = c(0xFFFFFFFF),
        accentGlow = c(0x2E4A6EE8), danger = c(0xFFFF5C7A),
        gradients = listOf(
            listOf(c(0xFF0E1E5C), c(0xFF050918)),
            listOf(c(0xFF0A1750), c(0xFF040713)),
            listOf(c(0xFF12267A), c(0xFF060B1E)),
        ),
        glass = GlassStyle.LIQUID,
        immersive = true
    )

    /** 28 · 中国红 —— 热情 · 福禄（Chinese Red #C8161D）*/
    val ChineseRed = Skin(
        id = "chinese_red",
        name = "中国红",
        tagline = "封面铺底 · 朱红玻璃",
        isLight = false,
        bg = c(0xFF1E0C0D), surface = c(0xCC2C1517), surface2 = c(0xCC371B1E), surface3 = c(0xB3442326),
        border = c(0x40FFFFFF), border2 = c(0x29FFFFFF),
        text = c(0xFFF9ECEA), textDim = c(0xFFDFC4C1), textMuted = c(0xFFA98D8A),
        accent = c(0xFFE0454B), accentDim = c(0xFFC8161D), onAccent = c(0xFFFFF5F3),
        accentGlow = c(0x2EE0454B), danger = c(0xFFFF7A5A),
        gradients = listOf(
            listOf(c(0xFF4A1013), c(0xFF140506)),
            listOf(c(0xFF3A0C0F), c(0xFF100405)),
            listOf(c(0xFF5C161A), c(0xFF170607)),
        ),
        glass = GlassStyle.LIQUID,
        immersive = true
    )

    val all: List<Skin> = listOf(
        // ---- 深色 ----
        MidnightAmber, Graphite, Ocean, NeonViolet, Forest, Crimson,
        NeonLime, CyberMagenta, ElectricCyan, SunsetBlaze, LiquidGlass, Immersive,
        TitianRed, LimeGreen, MarrsGreen, KleinBlue, ChineseRed,
        // ---- 浅色 ----
        Ivory, Sakura, Mint, Mist, Cloud, Lavender, Coral, Clay,
        Tropical, SkyLemon, FrostedGlass
    )

    fun byId(id: String?): Skin = all.firstOrNull { it.id == id } ?: MidnightAmber
}

val LocalSkin = staticCompositionLocalOf { Skins.MidnightAmber }

/**
 * 弹窗/浮层面板背景：沉浸皮肤的 surface 家族带透明度（叠在页面上是玻璃卡片），
 * 但用作**整块弹窗背景**时会透出后面的页面内容。这里把 alpha 抬到 ≥0.96，
 * 保留一丝层次观感的同时保证弹窗内容可读。
 */
fun Color.solidSurface(): Color = copy(alpha = alpha.coerceAtLeast(0.96f))
