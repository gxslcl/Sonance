package com.sonance.player.data.lx

import com.sonance.player.data.OnlineSource
import com.sonance.player.data.Prefs
import com.sonance.player.data.RemotePlaylist
import com.sonance.player.data.RemotePlaylistPage
import com.sonance.player.data.RemoteSearchPage
import com.sonance.player.data.RemoteTrack
import java.util.concurrent.ConcurrentHashMap

/**
 * 将「洛雪自定义脚本 + 单个平台曲库」适配为 [OnlineSource]。
 *
 * 播放队列 / 下载器 / 恢复播放 / 徽标系统只需按 `song.source`（如 `"lx:kw"`）查到此实例，
 * 就能复用统一播放与边听边存管线；同时此适配器仅放进 [com.sonance.player.data.OnlineSources] 的
 * 按 id 解析表，不混入内置音源切换页。
 */
class LxPlatformSource(
    private val repo: LxRepository,
    private val runtime: LxRuntime,
    val platform: LxPlatform
) : OnlineSource {

    override val id: String = platform.sourceId
    override val label: String = "洛雪 · ${platform.displayName}"

    private val prefs = Prefs(repo.appContext)
    private val lastRepoErrors = ConcurrentHashMap<String, String>()

    override suspend fun search(query: String, limit: Int, offset: Int): RemoteSearchPage? =
        LxCatalogProvider.searchSongs(platform.id, query, limit, offset)?.also { page ->
            repo.rememberTrackHints(platform.id, page.tracks)
        }

    override suspend fun searchPlaylists(
        query: String,
        limit: Int,
        offset: Int
    ): RemotePlaylistPage? = LxCatalogProvider.searchSheets(platform.id, query, limit, offset)?.let { page ->
        RemotePlaylistPage(
            playlists = page.sheets.map { it.toRemotePlaylist() },
            total = page.total
        )
    }

    override suspend fun recommendedPlaylists(limit: Int): List<RemotePlaylist>? =
        LxCatalogProvider.fetchRecommendedSheets(platform.id, limit)?.map { it.toRemotePlaylist() }

    override suspend fun playlistTracks(playlistId: String): List<RemoteTrack>? =
        LxCatalogProvider.fetchSheetTracks(platform.id, playlistId)?.also { tracks ->
            repo.rememberTrackHints(platform.id, tracks)
        }

    override fun streamUrl(remoteId: String): String =
        runtime.cachedStreamUrl(platform.id, remoteId)

    override fun hasFreshStream(remoteId: String): Boolean =
        runtime.hasFreshStream(platform.id, remoteId)

    override suspend fun resolveStream(remoteId: String): String? =
        resolveStream(remoteId, expectedDurationMs = 0L)

    override suspend fun resolveStream(remoteId: String, expectedDurationMs: Long): String? {
        val active = repo.activeScript()
        if (active == null) {
            lastRepoErrors[remoteId] = "请先在设置中导入洛雪音源脚本"
            return null
        }
        val raw = repo.readRawScript(active.id)
        if (raw.isNullOrBlank()) {
            lastRepoErrors[remoteId] = "找不到洛雪脚本文件，请重新导入"
            return null
        }
        lastRepoErrors.remove(remoteId)
        val hint = repo.getTrackHint(platform.id, remoteId)
        val res = runtime.resolveMusicUrl(
            scriptId = active.id,
            rawScript = raw,
            meta = active.meta,
            platform = platform.id,
            remoteId = remoteId,
            title = hint?.title.orEmpty(),
            artist = hint?.artist.orEmpty(),
            album = hint?.album.orEmpty(),
            durationMs = hint?.durationMs?.takeIf { it > 0L } ?: expectedDurationMs,
            requestedQuality = prefs.lxQuality,
            supportedQualitys = active.platforms[platform.id]?.qualitys.orEmpty()
        )
        return res.url
    }

    override fun lastStreamError(remoteId: String): String? =
        lastRepoErrors[remoteId] ?: runtime.lastStreamError(platform.id, remoteId)

    override fun streamQualityLabel(remoteId: String): String? =
        runtime.streamQualityLabel(platform.id, remoteId)

    override fun streamFormat(remoteId: String): String? =
        runtime.streamFormat(platform.id, remoteId)

    private fun LxSheetSummary.toRemotePlaylist(): RemotePlaylist = RemotePlaylist(
        remoteId = id,
        name = name,
        coverUrl = coverUrl,
        trackCount = trackCount,
        creatorName = creatorName
    )
}
