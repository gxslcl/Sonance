package com.sonance.player.data

/**
 * 冷启动「恢复上次播放现场」用到的纯逻辑。
 *
 * 单独拎出来是为了**可测**：这里最容易出错的地方是「重新扫描之后歌曲 id 变了」——
 * 存档里的 id 可能整条都不在新曲库里，也可能只剩一半。
 * 这些分支在 JVM 上就能穷举，不需要真机。
 *
 * 刻意**只处理 id、不碰 [Song]**：`Song` 里带 `android.net.Uri`，
 * 而单测跑在 JVM 上、`Uri.parse` 是个返回 null 的桩（见 build.gradle 的
 * `isReturnDefaultValues`）。把 [Song] 挡在外面，这套规则才测得了。
 */
object PlaybackRestore {

    /**
     * 解析持久化的 id 列表（逗号分隔）。
     *
     * 对脏数据一律**跳过**而不是抛异常：这是从 SharedPreferences 读回来的东西，
     * 版本升级、手改配置都可能留下奇怪的内容，不值得为它崩一次。
     */
    fun parseIdList(raw: String): List<Long> {
        if (raw.isBlank()) return emptyList()
        return raw.split(',').mapNotNull { it.trim().toLongOrNull() }
    }

    /**
     * 从存档还原出「实际还能播的 id 队列」和「播到第几首」。
     *
     * @param savedIds    存档里的歌曲 id（顺序即队列顺序）
     * @param savedIndex  存档里播到第几首
     * @param libraryIds  当前曲库里还存在的 id
     * @return 队列与下标；存档已经不可用（一首都不在曲库里）时返回 null
     *
     * 两条规则：
     * - **只保留还在曲库里的**。重新扫描后 id 会变，旧 id 对应的歌已经找不回来了，
     *   硬塞进去只会得到一堆空壳。
     * - **下标要重新夹紧**。丢掉一些歌之后，原来的下标可能越界 ——
     *   不夹的话播放器就停在一首不存在的歌上。
     */
    fun resolveIds(
        savedIds: List<Long>,
        savedIndex: Int,
        libraryIds: Set<Long>
    ): Pair<List<Long>, Int>? {
        val queue = savedIds.filter { it in libraryIds }
        if (queue.isEmpty()) return null
        return queue to savedIndex.coerceIn(0, queue.lastIndex)
    }
}
