package com.sonance.player.data.lx

import com.sonance.player.data.NeteaseSource
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
    private val neteaseFallbackIds = ConcurrentHashMap<String, String>()

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
        runtime.cachedStreamUrl(platform.id, remoteId).ifBlank {
            neteaseFallbackIds[remoteId]?.let { NeteaseSource.streamUrl(it) }.orEmpty()
        }

    override fun hasFreshStream(remoteId: String): Boolean =
        runtime.hasFreshStream(platform.id, remoteId) ||
            neteaseFallbackIds[remoteId]?.let { NeteaseSource.hasFreshStream(it) } == true

    override suspend fun resolveStream(remoteId: String): String? =
        resolveStream(remoteId, expectedDurationMs = 0L)

    override suspend fun resolveStream(remoteId: String, expectedDurationMs: Long): String? {
        val hint = repo.getTrackHint(platform.id, remoteId)
        val title = hint?.title.orEmpty()
        val artist = hint?.artist.orEmpty()
        val album = hint?.album.orEmpty()
        val durationMs = hint?.durationMs?.takeIf { it > 0L } ?: expectedDurationMs

        val active = repo.activeScript()
        if (active == null) {
            fallbackToNetease(remoteId, title, artist, durationMs)?.let { return it }
            lastRepoErrors[remoteId] = "请先在设置中导入洛雪音源脚本"
            return null
        }
        val raw = repo.readRawScript(active.id)
        if (raw.isNullOrBlank()) {
            fallbackToNetease(remoteId, title, artist, durationMs)?.let { return it }
            lastRepoErrors[remoteId] = "找不到洛雪脚本文件，请重新导入"
            return null
        }
        lastRepoErrors.remove(remoteId)
        val res = runtime.resolveMusicUrl(
            scriptId = active.id,
            rawScript = raw,
            meta = active.meta,
            platform = platform.id,
            remoteId = remoteId,
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            requestedQuality = prefs.lxQuality,
            supportedQualitys = active.platforms[platform.id]?.qualitys.orEmpty()
        )
        val url = res.url
        if (url != null) {
            neteaseFallbackIds.remove(remoteId)
            return url
        }

        // 无感多音源兜底：当前平台拿不到地址时，按脚本支持的其它平台逐个临时借一次。
        // 全程不改用户选中的音源（platform / prefs 都不动），也不弹任何切换提示；
        // 若所有洛雪脚本平台仍失败，再尝试用内置网易云音源按歌名+歌手兜底播放（适配外部导入的酷狗/QQ歌单）。
        if (prefs.lxAutoFallback && title.isNotBlank()) {
            val query = if (artist.isNotBlank()) "$title $artist" else title
            for (p in active.supportedCatalogPlatforms()) {
                if (p == platform) continue
                val page = LxCatalogProvider.searchSongs(p.id, query, limit = 10) ?: continue
                val match = pickLxFallbackMatch(page.tracks, title, artist, durationMs) ?: continue
                val alt = runtime.resolveMusicUrl(
                    scriptId = active.id,
                    rawScript = raw,
                    meta = active.meta,
                    platform = p.id,
                    remoteId = match.remoteId,
                    title = match.title,
                    artist = match.artist,
                    album = match.album,
                    durationMs = match.durationMs.takeIf { it > 0L } ?: durationMs,
                    requestedQuality = prefs.lxQuality,
                    supportedQualitys = active.platforms[p.id]?.qualitys.orEmpty()
                )
                val altUrl = alt.url
                if (altUrl != null) {
                    // 镜像到原曲缓存键：音质徽标 / 预取 / 恢复播放按原曲 id 也能命中
                    runtime.aliasResolvedStream(
                        fromPlatform = p.id,
                        fromRemoteId = match.remoteId,
                        toPlatform = platform.id,
                        toRemoteId = remoteId,
                        scriptId = active.id,
                        requestedQuality = prefs.lxQuality
                    )
                    neteaseFallbackIds.remove(remoteId)
                    return altUrl
                }
            }
            fallbackToNetease(remoteId, title, artist, durationMs)?.let { return it }
            lastRepoErrors[remoteId] = "所有音源都没有「$title」的播放地址"
        }
        return null
    }

    private suspend fun fallbackToNetease(
        remoteId: String,
        title: String,
        artist: String,
        durationMs: Long
    ): String? {
        if (title.isBlank()) return null
        val query = if (artist.isNotBlank()) "$title $artist" else title
        val page = NeteaseSource.search(query, limit = 10, offset = 0) ?: return null
        val match = pickLxFallbackMatch(page.tracks, title, artist, durationMs) ?: return null
        val resolved = NeteaseSource.resolveStream(match.remoteId, match.durationMs.takeIf { it > 0L } ?: durationMs)
            ?: return null
        neteaseFallbackIds[remoteId] = match.remoteId
        lastRepoErrors.remove(remoteId)
        return resolved
    }

    override fun lastStreamError(remoteId: String): String? =
        lastRepoErrors[remoteId]
            ?: neteaseFallbackIds[remoteId]?.let { NeteaseSource.lastStreamError(it) }
            ?: runtime.lastStreamError(platform.id, remoteId)

    override fun streamQualityLabel(remoteId: String): String? =
        runtime.streamQualityLabel(platform.id, remoteId)
            ?: neteaseFallbackIds[remoteId]?.let { NeteaseSource.streamQualityLabel(it) }

    override fun streamFormat(remoteId: String): String? =
        runtime.streamFormat(platform.id, remoteId)
            ?: neteaseFallbackIds[remoteId]?.let { NeteaseSource.streamFormat(it) }

    private fun LxSheetSummary.toRemotePlaylist(): RemotePlaylist = RemotePlaylist(
        remoteId = id,
        name = name,
        coverUrl = coverUrl,
        trackCount = trackCount,
        creatorName = creatorName
    )
}

/**
 * 从别的平台的搜索结果里挑出「同一首歌」（无感多音源兜底用）。
 *
 * 标题归一化后精确匹配优先；仅包含关系时必须歌手也对得上，否则极易串歌
 * （例如搜「爱」会命中一大片）。明确标注不可播（无版权 / 数专）的直接跳过。
 * 抽成顶层函数便于单测钉住「不串歌」这条底线。
 */
internal fun pickLxFallbackMatch(
    tracks: List<RemoteTrack>,
    title: String,
    artist: String,
    durationMs: Long
): RemoteTrack? {
    val nt = normLxMatch(title)
    if (nt.isEmpty()) return null
    val na = normLxMatch(artist)
    var best: RemoteTrack? = null
    var bestScore = Int.MIN_VALUE
    for (t in tracks) {
        if (t.unavailableReason != null) continue
        val tt = normLxMatch(t.title)
        val titleExact = tt == nt
        val titlePartial = tt.contains(nt) || nt.contains(tt)
        if (!titleExact && !titlePartial) continue
        val ta = normLxMatch(t.artist)
        val artistMatch = na.isNotEmpty() && (ta == na || ta.contains(na) || na.contains(ta))
        if (!titleExact && !artistMatch) continue
        var score = 0
        if (titleExact) score += 100
        if (artistMatch) score += 40
        if (durationMs > 0L && t.durationMs > 0L) {
            val d = kotlin.math.abs(t.durationMs - durationMs)
            if (d <= 2_000L) score += 20 else if (d <= 5_000L) score += 10
        }
        if (t.playable) score += 5
        if (score > bestScore) {
            bestScore = score
            best = t
        }
    }
    return best
}

internal fun normLxMatch(s: String): String =
    s.lowercase().replace(Regex("[\\s\\p{Punct}·・]+"), "")
