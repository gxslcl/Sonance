package com.sonance.player.ui.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * 自绘线性图标集（24x24 视口，2px 描边，与原型 HTML 的 feather 风格一致）。
 * 不依赖 material-icons-extended，减小体积并保证风格统一。
 */
object SonanceIcons {

    private fun circle(cx: Double, cy: Double, r: Double): String =
        "M${cx - r},${cy} a$r,$r 0 1,0 ${r * 2},0 a$r,$r 0 1,0 ${-r * 2},0"

    private fun dot(cx: Double, cy: Double): String = "M$cx,$cy h0.01"

    private fun build(name: String, filled: Boolean, paths: List<String>): ImageVector {
        val builder = ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        )
        paths.forEach { d ->
            if (filled) {
                builder.addPath(
                    pathData = PathParser().parsePathString(d).toNodes(),
                    fill = SolidColor(Color.White)
                )
            } else {
                builder.addPath(
                    pathData = PathParser().parsePathString(d).toNodes(),
                    stroke = SolidColor(Color.White),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round
                )
            }
        }
        return builder.build()
    }

    private fun o(name: String, vararg d: String) = lazy { build(name, false, d.toList()) }
    private fun f(name: String, vararg d: String) = lazy { build(name, true, d.toList()) }

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
    val Heart by o(
        "Heart",
        "M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z"
    )
    val HeartFilled by f(
        "HeartFilled",
        "M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z"
    )
    val Palette by o("Palette", "M12 2.69l5.66 5.66a8 8 0 1 1-11.31 0z")
    val Info by o("Info", circle(12.0, 12.0, 10.0), "M12 16v-4", "M12 8h.01")

    // ===== 操作 =====
    val Search by o("Search", circle(11.0, 11.0, 8.0), "M21 21l-4.35-4.35")
    val ListView by o("ListView", "M8 6h13", "M8 12h13", "M8 18h13", dot(3.0, 6.0), dot(3.0, 12.0), dot(3.0, 18.0))
    val GridView by o(
        "GridView",
        "M3 3h7v7H3z", "M14 3h7v7h-7z", "M14 14h7v7h-7z", "M3 14h7v7H3z"
    )
    val Sort by o("Sort", "M3 6h11", "M3 12h8", "M3 18h5", "M17 8v11", "M14 16l3 3 3-3")
    val Close by o("Close", "M18 6L6 18", "M6 6l12 12")
    val ChevronDown by o("ChevronDown", "M6 9l6 6 6-6")
    val ArrowLeft by o("ArrowLeft", "M19 12H5", "M12 19l-7-7 7-7")
    val MoreVertical by o("MoreVertical", dot(12.0, 12.0), dot(12.0, 5.0), dot(12.0, 19.0))
    val Check by o("Check", "M20 6L9 17l-5-5")
    val Refresh by o(
        "Refresh",
        "M23 4v6h-6",
        "M1 20v-6h6",
        "M3.51 9a9 9 0 0 1 14.85-3.36L23 10",
        "M1 14l4.64 4.36A9 9 0 0 0 20.49 15"
    )
    val Trash by o(
        "Trash",
        "M3 6h18",
        "M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6",
        "M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"
    )
    val Plus by o("Plus", "M12 5v14", "M5 12h14")
    val Waveform by o("Waveform", "M22 12h-4l-3 9L9 3l-3 9H2")
    val Image by o(
        "Image",
        "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z",
        circle(8.5, 8.5, 1.5),
        "M21 15l-5-5L5 21"
    )
    val Layers by o("Layers", "M12 2L2 7l10 5 10-5-10-5z", "M2 17l10 5 10-5", "M2 12l10 5 10-5")

    /** 歌词：几行长短不一的文本 */
    val Lyrics by o("Lyrics", "M4 7h11", "M4 12h16", "M4 17h8")

    /** 齿轮（设置） */
    val Settings by o(
        "Settings",
        circle(12.0, 12.0, 3.0),
        "M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"
    )

    /** 律动柱 / 均衡器：四根高度不同的实心柱 */
    val Equalizer by f(
        "Equalizer",
        "M2.5,14 h3.5 v7 h-3.5 z",
        "M8,9 h3.5 v12 h-3.5 z",
        "M13.5,4 h3.5 v17 h-3.5 z",
        "M19,12 h3.5 v9 h-3.5 z"
    )

    /** 睡眠定时器：带指针的圆 + 顶上两根"发条" */
    val Timer by o(
        "Timer",
        circle(12.0, 13.5, 8.0),
        "M12 9.5v4l2.5 1.5",
        "M9 2h6",
        "M12 2v3.5"
    )

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

    // ===== 播放 =====
    val Play by f("Play", "M6 3l14 9-14 9z")
    val Pause by f("Pause", "M6 4h4v16H6z", "M14 4h4v16h-4z")
    val Prev by f("Prev", "M19 20L9 12l10-8z", "M5 5h2v14H5z")
    val Next by f("Next", "M5 4l10 8-10 8z", "M17 5h2v14h-2z")
    val Shuffle by o(
        "Shuffle",
        "M16 3h5v5",
        "M4 20L21 3",
        "M21 16v5h-5",
        "M15 15l6 6",
        "M4 4l5 5"
    )
    val Repeat by o(
        "Repeat",
        "M17 1l4 4-4 4",
        "M3 11V9a4 4 0 0 1 4-4h14",
        "M7 23l-4-4 4-4",
        "M21 13v2a4 4 0 0 1-4 4H3"
    )
    val RepeatOne by o(
        "RepeatOne",
        "M17 1l4 4-4 4",
        "M3 11V9a4 4 0 0 1 4-4h14",
        "M7 23l-4-4 4-4",
        "M21 13v2a4 4 0 0 1-4 4H3",
        "M11.4 11.2l1.6-1.1V16"
    )
    val Queue by o("Queue", "M3 6h12", "M3 11h12", "M3 16h7", "M17 12.5l5 3.2-5 3.2z")
    val Volume by o(
        "Volume",
        "M11 5L6 9H2v6h4l5 4z",
        "M19.07 4.93a10 10 0 0 1 0 14.14",
        "M15.54 8.46a5 5 0 0 1 0 7.07"
    )
    val VolumeLow by o("VolumeLow", "M11 5L6 9H2v6h4l5 4z", "M15.54 8.46a5 5 0 0 1 0 7.07")
    val VolumeMute by o("VolumeMute", "M11 5L6 9H2v6h4l5 4z", "M23 9l-6 6", "M17 9l6 6")
}
