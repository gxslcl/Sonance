package com.sonance.player.ui.vinyl

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import android.view.HapticFeedbackConstants
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sonance.player.data.ArtworkLoader
import com.sonance.player.data.Lyrics
import com.sonance.player.data.LyricsRepository
import com.sonance.player.data.OnlineCover
import com.sonance.player.data.Prefs
import com.sonance.player.data.Song
import com.sonance.player.playback.PlayMode
import com.sonance.player.playback.PlayerUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * 全屏 WebView 黑胶页：直接加载 assets/vinyl/vinyl.html（黑胶原型一比一）。
 *
 * 职责切分：
 *  - HTML 保留全部视觉/手势/落片动画/状态机/机械音效；
 *  - 割掉原型里的假数据、和弦模拟发声、假进度时钟；
 *  - 原生推：歌单（setQueue）/播放状态与进度（syncPlayback，500ms 校准）/封面（setCover，base64）/音量（setVolume）；
 *  - HTML 上抛：选歌 onSelectSong（音频先动，原生 playAt）/播放暂停 onTogglePlay/拖动 seek/音量/触感/关闭。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun VinylWebScreen(
    state: PlayerUiState,
    queue: List<Song>,
    onClose: () -> Unit,
    onPlayAt: (Int) -> Unit,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    onVolume: (Float) -> Unit,
    onPlayMode: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    BackHandler { onClose() }

    // Bridge 回调一律走 rememberUpdatedState + 主线程 post，避免捕获过期闭包
    val cbPlayAt = rememberUpdatedState(onPlayAt)
    val cbToggle = rememberUpdatedState(onTogglePlay)
    val cbSeek = rememberUpdatedState(onSeek)
    val cbVolume = rememberUpdatedState(onVolume)
    val cbPlayMode = rememberUpdatedState(onPlayMode)
    val cbClose = rememberUpdatedState(onClose)

    val webRef = remember { mutableStateOf<WebView?>(null) }
    val stateRef = rememberUpdatedState(state)
    val queueJson = remember(queue) { buildQueueJson(queue) }
    // 页面就绪标记：onPageFinished 后才推封面/歌词。页面中途重载（渲染进程
    // 崩溃后 WebView 自动恢复，滑动建新纹理的内存峰值可能触发）会经
    // onPageStarted 复位，完成后全量重推——否则歌单还在、封面永久丢失
    val pageReady = remember { mutableStateOf(false) }
    // 进入遮罩：3D 引擎初始化那半秒是纯黑 + 唱机瞬现。用深棕渐变盖住首帧前的
    // 黑屏，页面渲染出第一帧后 320ms 淡出——推开暗房的门，而不是闪黑
    val firstFrame = remember { mutableStateOf(false) }
    val maskAlpha = remember { Animatable(1f) }
    val maskGone = remember { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        // 黑胶页隐藏系统状态栏（沉浸全屏），退出页面时恢复；下滑可临时唤出
        val activity = context as? android.app.Activity
        val insetsController = activity?.window?.let {
            WindowCompat.getInsetsController(it, it.decorView)
        }
        insetsController?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController?.hide(WindowInsetsCompat.Type.statusBars())
        val observer = LifecycleEventObserver { _, event ->
            webRef.value?.let { wv ->
                when (event) {
                    Lifecycle.Event.ON_PAUSE -> {
                        // 退后台：WebView 自身暂停 + 显式关掉页面渲染循环（双保险，
                        // 不能依赖 WebView 的 rAF 节流；页面内 setRenderActive 会
                        // cancelAnimationFrame，回前台再重启）
                        wv.onPause()
                        wv.evaluateJavascript(
                            "window.__vinyl && window.__vinyl.setRenderActive(false)", null
                        )
                    }
                    Lifecycle.Event.ON_RESUME -> {
                        wv.onResume()
                        wv.evaluateJavascript(
                            "window.__vinyl && window.__vinyl.setRenderActive(true)", null
                        )
                    }
                    else -> {}
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            insetsController?.show(WindowInsetsCompat.Type.statusBars())
            lifecycleOwner.lifecycle.removeObserver(observer)
            webRef.value?.apply {
                onPause()
                destroy()
            }
            webRef.value = null
        }
    }

    // 顶部安全区桥传：不再只取挖孔矩形，而是把 WindowInsets 里两类真实几何一起推给页面：
    //  displayCutout() → 挖孔实矩形 boundingRectTop + 四边 safeInset（null 机型时为 0），
    //  statusBars() → 状态栏高度（用 IgnoringVisibility 取真实值，黑胶页隐藏状态栏时
    //  也不会读到 0，退到后台重新显示时胶囊不会跳位）。
    // 页面据此把胶囊整块下移到安全区下沿，居中/左上/右上挖孔都只是下移量不同，
    // 不写死机型数值；旋转/折叠态变化时由 setOnApplyWindowInsetsListener 重推。
    val cutoutInsetReceiver = { wv: android.webkit.WebView ->
        val d = wv.resources.displayMetrics.density.takeIf { it > 0f } ?: 1f
        val insets = wv.rootWindowInsets
        val compat = insets?.let { WindowInsetsCompat.toWindowInsetsCompat(it, wv) }
        val cutoutSafe = compat?.getInsets(WindowInsetsCompat.Type.displayCutout())
        val status = compat?.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars())
        val rect = if (android.os.Build.VERSION.SDK_INT >= 28) {
            insets?.displayCutout?.boundingRectTop
        } else {
            null
        }
        val l = (rect?.left ?: 0) / d
        val r = (rect?.right ?: 0) / d
        val t = (rect?.top ?: 0) / d
        val b = (rect?.bottom ?: 0) / d
        val safeTop = (cutoutSafe?.top ?: 0) / d
        val safeLeft = (cutoutSafe?.left ?: 0) / d
        val safeRight = (cutoutSafe?.right ?: 0) / d
        val statusTop = (status?.top ?: 0) / d
        val payload = "{\"l\":$l,\"r\":$r,\"t\":$t,\"b\":$b," +
            "\"safeTop\":$safeTop,\"safeLeft\":$safeLeft,\"safeRight\":$safeRight," +
            "\"statusTop\":$statusTop}"
        wv.evaluateJavascript(bridgeInject("window.__vinyl.setCutout($payload);"), null)
    }

    Box(modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                pageReady.value = false
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.allowFileAccess = true
                    settings.allowContentAccess = true
                    // 不走缓存：assets 页面每次从安装包重读，避免 install -r 后 WebView
                    // 用旧缓存渲染（比 file URL 带 ?v= query 安全——部分 WebView 对
                    // 带 query 的 file 路径做资源查找会失败，直接黑屏）
                    settings.cacheMode = WebSettings.LOAD_NO_CACHE
                    setBackgroundColor(0xFF0B0A09.toInt())
                    addJavascriptInterface(
                        VinylBridge(
                            onPlayAtIdx = { idx -> post { cbPlayAt.value(idx) } },
                            onToggle = { post { cbToggle.value() } },
                            onSeekSec = { sec -> post { cbSeek.value((sec * 1000).toLong()) } },
                            onVolumeValue = { v -> post { cbVolume.value(v.coerceIn(0f, 1f)) } },
                            onHapticMs = { this@apply.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) },
                            onClosePage = { post { cbClose.value() } },
                            onFirstFrameReady = { post { firstFrame.value = true } },
                            onSfxChanged = { enabled ->
                                post {
                                    runCatching { Prefs(ctx).vinylSfxEnabled = enabled }
                                }
                            },
                            onPlayModeRequest = { mode -> post { cbPlayMode.value(mode) } }
                        ),
                        "VinylBridge"
                    )
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            // 同一 WebView 内的重载：复位就绪标记，onPageFinished 后全量重推
                            pageReady.value = false
                        }

                        override fun onPageFinished(view: WebView, url: String?) {
                            // 页面脚本已执行完毕，__vinyl 可用；推首包（歌单 + 对位 + 音量）
                            pushQueue(view, queueJson, stateRef.value)
                            pushPlayback(view, stateRef.value)
                            // 补推挖孔 inset（insets listener 首次触发可能早于页面脚本就绪）
                            cutoutInsetReceiver(view)
                            // 机械音效设置同步一次（页面内开关切换经 onSfxEnabled 回写 Prefs）
                            val p = Prefs(view.context)
                            val sfxJs =
                                bridgeInject("window.__vinyl.setSfx(${p.vinylSfxEnabled}, ${p.vinylSfxVolume});")
                            view.post { view.evaluateJavascript(sfxJs, null) }
                            // 补推真实视口：页面加载可能早于 WebView 首次布局，100vh 被冻结为 0
                            pushViewport(view)
                            pageReady.value = true
                        }

                        override fun onReceivedError(
                            view: WebView,
                            request: WebResourceRequest?,
                            error: WebResourceError?
                        ) {
                            super.onReceivedError(view, request, error)
                            val desc = error?.description?.toString() ?: "未知错误"
                            Log.e("VinylWeb", "加载失败: ${request?.url} $desc")
                            if (request?.isForMainFrame == true) {
                                // 主页面加载失败时 WebView 只剩背景色（纯黑屏），
                                // 直接把原因画出来，截图即可定位
                                val js =
                                    "window.showFatal && showFatal('页面加载失败: $desc\\n${request.url}')"
                                view.post { view.evaluateJavascript(js, null) }
                            }
                        }
                    }
                    // 布局变化时把真实尺寸按 CSS px 推给页面（修复 innerHeight=0 冻结问题）：
                    // WebView 内部 CSS px = View px / density，与 devicePixelRatio 一致
                    var lastPushW = 0
                    var lastPushH = 0
                    addOnLayoutChangeListener { v, left, top, right, bottom, _, _, _, _ ->
                        val w = right - left
                        val h = bottom - top
                        if (w <= 0 || h <= 0 || (w == lastPushW && h == lastPushH)) {
                            return@addOnLayoutChangeListener
                        }
                        lastPushW = w
                        lastPushH = h
                        pushViewport(v as WebView)
                    }
                    // 挖孔 inset 变化（旋转/折叠态切换）时重推顶部胶囊自适应参数
                    setOnApplyWindowInsetsListener { v, insets ->
                        cutoutInsetReceiver(v as WebView)
                        insets
                    }
                    // 系统层兜底：按下即声明不让父级拦截触摸流。WebView 被祖先
                    // ViewGroup 半路抢走触摸的表现就是 JS 侧 pointercancel 风暴
                    setOnTouchListener { v, ev ->
                        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                        }
                        false // 返回 false，WebView 自身 onTouchEvent 照常处理触摸
                    }
                    webRef.value = this
                    loadUrl("file:///android_asset/vinyl/vinyl.html")
                }
            }
        )

        // 进入遮罩：深棕渐变 + 中央一点暗金微光，盖住 3D 引擎初始化的纯黑半秒。
        // VinylWebScreen 经 AnimatedVisibility fadeIn 进场时下层还是 2D 播放页，
        // 遮罩随其淡入——从 2D 到唱机全程无纯黑帧，如推开一扇暗房的门
        if (!maskGone.value) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = maskAlpha.value }
                    .background(
                        Brush.verticalGradient(
                            0f to Color(0xFF36291B),
                            0.5f to Color(0xFF211A11),
                            1f to Color(0xFF110E0A)
                        )
                    )
                    .background(
                        Brush.radialGradient(listOf(Color(0x2EE5A94C), Color(0x00E5A94C)))
                    )
            )
        }
    }

    // 首帧到位（或 2.5s 兜底——页面加载失败时 showFatal 提示也要能被看见）后淡出遮罩
    LaunchedEffect(firstFrame.value) {
        if (!firstFrame.value) return@LaunchedEffect
        maskAlpha.animateTo(0f, tween(320))
        maskGone.value = true
    }
    LaunchedEffect(Unit) {
        delay(2500)
        firstFrame.value = true
    }

    // 歌单变化（库内删除等）：重推
    LaunchedEffect(webRef.value, queueJson) {
        val wv = webRef.value ?: return@LaunchedEffect
        pushQueue(wv, queueJson, stateRef.value)
    }

    // 播放状态/进度校准：500ms 一推（HTML 本地插值平滑，两推之间 +dt）
    LaunchedEffect(webRef.value) {
        val wv = webRef.value ?: return@LaunchedEffect
        while (true) {
            pushPlayback(wv, stateRef.value)
            delay(500)
        }
    }

    // 封面推送：页面就绪后启动（重载后 onPageFinished 置回 true 会整段重跑）。
    // 当前曲 ±6 张优先逐张推（80ms）；其余合并成批——一次 evaluateJavascript
    // 带 5 张：大曲库逐张 250ms 要几十秒，滑列表比推送快，新碟片只能显示占位标签
    LaunchedEffect(webRef.value, queue, pageReady.value) {
        val wv = webRef.value ?: return@LaunchedEffect
        if (queue.isEmpty() || !pageReady.value) return@LaunchedEffect
        val cur = stateRef.value.queueIndex.coerceIn(0, queue.lastIndex)
        val order = buildList {
            for (d in 0..6) {
                (cur + d).takeIf { it < queue.size }?.let(::add)
                (cur - d).takeIf { it >= 0 }?.let(::add)
            }
            queue.indices.forEach(::add)
        }.distinct()
        val priorityCount = minOf(13, order.size) // ±6 窗口
        order.take(priorityCount).forEach { i ->
            val url = coverDataUrl(context, queue[i]) ?: return@forEach
            val js = bridgeInject("window.__vinyl.setCover($i, \"$url\");")
            wv.post { wv.evaluateJavascript(js, null) }
            delay(80)
        }
        order.drop(priorityCount).chunked(5).forEach { chunk ->
            delay(300) // 优先窗口推完停一拍，避开入场操作高峰
            val entries = chunk.mapNotNull { i ->
                coverDataUrl(context, queue[i])?.let { "\"$i\":\"$it\"" }
            }
            if (entries.isNotEmpty()) {
                val js = bridgeInject("window.__vinyl.setCovers({${entries.joinToString(",")}});")
                wv.post { wv.evaluateJavascript(js, null) }
            }
        }
    }

    // 歌词推送：页面就绪后，当前曲变化时重取。内嵌 + 本地缓存优先，用户开了
    // 在线歌词才联网补当前曲这一首（与歌词页同源设置），并应用每首歌的偏移记忆
    LaunchedEffect(webRef.value, state.queueIndex, queue, pageReady.value) {
        val wv = webRef.value ?: return@LaunchedEffect
        if (!pageReady.value) return@LaunchedEffect
        val song = queue.getOrNull(state.queueIndex) ?: return@LaunchedEffect
        pushLyrics(wv, context, song)
    }
}

// ===== 桥接对象（JS 线程回调，内部已 post 主线程） =====

private class VinylBridge(
    val onPlayAtIdx: (Int) -> Unit,
    val onToggle: () -> Unit,
    val onSeekSec: (Float) -> Unit,
    val onVolumeValue: (Float) -> Unit,
    val onHapticMs: (Int) -> Unit,
    val onClosePage: () -> Unit,
    val onFirstFrameReady: () -> Unit,
    val onSfxChanged: (Boolean) -> Unit,
    val onPlayModeRequest: (String) -> Unit
) {
    /** 预览选中并落片播放（音频先动：原生立刻 playAt） */
    @JavascriptInterface
    fun onSelectSong(idx: Int) = onPlayAtIdx(idx)

    /** 页面调试日志上抛（logcat 过滤 VinylDebug），用于落片链路竞态取证 */
    @JavascriptInterface
    fun onDebug(msg: String?) {
        Log.d("VinylDebug", msg ?: "null")
    }

    @JavascriptInterface
    fun onTogglePlay() = onToggle()

    /** 进度条拖动落点（秒） */
    @JavascriptInterface
    fun onSeek(sec: Float) = onSeekSec(sec)

    @JavascriptInterface
    fun onVolume(v: Float, muted: Boolean) = onVolumeValue(if (muted) 0f else v)

    @JavascriptInterface
    fun onHaptic(ms: Int) = onHapticMs(ms)

    @JavascriptInterface
    fun onClose() = onClosePage()

    /** 首帧渲染完毕：原生进入遮罩开始淡出 */
    @JavascriptInterface
    fun onFirstFrame() = onFirstFrameReady()

    /** 页面内机械音效开关切换：回写 Prefs，设置页读同一份 */
    @JavascriptInterface
    fun onSfxEnabled(enabled: Boolean) = onSfxChanged(enabled)

    /** 播放模式切换请求（'shuffle'/'repeat_all'/'repeat_one'）：与主界面同一套三态逻辑 */
    @JavascriptInterface
    fun onPlayModeRequested(mode: String) = onPlayModeRequest(mode)

    @JavascriptInterface
    fun onLog(msg: String?) {
        Log.d("VinylWeb", msg ?: "")
    }
}

// ===== 推送实现 =====

/**
 * 桥注入统一包装：页面尚未加载完时 __vinyl 未定义，一次性注入会被
 * `window.__vinyl && ...` 守卫静默丢弃且永不重推（封面歌词"部分缺失"的根源）。
 * 自重试直到桥就绪：30 次 × 120ms ≈ 3.6s，足够覆盖 onPageFinished。
 * 仅包裹必须送达的一次性推送；syncPlayback 等周期性推送自带下一拍，无需包装。
 */
private fun bridgeInject(statement: String): String =
    "(function r(n){if(window.__vinyl){$statement}else if(n>0)setTimeout(function(){r(n-1)},120)})(30);"

private fun pushQueue(wv: WebView, json: String, state: PlayerUiState) {
    val js = "window.__vinyl && window.__vinyl.setQueue($json, ${state.queueIndex}, ${state.isPlaying}, ${state.volume}, ${state.volume <= 0.001f});"
    wv.post { wv.evaluateJavascript(js, null) }
}

/** 把 WebView 真实布局尺寸换算成 CSS px 推给页面，硬设视口高度（修复 100vh=0 黑屏） */
private fun pushViewport(wv: WebView) {
    val w = wv.width
    val h = wv.height
    if (w <= 0 || h <= 0) return
    val dpr = wv.resources.displayMetrics.density
    if (dpr <= 0f) return
    val js = "window.__forceViewport && window.__forceViewport(${w / dpr}, ${h / dpr})"
    wv.post { wv.evaluateJavascript(js, null) }
}

private fun pushPlayback(wv: WebView, state: PlayerUiState) {
    if (state.queueIndex < 0) return
    val pos = state.positionMs / 1000.0
    val dur = state.durationMs / 1000.0
    // 播放模式随 500ms 校准一并发（'shuffle'/'repeat_all'/'repeat_one'），
    // 页面按钮图标由它驱动——500ms 周期推送天然自愈，不怕时序竞争
    val mode = PlayMode.of(state.shuffle, state.repeat).name.lowercase()
    val js = "window.__vinyl && window.__vinyl.syncPlayback(${state.queueIndex}, ${state.isPlaying}, ${state.isBuffering}, $pos, $dur, \"$mode\");"
    wv.post { wv.evaluateJavascript(js, null) }
}

/** 推歌词：[{t:毫秒,text,tr}]，t=-1 表示无时间轴纯文本，空数组表示无歌词 */
private suspend fun pushLyrics(wv: WebView, context: android.content.Context, song: Song) =
    withContext(Dispatchers.IO) {
        val prefs = Prefs(context)
        val result = runCatching {
            LyricsRepository.load(
                context = context,
                songId = song.id,
                uri = song.uri,
                title = song.title,
                artist = song.artist,
                album = song.album,
                // 与歌词页同源：用户开了在线歌词才联网补当前曲这一首
                //（仓库约束：一次只为一首歌发一次请求，拿到/没拿到都落盘）
                onlineEnabled = prefs.onlineLyricsEnabled,
                apiBase = prefs.lyricsApiBase,
                convertToSimplified = prefs.lyricsConvertToSimplified,
                durationMs = song.durationMs,
                neteaseEnabled = prefs.neteaseFillEnabled
            )
        }.getOrNull()
        val lines = JSONArray()
        var synced = false
        when (val l = result?.lyrics) {
            is Lyrics.Synced -> {
                synced = true
                val offset = Prefs(context).lyricsOffsetMs(song.title, song.artist)
                l.lines.forEach { line ->
                    lines.put(
                        JSONObject()
                            .put("t", line.timeMs + offset)
                            .put("text", line.text)
                            .put("tr", line.translation ?: "")
                    )
                }
            }
            is Lyrics.Plain -> l.lines.forEach { text ->
                lines.put(JSONObject().put("t", -1).put("text", text).put("tr", ""))
            }
            else -> {}
        }
        val payload = JSONObject().put("synced", synced).put("lines", lines)
        val js = bridgeInject("window.__vinyl.setLyrics($payload);")
        wv.post { wv.evaluateJavascript(js, null) }
    }

private fun buildQueueJson(queue: List<Song>): String {
    val arr = JSONArray()
    queue.forEach { s ->
        arr.put(
            JSONObject().apply {
                put("title", s.title)
                put("artist", s.artist)
                put("duration", (s.durationMs / 1000).toInt())
            }
        )
    }
    return arr.toString()
}

private suspend fun coverDataUrl(context: android.content.Context, song: Song): String? =
    withContext(Dispatchers.IO) {
        try {
            // 与主界面三层封面链路对齐：内存在线封面 → 本地（内嵌/MediaStore）→
            // 磁盘下载封面（coverKey 是在线补图/手选封面/在线歌共用的落盘键）。
            // 旧实现漏传 coverKey —— 封面只存在 CoverCache 里的歌全部返回 null，
            // 表现为黑胶页部分碟片没有封面
            val img = ArtworkLoader.online(song.id)
                ?: ArtworkLoader.load(
                    // 640：转盘大碟用 1024 纹理（标签区约 664px），320 在 2x 屏上是
                    // 被放大 2 倍再采样——封面上那层细纹/字就是糊的
                    context, song.albumId, 640, song.uri,
                    OnlineCover.keyOf(song.title, song.artist)
                )
                ?: return@withContext null
            val bmp = img.asAndroidBitmap()
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 78, out)
            "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }
