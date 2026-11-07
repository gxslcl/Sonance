package com.sonance.player.data.plugin

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 已安装的内容源插件。
 *
 * 脚本落在 `filesDir/plugins/<hash>.js`，清单落在 `filesDir/plugins.json`。
 * 只存脚本本身和必要的元信息，**不内置任何插件、不提供获取渠道** ——
 * 用户从本地文件或粘贴的文本自行导入，导入时明确提示第三方脚本风险。
 */
class PluginRepository(context: Context) {

    private val app = context.applicationContext
    private val dir = File(app.filesDir, "plugins")
    private val index = File(app.filesDir, "plugins.json")
    val runtime = PluginRuntime(app)

    fun list(): List<PluginRecord> = runCatching {
        if (!index.exists()) return emptyList()
        val arr = JSONArray(index.readText())
        (0 until arr.length()).mapNotNull { i ->
            runCatching { PluginCodec.recordFromJson(arr.getJSONObject(i)) }.getOrNull()
        }.filter { File(dir, it.file).exists() }
    }.getOrDefault(emptyList())

    fun enabled(): List<PluginRecord> = list().filter { it.enabled }

    fun script(record: PluginRecord): String = runCatching {
        File(dir, record.file).readText()
    }.getOrDefault("")

    /**
     * 把已启用的插件注入 [OnlineSources]，让设置页的音源 chips 自动多出几项。
     * 应用启动时调一次，插件增删/启停后再调一次。
     */
    fun refreshSources() {
        val sources = enabled().map { record ->
            PluginOnlineSource(runtime, record, script(record))
        }
        com.sonance.player.data.OnlineSources.replacePlugins(sources)
    }

    /**
     * 导入一个插件脚本。
     *
     * 同名 platform 视为同一插件的新版本：覆盖脚本、保留启用状态。
     * 静态 peek 出的 platform 只用于去重和列表展示，真正生效的元信息以
     * [PluginRuntime.load] 执行后回传的为准。
     */
    suspend fun install(script: String): Result<PluginRecord> = withContext(Dispatchers.IO) {
        runCatching {
            require(script.length in 20..(2 * 1024 * 1024)) { "脚本内容不合法（过大或为空）" }
            require(script.contains("module.exports")) { "不是 CommonJS 插件脚本" }
            // 先真正加载一次，确认能执行、能读到 platform 与必需方法
            val runtimeMeta = runtime.load("__probe__", script).getOrThrow()
            require(runtimeMeta.platform.isNotBlank()) { "插件缺少 platform 字段，或脚本执行失败" }
            // 必需方法（search / getMediaSource）不在这里强校验：
            // 插件可以按需实现，真正调用到缺失方法时给明确报错即可。

            dir.mkdirs()
            val file = "${Integer.toHexString(runtimeMeta.platform.hashCode())}.js"
            File(dir, file).writeText(script)

            val old = list()
            val previous = old.firstOrNull { it.meta.platform == runtimeMeta.platform }
            val record = PluginRecord(
                meta = runtimeMeta,
                file = file,
                enabled = previous?.enabled ?: true,
            )
            val next = old.filterNot { it.meta.platform == runtimeMeta.platform } + record
            save(next)
            record
        }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        save(list().map { if (it.meta.id == id) it.copy(enabled = enabled) else it })
    }

    fun remove(id: String) {
        val target = list().firstOrNull { it.meta.id == id } ?: return
        runCatching { File(dir, target.file).delete() }
        save(list().filterNot { it.meta.id == id })
    }

    private fun save(records: List<PluginRecord>) {
        runCatching {
            val arr = JSONArray()
            records.forEach { arr.put(PluginCodec.recordToJson(it)) }
            val tmp = File(app.filesDir, "plugins.json.tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(index)) {
                index.writeText(arr.toString())
                tmp.delete()
            }
        }
    }

    companion object {
        @Volatile
        private var instance: PluginRepository? = null

        fun get(context: Context): PluginRepository =
            instance ?: synchronized(this) {
                instance ?: PluginRepository(context.applicationContext).also { instance = it }
            }
    }
}
