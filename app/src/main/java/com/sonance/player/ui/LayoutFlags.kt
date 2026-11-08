package com.sonance.player.ui

import android.content.res.Configuration

/**
 * 「手机横屏细长屏」这一形态的**唯一判据**。
 *
 * 状态栏隐藏（`Shell`）和播放页横屏布局参数（`FullPlayer`）必须共用它，
 * 否则两处条件会各自漂移 —— 表现是「有的分辨率藏了状态栏却没调布局，
 * 有的调了布局却还留着状态栏」，很难查。
 *
 * 判据刻意**不硬编码像素分辨率**（2780×1264 这种值在改显示大小、分屏、
 * 换机时都会变），而是用三个与旋转/密度无关的量描述同一个状态：
 *
 * - `smallestScreenWidthDp < 600`：设备类型，不随旋转变化。横屏后
 *   `screenWidthDp` 会涨到 ~1000，用 [resolveLayout] 的档位会把手机
 *   误认成平板；最小宽度才是 Android 区分手机/平板的标准量。
 * - `ORIENTATION_LANDSCAPE`：只在横屏生效，竖屏原样。
 * - 宽 ≥ 高 × 1.8：细长手机横屏（20:9 转过来约 2.2:1）。
 *   平板横屏通常 16:10（1.6:1），不会命中。
 *
 * 三条同时成立，正好对应用户截图那个「手机横屏、页面被状态栏往下挤」的场景。
 */
internal fun isCompactLandscapePhone(config: Configuration): Boolean {
    val landscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE
    val phone = config.smallestScreenWidthDp < 600
    val longAndNarrow = config.screenWidthDp >= config.screenHeightDp * 1.8f
    return landscape && phone && longAndNarrow
}
