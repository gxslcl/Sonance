package com.sonance.player.ui.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * 自绘线性图标集（24x24 视口）。
 *
 * ## 三套可切换风格
 *
 * 原型 `gemini-code-1794107924799.html` 里的播放相关图标全部按原始 path 重建，
 * 并在此基础上做了两套差异化变体，通过 [IconSetState.style] 全局切换：
 *
 * - **0 原型原版**：HTML 内联 SVG 的 1:1 复刻，描边 1.9~2.0、Round 端点；
 *   上一/下一首用原型默认的 solid 方案（实心圆角三角 + 竖条）。
 * - **1 极简线条**：1.4 线宽的几何骨架，主动删细节 —— 歌词只留两行、
 *   随机改成两条直线交叉箭头、循环改成一个闭合圆角环 + 单箭头、
 *   上一/下一首用原型的 line 方案（竖线 + 空心三角）。
 * - **2 实心圆润**：2.6~3.2 的重笔 + 实心块面 —— 上一/下一首用原型的 double
 *   方案（双实心三角）、心形实心、音符实心、均衡器圆点实心。
 *
 * 原型里没有对应物的图标（[Maximize]/[Minimize] 黑胶全屏、导航类等）保持单套几何，
 * 只让线宽跟随当前套系的描边规则，不复制三份。
 */
object SonanceIcons {

    private fun circle(cx: Double, cy: Double, r: Double): String =
        "M${cx - r},${cy} a$r,$r 0 1,0 ${r * 2},0 a$r,$r 0 1,0 ${-r * 2},0"

    private fun dot(cx: Double, cy: Double): String = "M$cx,$cy h0.01"

    /**
     * 组装图标（描边 / 填充两组，统一线宽）。
     *
     * 描边和填充分成两组传。**整只图标只能有一种模式**的话，
     * 「描边横线 + 实心音符」这种组合就表达不出来 —— 硬塞进描边的结果是
     * 音符也被描边、变成空心的（队列图标踩过这个坑）。混合图标走 [mx]。
     */
    private fun build(
        name: String,
        stroked: List<String>,
        filled: List<String>,
        width: Float = 2f
    ): ImageVector = buildW(name, stroked.map { it to width }, filled)

    /** 每根描边可以带自己的线宽（例如「队列」竖线 2.4、横线 2.8） */
    private fun buildW(
        name: String,
        stroked: List<Pair<String, Float>>,
        filled: List<String>
    ): ImageVector {
        val builder = ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        )
        stroked.forEach { (d, w) ->
            builder.addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                stroke = SolidColor(Color.White),
                strokeLineWidth = w,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            )
        }
        filled.forEach { d ->
            builder.addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                fill = SolidColor(Color.White)
            )
        }
        return builder.build()
    }

    /** D-LAN / DLNA 无线投屏与局域网共享图标 */
    val Cast by o(
        "cast",
        "M2 8V6C2 4.9 2.9 4 4 4H20C21.1 4 22 4.9 22 6V18C22 19.1 21.1 20 20 20H14",
        "M2 12C6.42 12 10 15.58 10 20",
        "M2 16C4.21 16 6 17.79 6 20",
        "M2 20H2.01"
    )

    private fun o(name: String, vararg d: String) = lazy { build(name, d.toList(), emptyList()) }
    private fun ow(name: String, width: Float, vararg d: String) =
        lazy { build(name, d.toList(), emptyList(), width) }
    private fun f(name: String, vararg d: String) = lazy { build(name, emptyList(), d.toList()) }

    /** 描边 + 填充混合：第一组描边，第二组填充 */
    private fun mx(name: String, stroked: List<String>, filled: List<String>) =
        lazy { build(name, stroked, filled) }

    /** 按当前套系取图标；读取发生在组合期，切换后立即重组 */
    private fun pick(sets: List<Lazy<ImageVector>>): ImageVector =
        sets[IconSetState.style.coerceIn(0, sets.lastIndex)].value

    /** 设置页预览用：取指定套系的三枚代表图标（上一首 / 收藏 / 队列） */
    fun previewSet(style: Int): Triple<ImageVector, ImageVector, ImageVector> {
        val i = style.coerceIn(0, 2)
        return Triple(prevSets[i].value, heartSets[i].value, queueSets[i].value)
    }

    // ===== 品牌 =====
    val Logo by o("Logo", circle(12.0, 12.0, 9.0), circle(12.0, 12.0, 2.5))

    // ===== 导航 =====
    val Music by o("Music", "M9 18V5l12-2v13", circle(6.0, 18.0, 3.0), circle(18.0, 16.0, 3.0))
    val Album by o(
        "Album",
        "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z",
        circle(12.0, 12.0, 3.0)
    )
    val Users by o(
        "Users",
        "M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2",
        circle(9.0, 7.0, 4.0),
        "M22 21v-2a4 4 0 0 0-3-3.87",
        "M16 3.13a4 4 0 0 1 0 7.75"
    )
    val Folder by o("Folder", "M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z")
    val Clock by o("Clock", circle(12.0, 12.0, 10.0), "M12 6v6l4 2")

    // ===== 原型覆盖：收藏 =====
    private val heartPath =
        "M12 20c-4.2-3-8-6.2-8-10.6A4.5 4.5 0 0 1 8.5 5c1.5 0 2.8.8 3.5 1.9" +
            ".7-1.1 2-1.9 3.5-1.9A4.5 4.5 0 0 1 20 9.4c0 4.4-3.8 7.6-8 10.6z"

    private val heartSets = listOf(
        o("Heart0", heartPath),                              // 原型：1.9 描边
        lazy { build("Heart1", listOf(heartPath), emptyList(), 1.4f) }, // 极简细描边
        f("Heart2", heartPath)                               // 实心
    )
    val Heart: ImageVector get() = pick(heartSets)

    private val heartFilledSets = listOf(
        f("HeartFilled0", heartPath),
        f("HeartFilled1", heartPath),
        f("HeartFilled2", heartPath)
    )
    val HeartFilled: ImageVector get() = pick(heartFilledSets)

    val Palette by o("Palette", "M12 2.69l5.66 5.66a8 8 0 1 1-11.31 0z")
    val Info by o("Info", circle(12.0, 12.0, 10.0), "M12 16v-4", "M12 8h.01")

    /** 画面提亮开关 */
    val Sun by o(
        "Sun",
        circle(12.0, 12.0, 4.0),
        "M12 2v2", "M12 20v2", "M2 12h2", "M20 12h2",
        "M4.93 4.93l1.41 1.41", "M17.66 17.66l1.41 1.41",
        "M4.93 19.07l1.41-1.41", "M17.66 6.34l1.41-1.41"
    )

    /**
     * 锁屏 / 后台继续播放开关。
     *
     * 用月牙而不是一个「设置齿轮」：它表达的是「熄屏后还在响」，
     * 和左边 [Sun]（画面提亮）在底栏里成对出现，开启/关闭只靠亮度区分，
     * 不占常驻大按钮的位置。
     */
    val Moon by o(
        "Moon",
        "M20.6 13.1A8.6 8.6 0 0 1 10.9 3.4 9 9 0 1 0 20.6 13.1z"
    )

    // ===== 操作 =====
    // Playback chrome follows the exact same set switch as Prev/Next/Queue/Equalizer.
    private val searchSets = listOf(
        ow("Search0", 1.9f, circle(10.5, 10.5, 6.5), "M16 16l4 4"),
        ow("Search1", 1.4f, circle(10.5, 10.5, 6.5), "M16 16l4 4"),
        ow("Search2", 2.8f, circle(10.5, 10.5, 6.2), "M15.5 15.5l4.5 4.5")
    )
    val Search: ImageVector get() = pick(searchSets)
    val ListView by o("ListView", "M8 6h13", "M8 12h13", "M8 18h13", dot(3.0, 6.0), dot(3.0, 12.0), dot(3.0, 18.0))
    val GridView by o(
        "GridView",
        "M3 3h7v7H3z", "M14 3h7v7h-7z", "M14 14h7v7h-7z", "M3 14h7v7H3z"
    )
    /** 封面墙（5 格不规则拼图，复刻 5.5 原型顶栏图标） */
    val CoverWall by ow(
        "CoverWall",
        1.8f,
        "M5 3h7a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z",
        "M17.2 3h2.6A1.2 1.2 0 0 1 21 4.2v2.6A1.2 1.2 0 0 1 19.8 8h-2.6A1.2 1.2 0 0 1 16 6.8V4.2A1.2 1.2 0 0 1 17.2 3z",
        "M17.2 10h2.6a1.2 1.2 0 0 1 1.2 1.2v1.6a1.2 1.2 0 0 1-1.2 1.2h-2.6A1.2 1.2 0 0 1 16 12.8v-1.6A1.2 1.2 0 0 1 17.2 10z",
        "M4.2 16h2.6A1.2 1.2 0 0 1 8 17.2v2.6A1.2 1.2 0 0 1 6.8 21H4.2A1.2 1.2 0 0 1 3 19.8v-2.6A1.2 1.2 0 0 1 4.2 16z",
        "M11.2 16h8.6a1.2 1.2 0 0 1 1.2 1.2v2.6a1.2 1.2 0 0 1-1.2 1.2h-8.6A1.2 1.2 0 0 1 10 19.8v-2.6A1.2 1.2 0 0 1 11.2 16z"
    )
    val Sort by o("Sort", "M3 6h11", "M3 12h8", "M3 18h5", "M17 8v11", "M14 16l3 3 3-3")
    val Close by o("Close", "M18 6L6 18", "M6 6l12 12")

    // ===== 原型覆盖：收起箭头（顶栏） =====
    private val chevronSets = listOf(
        o("ChevronDown0", "M6.5 9.5l5.5 5.5 5.5-5.5"),
        lazy { build("ChevronDown1", listOf("M6.5 9.5l5.5 5.5 5.5-5.5"), emptyList(), 1.5f) },
        lazy { build("ChevronDown2", listOf("M6.5 9.5l5.5 5.5 5.5-5.5"), emptyList(), 2.5f) }
    )
    val ChevronDown: ImageVector get() = pick(chevronSets)

    /** 沉浸式黑胶模式入口：外圈唱片 + 纹路高光弧 + 中心标签环（线宽随套系） */
    private val vinylDiscSets = listOf(
        ow(
            "VinylDisc0",
            1.9f,
            circle(12.0, 12.0, 8.8),
            "M12 6.8a5.2 5.2 0 0 1 4.5 2.6",
            "M12 17.2a5.2 5.2 0 0 1-4.5-2.6",
            circle(12.0, 12.0, 2.5)
        ),
        ow(
            "VinylDisc1",
            1.5f,
            circle(12.0, 12.0, 8.8),
            "M12 7a5 5 0 0 1 4.3 2.5",
            circle(12.0, 12.0, 2.3)
        ),
        ow(
            "VinylDisc2",
            2.2f,
            circle(12.0, 12.0, 8.8),
            "M12 6.8a5.2 5.2 0 0 1 4.5 2.6",
            circle(12.0, 12.0, 2.5)
        )
    )
    val VinylDisc: ImageVector get() = pick(vinylDiscSets)

    /** 全屏（进入沉浸式黑胶播放页的入口图标，feather maximize；线宽跟随套系） */
    private val maximizeSets = listOf(
        o("Maximize0", "M8 3H5a2 2 0 0 0-2 2v3", "M16 3h3a2 2 0 0 1 2 2v3", "M8 21H5a2 2 0 0 1-2-2v-3", "M16 21h3a2 2 0 0 0 2-2v-3"),
        lazy { build("Maximize1", listOf("M8 3H5a2 2 0 0 0-2 2v3", "M16 3h3a2 2 0 0 1 2 2v3", "M8 21H5a2 2 0 0 1-2-2v-3", "M16 21h3a2 2 0 0 0 2-2v-3"), emptyList(), 1.4f) },
        lazy { build("Maximize2", listOf("M8 3H5a2 2 0 0 0-2 2v3", "M16 3h3a2 2 0 0 1 2 2v3", "M8 21H5a2 2 0 0 1-2-2v-3", "M16 21h3a2 2 0 0 0 2-2v-3"), emptyList(), 3.2f) }
    )
    val Maximize: ImageVector get() = pick(maximizeSets)

    /** 全屏页的「缩到浮窗」按钮：四角向内收，和 [Maximize] 是同一套线宽/圆角 */
    private val minimizeSets = listOf(
        o("Minimize0", "M9 3v5a1 1 0 0 1-1 1H3", "M15 3v5a1 1 0 0 0 1 1h5", "M9 21v-5a1 1 0 0 0-1-1H3", "M15 21v-5a1 1 0 0 1 1-1h5"),
        lazy { build("Minimize1", listOf("M9 3v5a1 1 0 0 1-1 1H3", "M15 3v5a1 1 0 0 0 1 1h5", "M9 21v-5a1 1 0 0 0-1-1H3", "M15 21v-5a1 1 0 0 1 1-1h5"), emptyList(), 1.4f) },
        lazy { build("Minimize2", listOf("M9 3v5a1 1 0 0 1-1 1H3", "M15 3v5a1 1 0 0 0 1 1h5", "M9 21v-5a1 1 0 0 0-1-1H3", "M15 21v-5a1 1 0 0 1 1-1h5"), emptyList(), 2.2f) }
    )
    val Minimize: ImageVector get() = pick(minimizeSets)

    val ArrowLeft by o("ArrowLeft", "M19 12H5", "M12 19l-7-7 7-7")

    /** 「回到顶部」按钮的箭头。实心向上，比 chevron 在小尺寸下更好认 */
    val ArrowUp by o("ArrowUp", "M12 20V6", "M6 12l6-6 6 6")

    /** 「定位到正在播放」按钮：准星（外圈 + 四向短线 + 中心点） */
    val Locate by o(
        "Locate",
        circle(12.0, 12.0, 7.0),
        dot(12.0, 12.0),
        "M12 2v3",
        "M12 19v3",
        "M2 12h3",
        "M19 12h3"
    )

    // ===== 原型覆盖：更多（三点） =====
    private val moreSets = listOf(
        f("MoreVertical0", circle(12.0, 5.0, 1.3), circle(12.0, 12.0, 1.3), circle(12.0, 19.0, 1.3)),
        lazy { build("MoreVertical1", listOf(circle(12.0, 5.0, 1.4), circle(12.0, 12.0, 1.4), circle(12.0, 19.0, 1.4)), emptyList(), 1.4f) },
        f("MoreVertical2", circle(12.0, 5.0, 1.6), circle(12.0, 12.0, 1.6), circle(12.0, 19.0, 1.6))
    )
    val MoreVertical: ImageVector get() = pick(moreSets)

    val Check by o("Check", "M20 6L9 17l-5-5")
    val Refresh by o(
        "Refresh",
        "M23 4v6h-6",
        "M1 20v-6h6",
        "M3.51 9a9 9 0 0 1 14.85-3.36L23 10",
        "M1 14l4.64 4.36A9 9 0 0 0 20.49 15"
    )

    /**
     * 云朵：在线音乐入口。
     *
     * 为什么不复用放大镜：搜索框已经占了一个放大镜（本地搜索），
     * 右上角再放一个，用户分不清哪个联网、哪个搜本地。
     */
    val Cloud by o(
        "Cloud",
        "M18 10h-1.26A8 8 0 1 0 9 20h9a5 5 0 0 0 0-10z"
    )
    val Trash by o(
        "Trash",
        "M3 6h18",
        "M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6",
        "M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"
    )
    val Plus by o("Plus", "M12 5v14", "M5 12h14")

    /** 下载：托盘 + 向下的箭头（feather download） */
    val Download by o(
        "Download",
        "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4",
        "M7 10l5 5 5-5",
        "M12 15V3"
    )
    val Waveform by o("Waveform", "M22 12h-4l-3 9L9 3l-3 9H2")
    val Image by o(
        "Image",
        "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z",
        circle(8.5, 8.5, 1.5),
        "M21 15l-5-5L5 21"
    )
    private val layersSets = listOf(
        ow("Layers0", 1.9f, "M12 3L3 8l9 5 9-5-9-5z", "M3 12l9 5 9-5", "M3 16l9 5 9-5"),
        ow("Layers1", 1.4f, "M12 4L4 9l8 5 8-5-8-5z", "M4 15l8 5 8-5"),
        lazy { build("Layers2", listOf("M3 13l9 5 9-5", "M3 17l9 5 9-5"),
            listOf("M12 2L2 7.5l10 5.5 10-5.5z"), 2.6f) }
    )
    val Layers: ImageVector get() = pick(layersSets)

    // ===== 原型覆盖：歌词（三行，原型是 4→15 / 4→20 / 4→12） =====
    private val lyricsSets = listOf(
        o("Lyrics0", "M4 7h11", "M4 12h16", "M4 17h8"),
        lazy {
            build("Lyrics1", listOf("M4 9h16", "M4 15h9"), emptyList(), 1.4f) // 只留两行，删掉短尾
        },
        lazy {
            build("Lyrics2", listOf("M4 6.4h11", "M4 12h16", "M4 17.6h8"), emptyList(), 3.2f) // 粗圆条
        }
    )
    val Lyrics: ImageVector get() = pick(lyricsSets)

    /** 齿轮（设置） */
    val Settings by o(
        "Settings",
        circle(12.0, 12.0, 3.0),
        "M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"
    )

    /** 律动柱 / 均衡器：四根高度不同的实心柱（列表/设置里的小标识，非原型 EQ） */
    val EqualizerBars by f(
        "EqualizerBars",
        "M2.5,14 h3.5 v7 h-3.5 z",
        "M8,9 h3.5 v12 h-3.5 z",
        "M13.5,4 h3.5 v17 h-3.5 z",
        "M19,12 h3.5 v9 h-3.5 z"
    )

    /** 睡眠定时器：精致圆润表盘 + 紧凑顶部按键，与其他顶栏图标保持同一视觉高度 */
    private val timerSets = listOf(
        ow("Timer0", 1.9f, circle(12.0, 13.0, 7.2), "M12 9.6v3.4l2.3 1.4", "M10 3.2h4", "M12 3.2v2.4"),
        ow("Timer1", 1.5f, circle(12.0, 13.0, 7.2), "M12 9.6v3.4l2.3 1.4", "M10 3.2h4"),
        ow("Timer2", 2.2f, circle(12.0, 13.0, 7.2), "M12 9.6v3.4l2.3 1.4", "M10 3.2h4", "M12 3.2v2.4")
    )
    val Timer: ImageVector get() = pick(timerSets)

    /** 均衡器滑杆（区别于 Equalizer 的实心柱，用细杆 + 旋钮表示"可调"） */
    val Sliders by o(
        "Sliders",
        "M4 21v-7",
        "M4 10V3",
        "M12 21v-9",
        "M12 8V3",
        "M20 21v-5",
        "M20 12V3",
        "M1 14h6",
        "M9 8h6",
        "M17 16h6"
    )

    /**
     * 麦克风（斜线）：人声/伴奏模式开关。
     *
     * feather「mic-off」：斜线压在话筒上表示"人声没了"—— 即伴奏模式。
     * 没有原型对应物，与 [Minimize] 一类保持单套几何、默认 2f 线宽。
     */
    val Mic by o(
        "Mic",
        "M1 1l22 22",
        "M9 9v3a3 3 0 0 0 5.12 2.12",
        "M15 9.34V5a3 3 0 0 0-5.94-.6",
        "M17 16.95A7 7 0 0 1 5 12v-2",
        "M14 12v1a3 3 0 0 1-3 3",
        "M12 19v4",
        "M8 23h8"
    )

    /** 文件夹 + 减号：把整个文件夹从曲库列表排除（文件不动） */
    val FolderMinus by o(
        "FolderMinus",
        "M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2v2",
        "M9 13h6"
    )

    // ===== 原型覆盖：均衡器（三竖线 + 三圆点） =====
    private val eqLineX = listOf(6.0, 12.0, 18.0)
    private val eqDotY = listOf(14.0, 9.0, 15.0)
    private val equalizerSets = listOf(
        ow("Equalizer0", 1.9f, "M6 5v14", "M12 5v14", "M18 5v14", circle(6.0, 14.0, 2.2), circle(12.0, 9.0, 2.2), circle(18.0, 15.0, 2.2)),
        lazy {
            build(
                "Equalizer1",
                listOf("M6 5v14", "M12 5v14", "M18 5v14", circle(6.0, 14.0, 1.6), circle(12.0, 9.0, 1.6), circle(18.0, 15.0, 1.6)),
                emptyList(),
                1.4f
            )
        },
        lazy {
            buildW(
                "Equalizer2",
                stroked = listOf(
                    "M6 5v14" to 2.4f, "M12 5v14" to 2.4f, "M18 5v14" to 2.4f
                ),
                filled = eqLineX.indices.map { circle(eqLineX[it], eqDotY[it], 2.6) }
            )
        }
    )
    val Equalizer: ImageVector get() = pick(equalizerSets)

    // ===== 原型覆盖：播放 / 暂停（列表、迷你条上的小按钮） =====
    private val playSets = listOf(
        f("Play0", "M6 3l14 9-14 9z"),
        lazy { build("Play1", listOf("M7 4.5l11 7.5-11 7.5z"), emptyList(), 1.4f) },
        f("Play2", "M6 3l14 9-14 9z")
    )
    val Play: ImageVector get() = pick(playSets)

    private val pauseSets = listOf(
        f("Pause0", "M6 4h4v16H6z", "M14 4h4v16h-4z"),
        lazy { build("Pause1", listOf("M8.5 5v14", "M15.5 5v14"), emptyList(), 1.4f) },
        f("Pause2", "M5.5 4h5v16h-5z", "M13.5 4h5v16h-5z")
    )
    val Pause: ImageVector get() = pick(pauseSets)

    // ===== 原型覆盖：上一首 / 下一首 =====
    private val prevSets = listOf(
        // 0 原型 solid：竖条 + 圆角实心三角
        f(
            "Prev0",
            "M6.2 6h0.1a1.2 1.2 0 0 1 1.2 1.2v9.6a1.2 1.2 0 0 1-1.2 1.2h-0.1a1.2 1.2 0 0 1-1.2-1.2V7.2a1.2 1.2 0 0 1 1.2-1.2z",
            "M17.2 6.4c1.1-.7 2.5.1 2.5 1.4v8.4c0 1.3-1.4 2.1-2.5 1.4l-6.8-4.2c-1-.6-1-2.2 0-2.8l6.8-4.2z"
        ),
        // 1 原型 line：竖线 + 空心三角
        lazy { build("Prev1", listOf("M6 6.5v11", "M17.5 6.5L9.5 12l8 5.5V6.5z"), emptyList(), 1.6f) },
        // 2 原型 double：两块实心三角
        f(
            "Prev2",
            "M11.2 7.2c.9-.6 2.1 0 2.1 1.1v7.4c0 1.1-1.2 1.7-2.1 1.1l-5.4-3.7c-.8-.5-.8-1.7 0-2.2l5.4-3.7z",
            "M18.4 7.2c.9-.6 2.1 0 2.1 1.1v7.4c0 1.1-1.2 1.7-2.1 1.1l-5.4-3.7c-.8-.5-.8-1.7 0-2.2l5.4-3.7z"
        )
    )
    val Prev: ImageVector get() = pick(prevSets)

    private val nextSets = listOf(
        f(
            "Next0",
            "M16.4 6h0.1a1.2 1.2 0 0 1 1.2 1.2v9.6a1.2 1.2 0 0 1-1.2 1.2h-0.1a1.2 1.2 0 0 1-1.2-1.2V7.2a1.2 1.2 0 0 1 1.2-1.2z",
            "M6.8 6.4c-1.1-.7-2.5.1-2.5 1.4v8.4c0 1.3 1.4 2.1 2.5 1.4l6.8-4.2c1-.6 1-2.2 0-2.8L6.8 6.4z"
        ),
        lazy { build("Next1", listOf("M18 6.5v11", "M6.5 6.5l8 5.5-8 5.5V6.5z"), emptyList(), 1.6f) },
        f(
            "Next2",
            "M12.8 7.2c-.9-.6-2.1 0-2.1 1.1v7.4c0 1.1 1.2 1.7 2.1 1.1l5.4-3.7c.8-.5.8-1.7 0-2.2l-5.4-3.7z",
            "M5.6 7.2c-.9-.6-2.1 0-2.1 1.1v7.4c0 1.1 1.2 1.7 2.1 1.1l5.4-3.7c.8-.5.8-1.7 0-2.2L5.6 7.2z"
        )
    )
    val Next: ImageVector get() = pick(nextSets)

    // ===== 原型覆盖：随机 / 循环 / 单曲循环 =====
    private val shuffleSets = listOf(
        ow(
            "Shuffle0",
            1.9f,
            "M4 17c4.5 0 5.5-10 10.5-10h5",
            "M17 4.5L19.8 7 17 9.5",
            "M4 7c2.4 0 3.6 2 4.6 3.6",
            "M11.6 13.6c1 1.6 2.1 3.4 4.4 3.4h3.5",
            "M17 14.5L19.8 17 17 19.5"
        ),
        // 极简：两条直线交叉，端头小箭头
        lazy {
            build(
                "Shuffle1",
                listOf(
                    "M4 5L21 19",
                    "M18 16.4L21 19l-3.2.6",
                    "M4 19L21 5",
                    "M18 7.6L21 5l-3.2-.6"
                ),
                emptyList(),
                1.4f
            )
        },
        // 实心：粗 S 曲线 + 实心箭头块
        lazy {
            buildW(
                "Shuffle2",
                stroked = listOf("M4 17c4.5 0 5.5-10 10.5-10h6" to 2.8f, "M4 7c2.4 0 3.6 2 4.6 3.6" to 2.8f, "M11.6 13.6c1 1.6 2.1 3.4 4.4 3.4h5" to 2.8f),
                filled = listOf(
                    "M17.4 3.2l4.1 3.8-4.1 3.8z",
                    "M17.4 13.2l4.1 3.8-4.1 3.8z"
                )
            )
        }
    )
    val Shuffle: ImageVector get() = pick(shuffleSets)

    private val repeatSets = listOf(
        ow(
            "Repeat0",
            1.9f,
            "M17 4.5L19.5 7 17 9.5",
            "M4.5 11v-1a3 3 0 0 1 3-3h12",
            "M7 19.5L4.5 17 7 14.5",
            "M19.5 13v1a3 3 0 0 1-3 3h-12"
        ),
        // 极简：一个闭合圆角环 + 单箭头
        lazy {
            build(
                "Repeat1",
                listOf(
                    "M7 6h10a3 3 0 0 1 3 3v6a3 3 0 0 1-3 3H7a3 3 0 0 1-3-3V9a3 3 0 0 1 3-3z",
                    "M13.8 3.2l3.2 3-3.2 3"
                ),
                emptyList(),
                1.4f
            )
        },
        lazy {
            buildW(
                "Repeat2",
                stroked = listOf(
                    "M4.5 11v-1a3 3 0 0 1 3-3h12" to 2.8f,
                    "M19.5 13v1a3 3 0 0 1-3 3h-12" to 2.8f
                ),
                filled = listOf(
                    "M16.4 2.8l4.2 3.7-4.2 3.7z",
                    "M7.6 21.2l-4.2-3.7 4.2-3.7z"
                )
            )
        }
    )
    val Repeat: ImageVector get() = pick(repeatSets)

    private val repeatOneSets = listOf(
        ow(
            "RepeatOne0",
            1.9f,
            "M17 4.5L19.5 7 17 9.5",
            "M4.5 11v-1a3 3 0 0 1 3-3h12",
            "M7 19.5L4.5 17 7 14.5",
            "M19.5 13v1a3 3 0 0 1-3 3h-12",
            "M11 10.5l1.5-1v5"
        ),
        lazy {
            build(
                "RepeatOne1",
                listOf(
                    "M7 6h10a3 3 0 0 1 3 3v6a3 3 0 0 1-3 3H7a3 3 0 0 1-3-3V9a3 3 0 0 1 3-3z",
                    "M13.8 3.2l3.2 3-3.2 3",
                    "M10.6 10.6l1.4-1v5"
                ),
                emptyList(),
                1.4f
            )
        },
        lazy {
            buildW(
                "RepeatOne2",
                stroked = listOf(
                    "M4.5 11v-1a3 3 0 0 1 3-3h12" to 2.8f,
                    "M19.5 13v1a3 3 0 0 1-3 3h-12" to 2.8f,
                    "M11 10.5l1.5-1v5" to 2.2f
                ),
                filled = listOf(
                    "M16.4 2.8l4.2 3.7-4.2 3.7z",
                    "M7.6 21.2l-4.2-3.7 4.2-3.7z"
                )
            )
        }
    )
    val RepeatOne: ImageVector get() = pick(repeatOneSets)

    /**
     * 播放队列：三横线 + 音符（原型：横线长 4.5→14.5 / 12 / 10，音符 x16.5）。
     *
     * 原型整只是描边（圆点音符也是空心圈），所以 0 号套系也全描边；
     * 2 号套系把符头和横线加重，视觉上和「实心圆润」一致。
     */
    private val queueSets = listOf(
        ow(
            "Queue0",
            1.9f,
            "M4.5 6.5h10",
            "M4.5 12h7.5",
            "M4.5 17.5h5.5",
            "M16.5 16V7.5l3.5 1.8",
            circle(14.5, 16.5, 2.0)
        ),
        lazy {
            build(
                "Queue1",
                listOf("M4 6h9", "M4 12h6.5", "M4 18h5", "M16 16V8l4 2", circle(14.2, 16.6, 1.8)),
                emptyList(),
                1.4f
            )
        },
        lazy {
            buildW(
                "Queue2",
                stroked = listOf(
                    "M4.5 6.2h10" to 2.8f,
                    "M4.5 12h7.5" to 2.8f,
                    "M4.5 17.8h5.5" to 2.8f,
                    "M18 8.4v8.2" to 2.4f
                ),
                filled = listOf(
                    circle(15.2, 16.8, 2.7),
                    "M18 7.4h3.6v2.4h-3.6z"
                )
            )
        }
    )
    val Queue: ImageVector get() = pick(queueSets)

    // ===== 原型覆盖：音量（喇叭 + 两段弧） =====
    private val volSpeaker = "M11 5L6 9H3v6h3l5 4V5z"
    private val volumeSets = listOf(
        ow("Volume0", 1.9f, volSpeaker, "M15.5 8.5a5 5 0 0 1 0 7", "M18.5 6a9 9 0 0 1 0 12"),
        lazy { build("Volume1", listOf("M11 5L6 9H3v6h3l5 4V5z", "M15.5 8.5a5 5 0 0 1 0 7"), emptyList(), 1.4f) },
        lazy { build("Volume2", listOf(volSpeaker, "M15.5 8.5a5 5 0 0 1 0 7", "M18.5 6a9 9 0 0 1 0 12"), emptyList(), 2.6f) }
    )
    val Volume: ImageVector get() = pick(volumeSets)

    private val volumeLowSets = listOf(
        ow("VolumeLow0", 1.9f, volSpeaker, "M15.5 8.5a5 5 0 0 1 0 7"),
        lazy { build("VolumeLow1", listOf(volSpeaker, "M15.5 8.5a5 5 0 0 1 0 7"), emptyList(), 1.4f) },
        lazy { build("VolumeLow2", listOf(volSpeaker, "M15.5 8.5a5 5 0 0 1 0 7"), emptyList(), 2.6f) }
    )
    val VolumeLow: ImageVector get() = pick(volumeLowSets)

    private val volumeMuteSets = listOf(
        ow("VolumeMute0", 1.9f, volSpeaker, "M23 9l-6 6", "M17 9l6 6"),
        lazy { build("VolumeMute1", listOf(volSpeaker, "M23 9l-6 6", "M17 9l6 6"), emptyList(), 1.4f) },
        lazy { build("VolumeMute2", listOf(volSpeaker, "M23 9l-6 6", "M17 9l6 6"), emptyList(), 2.6f) }
    )
    val VolumeMute: ImageVector get() = pick(volumeMuteSets)
}
