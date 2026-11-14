package com.sonance.player.widget

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** 小组件卡片明暗外观策略 */
enum class WidgetAppearance(val prefValue: String, val label: String) {
    /** 深色烟熏琉璃（默认：高对比度、不挑壁纸） */
    DARK("DARK", "深色烟熏琉璃"),

    /** 浅色磨砂白（适配浅色壁纸或偏好通透白卡） */
    LIGHT("LIGHT", "浅色磨砂白"),

    /** 跟随应用当前皮肤的明暗属性 */
    FOLLOW_SKIN("FOLLOW_SKIN", "跟随应用皮肤");

    companion object {
        fun fromPref(value: String?): WidgetAppearance =
            entries.firstOrNull { it.prefValue.equals(value, ignoreCase = true) } ?: DARK
    }
}

/**
 * STYLE A「克制莫兰迪取色」算法计算出的完整配色板。
 *
 * 所有计算使用纯 Kotlin RGB ↔ HSL 数学，不依赖 Android 框架状态，可在 JVM 上直接单元测试：
 * 1. 黑白 / 水墨封面（原始饱和度 < 0.08）：彻底锁死 `S = 0`，避免底色泛红或偏绿；
 * 2. 彩色封面（含高艳荧光/柠檬黄等极端专辑）：
 *    - 深色烟熏模式：`S` 强压缩至 `0%~20%`，亮度锁定 `10.5%~17%`，只留隐约氛围冷暖；
 *    - 浅色磨砂模式：`S` 强压缩至 `0%~9%`，亮度锁定 `88%~94%`，保持高级白磨砂质感；
 * 3. 让专辑封面本身成为小组件卡片中唯一的视觉焦点，绝不喧宾夺主。
 */
data class WidgetAuraPalette(
    val isLight: Boolean,
    val isGrayscale: Boolean,
    val hueDeg: Float,
    val appliedSat: Float,
    val bgTopLeft: Int,
    val bgMid: Int,
    val bgBottomRight: Int,
    val glowTopLeft: Int,
    val glowBottomRight: Int,
    val topSheen: Int,
    val borderColor: Int,
    val textPrimary: Int,
    val textSecondary: Int,
    val textDim: Int,
    val pillBg: Int,
    val pillBorder: Int,
    val playBtnBg: Int,
    val playBtnFg: Int,
    val accentBar: Int,
    val trackBg: Int,
    val trackFill: Int,
    val placeholderBgTop: Int,
    val placeholderBgBottom: Int,
    val placeholderStroke: Int,
    val placeholderIconTint: Int,
    val coverStroke: Int
) {
    companion object {
        internal const val GRAY_LOCK_THRESHOLD = 0.08f
        internal const val MAX_DARK_SAT = 0.20f
        internal const val MAX_LIGHT_SAT = 0.09f

        /**
         * 从小尺寸采样像素阵列中提取专辑代表色（RGB）。
         *
         * 策略：
         * - 第一遍严格跳过接近纯黑（`< 22`）与纯白（`> 236`）的像素；
         * - 用**像素面积均值的饱和度**判断是否属于黑白水墨封面（避免画面角落一颗极小的红色印章把整张水墨画判成彩色）；
         * - 若属于彩色封面，用饱和度加权的**圆周色相均值**取代表色相，避免互补色直接做 RGB 平均后发灰跑偏。
         */
        fun extractRawColorFromPixels(pixels: IntArray): Int? {
            if (pixels.isEmpty()) return null
            val strict = samplePass(pixels, strict = true)
            if (strict != null) return strict
            return samplePass(pixels, strict = false)
        }

        private fun samplePass(pixels: IntArray, strict: Boolean): Int? {
            var count = 0
            var satSum = 0.0
            var lumSum = 0.0
            var sinSum = 0.0
            var cosSum = 0.0
            var weightSum = 0.0

            val hsl = FloatArray(3)
            for (c in pixels) {
                val a = (c ushr 24) and 0xFF
                if (a < 180) continue
                val r = (c ushr 16) and 0xFF
                val g = (c ushr 8) and 0xFF
                val b = c and 0xFF
                if (strict) {
                    if (max(r, max(g, b)) < 22) continue
                    if (min(r, min(g, b)) > 236) continue
                }
                rgbToHsl(r, g, b, hsl)
                val hRad = Math.toRadians(hsl[0].toDouble())
                val s = hsl[1].toDouble()
                val l = hsl[2].toDouble()
                val w = if (strict) 0.35 + s * 1.65 else 1.0

                count++
                satSum += s
                lumSum += l
                sinSum += sin(hRad) * w
                cosSum += cos(hRad) * w
                weightSum += w
            }
            if (count == 0 || weightSum <= 0.0) return null

            val avgSat = (satSum / count).toFloat().coerceIn(0f, 1f)
            val avgLum = (lumSum / count).toFloat().coerceIn(0.18f, 0.78f)
            val hue = if (avgSat < GRAY_LOCK_THRESHOLD) {
                0f
            } else {
                var deg = Math.toDegrees(atan2(sinSum / weightSum, cosSum / weightSum)).toFloat()
                if (deg < 0f) deg += 360f
                deg
            }
            return hslToArgb(hue, avgSat, avgLum, 0xFF)
        }

        /** 根据提取到的专辑代表色（为空时采用克制中性冷灰）生成完整 STYLE A 配色板 */
        fun fromRgb(rawRgb: Int?, isLight: Boolean): WidgetAuraPalette {
            val h: Float
            val rawSat: Float
            if (rawRgb == null) {
                h = 222f
                rawSat = 0f
            } else {
                val hsl = FloatArray(3)
                rgbToHsl((rawRgb ushr 16) and 0xFF, (rawRgb ushr 8) and 0xFF, rawRgb and 0xFF, hsl)
                h = hsl[0]
                rawSat = hsl[1]
            }

            val isGray = rawSat < GRAY_LOCK_THRESHOLD
            val hShift = (h + 14f) % 360f

            if (!isLight) {
                val darkSat = if (isGray) 0f else (rawSat * 0.22f).coerceAtMost(MAX_DARK_SAT)
                val glowSat = if (isGray) 0f else (rawSat * 0.38f).coerceAtMost(0.34f)
                return WidgetAuraPalette(
                    isLight = false,
                    isGrayscale = isGray,
                    hueDeg = if (isGray) 0f else h,
                    appliedSat = darkSat,
                    bgTopLeft = hslToArgb(h, darkSat, 0.17f, 232),
                    bgMid = hslToArgb(h, (darkSat - 0.03f).coerceAtLeast(0f), 0.135f, 238),
                    bgBottomRight = hslToArgb(hShift, darkSat, 0.105f, 245),
                    glowTopLeft = if (isGray) 0x10FFFFFF else hslToArgb(h, glowSat, 0.48f, 44),
                    glowBottomRight = if (isGray) 0x00000000 else hslToArgb(hShift, glowSat * 0.7f, 0.42f, 22),
                    topSheen = 0x26FFFFFF,
                    borderColor = 0x26FFFFFF,
                    textPrimary = 0xF5FFFFFF.toInt(),
                    textSecondary = 0xABFFFFFF.toInt(),
                    textDim = 0x6EFFFFFF,
                    pillBg = 0x1DFFFFFF,
                    pillBorder = 0x22FFFFFF,
                    playBtnBg = 0xF2FFFFFF.toInt(),
                    playBtnFg = if (isGray) 0xFF141720.toInt() else hslToArgb(h, min(0.32f, darkSat + 0.10f), 0.18f, 255),
                    accentBar = if (isGray) 0xA6FFFFFF.toInt() else hslToArgb(h, min(0.48f, darkSat + 0.24f), 0.68f, 255),
                    trackBg = 0x30FFFFFF,
                    trackFill = 0xEBFFFFFF.toInt(),
                    placeholderBgTop = hslToArgb(h, if (isGray) 0f else darkSat * 0.7f, 0.24f, 255),
                    placeholderBgBottom = hslToArgb(hShift, if (isGray) 0f else darkSat * 0.7f, 0.16f, 255),
                    placeholderStroke = 0x28FFFFFF,
                    placeholderIconTint = 0xBAFFFFFF.toInt(),
                    coverStroke = 0x26FFFFFF
                )
            } else {
                val lightSat = if (isGray) 0f else (rawSat * 0.11f).coerceAtMost(MAX_LIGHT_SAT)
                val glowSat = if (isGray) 0f else (rawSat * 0.28f).coerceAtMost(0.24f)
                return WidgetAuraPalette(
                    isLight = true,
                    isGrayscale = isGray,
                    hueDeg = if (isGray) 0f else h,
                    appliedSat = lightSat,
                    bgTopLeft = hslToArgb(h, lightSat, 0.94f, 236),
                    bgMid = hslToArgb(h, lightSat, 0.91f, 234),
                    bgBottomRight = hslToArgb(hShift, lightSat, 0.88f, 240),
                    glowTopLeft = if (isGray) 0x18FFFFFF else hslToArgb(h, glowSat, 0.62f, 26),
                    glowBottomRight = if (isGray) 0x00000000 else hslToArgb(hShift, glowSat * 0.7f, 0.56f, 14),
                    topSheen = 0xDDFFFFFF.toInt(),
                    borderColor = 0xCCFFFFFF.toInt(),
                    textPrimary = 0xFF151822.toInt(),
                    textSecondary = 0x9E151822.toInt(),
                    textDim = 0x62151822,
                    pillBg = 0x13151822,
                    pillBorder = 0x18151822,
                    playBtnBg = 0xFF171A24.toInt(),
                    playBtnFg = 0xFFFFFFFF.toInt(),
                    accentBar = if (isGray) 0x88151822.toInt() else hslToArgb(h, min(0.45f, lightSat + 0.26f), 0.42f, 255),
                    trackBg = 0x22151822,
                    trackFill = 0xFF171A24.toInt(),
                    placeholderBgTop = hslToArgb(h, lightSat, 0.92f, 255),
                    placeholderBgBottom = hslToArgb(hShift, lightSat, 0.85f, 255),
                    placeholderStroke = 0x1E151822,
                    placeholderIconTint = 0x8A151822.toInt(),
                    coverStroke = 0x1A151822
                )
            }
        }

        internal fun rgbToHsl(r: Int, g: Int, b: Int, out: FloatArray) {
            val rf = r.coerceIn(0, 255) / 255f
            val gf = g.coerceIn(0, 255) / 255f
            val bf = b.coerceIn(0, 255) / 255f
            val max = max(rf, max(gf, bf))
            val min = min(rf, min(gf, bf))
            val d = max - min
            val l = (max + min) * 0.5f
            val h: Float
            val s: Float
            if (d < 1e-5f) {
                h = 0f
                s = 0f
            } else {
                s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
                val rawH = when {
                    abs(max - rf) < 1e-5f -> ((gf - bf) / d + (if (gf < bf) 6f else 0f)) * 60f
                    abs(max - gf) < 1e-5f -> ((bf - rf) / d + 2f) * 60f
                    else -> ((rf - gf) / d + 4f) * 60f
                }
                h = ((rawH % 360f) + 360f) % 360f
            }
            out[0] = h
            out[1] = s.coerceIn(0f, 1f)
            out[2] = l.coerceIn(0f, 1f)
        }

        internal fun hslToArgb(hDeg: Float, sat: Float, lum: Float, alpha: Int = 255): Int {
            val a = alpha.coerceIn(0, 255)
            val s = sat.coerceIn(0f, 1f)
            val l = lum.coerceIn(0f, 1f)
            val r: Int
            val g: Int
            val b: Int
            if (s < 1e-4f) {
                val v = (l * 255f).roundToInt().coerceIn(0, 255)
                r = v
                g = v
                b = v
            } else {
                val h = (((hDeg % 360f) + 360f) % 360f) / 360f
                val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
                val p = 2f * l - q
                r = (hueToRgb(p, q, h + 1f / 3f) * 255f).roundToInt().coerceIn(0, 255)
                g = (hueToRgb(p, q, h) * 255f).roundToInt().coerceIn(0, 255)
                b = (hueToRgb(p, q, h - 1f / 3f) * 255f).roundToInt().coerceIn(0, 255)
            }
            return (a shl 24) or (r shl 16) or (g shl 8) or b
        }

        private fun hueToRgb(p: Float, q: Float, tRaw: Float): Float {
            var t = tRaw
            if (t < 0f) t += 1f
            if (t > 1f) t -= 1f
            return when {
                t < 1f / 6f -> p + (q - p) * 6f * t
                t < 0.5f -> q
                t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
                else -> p
            }
        }
    }
}
