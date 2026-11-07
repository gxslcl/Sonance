package com.sonance.player.data.plugin

import org.json.JSONObject

/**
 * 一个内容源插件的元信息。
 *
 * 采用 MusicFree 插件协议的最小子集（CommonJS `module.exports`）：
 * 必需字段是 `platform`（来源唯一标识）和两个必需方法 `search` / `getMediaSource`。
 * 只解析元信息不执行脚本，导入时先把 `platform` 读出来用于去重和展示。
 */
data class PluginMeta(
    val platform: String,
    val version: String,
    val author: String,
    val srcUrl: String?,
    /** 插件声明的主键字段，用于把返回的媒体项映射成稳定的 remoteId */
    val primaryKey: List<String>,
) {
    /** 稳定 id：来自 platform，不随文件重命名变化 */
    val id: String get() = "plugin:$platform"
}

/** 一条已安装插件的记录（落盘在 filesDir/plugins.json） */
data class PluginRecord(
    val meta: PluginMeta,
    /** 脚本文件名（filesDir/plugins/<file>） */
    val file: String,
    val enabled: Boolean = true,
)

object PluginCodec {

    /**
     * 从脚本里提取 `module.exports = { ... }` 顶层的几个字符串/数组字段。
     *
     * 不做完整 JS 解析：导入时只需要元信息，真正的字段读取在 WebView 里执行完
     * 脚本后由 JS 回传（见 [PluginRuntime]）。这里的静态提取只用于「文件还没加载
     * 就要在列表里显示名字」的场景，读不到就给兜底值，不让导入失败。
     */
    fun peek(script: String): PluginMeta {
        val platform = findString(script, "platform") ?: "未命名插件"
        return PluginMeta(
            platform = platform,
            version = findString(script, "version") ?: "0.0.0",
            author = findString(script, "author") ?: "",
            srcUrl = findString(script, "srcUrl"),
            primaryKey = emptyList(),
        )
    }

    private fun findString(script: String, key: String): String? {
        val re = Regex("""\b$key\s*:\s*(["'`])(.*?)\1""")
        return re.find(script)?.groupValues?.get(2)?.takeIf { it.isNotBlank() }
    }

    fun recordToJson(r: PluginRecord): JSONObject = JSONObject().apply {
        put("platform", r.meta.platform)
        put("version", r.meta.version)
        put("author", r.meta.author)
        put("srcUrl", r.meta.srcUrl ?: "")
        put("primaryKey", r.meta.primaryKey.joinToString(","))
        put("file", r.file)
        put("enabled", r.enabled)
    }

    fun recordFromJson(o: JSONObject): PluginRecord = PluginRecord(
        meta = PluginMeta(
            platform = o.optString("platform"),
            version = o.optString("version", "0.0.0"),
            author = o.optString("author"),
            srcUrl = o.optString("srcUrl").takeIf { it.isNotBlank() },
            primaryKey = o.optString("primaryKey").split(",").filter { it.isNotBlank() },
        ),
        file = o.optString("file"),
        enabled = o.optBoolean("enabled", true),
    )
}
