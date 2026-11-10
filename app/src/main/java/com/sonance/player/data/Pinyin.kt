package com.sonance.player.data

import android.icu.text.Transliterator
import java.text.Normalizer
import java.util.Locale

/**
 * 歌名 / 歌手 / 专辑的拼音索引。
 *
 * 用系统内置 ICU 的 `Han-Latin` 转换器（API 24 起可用），不打包任何拼音字典，
 * APK 体积增量为 0。转换结果去声调、小写、只留字母数字，输出
 * `"<全拼> <首字母>"`，例如「周杰伦」→ `"zhoujielun zjl"`。
 *
 * 只应在扫描完成后的后台线程批量构建；单条 [build] 内部对 Transliterator 加锁，
 * 因为 ICU Transliterator 实例不是线程安全的。
 */
object PinyinIndex {

    private val transliterator: Transliterator? by lazy {
        // 只用 Han-Latin：声调符号由下面的 NFD 去符号步骤处理，
        // 不用复合 ID，兼容性更好。
        runCatching { Transliterator.getInstance("Han-Latin") }.getOrNull()
    }

    private val toneMarks = Regex("\\p{Mn}+")

    fun build(text: String): String {
        if (text.isBlank()) return ""
        val latin = runCatching {
            val t = transliterator ?: return@runCatching text
            synchronized(t) { t.transliterate(text) }
        }.getOrDefault(text)

        val plain = Normalizer.normalize(latin, Normalizer.Form.NFD)
            .replace(toneMarks, "")
            .lowercase(Locale.ROOT)
            .filter { it in 'a'..'z' || it in '0'..'9' || it == ' ' }

        // 全拼去掉空格；首字母取每个音节的首字符。
        val full = plain.replace(" ", "")
        if (full.isEmpty()) return ""
        val initials = plain.split(' ')
            .mapNotNull { it.firstOrNull() }
            .joinToString("")
        return "$full $initials"
    }
}
