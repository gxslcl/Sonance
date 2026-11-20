package com.sonance.player.data

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import java.io.File

/** 已授权的 SAF 外部文件夹（如车载 U 盘 / SD 卡目录） */
data class SafScanTree(
    val uriString: String,
    val label: String
)

/**
 * 车载 / 移动设备外置存储（U 盘 / OTG / TF 卡）路径发现与 SAF 授权列表序列化工具。
 *
 * 车机常见情况：System MediaStore 不索引外接 FAT32/exFAT U 盘，或仅挂载在
 * `/storage/<UUID>`、`/mnt/usb_storage`、`/mnt/usbhost`、`/mnt/media_rw` 等路径。
 */
object ExternalStoragePaths {

    const val SAF_SCOPE_PREFIX = "saf://"

    private val NON_REMOVABLE_STORAGE_NAMES = setOf("emulated", "self", "enc_emulated", "knox-emulated")

    private val STATIC_USB_CANDIDATES = listOf(
        "/mnt/usb_storage",
        "/mnt/usbhost",
        "/mnt/usb_host",
        "/mnt/usb",
        "/mnt/udisk",
        "/mnt/udisk0",
        "/mnt/udisk1",
        "/mnt/media_rw",
        "/storage/usb0",
        "/storage/usb1",
        "/storage/udisk"
    )

    private val PARENT_PROBE_DIRS = listOf(
        "/storage",
        "/mnt/usb_storage",
        "/mnt/usbhost",
        "/mnt/usb_host",
        "/mnt/media_rw",
        "/mnt/usb",
        "/mnt/udisk"
    )

    /** 发现除默认内置主存储外当前可直接读取的外置/U 盘挂载根目录。 */
    fun discoverReadableExternalRoots(context: Context, primaryRoot: File? = null): List<File> {
        val primaryCanonical = primaryRoot?.let(::canonicalOrAbsolute)
        val seen = HashSet<String>()
        if (primaryCanonical != null) seen += primaryCanonical
        val candidates = ArrayList<File>()

        fun consider(file: File?) {
            if (file == null || !file.isDirectory || !file.canRead()) return
            val key = canonicalOrAbsolute(file)
            if (seen.add(key)) candidates += file
        }

        // 1. StorageManager.storageVolumes（标准 Android USB OTG / 可移动卷发现入口）
        val sm = runCatching { context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager }.getOrNull()
        runCatching { sm?.storageVolumes }.getOrNull()?.forEach { vol ->
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    consider(vol.directory)
                } else {
                    // Android 7~10（含大量车机系统）：通过反射读 getPathFile / getPath，以及 /storage/<uuid> 兜底
                    val byMethod = runCatching {
                        vol.javaClass.getMethod("getPathFile").invoke(vol) as? File
                    }.getOrNull() ?: runCatching {
                        (vol.javaClass.getMethod("getPath").invoke(vol) as? String)?.let(::File)
                    }.getOrNull()
                    consider(byMethod)
                }
                val uuid = vol.uuid?.takeIf { it.isNotBlank() }
                if (uuid != null && !vol.isPrimary) {
                    consider(File("/storage/$uuid"))
                }
            }
        }

        // 2. 常见车机 / Android TV / 定制 ROM 的 USB 静态与容器挂载点
        STATIC_USB_CANDIDATES.forEach { runCatching { consider(File(it)) } }
        PARENT_PROBE_DIRS.forEach { parentPath ->
            runCatching {
                val parent = File(parentPath)
                if (!parent.isDirectory || !parent.canRead()) return@runCatching
                parent.listFiles()?.forEach { child ->
                    if (child.isDirectory && child.name !in NON_REMOVABLE_STORAGE_NAMES) {
                        consider(child)
                    }
                }
            }
        }
        return candidates
    }

    /** 根据根目录与当前目录生成不与内置存储冲突的文件夹归属路径。 */
    internal fun formatVolumeRelativeFolder(
        relativePath: String,
        volumeLabel: String?,
        isExternalVolume: Boolean
    ): String {
        val rel = relativePath.trim('/').trim()
        if (!isExternalVolume) return rel
        val prefix = volumeLabel?.trim()?.takeIf { it.isNotEmpty() } ?: "外置存储"
        return if (rel.isEmpty()) prefix else "$prefix/$rel"
    }

    /** 构造 SAF 文件夹在歌曲/目录列表里的路径标识 */
    fun safFolderPath(treeLabel: String, relativeSubDir: String): String {
        val base = "外置 · ${treeLabel.ifBlank { "U盘目录" }}"
        val sub = relativeSubDir.trim('/').trim()
        return if (sub.isEmpty()) base else "$base/$sub"
    }

    /** 扫描范围弹窗使用的 SAF 根目录选择键（与 MediaStore 目录 path 区分） */
    fun safScopePathOf(treeUriString: String): String = "$SAF_SCOPE_PREFIX$treeUriString"

    /**
     * 判断某首曲目的 [folderPath] 是否命中当前选中的扫描范围（支持普通目录与 `saf://` 外置授权目录）。
     */
    fun matchesScopedPath(
        folderPath: String,
        selectedScopes: Set<String>,
        safTrees: List<SafScanTree>
    ): Boolean {
        if (folderPath in selectedScopes) return true
        for (tree in safTrees) {
            if (safScopePathOf(tree.uriString) !in selectedScopes) continue
            val base = safFolderPath(tree.label, "")
            if (folderPath == base || folderPath.startsWith("$base/")) return true
        }
        return false
    }

    fun parseSafScanTrees(raw: String): List<SafScanTree> {
        if (raw.isBlank()) return emptyList()
        val result = ArrayList<SafScanTree>()
        val seen = HashSet<String>()
        raw.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach
            val tab = trimmed.indexOf('\t')
            val uri = if (tab >= 0) trimmed.substring(0, tab).trim() else trimmed
            val label = if (tab >= 0) trimmed.substring(tab + 1).trim().ifEmpty { "外置目录" } else "外置目录"
            if (uri.isNotEmpty() && seen.add(uri)) {
                result += SafScanTree(uriString = uri, label = label)
            }
        }
        return result
    }

    fun encodeSafScanTrees(trees: List<SafScanTree>): String =
        trees.distinctBy { it.uriString }
            .joinToString("\n") { "${it.uriString}\t${it.label.replace('\t', ' ').replace('\n', ' ')}" }

    fun addSafTree(raw: String, uriString: String, label: String): String {
        val current = parseSafScanTrees(raw).filterNot { it.uriString == uriString }
        val cleanLabel = label.trim().ifEmpty { "外置目录" }
        return encodeSafScanTrees(current + SafScanTree(uriString = uriString, label = cleanLabel))
    }

    fun removeSafTree(raw: String, uriString: String): String =
        encodeSafScanTrees(parseSafScanTrees(raw).filterNot { it.uriString == uriString })

    private fun canonicalOrAbsolute(file: File): String =
        runCatching { file.canonicalPath }.getOrElse { file.absolutePath }.trimEnd('/')

    @Suppress("DEPRECATION")
    internal fun defaultPrimaryRoot(): File? =
        runCatching { Environment.getExternalStorageDirectory() }.getOrNull()
}
