package com.sonance.player.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 「全部歌曲」右侧字母索引的数据层。
 *
 * 设计上只有一条硬规矩：**排序键和分组键必须是同一个串**。
 * 原来 `MediaRepository.sortSongs` 对文本字段用 `String.compareTo(ignoreCase)`，
 * 那是 Unicode 码点序 —— 中文歌根本不按拼音排。若字母条另按拼音首字母分桶，
 * 跳到 Z 之后下面还会躺着 A 开头的中文歌，索引就成了假的。
 *
 * 现在每首歌在扫描完成后由 [IndexKey] 预先算出四个文本字段的归一化键
 * （汉字转拼音全拼、去声调、小写、只留字母数字），排序用它比较，
 * 字母条取它的首字符分组，两边天然一致。全部在后台线程算一次并缓存，
 * 搜索输入时不重新做 ICU 转写。
 */
data class IndexKeys(
    val title: String,
    val artist: String,
    val album: String,
    val fileName: String
) {
    fun forField(field: SortField): String = when (field) {
        SortField.TITLE -> title
        SortField.ARTIST -> artist
        SortField.ALBUM -> album
        SortField.FILE_NAME -> fileName
        else -> ""
    }
}

object IndexKey {
    fun of(text: String): String = PinyinIndex.latinKey(text)

    /** 归一化键 → 索引字母。数字 / 符号 / 转不出的文字统一归 `#` */
    fun letterOf(key: String): Char {
        val c = key.firstOrNull() ?: return '#'
        return if (c in 'a'..'z') c.uppercaseChar() else '#'
    }

    fun keysOf(song: Song): IndexKeys = IndexKeys(
        title = of(song.title),
        artist = of(song.artist),
        album = of(song.album),
        fileName = of(song.displayName)
    )
}

/**
 * 只有「列表按某个文本字段有序」时字母索引才成立。
 * 时长 / 添加时间 / 播放次数 / 最近播放这四种排序下 A–Z 没有意义，
 * 那种情况下右侧仍显示原来的比例滑块。
 */
val INDEXABLE_SORT_FIELDS: Set<SortField> = setOf(
    SortField.TITLE,
    SortField.ARTIST,
    SortField.ALBUM,
    SortField.FILE_NAME
)

/** 固定 27 格：位置恒定，滑动映射才是线性的 —— 不要只显示有歌的字母 */
val RAIL_LETTERS: List<Char> = listOf('#') + ('A'..'Z').toList()

/**
 * 从**已排好序的列表**算出的分组表。
 *
 * @param firstIndex 有歌的字母 → 该组第一行的下标
 * @param letterOfItem 每一行属于哪个字母（给列表滚动时反查高亮用）
 */
data class LetterSections(
    val firstIndex: Map<Char, Int>,
    val letterOfItem: List<Char>,
    val counts: Map<Char, Int>
) {
    /** 点到空字母时吸附到最近的有歌字母，绝不出现「点了没反应」 */
    fun nearest(ch: Char): Int? {
        firstIndex[ch]?.let { return it }
        val i = RAIL_LETTERS.indexOf(ch)
        if (i < 0) return firstIndex.values.minOrNull()
        for (d in 1 until RAIL_LETTERS.size) {
            val before = RAIL_LETTERS.getOrNull(i - d)
            if (before != null) firstIndex[before]?.let { return it }
            val after = RAIL_LETTERS.getOrNull(i + d)
            if (after != null) firstIndex[after]?.let { return it }
        }
        return null
    }

    companion object {
        val EMPTY = LetterSections(emptyMap(), emptyList(), emptyMap())

        /** [keys] 顺序必须与列表顺序一致，且每个串来自 [IndexKeys.forField] */
        fun build(keys: List<String>): LetterSections {
            if (keys.isEmpty()) return EMPTY
            val first = LinkedHashMap<Char, Int>()
            val counts = LinkedHashMap<Char, Int>()
            val ofItem = ArrayList<Char>(keys.size)
            keys.forEachIndexed { i, key ->
                val ch = IndexKey.letterOf(key)
                ofItem.add(ch)
                if (!first.containsKey(ch)) first[ch] = i
                counts[ch] = (counts[ch] ?: 0) + 1
            }
            return LetterSections(first, ofItem, counts)
        }
    }
}

/**
 * 索引缓存格式版本。键结构变了就 +1，旧缓存解码失败自然退回重建。
 */
private const val INDEX_CACHE_VERSION = 1

/**
 * 把拼音搜索索引 + 归一化排序键序列化，与歌曲缓存（[encodeSongsToJson]）配套落盘。
 *
 * 为什么必须存：ICU 转写只能在后台算（千首歌要几百毫秒），冷启动读缓存那一刻
 * 排序键还是空的，列表先按码点序排；后台算完整表重排成拼音序 —— 用户看到的就是
 * "停在原地的十首歌突然换成另外十首"。索引随缓存一起同步加载，第一帧即最终顺序。
 */
fun encodeLibraryIndexJson(pinyin: Map<Long, String>, keys: Map<Long, IndexKeys>): String {
    val p = JSONObject()
    pinyin.forEach { (id, v) -> p.put(id.toString(), v) }
    val k = JSONObject()
    keys.forEach { (id, v) ->
        k.put(id.toString(), JSONArray().put(v.title).put(v.artist).put(v.album).put(v.fileName))
    }
    return JSONObject().put("v", INDEX_CACHE_VERSION).put("p", p).put("k", k).toString()
}

/** 解码失败（损坏 / 截断 / 版本不符 / 字段缺失）一律返回 null，调用方退回后台重建。 */
fun decodeLibraryIndexJson(json: String): Pair<Map<Long, String>, Map<Long, IndexKeys>>? = try {
    val root = JSONObject(json)
    if (root.optInt("v") != INDEX_CACHE_VERSION) {
        null
    } else {
        val p = root.getJSONObject("p")
        val k = root.getJSONObject("k")
        val pm = HashMap<Long, String>(p.length())
        for (id in p.keys()) pm[id.toLong()] = p.getString(id)
        val km = HashMap<Long, IndexKeys>(k.length())
        for (id in k.keys()) {
            val a = k.getJSONArray(id)
            km[id.toLong()] = IndexKeys(a.getString(0), a.getString(1), a.getString(2), a.getString(3))
        }
        pm to km
    }
} catch (e: Exception) {
    null
}

/**
 * 缓存的索引是否覆盖这批歌（两个索引都要全）。隐藏 / 排除只减歌，索引是超集也算覆盖；
 * 新增 / 改名的歌缺键时返回 false，触发一次重建并把新索引落盘，下次启动即恢复。
 */
fun libraryIndexCovers(ids: List<Long>, pinyin: Map<Long, String>, keys: Map<Long, IndexKeys>): Boolean =
    ids.all { it in pinyin && it in keys }
