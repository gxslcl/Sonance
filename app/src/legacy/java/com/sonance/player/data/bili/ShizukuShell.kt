package com.sonance.player.data.bili

import android.content.Context
import java.io.File

/**
 * legacy（Android 5/6）发行版的空实现：Shizuku 自身要求 Android 6+，
 * 且依赖它的 B站缓存 / 网易云嗅探入口在本发行版已整体隐藏。
 * 这里保持与 modern 版完全相同的公开签名，让共用代码照常编译；
 * 所有能力一律报告「不可用」，任何漏网的调用点都会走既有的降级分支。
 */
object ShizukuShell {

    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    fun isInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    }.getOrDefault(false)

    fun isRunning(): Boolean = false

    fun hasPermission(): Boolean = false

    fun requestPermission(onResult: (Boolean) -> Unit) {
        onResult(false)
    }

    fun exec(script: String, limitBytes: Int = 8 * 1024 * 1024): String? = null

    fun copyLocal(srcPath: String, dstPath: String): Boolean = false

    fun copyTo(srcPath: String, dst: File, onProgress: ((Long) -> Unit)? = null): Boolean = false

    fun deleteTree(paths: List<String>): Boolean = paths.none { it.isNotBlank() }
}