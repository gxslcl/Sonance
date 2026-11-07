package com.sonance.player.sync

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 多设备同步的进程内诊断日志（环形缓冲，最多 [MAX_LINES] 行）。
 *
 * 只记链路关键事件与失败原因：设备名、IP、端口、阶段耗时、传输字节数。
 * **不记录曲库内容**（曲目只用 mediaId / 大小 / mime 表示，不写标题与歌手），
 * 避免用户导出日志时泄露隐私。
 *
 * 导出路径：[Context.getCacheDir]/group_logs/sonance-group-*.txt，
 * 通过 FileProvider（authority = "${包名}.covers"）分享出去，用户无需 adb 也能抓取。
 */
object GroupLog {

    private const val MAX_LINES = 400
    private const val TAG = "SonanceGroup"

    private val lines = ArrayDeque<String>()
    private val lock = Any()

    fun log(tag: String, message: String) {
        val line = "${timeText(System.currentTimeMillis())} [$tag] $message"
        synchronized(lock) {
            if (lines.size >= MAX_LINES) lines.removeFirst()
            lines.addLast(line)
        }
        // 同时进 Logcat，方便有 adb 的场合直接 logcat | grep SonanceGroup
        Log.d(TAG, "$tag: $message")
    }

    fun snapshot(): String = synchronized(lock) { lines.joinToString("\n") }

    /** 把当前缓冲写成 txt；失败返回 null */
    fun export(context: Context): File? = runCatching {
        val dir = File(context.cacheDir, "group_logs").apply { mkdirs() }
        val name = "sonance-group-${fileStamp(System.currentTimeMillis())}.txt"
        val file = File(dir, name)
        file.writeText(
            buildString {
                appendLine("Sonance 多设备同步诊断日志")
                appendLine("导出时间：${timeText(System.currentTimeMillis())}")
                appendLine("设备：${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("系统：Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                appendLine("说明：只含链路事件，不含曲库标题/歌手等个人信息")
                appendLine("----------------------------------------------------------------")
                append(snapshot())
                appendLine()
            }
        )
        file
    }.onFailure { Log.w(TAG, "export failed: ${it.message}") }.getOrNull()

    private fun timeText(ms: Long): String =
        SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date(ms))

    private fun fileStamp(ms: Long): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(ms))
}
