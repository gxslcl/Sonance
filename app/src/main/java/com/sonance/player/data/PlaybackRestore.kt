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
        val (kept, mapped) = alignIndices(savedIds, savedIndex) { it in libraryIds }
        if (kept.isEmpty()) return null
        val queue = kept.map { savedIds[it] }
        // 存档里那首**还在** → 用它对齐后的真实位置（这时可能和原下标不同，
        // 因为前面被丢掉的歌会让它前移）。它自己也没了 → 保留旧的"夹紧"行为，
        // 至少不会因为下标越界而崩。
        val index = if (mapped >= 0) mapped else savedIndex.coerceIn(0, queue.lastIndex)
        return queue to index
    }

    /**
     * 按条件过滤一个 id 序列，**同时把下标一起对齐**。
     *
     * ## 为什么必须一起做
     *
     * 凡是"过滤掉一些 id、但下游仍然按下标取歌"的地方，只过滤不重映射下标，
     * 就会静默串歌：显示的是 `queue[index]`，而 index 还是过滤前的那个数 ——
     * 少掉一条，后面全体前移一位。这类错误不报异常、不打日志，
     * 表面现象只是"播放条是这首、进去却是另一首"，极难定位。
     *
     * 冷启动恢复现场、播放页的队列、队列面板都吃这条规则，所以抽在这里共用。
     *
     * @param ids     原始 id 序列（顺序即队列顺序）
     * @param index   关心它在过滤后的位置的那个下标（通常是"当前播到第几首"）
     * @param keep    这个 id 是否保留
     * @return first = 保留项在**原始序列里的下标**（升序，可直接拿去取歌）；
     *         second = `ids[index]` 在保留后的下标，**它自己没被保留时为 -1**
     */
    fun alignIndices(ids: List<Long>, index: Int, keep: (Long) -> Boolean): Pair<List<Int>, Int> {
        val kept = ArrayList<Int>(ids.size)
        var mapped = -1
        ids.forEachIndexed { i, id ->
            if (!keep(id)) return@forEachIndexed
            if (i == index) mapped = kept.size
            kept += i
        }
        return kept to mapped
    }
}
