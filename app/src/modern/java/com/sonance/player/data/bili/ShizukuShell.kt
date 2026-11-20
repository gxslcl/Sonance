package com.sonance.player.data.bili

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.io.File

/**
 * 以 **shell 身份**（uid 2000）访问文件系统。
 *
 * ## 为什么非它不可
 *
 * B 站把离线缓存放在 `/sdcard/Android/data/tv.danmaku.bilibilihd/download/`。
 * 从 Android 11 起，这个目录对**其他应用**是封闭的：普通存储权限进不去，
 * `ACTION_OPEN_DOCUMENT_TREE`（SAF）不给选，连 `MANAGE_EXTERNAL_STORAGE`
 * （所有文件访问权限）也无效 —— 这是 FUSE 层的路径级过滤，不是权限声明问题。
 *
 * 能进这个目录的身份只有三种：该应用自己、root、shell。Shizuku 用无线调试
 * 把 shell 身份借给普通应用，是目前唯一不用 root 的路子。
 *
 * ## 用到的能力
 *
 * 只用了 Shizuku 最基础的两件事：
 * - `newProcess` 起一个 shell 进程执行命令（列目录、读 entry.json）；
 * - 同一个进程的 stdout 直接读出来（`cat` 文件内容）。
 *
 * 没有用 UserService（要额外编译服务端 jar），因为这些操作都是短命令，
 * `sh -c` 足够，而且少一层 Binder 生命周期管理。
 */
object ShizukuShell {

    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val PERMISSION_REQUEST_CODE = 4213

    /** 一次性授权回调。Shizuku 的 listener 是全局的，收完就摘，避免重复触发 */
    private var pendingResult: ((Boolean) -> Unit)? = null

    /**
     * 用匿名对象而不是 SAM lambda：lambda 里的 `this` 会指向外面的 object，
     * 摘监听时就摘错对象了（编译期就会报类型不匹配）。匿名对象里的 `this` 才是监听器本身。
     */
    private val resultListener = object : Shizuku.OnRequestPermissionResultListener {
        override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
            val cb = pendingResult
            pendingResult = null
            runCatching { Shizuku.removeRequestPermissionResultListener(this) }
            cb?.invoke(grantResult == PackageManager.PERMISSION_GRANTED)
        }
    }

    /** 手机上装没装 Shizuku */
    fun isInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    }.getOrDefault(false)

    /** Shizuku 服务是否在运行（没启动时 binder 为 null） */
    fun isRunning(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    /** 是否已经授予本应用 shell 权限 */
    fun hasPermission(): Boolean = runCatching {
        isRunning() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /**
     * 弹出 Shizuku 的授权框。
     *
     * 注意：**必须由用户点击触发**，不能在页面一打开就调用 —— Shizuku 的授权
     * 是一次性的系统级弹窗，用户还没理解为什么需要它就被拦一道，很容易直接拒绝。
     */
    fun requestPermission(onResult: (Boolean) -> Unit) {
        if (!isRunning()) {
            onResult(false)
            return
        }
        if (hasPermission()) {
            onResult(true)
            return
        }
        pendingResult = onResult
        runCatching {
            Shizuku.addRequestPermissionResultListener(resultListener)
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
        }.onFailure {
            pendingResult = null
            runCatching { Shizuku.removeRequestPermissionResultListener(resultListener) }
            onResult(false)
        }
    }

    /**
     * `Shizuku.newProcess` 在 13.x 的 api 里是 private（官方只承诺 UserService 那条路），
     * 但它在 binder 上是可用的、签名稳定。这里走反射直接调：
     *
     * - 反射的是**第三方库自己的类**（不是 framework 的 hidden API），不受
     *   `HiddenApiBypass` 那套限制；
     * - 失败就返回 null，上层降级成「读取失败」，不会崩。
     *
     * 之所以不一开始就上 UserService：那条路要写 AIDL + 单独打包服务端 dex，
     * 而这些操作全是短命令，`sh -c` 足够。真遇到 Shizuku 改签名再换也不迟。
     */
    private val newProcessMethod: java.lang.reflect.Method? by lazy {
        runCatching {
            Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            ).apply { isAccessible = true }
        }.getOrNull()
    }

    private fun startShell(script: String): Process? {
        val method = newProcessMethod ?: return null
        return runCatching {
            method.invoke(null, arrayOf("sh", "-c", script), null, null) as? Process
        }.getOrNull()
    }

    /**
     * 执行一段 shell 脚本，返回 stdout 全文。
     *
     * 脚本里所有命令都请自带 `2>/dev/null` —— stderr 不消费的话，
     * 输出量大时管道缓冲写满，进程会卡住等我们读。
     */
    fun exec(script: String, limitBytes: Int = 8 * 1024 * 1024): String? {
        if (!hasPermission()) return null
        return runCatching {
            val process = startShell(script) ?: return@runCatching null
            val text = process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val sb = StringBuilder()
                val buf = CharArray(64 * 1024)
                while (sb.length < limitBytes) {
                    val n = reader.read(buf)
                    if (n <= 0) break
                    sb.append(buf, 0, n)
                }
                sb.toString()
            }
            process.waitFor()
            text
        }.getOrNull()
    }

    /**
     * 让 shell **在自己那边**把文件复制过去（内核态 `cp`）。
     *
     * 这是准备阶段提速的关键：走 [copyTo] 时每个字节都要经过
     * `sh → Shizuku binder → 我们的进程 → 落盘`，实测只有 ~30MB/s，
     * 400MB 的视频光复制就要十几秒。改成 shell 内部 `cp` 之后数据完全
     * 不经过我们的进程，实测 36MB 只用了 144ms（≈250MB/s）。
     *
     * 前提：目标必须是 **shell 可写**的位置 —— 我们自己的外部私有目录
     * （`/sdcard/Android/data/<pkg>/cache/...`）满足条件，内部私有目录
     * （`/data/data/<pkg>/`）不行（SELinux 挡住）。不满足时返回 false，
     * 由上层退回 [copyTo]。
     *
     * `cp` 不给进度，想要进度就轮询目标文件大小（见 BiliRepository.prepare）。
     */
    fun copyLocal(srcPath: String, dstPath: String): Boolean {
        if (!hasPermission()) return false
        val out = exec(
            "cp -f ${quote(srcPath)} ${quote(dstPath)} 2>/dev/null && echo OK",
            limitBytes = 64
        )
        return out?.trim()?.endsWith("OK") == true
    }

    /**
     * 把 shell 能读、但我们读不到的文件拷到自己的目录（经 stdout 管道）。
     *
     * 只在 [copyLocal] 不可用（目标目录 shell 写不进去）时才用这条兜底：
     * 数据要经过一层进程间管道，慢得多，但好在什么地方都能落盘。
     */
    fun copyTo(srcPath: String, dst: File, onProgress: ((Long) -> Unit)? = null): Boolean {
        if (!hasPermission()) return false
        return runCatching {
            val process = startShell("cat ${quote(srcPath)} 2>/dev/null")
                ?: return@runCatching false
            dst.parentFile?.mkdirs()
            var total = 0L
            process.inputStream.use { input ->
                dst.outputStream().use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        total += n
                        onProgress?.invoke(total)
                    }
                }
            }
            val exit = process.waitFor()
            exit == 0 && total > 0L && dst.length() == total
        }.getOrDefault(false)
    }

    /**
     * 以 shell 身份删除若干个路径（`rm -rf`，文件或目录都行）。
     *
     * 删完会再 stat 一遍确认路径真的不存在：Shizuku 失联、B 站正在写这个目录、
     * SELinux 拒绝都会让 `rm` 静默失败，只看退出码不够。
     *
     * 返回 true 表示这些路径现在都不存在了 —— **原本就不存在也算成功**，
     * 这样用户重试或目录被 B 站自己清掉时不会误报失败。
     */
    fun deleteTree(paths: List<String>): Boolean {
        if (!hasPermission()) return false
        val valid = paths.filter { it.isNotBlank() }
        if (valid.isEmpty()) return true
        val remove = valid.joinToString("\n") { p -> "rm -rf ${quote(p)} 2>/dev/null" }
        val remaining = valid.joinToString(" || ") { p -> "[ -e ${quote(p)} ]" }
        val out = exec(
            "$remove\nif $remaining; then echo DELETE_FAIL; else echo DELETE_OK; fi",
            limitBytes = 64
        )
        return out?.trim()?.endsWith("DELETE_OK") == true
    }

    /** 单引号包裹路径，内部单引号按 POSIX 规则转义 */
    private fun quote(path: String): String = "'" + path.replace("'", "'\\''") + "'"
}
