package com.sonance.player.data

import androidx.compose.ui.graphics.Color

/**
 * 「跟手」过渡里，这一刻该画哪一份色卡。
 *
 * @param cur  `floor(pos)` 那一页的色卡（可能为空 —— 色卡是异步算出来的）
 * @param next 下一页的色卡
 * @param t    在两者之间的位置：0 = 完全是 [cur]，1 = 完全是 [next]
 *
 * 三条规则，每一条都对应一种用户看得见的「闪」：
 *
 * 1. **两侧都有** → 在**颜色本身**上插值。这就是「跟手」的全部内容。
 *    ⚠️ 不能改成"相邻两页各画一层、按 (1-t) / t 交叉淡化"：两层各自半透明地
 *    叠起来之后，中点的总覆盖度低于两端（底色从中间透出来），背景会亮一下。
 *    实测（1080×2400，给测试曲注入高饱和封面）端点 G 通道 56 / 93，
 *    中间几帧只有 64~75，比端点连线低 8~10 个单位 —— 肉眼就是"闪一下"。
 * 2. **next 还没算出来** → 沿用 [cur] 的颜色。**不能返回空**：返回空等于那一层
 *    什么都不画，切到一半背景会整个淡掉，等色卡到了再突变回来。
 * 3. **cur 还没算出来** → 只有在真的滑向 next（t > 0）时才用 next；落定时
 *    （t = 0）返回空，交给调用方「沿用上一次真正画出来的颜色」兜底 ——
 *    否则会在落定那一刻显示成**下一首**的颜色。
 */
fun blendGlowColors(cur: List<Color>, next: List<Color>, t: Float): List<Color> {
    if (cur.isEmpty()) {
        if (next.isEmpty()) return emptyList()
        return if (t > 0f) next else emptyList()
    }
    if (next.isEmpty()) return cur
    if (t <= 0f) return cur
    if (t >= 1f) return next
    // 色卡要么是空、要么是三色，尺寸不一致说明上游改了形状 —— 这时按更近的一侧取，
    // 不去猜怎么插值（插错了会在过渡中途跳成别的颜色）。
    if (cur.size != next.size) return if (t < 0.5f) cur else next
    return cur.mapIndexed { i, c -> mix(c, next[i], t) }
}

/**
 * 逐分量线性插值。
 *
 * 刻意**不用** `androidx.compose.ui.graphics.lerp` —— 实测它在 sRGB 上不是逐分量
 * 线性的（两端纯红 / 纯蓝各取一半，红分量给的是 0.549 而不是 0.5），
 * 而这里要的正是"严格的中点"：只要中点不是端点真正的中点，过渡中途就会出现
 * 亮度差，也就是用户看到的那一下闪。逐分量插值同时也是「渐变」模式
 * （`animateColorAsState` 走 `Color.VectorConverter`）的行为，两种过渡因此一致。
 */
private fun mix(a: Color, b: Color, t: Float): Color = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = a.alpha + (b.alpha - a.alpha) * t,
    colorSpace = a.colorSpace
)
