package com.sonance.player.data

import android.content.Context
import com.sonance.player.R
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 繁体 → 简体的**单字**映射转换。
 *
 * 词表来自 OpenCC 的 `TSCharacters.txt`（`res/raw/t2s.txt`，2965 条）。
 * 只做单字替换、不做词组转换 —— OpenCC 完整流程是多阶段的（词组 → 单字），
 * 但歌词场景下单字表已经覆盖 99% 的用字，而完整词典要带几百 KB 数据，
 * 对一个"把歌词转成人眼舒服的字"的需求来说不划算。
 *
 * 首次调用时读一次资源并建表，之后常驻内存；读取失败则原样返回，不影响使用。
 */
object ChineseConverter {

    private val map = HashMap<Char, Char>(3200)
    private val ready = AtomicBoolean(false)

    private fun ensureLoaded(context: Context) {
        if (ready.get()) return
        synchronized(this) {
            if (ready.get()) return
            runCatching {
                context.applicationContext.resources
                    .openRawResource(R.raw.t2s)
                    .bufferedReader(Charsets.UTF_8)
                    .useLines { lines ->
                        for (line in lines) {
                            if (line.length < 2) continue
                            map[line[0]] = line[1]
                        }
                    }
            }.onFailure {
                android.util.Log.w("SonanceLyrics", "繁简词表加载失败: ${it.message}")
            }
            ready.set(true)
        }
    }

    /** 把文本里的繁体字替换成简体；没有对应条目的字原样保留 */
    fun toSimplified(context: Context, text: String): String {
        if (text.isEmpty()) return text
        ensureLoaded(context)
        if (map.isEmpty()) return text
        val out = StringBuilder(text.length)
        for (ch in text) out.append(map[ch] ?: ch)
        return out.toString()
    }

    /** 表里有多少条，设置页展示用 */
    fun entryCount(context: Context): Int {
        ensureLoaded(context)
        return map.size
    }
}
