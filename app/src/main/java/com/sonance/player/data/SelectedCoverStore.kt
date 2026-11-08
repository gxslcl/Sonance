package com.sonance.player.data

import java.io.File
import java.security.MessageDigest

/** 用户选中的原图独立于可淘汰缩略图；文件名不包含歌曲名称或路径明文。 */
internal class SelectedCoverStore(private val directory: File) {
    private fun fileOf(songId: Long, sourceUri: String?): File? {
        val identity = selectedCoverIdentity(songId, sourceUri) ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        val name = digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return File(directory, "$name.jpg")
    }

    fun store(songId: Long, sourceUri: String?, bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        val file = fileOf(songId, sourceUri) ?: return false
        return DurableCacheFile.write(file, bytes, sync = true)
    }

    fun lookup(songId: Long, sourceUri: String?): ByteArray? =
        fileOf(songId, sourceUri)?.let(DurableCacheFile::read)?.takeIf { it.isNotEmpty() }

    fun exists(songId: Long, sourceUri: String?): Boolean =
        fileOf(songId, sourceUri)?.let { it.isFile && it.length() > 0L } == true

    fun migrateIfAbsent(songId: Long, sourceUri: String?, bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        val file = fileOf(songId, sourceUri) ?: return false
        return DurableCacheFile.writeIfAbsent(file, bytes, sync = true)
    }

    fun remove(songId: Long, sourceUri: String?) {
        fileOf(songId, sourceUri)?.let { DurableCacheFile.delete(it) }
    }
}
