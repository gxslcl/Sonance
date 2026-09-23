package com.sonance.player.data

import androidx.compose.ui.graphics.Color

/**
 * 一份要画的颜色，外加它的**可信度**。
 *
 * @param colors    要画的三色取样
 * @param readiness 0~1。1 = 两侧素材都齐了、按满浓度画；
 *                  小于 1 = 有一侧的色卡还没算出来，画淡一点。
 *
 * ## 为什么需要 [readiness]
 *
 * 色卡是**异步**算出来的（封面解码完才提取得出）。只返回颜色的话，
 * 缺一侧时只能拿另一侧顶上，于是「色卡到位」那一帧颜色会从顶上的值
 * 直接跳到插值结果 —— 一帧之内完成，就是用户看到的「闪一下」。
 *
 * 带上可信度之后，缺一侧的表现变成**逐渐变淡**：每一帧都在变，
 * 没有「长时间不动然后突然跳」的那个台阶。
 */
data class GlowSample(val colors: List<Color>, val readiness: Float) {
    companion object {
        /** 两侧都没有色卡。可信度 0 = 这一层整个不画 */
        val EMPTY = GlowSample(emptyList(), 0f)
    }
}

/**
 * 色卡缺失时，强度最多淡到多少。
 *
 * 刻意**不淡到 0**：那等于背景整个掉回纯色，又是另一种闪
 * （「颜色消失 → 又出现」）。留一半多一点，观感是轻轻呼吸一下。
 */
private const val MISS_FLOOR = 0.55f

/**
 * 「跟手」过渡里，这一刻该画哪一份颜色、以及它有多可信。
 *
 * @param cur  `floor(pos)` 那一页的色卡（可能为空 —— 色卡是异步算出来的）
 * @param next 下一页的色卡
 * @param t    在两者之间的位置：0 = 完全是 [cur]，1 = 完全是 [next]
 *
 * 四条规则，每一条都对应一种用户看得见的「闪」：
 *
 * 1. **两侧都有** → 在**颜色本身**上插值，可信度 1。这就是「跟手」的全部内容。
 *    ⚠️ 不能改成"相邻两页各画一层、按 (1-t) / t 交叉淡化"：两层各自半透明地
 *    叠起来之后，中点的总覆盖度低于两端（底色从中间透出来），背景会亮一下。
 *    实测（1080×2400，给测试曲注入高饱和封面）端点 G 通道 56 / 93，
 *    中间几帧只有 64~75，比端点连线低 8~10 个单位 —— 肉眼就是"闪一下"。
 * 2. **next 还没算出来** → 用 [cur] 的颜色，但**可信度随 t 下降**。
 *    这条是本轮修掉的那一下闪的关键：以前是「颜色原样返回、强度不变」，
 *    于是从切歌开始到色卡到位之间，屏幕**完全不动**，然后一帧跳到
 *    `mix(cur, next, t)` —— 跳变量约 30% 的色差、一帧完成。
 *    现在改成随滑动逐渐变淡，每一帧都在变，没有那个台阶。
 *    （09-23 03:30 那个「不闪」的版本正是这个行为，当时靠的是
 *    「缺失的那层不画、另一层的 alpha 连续下降」。）
 * 3. **cur 还没算出来** → 只有在真的滑向 next（t > 0）时才用 next，
 *    且可信度就是 t（滑过去多少就显现多少）；落定时（t = 0）返回空，
 *    交给调用方「沿用上一次真正画出来的颜色」兜底 ——
 *    否则会在落定那一刻显示成**下一首**的颜色。
 * 4. **两侧都没有** → 空 + 可信度 0。
 */
fun blendGlowSample(cur: List<Color>, next: List<Color>, t: Float): GlowSample {
    if (cur.isEmpty()) {
        if (next.isEmpty()) return GlowSample.EMPTY
        return if (t > 0f) GlowSample(next, t) else GlowSample.EMPTY
    }
    if (next.isEmpty()) return GlowSample(cur, 1f - t * (1f - MISS_FLOOR))
    if (t <= 0f) return GlowSample(cur, 1f)
    if (t >= 1f) return GlowSample(next, 1f)
    // 色卡要么是空、要么是三色，尺寸不一致说明上游改了形状 —— 这时按更近的一侧取，
    // 不去猜怎么插值（插错了会在过渡中途跳成别的颜色）。
    if (cur.size != next.size) return GlowSample(if (t < 0.5f) cur else next, 1f)
    return GlowSample(cur.mapIndexed { i, c -> mix(c, next[i], t) }, 1f)
}

/**
 * 「跟手」过渡里，这一刻该画哪一份色卡。
 *
 * 保留它是为了不打断既有调用与单测；它等价于 [blendGlowSample] 丢掉可信度。
 * **新代码请用 [blendGlowSample]** —— 只用颜色是修不掉「色卡到位那一帧跳变」的。
 */
fun blendGlowColors(cur: List<Color>, next: List<Color>, t: Float): List<Color> =
    blendGlowSample(cur, next, t).colors

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
