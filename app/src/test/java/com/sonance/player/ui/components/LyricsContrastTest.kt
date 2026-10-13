package com.sonance.player.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.sonance.player.data.LyricAdaptMode
import com.sonance.player.ui.theme.Skins
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 歌词自动适配背景。
 *
 * 这里的用例钉的是「**到底能不能看清**」这条硬指标，不是"跑一遍不崩"：
 *
 * 1. 对比度不达标的颜色，调完必须达标（这就是用户要的东西）；
 * 2. 调完**色相不能变** —— 用户要的是"还是原来那套颜色，只是看得清了"；
 * 3. 本来就够清楚的颜色**不许动**，否则整套配色会被无谓地改掉；
 * 4. 逐段取色：上亮下暗的封面，只该动上半截那几行，下半截原样不动。
 *
 * 注：[artBandColors] 要真的读位图像素，在 JVM 单测里跑不了（需要真机 / 模拟器），
 * 所以这里从"已经算好的封面色带"开始验。
 */
class LyricsContrastTest {

    private val ocean = Skins.Ocean
    private val ivory = Skins.Ivory

    private fun hsvOf(c: Color): FloatArray {
        val out = FloatArray(3)
        rgbToHsv(c.red, c.green, c.blue, out)
        return out
    }

    private fun hueOf(c: Color): Float = hsvOf(c)[0]

    private fun valueOf(c: Color): Float = hsvOf(c)[2]

    /** 色相角差（0~180） */
    private fun hueGap(a: Float, b: Float): Float {
        val d = kotlin.math.abs(a - b) % 360f
        return if (d > 180f) 360f - d else d
    }

    // ===== 对比度公式 =====

    @Test
    fun `对比度符合 WCAG 的黑白基准`() {
        assertEquals(21f, contrastRatio(Color.Black, Color.White), 0.05f)
        assertEquals(1f, contrastRatio(ocean.accent, ocean.accent), 0.001f)
        // 顺序无关
        assertEquals(
            contrastRatio(Color.White, ocean.bg),
            contrastRatio(ocean.bg, Color.White),
            0.001f
        )
    }

    // ===== 调色 =====

    @Test
    fun `本来就看得清的颜色原样返回`() {
        // 深色皮肤底色 + 亮强调色：本来就够清楚，不许动
        assertTrue(ocean.accent == ensureReadable(ocean.accent, ocean.bg, TARGET_CURRENT))
    }

    @Test
    fun `和背景撞色的当前句会被调到达标且色相不变`() {
        // 模拟"封面透出来的蓝正好和强调色撞色"
        val bg = Color(0xFF2C6E8E)
        assertTrue(
            "这个用例的前提是原本不达标",
            contrastRatio(ocean.accent, bg) < TARGET_CURRENT
        )

        val after = ensureReadable(ocean.accent, bg, TARGET_CURRENT)
        assertTrue(
            "调完必须达标，实际 ${contrastRatio(after, bg)}",
            contrastRatio(after, bg) >= TARGET_CURRENT
        )
        assertTrue(
            "色相不能变：${hueOf(ocean.accent)} → ${hueOf(after)}",
            hueGap(hueOf(ocean.accent), hueOf(after)) < 2f
        )
    }

    @Test
    fun `亮背景上的强调色被压暗`() {
        val bg = Color(0xFFEDE3D2)   // 封面透出来的暖米色
        val after = ensureReadable(ocean.accent, bg, TARGET_CURRENT)
        assertTrue(contrastRatio(after, bg) >= TARGET_CURRENT)
        assertTrue("亮背景上只能压暗", valueOf(after) < valueOf(ocean.accent))
        assertTrue(hueGap(hueOf(ocean.accent), hueOf(after)) < 2f)
    }

    @Test
    fun `中灰背景也能调到目标`() {
        // 中灰是最难办的一种：往白往黑都够呛。两段式走法要能一路走到纯白 / 纯黑，
        // 否则这里就会卡在"够不到目标"。
        val midGray = Color(0xFF808080)
        val after = ensureReadable(ocean.textMuted, midGray, TARGET_CURRENT)
        assertTrue(
            "实际 ${contrastRatio(after, midGray)}",
            contrastRatio(after, midGray) >= TARGET_CURRENT
        )
        assertTrue(hueGap(hueOf(ocean.textMuted), hueOf(after)) < 3f)
    }

    @Test
    fun `浅色皮肤的字在浅色封面上也会被调暗`() {
        val bg = Color(0xFFF3DFC0)
        val after = ensureReadable(ivory.accent, bg, TARGET_CURRENT)
        assertTrue(contrastRatio(after, bg) >= TARGET_CURRENT)
        assertTrue(valueOf(after) < valueOf(ivory.accent))
    }

    @Test
    fun `浅色皮肤的字压在偏暗的封面上会被调亮`() {
        // 「亮背景调暗」的镜像那一半：浅色皮肤的文字本身是深色，
        // 封面透出来偏暗时它会和背景糊在一起，这时必须往**亮**走。
        // 两个方向都要扫、取改动最小的那个，就是为了这一对能同时成立。
        val darkBg = Color(0xFF4A4A4A)
        assertTrue(
            "这个用例的前提是原本不达标，实际 ${contrastRatio(ivory.accent, darkBg)}",
            contrastRatio(ivory.accent, darkBg) < TARGET_CURRENT
        )

        val after = ensureReadable(ivory.accent, darkBg, TARGET_CURRENT)
        assertTrue(
            "调完必须达标，实际 ${contrastRatio(after, darkBg)}",
            contrastRatio(after, darkBg) >= TARGET_CURRENT
        )
        assertTrue(
            "暗底上只能往亮走：${valueOf(ivory.accent)} → ${valueOf(after)}",
            valueOf(after) > valueOf(ivory.accent)
        )
        assertTrue(
            "色相不能变：${hueOf(ivory.accent)} → ${hueOf(after)}",
            hueGap(hueOf(ivory.accent), hueOf(after)) < 3f
        )
    }

    @Test
    fun `非当前句的目标更松，保住当前句最显眼的层次`() {
        assertTrue(TARGET_CURRENT > TARGET_OTHER)
    }

    @Test
    fun `非当前句的目标留了余量，扛得住模型误差`() {
        // 背景色带是算出来的，和屏幕上真画出来的那个总有一点差（模糊会把明暗交界处
        // 往对面拉）。照着算出来的背景调到"刚好 3:1"，实测就会差一口气 —— 所以门槛
        // 必须比 WCAG 大字号要求的 3:1 高出一档。
        assertTrue("非当前句至少要高于 WCAG 大字号门槛 3:1，实际 $TARGET_OTHER", TARGET_OTHER > 3.0f)
        assertTrue("当前句要比非当前句更严", TARGET_CURRENT > TARGET_OTHER)
    }

    // ===== 背景色带 =====

    @Test
    fun `色带按位置取值、段间插值、越界夹住`() {
        val p = BgProfile(listOf(Color.Black, Color.White))
        assertTrue(Color.Black == p.at(0f))
        assertTrue(Color.White == p.at(1f))
        val mid = p.at(0.5f)
        assertTrue("中点应该介于黑白之间", mid.red > 0.4f && mid.red < 0.6f)
        // 越界不许抛异常
        assertTrue(Color.Black == p.at(-3f))
        assertTrue(Color.White == p.at(9f))
        // 单段 / 空段都不能崩
        assertTrue(Color.Red == BgProfile(listOf(Color.Red)).at(0.7f))
        assertTrue(Color.Black == BgProfile(emptyList()).at(0.5f))
    }

    @Test
    fun `深色封面配深色皮肤时背景仍够暗，歌词颜色不动`() {
        val bands = List(PROFILE_BANDS) { Color(0xFF101820) }
        val profile = buildBgProfile(
            bands = bands,
            artW = 320, artH = 320,
            boxW = 1080f, boxH = 2000f,
            skin = ocean,
            visibility = 55,
            canBlur = true
        )!!

        assertTrue("背景应该仍然是深色", profile.at(0f).luminance() < 0.2f)
        assertTrue(profile.at(1f).luminance() < 0.2f)
        // 深背景 + 亮强调色：本来就清楚，逐段取色也不该改它
        assertTrue(ocean.accent == lyricColor(ocean.accent, 0f, true, profile))
        assertTrue(ocean.accent == lyricColor(ocean.accent, 1f, true, profile))
    }

    @Test
    fun `上亮下暗的封面只影响上半截的歌词`() {
        // 就是"只有屏幕上半截看不清"的那种封面
        val bands = List(PROFILE_BANDS) { i ->
            if (i < PROFILE_BANDS / 2) Color(0xFFDCE8F2) else Color(0xFF12202C)
        }
        val profile = buildBgProfile(
            bands = bands,
            artW = 320, artH = 320,
            boxW = 1080f, boxH = 2000f,
            skin = ocean,
            visibility = 78,
            canBlur = true
        )!!

        val topBg = profile.at(0.05f)
        val bottomBg = profile.at(0.95f)
        assertTrue("上半截确实更亮", topBg.luminance() > bottomBg.luminance())

        val topLine = lyricColor(ocean.accent, 0.05f, true, profile)
        val bottomLine = lyricColor(ocean.accent, 0.95f, true, profile)

        assertTrue(contrastRatio(topLine, topBg) >= TARGET_CURRENT)
        assertTrue(contrastRatio(bottomLine, bottomBg) >= TARGET_CURRENT)
        // 关键：上半截被调过、下半截原样不动 —— 这就是"按区域调"的意义
        assertTrue("上半截应该被调整", topLine != ocean.accent)
        assertTrue("下半截不该被碰", bottomLine == ocean.accent)
        assertTrue(hueGap(hueOf(ocean.accent), hueOf(topLine)) < 2f)
    }

    @Test
    fun `参数不合法时不产生色带`() {
        val bands = List(PROFILE_BANDS) { Color.Gray }
        assertTrue(
            buildBgProfile(bands, 0, 0, 1080f, 2000f, ocean, 55, true) == null
        )
        assertTrue(
            buildBgProfile(bands, 320, 320, 0f, 0f, ocean, 55, true) == null
        )
        assertTrue(
            buildBgProfile(emptyList(), 320, 320, 1080f, 2000f, ocean, 55, true) == null
        )
    }

    @Test
    fun `没有真模糊的机器上背景更淡，色带也跟着变淡`() {
        val bands = List(PROFILE_BANDS) { Color(0xFFFFFFFF) }
        fun profile(canBlur: Boolean) = buildBgProfile(
            bands = bands,
            artW = 320, artH = 320,
            boxW = 1080f, boxH = 2000f,
            skin = ocean,
            visibility = 78,
            canBlur = canBlur
        )!!

        assertTrue(
            "拿不到真模糊时封面只有 45% 不透明度，背景必须更暗（更接近皮肤底色）",
            profile(false).at(0.5f).luminance() < profile(true).at(0.5f).luminance()
        )
    }

    @Test
    fun `液态玻璃的顶部镜面高光只提亮屏幕最上面那一段`() {
        // 液态玻璃在封面和遮罩之上还叠了一道白光（顶部 0.20 → 18% 处 0.07 → 40% 处 0）。
        // 这一段漏算的话，实测顶部背景亮度会被低估约 0.07 —— 调出来的歌词颜色浅一档，
        // 屏幕上就是"调了，但还是发灰"。
        val bands = List(PROFILE_BANDS) { Color(0xFF8E8E8E) }
        fun profile(liquid: Boolean) = buildBgProfile(
            bands = bands,
            artW = 320, artH = 320,
            boxW = 1080f, boxH = 2000f,
            skin = ocean,
            visibility = 92,
            canBlur = true,
            liquid = liquid
        )!!

        val plain = profile(false)
        val liquid = profile(true)

        assertTrue(
            "顶部必须更亮：${plain.at(0f).luminance()} → ${liquid.at(0f).luminance()}",
            liquid.at(0f).luminance() > plain.at(0f).luminance() + 0.03f
        )
        assertTrue(
            "高光是往下衰减的",
            liquid.at(0f).luminance() > liquid.at(0.2f).luminance()
        )
        assertTrue(
            "40% 以下没有高光，两档必须完全一致",
            liquid.at(0.9f) == plain.at(0.9f)
        )
    }

    @Test
    fun `液态玻璃下压在亮带上的歌词也能调到达标`() {
        // 复刻真机上那次实测：封面顶部一条亮带、下半深色，液态玻璃档。
        // 漏掉高光时，顶部那几行只能调到 2.5 左右（实测），差一口气。
        val bands = List(PROFILE_BANDS) { i ->
            if (i < PROFILE_BANDS / 2) Color(0xFFEAF4FF) else Color(0xFF0E1A24)
        }
        val profile = buildBgProfile(
            bands = bands,
            artW = 320, artH = 320,
            boxW = 1080f, boxH = 2400f,
            skin = ocean,
            visibility = 92,
            canBlur = true,
            liquid = true
        )!!

        for (f in listOf(0.05f, 0.25f, 0.75f, 0.95f)) {
            val bg = profile.at(f)
            val line = lyricColor(ocean.textDim, f, false, profile)
            assertTrue(
                "第 $f 处必须达标，实际 ${contrastRatio(line, bg)}（背景亮度 ${bg.luminance()}）",
                contrastRatio(line, bg) >= TARGET_OTHER
            )
            assertTrue(
                "色相不能变：${hueOf(ocean.textDim)} → ${hueOf(line)}",
                hueGap(hueOf(ocean.textDim), hueOf(line)) < 3f
            )
        }
    }

    // ===== 档位 =====

    @Test
    fun `关闭档位下任何一行都不调色`() {
        assertTrue(!shouldAdapt(isCurrent = true, mode = LyricAdaptMode.OFF))
        assertTrue(!shouldAdapt(isCurrent = false, mode = LyricAdaptMode.OFF))
    }

    @Test
    fun `只调当前句的档位只放过当前句`() {
        // 纯文本歌词没有"当前句"，所以它在那一页会一行都不调 —— 这是刻意的，
        // 由 shouldAdapt(isCurrent = false, ...) 自动成立，不用单独判断。
        assertTrue(shouldAdapt(isCurrent = true, mode = LyricAdaptMode.CURRENT_ONLY))
        assertTrue(!shouldAdapt(isCurrent = false, mode = LyricAdaptMode.CURRENT_ONLY))
    }

    @Test
    fun `全部档位下每一行都调色`() {
        assertTrue(shouldAdapt(isCurrent = true, mode = LyricAdaptMode.ALL))
        assertTrue(shouldAdapt(isCurrent = false, mode = LyricAdaptMode.ALL))
    }

    @Test
    fun `档位按 id 存取，认不出的值退回默认的全部`() {
        // 存的是字符串 id（和 GlowMode / GlowTransition 一致），不是 ordinal ——
        // 以后调整枚举顺序也不会把老用户的设置读错。
        LyricAdaptMode.entries.forEach { m ->
            assertTrue("$m 应该能按 id 读回来", LyricAdaptMode.fromId(m.id) == m)
        }
        assertTrue(LyricAdaptMode.fromId(null) == LyricAdaptMode.ALL)
        assertTrue(LyricAdaptMode.fromId("") == LyricAdaptMode.ALL)
        assertTrue(LyricAdaptMode.fromId("早就删掉的档位") == LyricAdaptMode.ALL)
    }

    // ===== 纯文本歌词的行定位 =====

    @Test
    fun `可见行按自己的位置换算成纵向比例`() {
        val m = visibleFractions(
            boxes = listOf(
                VisibleLine(index = 0, offset = -100, size = 100),  // 已经滚出视口顶部
                VisibleLine(index = 1, offset = 0, size = 100),     // 贴着视口顶
                VisibleLine(index = 2, offset = 950, size = 100),   // 正中
                VisibleLine(index = 3, offset = 1950, size = 100)   // 贴着视口底
            ),
            viewportHeightPx = 2000
        )
        assertTrue("滚出顶部的要夹到 0，实际 ${m[0]}", m[0] == 0f)
        assertTrue("贴顶的应该接近 0，实际 ${m[1]}", m[1]!! in 0f..0.1f)
        assertTrue("正中的应该是 0.5，实际 ${m[2]}", m[2]!! in 0.49f..0.51f)
        assertTrue("贴底的应该夹到 1，实际 ${m[3]}", m[3] == 1f)
    }

    @Test
    fun `拿不到视口高度时不产生任何行位置`() {
        // 这时调用方会退回"整页按中段取色"，行为与改动前一致
        val boxes = listOf(VisibleLine(index = 0, offset = 0, size = 100))
        assertTrue(visibleFractions(boxes, 0).isEmpty())
        assertTrue(visibleFractions(boxes, -1).isEmpty())
        assertTrue(visibleFractions(emptyList(), 2000).isEmpty())
    }

    @Test
    fun `同一块背景上的行拿到同一个颜色`() {
        // 纯文本歌词整页压在**同一段**背景上时，逐行取色的结果必须和整页统一取色一致 ——
        // 否则就说明位置换算把行映射到了错误的段上。
        val bands = List(PROFILE_BANDS) { Color(0xFFEDE3D2) }
        val profile = buildBgProfile(
            bands = bands,
            artW = 320, artH = 320,
            boxW = 1080f, boxH = 2000f,
            skin = ocean,
            visibility = 55,
            canBlur = true
        )!!

        val pageWide = lyricColor(ocean.textDim, 0.5f, false, profile)
        for (f in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            assertTrue(
                "同一块背景上第 $f 处的颜色应该和整页取色一致",
                lyricColor(ocean.textDim, f, false, profile) == pageWide
            )
        }
    }
}
