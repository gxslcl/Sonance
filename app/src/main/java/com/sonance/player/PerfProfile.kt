package com.sonance.player

/**
 * legacy（Android 5 低配车机/老手机）性能档案的集中开关。
 *
 * 所有降档都从这里取值，modern 包走原值、行为零变化。
 * 原则：装饰性持续动画在 legacy 上静止或大幅降频，
 * 数据/解码类开销减半，交互反馈（按压、一次性过渡）保留。
 */
object PerfProfile {

    /** 是否低配档案（仅 legacy flavor） */
    val legacy: Boolean = BuildConfig.FLAVOR == "legacy"

    /** 装饰性动效发布步长：modern ~30fps；legacy ~10fps */
    val decorStepMs: Long = if (legacy) 100L else 33L

    /** 装饰性持续动画（呼吸/光晕公转/水滴形变）是否运行；legacy 静止 */
    val decorAnimEnabled: Boolean = !legacy

    /** 播放进度轮询间隔：legacy 1s，减少整树重组频率 */
    val positionPollMs: Long = if (legacy) 1000L else 500L

    /** 曲库 Pager 视口外预组合页数 */
    val pagerBeyondViewport: Int = if (legacy) 0 else 1

    /** 滚动预取节流间隔（ms） */
    val scrollPrefetchThrottleMs: Long = if (legacy) 150L else 0L

    /** 在线封面内存缓存条数 / 单边像素 */
    val onlineCoverCacheCount: Int = if (legacy) 40 else 240
    val onlineCoverCachePx: Int = if (legacy) 512 else 800

    /** 封面「大档」解码尺寸 */
    val artworkLargePx: Int = if (legacy) 512 else 768

    /** 封面墙 LRU 上限（MB）与纹理尺寸 */
    val wallLruMb: Int = if (legacy) 24 else 64
    val wallTextureSmall: Int = if (legacy) 128 else 256
    val wallTextureLarge: Int = if (legacy) 256 else 512

    /** 封面墙引擎帧率上限与陀螺仪视差/自动漂移 */
    val wallFpsCap: Int = if (legacy) 30 else 60
    val wallParallaxEnabled: Boolean = !legacy
    val wallDriftEnabled: Boolean = !legacy

    /** 可视化帧率上限 */
    val vizFpsCap: Int = if (legacy) 30 else 60
}