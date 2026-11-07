package com.sonance.player.data

import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log

/**
 * 把本地歌曲的**音频文件**从设备上删掉（MediaStore 路线）。
 *
 * 结构刻意照着 [CoverTagWriter] 来：同样是"分区存储下动别人的文件会被系统拦"，
 * 同样把「需要用户点一下确认」包装成 [Result.NeedsPermission] 交回上层拉起系统弹窗。
 *
 * 两条路径：
 * - API 30+：[MediaStore.createDeleteRequest] —— 一次提交一批 URI，系统弹一次
 *   「允许删除这些文件？」；应用自己贡献的文件不会弹框，直接按已授权处理。
 *   **用户点同意后系统自己完成删除**，上层不要再去 delete 一次。
 * - API 29 及以下：直接 `ContentResolver.delete`。API 29 被拦时异常自带确认框
 *   （[android.app.RecoverableSecurityException]）；API 28 及以下没有这套机制，
 *   返回 [Result.NeedsPermission](null)，由界面先去要 `WRITE_EXTERNAL_STORAGE`。
 */
object MediaFileDeleter {

    private const val TAG = "SonanceDelete"

    sealed interface Result {

        /**
         * 文件已处理完。
         *
         * [deletedUris] 是**确认已经不存在**的条目；[failed] 是逐个删除时失败的条数。
         * API 30+ 走 createDeleteRequest 时不会返回这个结果（由系统代删，见 [NeedsPermission]）。
         */
        data class Deleted(
            val deletedUris: Set<Uri> = emptySet(),
            val failed: Int = 0
        ) : Result

        /**
         * 被系统拦下，需要用户确认。
         *
         * [intentSender] 非空 → 用 `StartIntentSenderForResult` 拉起「允许删除？」，
         * 同意后**删除已由系统完成**；为空 → API 28 及以下，先授予
         * `WRITE_EXTERNAL_STORAGE`，拿到权限后**重新走一遍 [request]**。
         */
        data class NeedsPermission(val intentSender: IntentSender?) : Result

        /** URI 不受 MediaStore 管理等真失败 */
        data object Failed : Result
    }

    /**
     * 删除一批本地歌曲的音频文件。
     *
     * 调用方保证 [songs] 全是本地歌（`isLocal`），这里不再过滤。
     */
    fun request(context: Context, songs: List<Song>): Result {
        if (songs.isEmpty()) return Result.Deleted()
        val resolver = context.contentResolver
        val uris = songs.map { it.uri }

        // API 30+：批量提交一个删除请求，系统统一弹一次确认
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val sender = runCatching {
                MediaStore.createDeleteRequest(resolver, uris).intentSender
            }.onFailure {
                Log.w(TAG, "createDeleteRequest 失败: ${it.javaClass.simpleName}: ${it.message}")
            }.getOrNull() ?: return Result.Failed
            return Result.NeedsPermission(sender)
        }

        // API 29 及以下：逐个直接删，按 delete() 的返回行数统计成功/失败
        val deleted = LinkedHashSet<Uri>()
        var failed = 0
        for (uri in uris) {
            try {
                // delete() 返回 0 时再查一次：如果行已经不在了，说明之前那轮授权删除
                // 其实已经成功，不要误报成失败（API 29 二次执行时会遇到）
                if (resolver.delete(uri, null, null) > 0 || !rowExists(resolver, uri)) {
                    deleted += uri
                } else {
                    failed++
                }
            } catch (t: Throwable) {
                Log.w(TAG, "删除失败: ${t.javaClass.simpleName}: ${t.message}")
                // API 29：异常自己带着"让用户确认"的 IntentSender
                if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                    val rse = t as? android.app.RecoverableSecurityException
                    if (rse != null) {
                        return Result.NeedsPermission(rse.userAction.actionIntent.intentSender)
                    }
                    return Result.Failed
                }
                // API 28 及以下：老权限模型，缺 WRITE_EXTERNAL_STORAGE
                if (t is SecurityException) return Result.NeedsPermission(null)
                failed++
            }
        }
        return Result.Deleted(deleted, failed)
    }

    private fun rowExists(resolver: android.content.ContentResolver, uri: Uri): Boolean =
        runCatching {
            resolver.query(uri, arrayOf(MediaStore.Audio.Media._ID), null, null, null)
                ?.use { it.count > 0 } ?: false
        }.getOrDefault(true)

    /**
     * 系统授权删除之后，按 URI 查一下哪些文件**还活着**。
     *
     * createDeleteRequest 的结果只有「同意 / 取消」，不告诉你是哪几个没删掉；
     * 用户在系统弹窗里同意之后，再查一遍才能给出准确的「成功 N / 失败 M」。
     * 查询本身出错时按"还存在"处理，宁可少报成功也不虚报。
     */
    fun remaining(context: Context, songs: List<Song>): List<Song> {
        if (songs.isEmpty()) return emptyList()
        return songs.filter { song ->
            runCatching {
                context.contentResolver.query(
                    song.uri,
                    arrayOf(MediaStore.Audio.Media._ID),
                    null,
                    null,
                    null
                )?.use { it.count > 0 } ?: false
            }.getOrDefault(true)
        }
    }
}
