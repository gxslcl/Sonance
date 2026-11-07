package com.sonance.player.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.TextureView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sonance.player.data.Prefs
import com.sonance.player.data.bili.BiliEntry
import com.sonance.player.data.bili.BiliRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * B 站视频播放会话。
 *
 * 为什么要有它：播放实例原先绑在 `VideoPlayerActivity` 的 Compose 页里，
 * Activity 一停就走 `ON_PAUSE -> pause()`，退出就 release。应用内浮窗要求
 * 「浮窗换页面还继续播」、后台播放要求「锁屏还在响」，播放器就必须脱离 Activity
 * 生命周期。这里把**唯一**的 [BiliVideoPlayer] 抬到应用级单例：
 *
 * - 全屏页、浮窗都只是「宿主视图」：[attachHost] / [detachHost] 只换 TextureView，
 *   不重建、不释放解码器；
 * - 切视频 = 释放旧实例再建新实例，仍然是「同一时刻最多一个 MediaPlayer」，
 *   不破坏 `distance == 0` 的单实例约束；
 * - surface 就绪才 prepare、`-38` 过滤、EGL 提亮仍由 [BiliVideoPlayer] 内部保证，
 *   这里只负责把「播哪条、播到哪、谁在显示」记下来。
 *
 * 状态全部用 Compose state 暴露，全屏页与浮窗读同一份，天然同步。
 */
class BiliVideoSession private constructor(private val app: Context) {

    companion object {
        private const val TAG = "SonanceVideo"
        private const val TICK_MS = 400L

        @Volatile
        private var instance: BiliVideoSession? = null

        fun of(context: Context): BiliVideoSession =
            instance ?: synchronized(this) {
                instance ?: BiliVideoSession(context.applicationContext).also { instance = it }
            }
    }

    /** 播放列表里的一条。列表播放用 [entry]；直接唤起时只有 [fallbackPath] */
    data class Item(
        val key: String,
        val title: String,
        val owner: String,
        val entry: BiliEntry?,
        val fallbackPath: String? = null
    )

    private val prefs = Prefs(app)
    private val repo = BiliRepository(app)
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // ===== 可观察状态 =====

    var items by mutableStateOf<List<Item>>(emptyList())
        private set

    var index by mutableIntStateOf(-1)
        private set

    /** 当前真正持有解码器的条目 key；null = 没有实例 */
    var playingKey by mutableStateOf<String?>(null)
        private set

    var isPlaying by mutableStateOf(false)
        private set

    var prepared by mutableStateOf(false)
        private set

    var preparing by mutableStateOf(false)
        private set

    var progress by mutableFloatStateOf(0f)
        private set

    var position by mutableLongStateOf(0L)
        private set

    var duration by mutableLongStateOf(0L)
        private set

    var videoAspect by mutableFloatStateOf(0f)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    /** 是否处于应用内浮窗 */
    var floating by mutableStateOf(false)
        private set

    /** 浮窗中心位置的屏幕比例；-1 = 还没拖过 */
    var floatX by mutableFloatStateOf(prefs.biliFloatX)
        private set

    var floatY by mutableFloatStateOf(prefs.biliFloatY)
        private set

    val current: Item? get() = items.getOrNull(index)
    /** 当前这条已合成的本地文件路径（浮窗点「全屏」时回传给它，免得 Activity 再找一遍） */
    val currentPathOrNull: String? get() = currentPath
    val hasList: Boolean get() = items.size > 1
    val canPrevious: Boolean get() = index > 0
    val canNext: Boolean get() = index in 0 until items.lastIndex

    val gainLevel: GainLevel get() = GainLevel.fromOrdinal(prefs.biliBrightnessGain)

    /** 全屏页 / 浮窗正在显示的那个 TextureView */
    private var host: TextureView? = null
    private var player: BiliVideoPlayer? = null
    private var currentPath: String? = null
    private var currentHdr = false
    private var currentSarNum = 1
    private var currentSarDen = 1
    private var prepareJob: Job? = null

    /** 每次起播 +1，异步准备回来时用它判断「结果还属于当下这一次」 */
    private var token = 0

    private var tickerRunning = false
    private var pausedByBackground = false

    private val ticker = object : Runnable {
        override fun run() {
            val p = player
            if (p != null && prepared) {
                runCatching {
                    position = p.currentPosition.toLong()
                    duration = p.duration.toLong()
                    isPlaying = p.isPlaying
                }
            }
            if (tickerRunning) main.postDelayed(this, TICK_MS)
        }
    }

    // ===== 列表 / 起播 =====

    /**
     * 全屏页把播放列表交给会话。
     *
     * 如果会话里已经在放同一个 key（例如从浮窗点回全屏），保持实例不重启，
     * 只退出浮窗形态；否则从 [startKey] 重新起播。
     */
    fun load(list: List<Item>, startKey: String?) {
        if (list.isEmpty()) return
        val currentKey = playingKey
        items = list
        floating = false
        val target = list.indexOfFirst { it.key == startKey }.takeIf { it >= 0 } ?: 0
        // 只有「要播的还是当前这一条」才复用实例（从浮窗点回全屏 / 转屏重建）。
        // startKey 指向另一条时必须放掉旧实例：否则会把上一条的播放现场带进全屏，
        // 出现「点了 B 却还在播 A」。
        if (player != null && currentKey != null && startKey == currentKey) {
            index = target
            return
        }
        if (player != null) stopPlayerOnly()
        // 全屏页自己会预载并经 [openPage] 把现成路径交上来，这里只对齐索引，
        // 不再重复跑一遍合成（否则首帧和准备进度会做两次）
        index = target
    }

    /**
     * 浮窗存在时，从列表点选另一条：不换宿主、不进全屏，就地换内容继续播。
     * 列表与索引都从这次点击重新铺，避免浮窗侧继续读上一份过期选中项。
     */
    fun playFromList(list: List<Item>, key: String) {
        if (list.isEmpty()) return
        val target = list.indexOfFirst { it.key == key }
        if (target < 0) return
        items = list
        index = target
        error = null
        // 点的还是当前这条：保持现场，不从头再来
        if (playingKey == key && player != null) return
        startItem(items[target], autoPlay = true)
    }

    /** 全屏 Pager 落定某页：换条目前先把旧实例放掉，等这一页的预载结果调 [openPage] */
    fun selectPage(i: Int) {
        if (i !in items.indices || i == index) return
        index = i
        stopPlayerOnly()
    }

    /**
     * 全屏页预载完成、且自己就是当前页时调用。
     * [path] 已由页面准备好（预载 / 首帧都已完成），会话只负责建单实例并起播。
     */
    fun openPage(
        i: Int,
        key: String,
        path: String,
        hdr: Boolean,
        sarNum: Int,
        sarDen: Int
    ) {
        if (i != index || i !in items.indices) return
        if (playingKey == key && player != null) {
            currentPath = path
            host?.let { player?.attachView(it) }
            return
        }
        // 页面已经准备好路径：停掉会话自己的准备协程，直接用这份结果建实例
        prepareJob?.cancel()
        token++
        createPlayer(key, path, hdr, sarNum, sarDen, autoPlay = true)
    }

    private fun startItem(item: Item, autoPlay: Boolean) {
        prepareJob?.cancel()
        stopPlayerOnly()
        error = null
        floatProgressReset()
        val myToken = ++token
        val path = item.fallbackPath
        if (path != null) {
            // 直接唤起：路径现成，探一下容器头就能建播放器
            preparing = true
            scope.launch {
                val probe = withContext(Dispatchers.IO) { probeVideo(path) }
                if (myToken != token) return@launch
                createPlayer(item.key, path, probe.hdr, probe.sarNum, probe.sarDen, autoPlay)
            }
            return
        }
        val entry = item.entry ?: return
        preparing = true
        prepareJob = scope.launch {
            val outcome = repo.prepare(entry) { p -> main.post { if (myToken == token) progress = p } }
            if (myToken != token) return@launch
            outcome.onSuccess { media ->
                val probe = withContext(Dispatchers.IO) { probeVideo(media.file.absolutePath) }
                if (myToken != token) return@launch
                createPlayer(
                    item.key,
                    media.file.absolutePath,
                    probe.hdr,
                    probe.sarNum,
                    probe.sarDen,
                    autoPlay
                )
                Log.i(TAG, "MP4 就绪: key=${item.key} bytes=${media.file.length()}")
            }.onFailure { e ->
                if (e is CancellationException) throw e
                preparing = false
                error = e.message ?: "准备失败"
                Log.w(TAG, "合成失败: key=${item.key} err=${e.message}")
            }
        }
    }

    private fun createPlayer(
        key: String,
        path: String,
        hdr: Boolean,
        sarNum: Int,
        sarDen: Int,
        autoPlay: Boolean
    ) {
        releasePlayer()
        currentPath = path
        currentHdr = hdr
        currentSarNum = sarNum
        currentSarDen = sarDen
        playingKey = key
        preparing = false
        prepared = false
        progress = 1f
        error = null
        videoAspect = 0f
        position = 0L
        duration = 0L
        val p = BiliVideoPlayer(
            context = app,
            logTag = TAG,
            key = key,
            onPrepared = {
                prepared = true
                Log.i(TAG, "prepared: key=$key")
                // 回调到达时 player 字段已经赋值，用字段而不是构造中的局部变量
                if (autoPlay) runCatching { player?.start() }
            },
            onError = { what, extra ->
                prepared = false
                isPlaying = false
                error = describeBiliMediaError(what, extra)
                Log.w(TAG, "MediaPlayer error: key=$key what=$what extra=$extra")
            },
            onCompleted = { onCompleted() },
            onVideoSize = { w, h, sn, sd ->
                if (w > 0 && h > 0) {
                    val sar = if (sn > 0 && sd > 0) sn.toFloat() / sd.toFloat() else 1f
                    videoAspect = (w.toFloat() * sar) / h.toFloat()
                    Log.i(TAG, "video size: key=$key ${w}x$h sar=$sn/$sd")
                }
            },
            sourceSarNum = sarNum,
            sourceSarDen = sarDen
        )
        player = p
        p.configure(gainLevel, hdr)
        host?.let { p.attachView(it) }
        p.setSource(path)
        startTicker()
        // 开关打开时：起播就把前台服务挂上，之后切后台 / 锁屏进程才不会被回收。
        // 这里一定是在应用前台（用户点了播放 / 列表），符合系统对 FGS 启动的限制。
        if (prefs.biliBackgroundPlay) BiliVideoService.start(app)
    }

    private fun onCompleted() {
        val mode = PlayMode.entries.getOrElse(prefs.biliPlayMode) { PlayMode.REPEAT_ALL }
        Log.i(TAG, "completed: key=${current?.key} mode=${mode.label}")
        when (mode) {
            PlayMode.REPEAT_ONE -> {
                player?.seekTo(0)
                player?.start()
                isPlaying = true
            }
            PlayMode.SHUFFLE -> {
                if (items.size > 1) {
                    val others = items.indices.filter { it != index }
                    switchTo(others.random())
                }
            }
            PlayMode.REPEAT_ALL -> {
                if (items.size > 1) switchTo((index + 1) % items.size)
            }
        }
    }

    // ===== 控制 =====

    fun play() {
        if (player == null) return
        runCatching { player?.start() }
        isPlaying = player?.isPlaying == true
    }

    fun pause() {
        runCatching { player?.pause() }
        isPlaying = false
    }

    fun toggle() {
        if (player?.isPlaying == true) pause() else play()
    }

    fun seekTo(ms: Int) {
        runCatching { player?.seekTo(ms) }
        position = ms.toLong()
    }

    /** 手动切下一条：到头就不切（列表末尾不可切歌的约束保留在这里） */
    fun next() {
        if (!canNext) return
        switchTo(index + 1)
    }

    fun previous() {
        if (!canPrevious) return
        switchTo(index - 1)
    }

    fun switchTo(i: Int) {
        if (i !in items.indices || i == index) return
        index = i
        startItem(items[i], autoPlay = true)
    }

    /** 提亮档位：立即作用到当前实例；换视频时在 [createPlayer] 里带上 */
    fun setGain(level: GainLevel) {
        prefs.biliBrightnessGain = level.ordinal
        player?.setGain(level)
    }

    /**
     * 「锁屏 / 后台继续播放」开关。
     *
     * 先持久化（下次冷启动沿用），再按当前是否真有实例决定起停前台服务。
     * 关掉时立刻收通知并停服务，行为和旧版一致：切后台就暂停。
     */
    fun setBackgroundPlay(enabled: Boolean) {
        prefs.biliBackgroundPlay = enabled
        if (enabled) {
            if (player != null) BiliVideoService.start(app)
        } else {
            BiliVideoService.stop(app)
        }
        Log.i(TAG, "后台播放开关: $enabled")
    }

    fun retry() {
        val key = playingKey ?: return
        val path = currentPath ?: return
        createPlayer(key, path, currentHdr, currentSarNum, currentSarDen, autoPlay = true)
    }

    // ===== 宿主视图 =====

    fun attachHost(tv: TextureView) {
        if (host === tv) return
        if (host != null) player?.detachView()
        host = tv
        player?.attachView(tv)
        Log.i(TAG, "attachHost: key=$playingKey")
    }

    fun detachHost(tv: TextureView) {
        if (host !== tv) return
        host = null
        player?.detachView()
        Log.i(TAG, "detachHost: key=$playingKey")
    }

    // ===== 浮窗 =====

    fun enterFloating() {
        if (items.isEmpty()) return
        floating = true
    }

    /** 从浮窗回全屏：Activity 接管宿主，播放器不重建 */
    fun exitFloating() {
        floating = false
    }

    /** 关掉浮窗并且结束播放 */
    fun closeFloating() {
        floating = false
        stop()
    }

    fun saveFloatPosition(nx: Float, ny: Float) {
        floatX = nx.coerceIn(0f, 1f)
        floatY = ny.coerceIn(0f, 1f)
        prefs.biliFloatX = floatX
        prefs.biliFloatY = floatY
    }

    // ===== 生命周期 =====

    /**
     * 宿主 Activity / 应用进入后台。
     *
     * [enteringFloating] 为 true 表示这次 onPause 是「转浮窗」，不能暂停；
     * 其余情况：后台继续播放开关打开就保持，关闭就暂停（音画同停，回来原地续播）。
     */
    fun onHostPause(enteringFloating: Boolean): Boolean {
        if (enteringFloating || floating) return false
        if (prefs.biliBackgroundPlay) return false
        if (player?.isPlaying == true) {
            runCatching { player?.pause() }
            isPlaying = false
            return true
        }
        return false
    }

    fun onHostResume(resume: Boolean) {
        if (!resume) return
        play()
    }

    /** 整个应用退到后台（不是被另一个 Activity 盖住）：开关关闭就暂停 */
    fun onAppBackground() {
        if (prefs.biliBackgroundPlay) return
        if (player?.isPlaying == true) {
            runCatching { player?.pause() }
            isPlaying = false
            pausedByBackground = true
        }
    }

    fun onAppForeground() {
        if (!pausedByBackground) return
        pausedByBackground = false
        play()
    }

    // ===== 释放 =====

    /** 返回 / 关闭浮窗 / 退出应用：结束播放并清空现场 */
    fun stop() {
        prepareJob?.cancel()
        token++
        stopPlayerOnly()
        items = emptyList()
        index = -1
        floating = false
        preparing = false
        progress = 0f
        // 现场清空后就没有可控对象了，前台服务与媒体通知一并收掉
        BiliVideoService.stop(app)
    }

    /** 进程内兜底：应用退出（返回栈清空）时不留孤立播放器 */
    fun onAppExit() {
        if (!floating) stop()
        else stop()
    }

    private fun stopPlayerOnly() {
        releasePlayer()
        playingKey = null
        prepared = false
        isPlaying = false
        position = 0L
        duration = 0L
        videoAspect = 0f
        currentPath = null
        currentHdr = false
        currentSarNum = 1
        currentSarDen = 1
    }

    private fun releasePlayer() {
        stopTicker()
        val p = player ?: return
        player = null
        runCatching { p.detachView() }
        runCatching { p.release() }
    }

    private fun floatProgressReset() {
        progress = 0f
        prepared = false
        preparing = true
    }

    private fun startTicker() {
        if (tickerRunning) return
        tickerRunning = true
        main.postDelayed(ticker, TICK_MS)
    }

    private fun stopTicker() {
        tickerRunning = false
        main.removeCallbacks(ticker)
    }
}

private fun describeBiliMediaError(what: Int, extra: Int): String = when {
    extra == -38 || what == -38 ->
        "解码器初始化失败（-38），多为硬解资源被占用或该编码不支持硬解"
    else -> "播放器报告解码失败（what=$what, extra=$extra）"
}
