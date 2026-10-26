package com.sonance.player.data

import android.content.Context
import android.content.IntentSender
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

        /** 文件已删掉（createDeleteRequest 路径下也包括"系统弹框后用户同意、系统已代删"） */
        data object Deleted : Result

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
        if (songs.isEmpty()) return Result.Deleted
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

        // API 29 及以下：逐个直接删
        for (uri in uris) {
            try {
                resolver.delete(uri, null, null)
            } catch (t: Throwable) {
                Log.w(TAG, "删除失败: ${t.javaClass.simpleName}: ${t.message}")
                // API 29：异常自己带着"让用户确认"的 IntentSender
                if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                    val rse = t as? android.app.RecoverableSecurityException
                        ?: return Result.Failed
                    return Result.NeedsPermission(rse.userAction.actionIntent.intentSender)
                }
                // API 28 及以下：老权限模型，缺 WRITE_EXTERNAL_STORAGE
                if (t is SecurityException) return Result.NeedsPermission(null)
                return Result.Failed
            }
        }
        return Result.Deleted
    }
}
