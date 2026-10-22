package com.sonance.player.data

/**
 * 随机播放的排序。
 *
 * 三个目标，按顺序重要：
 *
 * 1. **一轮之内不重复**。做法是"洗出一整条队列然后顺着放完"（洗牌袋），
 *    而不是每首都现场随机挑 —— 后者才是"老是那几首"的根源：
 *    真随机在小样本上必然 clustering，听到第 20 首就撞车了。
 * 2. **刚听过的排后面**。开启随机的那一刻，最近听过的那批歌被压到队尾，
 *    于是接下来听到的都是这段时间没听过的。
 * 3. **不要把当前这首挪位置**。播放页的上下滑是分页器，页号 = 队列下标；
 *    把它挪到队首会让分页器从第 12 页一路滚回第 0 页，看着就是"闪一下"。
 */
object ShuffleOrder {

    /**
     * @param queue     待洗的队列（不会被修改）
     * @param keepIndex 保持不动的下标，即当前正在播的那首
     * @param recent    最近听过的歌曲 id（新的在前）。只取前 [window] 个由调用方决定。
     */
    fun build(queue: List<Song>, keepIndex: Int, recent: Set<Long>): List<Song> {
        val n = queue.size
        if (n <= 2) return queue.shuffled()

        val keep = keepIndex.coerceIn(0, n - 1)
        val anchor = queue[keep]

        // 分两堆：没听过的先放，最近听过的压到队尾
        val fresh = ArrayList<Song>(n - 1)
        val heard = ArrayList<Song>()
        for (i in queue.indices) {
            if (i == keep) continue
            val s = queue[i]
            if (s.id in recent) heard += s else fresh += s
        }
        fresh.shuffle()
        heard.shuffle()

        val tail = spread(fresh + heard)

        // 把当前这首放回**原来的下标**：队列长度不变，所以下标一定还落得进去。
        return ArrayList<Song>(n).apply {
            addAll(tail.take(keep))
            add(anchor)
            addAll(tail.drop(keep))
        }
    }

    /**
     * 打散挨在一起的同专辑歌曲。
     *
     * 同一张专辑的歌在曲库里本来就是连着的，洗完之后挨着的概率不低；
     * 连续三首同一个专辑，听感上就是"又在放那几首"。
     * 只做一趟、最多试两次 —— 这是锦上添花，为它遍历整条队列不值得。
     */
    private fun spread(list: List<Song>): List<Song> {
        if (list.size < 3) return list
        val out = list.toMutableList()
        for (i in 1 until out.lastIndex) {
            val prev = out[i - 1].albumId
            if (prev < 0 || out[i].albumId != prev) continue
            val span = out.size - 1 - i
            if (span <= 0) continue
            repeat(2) {
                val j = i + 1 + (Math.random() * span).toInt()
                if (j <= out.lastIndex && out[j].albumId != prev) {
                    val tmp = out[i]
                    out[i] = out[j]
                    out[j] = tmp
                    return@repeat
                }
            }
        }
        return out
    }
}
