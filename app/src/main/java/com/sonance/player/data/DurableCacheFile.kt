package com.sonance.player.data

import android.os.Build
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FileOutputStream

/** 同目录临时文件写完后替换目标；失败时不截断原有封面。 */
internal object DurableCacheFile {
    private val locks = Array(64) { Any() }

    private fun lockFor(file: File): Any =
        locks[(file.absolutePath.hashCode() and Int.MAX_VALUE) % locks.size]

    fun read(file: File): ByteArray? = synchronized(lockFor(file)) {
        try {
            if (!file.isFile) null else file.readBytes()
        } catch (_: Exception) {
            null
        }
    }

    /** 空结果不能覆盖已有图片；选图等重要数据可要求刷到磁盘后再返回。 */
    fun write(file: File, bytes: ByteArray, sync: Boolean = false, preserveNonEmpty: Boolean = false): Boolean =
        synchronized(lockFor(file)) {
            var temp: File? = null
            try {
                val dir = file.parentFile ?: return@synchronized false
                if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) return@synchronized false
                if (preserveNonEmpty && bytes.isEmpty() && file.isFile && file.length() > 0L) {
                    return@synchronized true
                }
                temp = File.createTempFile(".${file.name}.", ".tmp", dir)
                FileOutputStream(temp).use { out ->
                    out.write(bytes)
                    out.flush()
                    if (sync) out.fd.sync()
                }
                replace(temp, file)
            } catch (_: Exception) {
                false
            } finally {
                temp?.let { if (it.exists()) it.delete() }
            }
        }

    fun delete(file: File): Boolean = synchronized(lockFor(file)) {
        !file.exists() || file.delete()
    }

    /** 迁移旧数据时不能覆盖用户刚选的新封面。 */
    fun writeIfAbsent(file: File, bytes: ByteArray, sync: Boolean = false): Boolean =
        synchronized(lockFor(file)) {
            if (file.isFile && file.length() > 0L) true else write(file, bytes, sync)
        }

    /** 只删除确实读到的坏版本，不误删并发写入的新文件。 */
    fun deleteIfUnchanged(file: File, expected: ByteArray): Boolean = synchronized(lockFor(file)) {
        try {
            file.isFile && file.readBytes().contentEquals(expected) && file.delete()
        } catch (_: Exception) {
            false
        }
    }

    private fun replace(temp: File, target: File): Boolean {
        // java.nio.file 在 Android 26 起可用；普通 JVM 单测的 SDK_INT 为 0。
        // Android 24/25 在同一私有目录内使用 POSIX rename，仍是原子替换。
        return if (Build.VERSION.SDK_INT == 0 || Build.VERSION.SDK_INT >= 26) {
            ModernFileReplace.replace(temp, target)
        } else {
            temp.renameTo(target)
        }
    }
}

/** 隔离 API 26 类型，避免 Android 7 的类验证提前加载 java.nio.file。 */
@RequiresApi(26)
private object ModernFileReplace {
    fun replace(temp: File, target: File): Boolean = try {
        java.nio.file.Files.move(
            temp.toPath(), target.toPath(),
            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING
        )
        true
    } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
        temp.renameTo(target)
    } catch (_: Exception) {
        false
    }
}
