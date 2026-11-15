package com.sonance.player.data

import kotlin.math.ln
import kotlin.random.Random

/**
 * 随机播放的排序。
 *
 * 目标按顺序重要：
 * 1. **一轮之内不重复**。整条队列洗成完整序列顺次播放，整轮播完再在回绕点自动重洗下一轮，
 *    避免伪随机连撞同一批歌。
 * 2. **未听 / 少听优先，刚听过的按新旧梯次压后**。以 [keepIndex] 为起点按环形顺延填入：
 *    `keepIndex` 之后紧接没听过的与低播放次数曲目（逆频加权洗牌），
 *    最近听过的按「最久未听 → 刚刚听过」落在环形队列末尾（即 `keepIndex` 之前）。
 * 3. **打散同歌手 / 同专辑连续簇**。对有/无有效 `albumId` 的本地与在线歌曲一视同仁，
 *    同时避免 A→B→A 同一歌手反复切换。
 * 4. **不移动当前播放下标**。当前歌曲留在 [keepIndex]，防止分页器页码瞬移引起闪屏。
 */
object ShuffleOrder {

    internal data class ShuffleMeta(
        val id: Long,
        val artist: String = "",
        val album: String = "",
        val albumId: Long = -1L
    )

    /**
     * @param queue       待洗的队列（不会被修改）
     * @param keepIndex   保持不动的下标，即当前正在播的那首
     * @param recent      最近听过的歌曲 id 集合（落在本集合内的曲目压到本轮后段）
     * @param recentOrder 最近播放顺序（下标 0 表示最新播放，用于决定已听曲目在本轮后段的先后）
     * @param playCounts  每首歌历史播放次数（用于「未听 / 少听优先」的逆频加权抽样）
     * @param rng         随机数发生器（默认 `Random.Default`，单测可注入固定种子）
     */
    fun build(
        queue: List<Song>,
        keepIndex: Int,
        recent: Set<Long>,
        recentOrder: List<Long> = emptyList(),
        playCounts: Map<Long, Int> = emptyMap(),
        rng: Random = Random.Default
    ): List<Song> {
        if (queue.size <= 1) return queue
        val meta = queue.map { ShuffleMeta(it.id, it.artist, it.album, it.albumId) }
        val indices = planIndices(meta, keepIndex, recent, recentOrder, playCounts, rng)
        return indices.map { queue[it] }
    }

    internal fun planIndices(
        items: List<ShuffleMeta>,
        keepIndex: Int,
        recent: Set<Long>,
        recentOrder: List<Long> = emptyList(),
        playCounts: Map<Long, Int> = emptyMap(),
        rng: Random = Random.Default
    ): IntArray {
        val n = items.size
        if (n <= 1) return IntArray(n) { it }

        val keep = keepIndex.coerceIn(0, n - 1)
        val anchor = items[keep]

        val recentRank = if (recentOrder.isEmpty()) {
            emptyMap()
        } else {
            HashMap<Long, Int>(recentOrder.size).apply {
                recentOrder.forEachIndexed { idx, id -> putIfAbsent(id, idx) }
            }
        }

        // 分两堆：不在最近窗口内的先播（按播放次数逆频加权洗牌），在最近窗口内的按最久未听→刚听过压后
        val fresh = ArrayList<Int>(n - 1)
        val heard = ArrayList<Int>()
        for (i in items.indices) {
            if (i == keep) continue
            if (items[i].id in recent) heard += i else fresh += i
        }

        val freshOrdered = weightedShuffle(fresh, items, playCounts, rng)
        val heardOrdered = orderHeardByRecency(heard, items, recentRank, recentOrder.size, rng)

        // 分层内部各自做歌手/专辑去聚集，保证 fresh 整体仍在 heard 之前，同时避免紧跟当前歌撞车
        val freshSpread = spreadBand(freshOrdered, items, lead = anchor, rng = rng)
        val heardLead = freshSpread.lastOrNull()?.let { items[it] } ?: anchor
        val heardSpread = spreadBand(heardOrdered, items, lead = heardLead, rng = rng)
        val tail = ArrayList<Int>(freshSpread.size + heardSpread.size).apply {
            addAll(freshSpread)
            addAll(heardSpread)
        }

        // 从 keep 之后顺时针环形填入：无论 keep 在队列前/中/后哪一格，
        // 紧跟在 anchor 后面的第一首永远是 freshly-picked（freshSpread.firstOrNull()），
        // 刚听过的歌则安稳落回本轮最末尾。
        val result = IntArray(n)
        result[keep] = keep
        for (offset in tail.indices) {
            result[(keep + 1 + offset) % n] = tail[offset]
        }
        return result
    }

    /**
     * Efraimidis–Spirakis 无放回加权随机抽样：
     * 播放次数越多权重越低，未听/少听的歌更靠前，同时保留真随机的多样性。
     */
    private fun weightedShuffle(
        indices: List<Int>,
        items: List<ShuffleMeta>,
        playCounts: Map<Long, Int>,
        rng: Random
    ): List<Int> {
        if (indices.size <= 1) return indices
        if (playCounts.isEmpty()) return indices.shuffled(rng)
        return indices
            .map { idx ->
                val count = (playCounts[items[idx].id] ?: 0).coerceAtLeast(0)
                val weight = 1.0 / (1.0 + count * 0.65)
                val key = -ln(rng.nextDouble().coerceAtLeast(1e-9)) / weight
                idx to key
            }
            .sortedBy { it.second }
            .map { it.first }
    }

    /**
     * 最近听过的曲目按「最久未听（高 rank）在前，刚刚听过（低 rank）在后」排序，
     * 加轻微局部扰动避免多轮洗牌后退化成死板倒序。
     */
    private fun orderHeardByRecency(
        indices: List<Int>,
        items: List<ShuffleMeta>,
        recentRank: Map<Long, Int>,
        fallbackRank: Int,
        rng: Random
    ): List<Int> {
        if (indices.size <= 1) return indices
        if (recentRank.isEmpty()) return indices.shuffled(rng)
        val jitter = (indices.size * 0.25).coerceAtLeast(2.0)
        return indices
            .map { idx ->
                val rank = recentRank[items[idx].id] ?: fallbackRank
                val key = -rank.toDouble() + rng.nextDouble() * jitter
                idx to key
            }
            .sortedBy { it.second }
            .map { it.first }
    }

    private fun artistKey(s: ShuffleMeta): String {
        val raw = s.artist.trim().lowercase()
        return if (raw.isEmpty() || raw == "<unknown>" || raw == "未知歌手") "" else raw
    }

    private fun albumKey(s: ShuffleMeta): String {
        val raw = s.album.trim().lowercase()
        return if (raw.isEmpty() || raw == "<unknown>" || raw == "未知专辑") "" else raw
    }

    private fun sameArtist(a: ShuffleMeta, b: ShuffleMeta): Boolean {
        val ka = artistKey(a)
        return ka.isNotEmpty() && ka == artistKey(b)
    }

    private fun sameAlbum(a: ShuffleMeta, b: ShuffleMeta): Boolean {
        if (a.albumId >= 0L && a.albumId == b.albumId) return true
        val ka = albumKey(a)
        return ka.isNotEmpty() && ka == albumKey(b)
    }

    private fun conflicts(a: ShuffleMeta, b: ShuffleMeta): Boolean = sameArtist(a, b) || sameAlbum(a, b)

    /**
     * 单层内打散相邻的同歌手 / 同专辑曲目，兼容本地（albumId >= 0）与在线/未知专辑（albumId < 0）。
     * 不仅检查与前 1 首不能同歌手/同专辑，也尽量避免与前 2 首构成 A→B→A 歌手往返。
     */
    private fun spreadBand(
        indices: List<Int>,
        items: List<ShuffleMeta>,
        lead: ShuffleMeta,
        rng: Random
    ): List<Int> {
        if (indices.size < 2) return indices
        val out = indices.toMutableList()
        for (i in 0 until out.lastIndex) {
            val curItem = items[out[i]]
            val prev = if (i == 0) lead else items[out[i - 1]]
            val prev2 = when {
                i >= 2 -> items[out[i - 2]]
                i == 1 -> lead
                else -> null
            }
            val hitDirect = conflicts(curItem, prev)
            val hitPingPong = prev2 != null && sameArtist(curItem, prev2)
            if (!hitDirect && !hitPingPong) continue

            val candidates = out.size - 1 - i
            val tries = minOf(16, candidates)
            var fallbackSwap = -1
            var cleanSwap = -1
            for (t in 0 until tries) {
                val j = i + 1 + rng.nextInt(candidates)
                val candItem = items[out[j]]
                if (conflicts(candItem, prev)) continue
                if (prev2 != null && sameArtist(candItem, prev2)) continue
                val destPrev = items[out[j - 1]]
                val destNext = out.getOrNull(j + 1)?.let { items[it] }
                val destClean = !conflicts(curItem, destPrev) && (destNext == null || !conflicts(curItem, destNext))
                if (destClean) {
                    cleanSwap = j
                    break
                } else if (fallbackSwap < 0) {
                    fallbackSwap = j
                }
            }
            val swapWith = if (cleanSwap >= 0) cleanSwap else fallbackSwap
            if (swapWith >= 0) {
                val tmp = out[i]
                out[i] = out[swapWith]
                out[swapWith] = tmp
            }
        }
        return out
    }
}
