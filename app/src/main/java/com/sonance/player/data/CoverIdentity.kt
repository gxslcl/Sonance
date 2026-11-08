package com.sonance.player.data

/** 手选封面按歌曲来源保存，不用可能重复的歌名和歌手作为唯一身份。 */
internal fun selectedCoverIdentity(songId: Long, sourceUri: String?): String? {
    if (songId == 0L || songId == -1L) return null
    if (songId < 0L) return "online:$songId"
    val source = sourceUri?.takeIf { it.isNotBlank() }
    if (source != null && !source.startsWith("http://", true) && !source.startsWith("https://", true)) {
        return "local:$source"
    }
    return "local-id:$songId"
}

internal data class LocalCoverSources(val album: String?, val file: String?)

internal fun localCoverSources(albumId: Long, sourceUri: String?): LocalCoverSources {
    val local = sourceUri?.takeIf {
        it.isNotBlank() && !it.startsWith("http://", true) && !it.startsWith("https://", true)
    }
    return LocalCoverSources(
        album = if (albumId > 0L) "a:$albumId" else null,
        file = local?.let { "u:$it" }
    )
}

internal fun allLocalSourcesMissing(sources: LocalCoverSources, missing: (String) -> Boolean): Boolean =
    (sources.album == null || missing(sources.album)) && (sources.file == null || missing(sources.file))
