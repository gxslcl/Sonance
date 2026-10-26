package com.sonance.player.data.netease

import android.util.Log
import com.sonance.player.data.NeteaseSource
import com.sonance.player.data.OnlineCover

/**
 * 一次在线补全的结果：转换时用它给成品改名、写标签、嵌封面。
 *
 * [cover] 是已下载的封面原始字节（JPEG/PNG），可能为 null（网络失败 /
 * 平台没给封面）——为 null 时成品退回「无嵌入封面」，不阻塞转换。
 */
internal class NcMeta(
    val title: String,
    val artist: String,
    val album: String,
    val cover: ByteArray?
)

/**
 * 网易云缓存转换的**在线元数据补全**。
 *
 * NMSF 缓存文件名第一段是歌曲 ID（见 [NeteaseCacheParser.songIdOf]），
 * 拿它走网易云公开详情接口即可精确取回歌名 / 歌手 / 专辑 / 封面地址 ——
 * 与在线音源 [NeteaseSource] 共用同一套请求代码，不登录、不碰账号。
 *
 * 设计约束：
 * - **任何失败都返回 null**，转换流程照旧（成品退回哈希文件名）——
 *   补全是增强，不能让一次网络抖动把整个转换搞挂；
 * - 封面单独下载，与元数据请求解耦：详情成功而封面失败时，
 *   名称照样补全，只是没有嵌入图。
 */
internal object NcMetaFetcher {

    private const val TAG = "SonanceNc"

    /** 封面用多大：800px 与在线封面库 [OnlineCover.FULL_PX] 同档，写进文件足够 */
    private const val COVER_PX = OnlineCover.FULL_PX

    /**
     * 按歌曲 ID 补全元数据。
     *
     * @param songId 网易云歌曲 ID（来自 NMSF 文件名）
     * @return 元数据；查无此歌 / 标题为空 / 请求失败返回 null
     */
    suspend fun fetch(songId: String): NcMeta? {
        val detail = runCatching { NeteaseSource.songDetail(songId) }
            .onFailure { Log.w(TAG, "缓存元数据查询失败 id=$songId: ${it.message}") }
            .getOrNull()
            ?: return null
        val title = detail.title.trim()
        if (title.isEmpty()) return null

        val cover = detail.coverUrl?.takeIf { it.isNotBlank() }?.let { url ->
            runCatching {
                OnlineCover.downloadBytes(NeteaseSource.thumbUrl(url, COVER_PX))
            }.onFailure { Log.w(TAG, "缓存封面下载失败 id=$songId: ${it.message}") }
                .getOrNull()
        }
        return NcMeta(
            title = title,
            artist = detail.artist.trim(),
            album = detail.album.trim(),
            cover = cover?.takeIf { it.isNotEmpty() }
        )
    }

    /**
     * 输出文件名的 stem：`歌名` 或 `歌名 - 歌手`。
     *
     * 文件系统禁用字符在这里清掉（Windows 与 SAF 的交集最严格），
     * 空白折叠到单空格；歌手为空时只留歌名。
     */
    fun outputStem(meta: NcMeta): String {
        val bad = Regex("""[\\/:*?"<>|]""")
        val t = meta.title.replace(bad, " ").replace(Regex("""\s+"""), " ").trim()
        val a = meta.artist.replace(bad, " ").replace(Regex("""\s+"""), " ").trim()
        return if (a.isEmpty()) t else "$t - $a"
    }
}
