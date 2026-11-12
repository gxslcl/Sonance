package com.sonance.player.ui.wall

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sonance.player.data.Album
import com.sonance.player.data.Song
import com.sonance.player.playback.PlayerUiState
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * 封面墙的一个墙面单元：一张真实专辑，或曲库专辑太少时按单曲展开的一张「单曲卡」。
 *
 * [tracks] 是翻面列表里展示的曲目；[playContext] 是点播放时交给播放器的队列上下文 ——
 * 专辑卡两者相同（整张专辑入队），单曲卡展示一首、但队列仍是全曲库（下一首有得切）。
 */
data class WallAlbum(
    val key: String,
    val name: String,
    val artist: String,
    val fav: Boolean,
    val tracks: List<Song>,
    val playContext: List<Song>
)

/**
 * 专辑聚合 + 稀疏兜底：聚合专辑少于 24 张时按单曲展开，保证墙面铺满。
 * key 是页面与原生之间的不透明标识（专辑 = albumId+专辑名，与 groupAlbums 的分组键一致；
 * 单曲 = 歌曲 id），收藏专辑由页面布局引擎优先排进焦点大格。
 */
fun buildWallAlbums(albums: List<Album>, songs: List<Song>, favorites: Set<Long>): List<WallAlbum> {
    if (songs.isEmpty()) return emptyList()
    if (albums.size >= WALL_ALBUM_THRESHOLD) {
        return albums.map { a ->
            WallAlbum(
                key = "a:${a.albumId}:${a.name}",
                name = a.name,
                artist = a.artist,
                fav = a.songs.any { it.id in favorites },
                tracks = a.songs,
                playContext = a.songs
            )
        }
    }
    return songs.map { s ->
        WallAlbum(
            key = "s:${s.id}",
            name = s.title,
            artist = s.artist,
            fav = s.id in favorites,
            tracks = listOf(s),
            playContext = songs
        )
    }
}

private const val WALL_ALBUM_THRESHOLD = 24

/**
 * 全屏 WebView 封面墙：加载 assets/wall/wall.html（5.5 原型一比一 + 原生桥接）。
 *
 * 与黑胶页（VinylWebScreen）同一套架构范式：
 *  - HTML 保留全部视觉 / 物理 / 手势 / 3D 动画，封面仍是程序生成的虚拟艺术封面，
 *    跨桥只推真实专辑与曲目的文本元数据（几十 KB），零位图传输；
 *  - 原生推：专辑列表（setAlbums）/ 播放状态与进度（syncPlayback，500ms 校准）/
 *    安全区（setInsets）/ 陀螺仪倾角（setTilt，免 HTTPS）/ 前后台（setRenderActive）；
 *  - HTML 上抛：播放专辑 onPlayAlbum / 专辑入队 onQueueAlbum / 播放暂停 / 上下首 /
 *    seek / L1~L4 触感 / 关闭。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CoverWallWebScreen(
    state: PlayerUiState,
    albums: List<Album>,
    songs: List<Song>,
    favorites: Set<Long>,
    onClose: () -> Unit,
    onPlayAlbum: (WallAlbum, Int) -> Unit,
    onQueueAlbum: (WallAlbum) -> Unit,
    onTogglePlay: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // Bridge 回调一律走 rememberUpdatedState + 主线程 post，避免捕获过期闭包
    val cbClose = rememberUpdatedState(onClose)
    val cbPlayAlbum = rememberUpdatedState(onPlayAlbum)
    val cbQueueAlbum = rememberUpdatedState(onQueueAlbum)
    val cbToggle = rememberUpdatedState(onTogglePlay)
    val cbPrev = rememberUpdatedState(onPrev)
    val cbNext = rememberUpdatedState(onNext)
    val cbSeek = rememberUpdatedState(onSeek)

    val webRef = remember { mutableStateOf<WebView?>(null) }
    val stateRef = rememberUpdatedState(state)
    val wallAlbums = remember(albums, songs, favorites) { buildWallAlbums(albums, songs, favorites) }
    val wallAlbumsRef = rememberUpdatedState(wallAlbums)
    val albumsJson = remember(wallAlbums) { buildAlbumsJson(wallAlbums) }
    // 页面就绪标记：onPageFinished 后才推首包；渲染进程崩溃自动重载时经
    // onPageStarted 复位、完成后全量重推（同黑胶页的自愈机制）
    val pageReady = remember { mutableStateOf(false) }
    // 震动强度（关 / 轻 / 强）：页面调参面板切换时经 onHapticLevel 回写
    val hapticStrength = remember { mutableStateOf("light") }
    // 桥线程直接读取的强度副本：震动不经主线程 post，省掉一帧延迟以对齐滚动节拍
    val strengthRef = remember { java.util.concurrent.atomic.AtomicReference("light") }

    // 返回键优先交给页面：翻面 → 唱片插回墙上 → 调参面板 → 页面退出动画 → onClose。
    // handleBack 返回 false（页面还没就绪）时原生直接收起图层
    BackHandler {
        val wv = webRef.value
        if (wv == null) {
            cbClose.value()
        } else {
            wv.evaluateJavascript("window.__wall ? window.__wall.handleBack() : false") { res ->
                if (res == "false") cbClose.value()
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        // 沉浸全屏：隐藏状态栏（下滑可临时唤出），退出时恢复
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
                        wv.onPause()
                        wv.evaluateJavascript(
                            "window.__wall && window.__wall.setRenderActive(false)", null
                        )
                    }
                    Lifecycle.Event.ON_RESUME -> {
                        // 切后台回来系统会把状态栏还回来，重新压下去
                        insetsController?.hide(WindowInsetsCompat.Type.statusBars())
                        wv.onResume()
                        wv.evaluateJavascript(
                            "window.__wall && window.__wall.setRenderActive(true)", null
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

    // 安全区桥传：状态栏（IgnoringVisibility，隐藏后仍取真实值）与挖孔取大者 → --sat，
    // 手势条 → --sab。旋转/折叠态变化时由 insets listener 重推
    val insetReceiver = { wv: WebView ->
        val d = wv.resources.displayMetrics.density.takeIf { it > 0f } ?: 1f
        val compat = wv.rootWindowInsets?.let { WindowInsetsCompat.toWindowInsetsCompat(it, wv) }
        val status = compat?.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars())
        val cutout = compat?.getInsets(WindowInsetsCompat.Type.displayCutout())
        val nav = compat?.getInsets(WindowInsetsCompat.Type.navigationBars())
        val top = maxOf(status?.top ?: 0, cutout?.top ?: 0) / d
        val bottom = (nav?.bottom ?: 0) / d
        wv.evaluateJavascript(bridgeInject("window.__wall.setInsets($top, $bottom);"), null)
    }

    // 陀螺仪 → setTilt：优先 GAME_ROTATION_VECTOR（无磁干扰、姿态平滑），
    // 缺传感器时退到加速度计低通。四元数直接解出重力在设备 XY 的投影，
    // 页面内 ori.base 慢速跟随静息姿态做零点校准，30Hz 节流推送
    DisposableEffect(lifecycleOwner) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        var listener: SensorEventListener? = null
        if (sensorManager != null && sensor != null) {
            listener = object : SensorEventListener {
                private var sx = 0f
                private var sy = 0f
                private var primed = false
                private var lastPush = 0L
                private var lastCx = 0f
                private var lastCy = 0f
                private var primedPush = false

                override fun onSensorChanged(event: SensorEvent) {
                    val gx: Float
                    val gy: Float
                    if (event.sensor.type == Sensor.TYPE_GAME_ROTATION_VECTOR) {
                        val x = event.values[0]
                        val y = event.values[1]
                        val z = event.values[2]
                        val w = if (event.values.size > 3) event.values[3] else 0f
                        gx = 2f * (x * z - w * y)
                        gy = 2f * (y * z + w * x)
                    } else {
                        gx = event.values[0] / SensorManager.GRAVITY_EARTH
                        gy = event.values[1] / SensorManager.GRAVITY_EARTH
                    }
                    if (!primed) {
                        sx = gx; sy = gy; primed = true
                    } else {
                        sx += (gx - sx) * 0.18f
                        sy += (gy - sy) * 0.18f
                    }
                    val tn = SystemClock.elapsedRealtime()
                    if (tn - lastPush < 33) return
                    lastPush = tn
                    val wv = webRef.value ?: return
                    val cx = sx.coerceIn(-1.5f, 1.5f)
                    val cy = sy.coerceIn(-1.5f, 1.5f)
                    // 静止死区：手机不动时不再 30Hz 推 setTilt，页面得以停摆省电；
                    // 真实倾角（>0.003 归一化重力）立即恢复推送
                    if (primedPush && kotlin.math.abs(cx - lastCx) < 0.003f && kotlin.math.abs(cy - lastCy) < 0.003f) return
                    lastCx = cx; lastCy = cy; primedPush = true
                    val js = "window.__wall && window.__wall.setTilt(" +
                        String.format(Locale.US, "%.4f", cx) + ", " +
                        String.format(Locale.US, "%.4f", cy) + ");"
                    wv.post { wv.evaluateJavascript(js, null) }
                }

                override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
            }
            sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        }
        onDispose { listener?.let { sensorManager?.unregisterListener(it) } }
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
                    // 不走缓存：assets 页面每次从安装包重读，避免 install -r 后渲染旧缓存
                    settings.cacheMode = WebSettings.LOAD_NO_CACHE
                    setBackgroundColor(0xFF050507.toInt())
                    addJavascriptInterface(
                        WallBridge(
                            onPlayAlbumKey = { key, idx ->
                                post {
                                    val wa = wallAlbumsRef.value.firstOrNull { it.key == key }
                                    if (wa != null) cbPlayAlbum.value(wa, idx)
                                }
                            },
                            onQueueAlbumKey = { key ->
                                post {
                                    val wa = wallAlbumsRef.value.firstOrNull { it.key == key }
                                    if (wa != null) cbQueueAlbum.value(wa)
                                }
                            },
                            onToggle = { post { cbToggle.value() } },
                            onPrevTrack = { post { cbPrev.value() } },
                            onNextTrack = { post { cbNext.value() } },
                            onSeekSec = { sec -> post { cbSeek.value((sec * 1000).toLong()) } },
                            onHapticEvent = { level, _, durMs ->
                                // Vibrator 线程安全：直接在桥线程震动，省掉主线程 post 的一帧延迟，
                                // 让震动脉冲与页面滚动节拍对齐
                                performWallHaptic(ctx, level, durMs, strengthRef.get())
                            },
                            onHapticLevelChanged = { level -> strengthRef.set(level); post { hapticStrength.value = level } },
                            onClosePage = { post { cbClose.value() } }
                        ),
                        "WallBridge"
                    )
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            pageReady.value = false
                        }

                        override fun onPageFinished(view: WebView, url: String?) {
                            pushAlbums(view, albumsJson, stateRef.value, wallAlbumsRef.value)
                            pushPlayback(view, stateRef.value, wallAlbumsRef.value)
                            insetReceiver(view)
                            pushViewport(view)
                            // 部分 ROM 给 <button> 套系统原生 drawable，圆角裁剪压不住，
                            // 注入 !important 兜底（同黑胶页）
                            view.evaluateJavascript(
                                "(function(){var id='sonance-native-button-off';" +
                                    "if(document.getElementById(id))return;" +
                                    "var s=document.createElement('style');s.id=id;" +
                                    "s.textContent='button,button:active,button:focus," +
                                    "button:focus-visible,[role=button]{" +
                                    "-webkit-appearance:none!important;appearance:none!important;" +
                                    "-webkit-tap-highlight-color:transparent!important;" +
                                    "outline:none!important}';" +
                                    "document.head.appendChild(s);})()",
                                null
                            )
                            pageReady.value = true
                        }

                        override fun onReceivedError(
                            view: WebView,
                            request: WebResourceRequest?,
                            error: WebResourceError?
                        ) {
                            super.onReceivedError(view, request, error)
                            Log.e("WallWeb", "加载失败: ${request?.url} ${error?.description}")
                        }
                    }
                    // 布局变化时把真实尺寸按 CSS px 推给页面（修复 innerHeight=0 冻结）
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
                    setOnApplyWindowInsetsListener { v, insets ->
                        insetReceiver(v as WebView)
                        insets
                    }
                    // 按下即声明不让父级拦截触摸流，避免 JS 侧 pointercancel 风暴
                    setOnTouchListener { v, ev ->
                        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                        }
                        false
                    }
                    webRef.value = this
                    loadUrl("file:///android_asset/wall/wall.html")
                }
            }
        )
    }

    // 曲库变化（扫描完成 / 收藏变化）：重推专辑列表，页面保留滚动位置与当前专辑
    LaunchedEffect(webRef.value, albumsJson, pageReady.value) {
        val wv = webRef.value ?: return@LaunchedEffect
        if (!pageReady.value) return@LaunchedEffect
        pushAlbums(wv, albumsJson, stateRef.value, wallAlbumsRef.value)
    }

    // 播放状态/进度校准：500ms 一推（页面本地插值平滑，两推之间 +dt）
    LaunchedEffect(webRef.value) {
        val wv = webRef.value ?: return@LaunchedEffect
        while (true) {
            pushPlayback(wv, stateRef.value, wallAlbumsRef.value)
            delay(500)
        }
    }
}

// ===== 桥接对象（JS 线程回调，内部已 post 主线程） =====

private class WallBridge(
    val onPlayAlbumKey: (String, Int) -> Unit,
    val onQueueAlbumKey: (String) -> Unit,
    val onToggle: () -> Unit,
    val onPrevTrack: () -> Unit,
    val onNextTrack: () -> Unit,
    val onSeekSec: (Float) -> Unit,
    val onHapticEvent: (Int, String, Int) -> Unit,
    val onHapticLevelChanged: (String) -> Unit,
    val onClosePage: () -> Unit
) {
    /** 抽出播放 / 随便听听锁定 / 翻面点播放：立即出声（音频先动，动画随后） */
    @JavascriptInterface
    fun onPlayAlbum(albumKey: String, trackIndex: Int) = onPlayAlbumKey(albumKey, trackIndex)

    /** 翻面「下一首播放」：整张专辑曲目加入下一首播放队列 */
    @JavascriptInterface
    fun onQueueAlbum(albumKey: String) = onQueueAlbumKey(albumKey)

    @JavascriptInterface
    fun onTogglePlay() = onToggle()

    @JavascriptInterface
    fun onPrev() = onPrevTrack()

    @JavascriptInterface
    fun onNext() = onNextTrack()

    /** 进度条点击/拖动落点（秒） */
    @JavascriptInterface
    fun onSeek(sec: Float) = onSeekSec(sec)

    /** L1 质感 / L2 确认 / L3 冲击 / L4 蓄力，durMs 仅 L1 用（滚动节拍脉冲时长） */
    @JavascriptInterface
    fun onHaptic(level: Int, label: String?, durMs: Int) =
        onHapticEvent(level, label ?: "", durMs)

    /** 调参面板「关 / 轻 / 强」切换（页面启动时也会同步一次持久化值） */
    @JavascriptInterface
    fun onHapticLevel(strength: String?) = onHapticLevelChanged(strength ?: "light")

    @JavascriptInterface
    fun onClose() = onClosePage()

    /** 页面脚本就绪：首包由 onPageFinished 推，这里只记日志 */
    @JavascriptInterface
    fun onReady() {
        Log.d("WallWeb", "page ready")
    }

    @JavascriptInterface
    fun onLog(msg: String?) {
        Log.d("WallWeb", msg ?: "")
    }
}

// ===== 推送实现 =====

/**
 * 桥注入统一包装（同黑胶页）：页面尚未加载完时 __wall 未定义，一次性注入会被
 * 守卫静默丢弃且永不重推。自重试直到桥就绪：30 次 × 120ms ≈ 3.6s。
 * 仅包裹必须送达的一次性推送；syncPlayback 等周期推送自带下一拍，无需包装。
 */
private fun bridgeInject(statement: String): String =
    "(function r(n){if(window.__wall){$statement}else if(n>0)setTimeout(function(){r(n-1)},120)})(30);"

private fun buildAlbumsJson(list: List<WallAlbum>): String {
    val arr = JSONArray()
    list.forEach { a ->
        val tracks = JSONArray()
        a.tracks.forEach { s ->
            tracks.put(
                JSONObject()
                    .put("id", s.id.toString())
                    .put("title", s.title)
                    .put("dur", (s.durationMs / 1000).toInt())
            )
        }
        arr.put(
            JSONObject()
                .put("id", a.key)
                .put("name", a.name)
                .put("artist", a.artist)
                .put("fav", a.fav)
                .put("tracks", tracks)
        )
    }
    return arr.toString()
}

/** 当前播放对应的墙面 key：专辑模式 = albumId+专辑名；单曲展开模式 = 歌曲 id */
private fun currentWallKey(state: PlayerUiState, wallAlbums: List<WallAlbum>): String {
    if (!state.hasItem) return ""
    val expanded = wallAlbums.firstOrNull()?.key?.startsWith("s:") == true
    return if (expanded) "s:${state.mediaId}" else "a:${state.albumId}:${state.album}"
}

private fun pushAlbums(wv: WebView, json: String, state: PlayerUiState, wallAlbums: List<WallAlbum>) {
    val key = JSONObject.quote(currentWallKey(state, wallAlbums))
    val songId = JSONObject.quote(if (state.hasItem) state.mediaId.toString() else "")
    val pos = state.positionMs / 1000.0
    val dur = state.durationMs / 1000.0
    val js = bridgeInject(
        "window.__wall.setAlbums($json, $key, $songId, ${state.isPlaying}, $pos, $dur);"
    )
    wv.post { wv.evaluateJavascript(js, null) }
}

private fun pushPlayback(wv: WebView, state: PlayerUiState, wallAlbums: List<WallAlbum>) {
    val key = JSONObject.quote(currentWallKey(state, wallAlbums))
    val songId = JSONObject.quote(if (state.hasItem) state.mediaId.toString() else "")
    val title = JSONObject.quote(state.title)
    val artist = JSONObject.quote(state.artist)
    val pos = state.positionMs / 1000.0
    val dur = state.durationMs / 1000.0
    val js = "window.__wall && window.__wall.syncPlayback($key, $songId, $title, $artist, ${state.isPlaying}, $pos, $dur);"
    wv.post { wv.evaluateJavascript(js, null) }
}

/** 把 WebView 真实布局尺寸换算成 CSS px 推给页面（修复 100vh=0 黑屏，同黑胶页） */
private fun pushViewport(wv: WebView) {
    val w = wv.width
    val h = wv.height
    if (w <= 0 || h <= 0) return
    val dpr = wv.resources.displayMetrics.density
    if (dpr <= 0f) return
    val js = "window.__forceViewport && window.__forceViewport(${w / dpr}, ${h / dpr})"
    wv.post { wv.evaluateJavascript(js, null) }
}

// ===== L1~L4 原生触感 =====

/**
 * 四级分级震动，波形时长与页面 HV 节流窗口一致：
 *  L1 质感短脉冲（滚动节拍，时长由页面按速度给）/ L2 确认 / L3 冲击 / L4 蓄力序列。
 * 「轻 / 强」两档缩放时长与幅值；不支持幅值控制的马达忽略幅值项仍然会震。
 */
internal fun performWallHaptic(context: Context, level: Int, durMs: Int, strength: String) {
    if (strength == "off" || level !in 1..4) return
    runCatching {
        val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        if (vibrator?.hasVibrator() != true) return
        val strong = strength == "strong"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val effect = when (level) {
                1 -> VibrationEffect.createOneShot(
                    ((if (durMs > 0) durMs else 9) * (if (strong) 1.6f else 1f)).toLong().coerceAtLeast(4L),
                    if (strong) 180 else 110
                )
                2 -> VibrationEffect.createOneShot(if (strong) 26L else 16L, if (strong) 230 else 150)
                3 -> VibrationEffect.createOneShot(if (strong) 50L else 32L, 255)
                else -> {
                    val timings = if (strong) longArrayOf(0, 10, 32, 14, 24, 20, 16, 32)
                    else longArrayOf(0, 6, 36, 9, 28, 13, 20, 22)
                    val amps = if (strong) intArrayOf(0, 120, 0, 170, 0, 210, 0, 255)
                    else intArrayOf(0, 80, 0, 120, 0, 150, 0, 190)
                    VibrationEffect.createWaveform(timings, amps, -1)
                }
            }
            vibrator.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            when (level) {
                1 -> vibrator.vibrate(((if (durMs > 0) durMs else 9) * (if (strong) 1.6f else 1f)).toLong())
                2 -> vibrator.vibrate(if (strong) 26L else 16L)
                3 -> vibrator.vibrate(if (strong) 50L else 32L)
                else -> vibrator.vibrate(
                    if (strong) longArrayOf(10, 32, 14, 24, 20, 16, 32)
                    else longArrayOf(6, 36, 9, 28, 13, 20, 22),
                    -1
                )
            }
        }
    }
}