package com.sonance.player.playback

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Surface
import android.view.TextureView
import java.io.File

/**
 * 一个视频页的播放器 + 渲染层组合，替代原来的 `VideoView`。
 *
 * `VideoView` 只能把画面塞进 SurfaceView，无法在送显前插入 shader；这里改用
 * [TextureView] + [VideoGlRenderer]，并把原本 `VideoView` 包办的事情（surface
 * 就绪 → 绑定 → prepare → 尺寸回调 → 释放）自己按顺序做一遍。
 *
 * ## 顺序是这次的命门
 *
 * 上一轮 `-38` 的根因之一就是"surface 还没就绪就 prepare"。所以这里用一个显式
 * 状态机：
 *
 * ```
 * attachView() → onSurfaceTextureAvailable() → surfaceReady = true
 *                                              ↓（同时 setDataSource）
 *                                        prepareAsync()
 * ```
 *
 * [surfaceReady] 为 false 时**绝不**调用 `prepareAsync`，[setSource] 只把路径记下来。
 * 如果走提亮路径，`surfaceReady` 要等到 GL 渲染器把它的输入 SurfaceTexture 交回来
 * 才算成立 —— 也就是"替身 surface 也真就绪了"，不会把老问题带回来。
 *
 * 渲染器初始化失败时走 [fallbackToDefault]，先把 MediaPlayer 绑回 TextureView 的
 * 表层，再继续 prepare：宁可没提亮，也不能黑屏。
 */
class BiliVideoPlayer(
    private val context: Context,
    private val logTag: String,
    private val key: String,
    private val onPrepared: () -> Unit,
    private val onError: (what: Int, extra: Int) -> Unit,
    private val onCompleted: () -> Unit,
    private val onVideoSize: (width: Int, height: Int, sarNum: Int, sarDen: Int) -> Unit,
    /** 容器头里读到的像素宽高比（MediaPlayer 不暴露 SAR，只能自己解析） */
    private val sourceSarNum: Int = 1,
    private val sourceSarDen: Int = 1
) {

    private val main = Handler(Looper.getMainLooper())
    private val player = MediaPlayer()
    private val audioManager: AudioManager? =
        context.applicationContext.getSystemService(AudioManager::class.java)

    /* ================= 音频焦点 =================
     *
     * 用系统音频焦点和音乐互斥，而不是自己写广播：
     * - 视频开播请求 AUDIOFOCUS_GAIN → 音乐服务的 ExoPlayer（handleAudioFocus=true）
     *   会收到 LOSS 并自动暂停，队列与进度原样保留；
     * - 音乐回来点播放 → ExoPlayer 请求焦点 → 这里收到 LOSS → 暂停视频；
     * - 来电 / 其它 App 抢焦点（LOSS_TRANSIENT）→ 暂停，并在恢复时**只**在
     *   本来就是自己在播的情况下续播（用户手动暂停过的不会被偷偷续上）。
     *
     * 请求失败（返回 DELAYED / FAILED）时不阻塞播放：照常出声，只是下次
     * start() 再试一次焦点 —— 这就是"优雅降级"。
     */
    private var focusRequest: AudioFocusRequest? = null
    private var hasFocus = false

    /** 因焦点临时丢失而暂停时置 true；GAIN 回来才续播 */
    private var resumeOnFocusGain = false

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        main.post {
            if (released) return@post
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS -> {
                    // 被音乐 / 其它 App 长期抢走：暂停并放弃焦点
                    resumeOnFocusGain = false
                    hasFocus = false
                    runCatching { if (prepared) player.pause() }
                    Log.i(logTag, "音频焦点丢失，暂停视频: key=$key")
                }

                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    // 来电等：记下原本是否在播，回来时续播
                    resumeOnFocusGain = isPlaying
                    hasFocus = false
                    runCatching { if (prepared) player.pause() }
                    Log.i(logTag, "音频焦点临时丢失，暂停视频: key=$key")
                }

                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    // 视频不做"压低音量继续放"，直接停，避免两边都有声音
                    resumeOnFocusGain = isPlaying
                    hasFocus = false
                    runCatching { if (prepared) player.pause() }
                }

                AudioManager.AUDIOFOCUS_GAIN -> {
                    hasFocus = true
                    if (resumeOnFocusGain) {
                        resumeOnFocusGain = false
                        Log.i(logTag, "音频焦点恢复，续播视频: key=$key")
                        runCatching { if (prepared) player.start() }
                    }
                }
            }
        }
    }

    private var textureView: TextureView? = null
    private var outputSurface: Surface? = null
    /** 当前 outputSurface 包住的那个 SurfaceTexture，用来识别重复回调 */
    private var boundTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null
    private var renderer: VideoGlRenderer? = null

    private var pendingPath: String? = null
    private var surfaceReady = false
    private var prepared = false
    private var released = false

    private var hdrContent = false

    /** 当前档位。只有它不是 OFF 时才会创建 GL 渲染器 */
    var gainLevel: GainLevel = GainLevel.OFF
        private set

    /** 提亮路径是否真的在跑（失败回退后为 false） */
    var brightnessActive = false
        private set

    /** 是否已经真正 prepared。外层只有在这个为 true 时才读时长/进度 */
    val isPrepared: Boolean get() = prepared

    val isPlaying: Boolean get() = prepared && runCatching { player.isPlaying }.getOrDefault(false)

    /**
     * 只有 prepared 之后才去问 MediaPlayer。
     *
     * IDLE / PREPARING / 已 release 的实例在读 `duration` 时会被 native 判成
     * wrong state，并把 MEDIA_ERROR(-38) 回吐给 `setOnErrorListener` ——
     * 实测表现为满屏 "解码器初始化失败 (-38)"，并且把 UI 的 videoReady 打回 false。
     */
    val currentPosition: Int
        get() = if (prepared && !swappingSurface) {
            runCatching { player.currentPosition }.getOrDefault(0)
        } else 0

    val duration: Int
        get() = if (prepared && !swappingSurface) {
            runCatching { player.duration }.getOrDefault(0)
        } else 0

    /**
     * 正在换表层（默认 ↔ 渲染器）。这几十毫秒里解码器处于"没有输出表面"的中间态，
     * 读时长会被 native 直接当成 wrong state，所以这段时间既不读也不上报错误。
     */
    @Volatile
    private var swappingSurface = false

    /** 把 TextureView 挂上来；只在这之后才开始关心 surface 什么时候就绪 */
    fun attachView(view: TextureView) {
        if (textureView !== view) detachView()
        textureView = view
        updateViewSize(view.width, view.height)
        view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                updateViewSize(w, h)
                onSurfaceAvailable(st)
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                updateViewSize(w, h)
                renderer?.updateViewSize(w, h)
            }

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                Log.i(logTag, "TextureView 销毁: key=$key")
                // 先解绑解码器，再拆渲染层与表层包装，避免它抓着已失效的 ST
                runCatching { player.setSurface(null) }
                releaseGlPath()
                // 我们的 Surface 是包在这个 SurfaceTexture 外面的，先放掉，
                // 再让系统回收（返回 true）
                outputSurface?.release()
                outputSurface = null
                boundTexture = null
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
        }
        if (view.isAvailable) {
            view.surfaceTexture?.let { onSurfaceAvailable(it) }
        }
    }

    /**
     * 把宿主视图摘下来，但**不释放解码器**。
     *
     * 全屏页 → 浮窗、浮窗 → 全屏都靠这个换宿主：音频继续走，视频帧暂时不出；
     * 新的 TextureView 再 [attachView] 上来时，表层重新绑定即可，不需要重新 prepare。
     * 注意要先把监听器摘掉，否则旧 TextureView 销毁时会回调并把我这边的 surface 置空。
     */
    fun detachView() {
        val v = textureView ?: return
        textureView = null
        v.surfaceTextureListener = null
        swappingSurface = true
        runCatching { if (prepared) player.setSurface(null) }
        runCatching { releaseGlPath() }
        outputSurface?.release()
        outputSurface = null
        boundTexture = null
        swappingSurface = false
        Log.i(logTag, "detachView（保留解码器）: key=$key")
    }

    private fun updateViewSize(w: Int, h: Int) {
        if (w > 0 && h > 0) renderer?.updateViewSize(w, h)
        lastViewWidth = w
        lastViewHeight = h
    }

    private var lastViewWidth = 1
    private var lastViewHeight = 1

    /** 视频原始尺寸 + 像素宽高比。运行时才开提亮时要补推给新建的渲染器 */
    private var videoWidth = 0
    private var videoHeight = 0

    init {
        /*
         * 熄屏续播的唤醒锁交给 MediaPlayer 自己管。
         *
         * 只用 PARTIAL_WAKE_LOCK（只保 CPU、不亮屏），而且系统保证它**只在播放期间**持有：
         * pause / stop / release 时自动释放。这样既不会因为 CPU 休眠而断音，
         * 也不会像自己拿一把长锁那样在暂停后继续耗电。
         */
        runCatching {
            player.setWakeMode(context.applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
        }.onFailure { Log.w(logTag, "setWakeMode 失败: ${it.message}") }

        player.setOnPreparedListener {
            prepared = true
            Log.i(logTag, "prepared: key=$key ${it.duration}ms")
            onPrepared()
        }
        player.setOnCompletionListener {
            abandonFocus()
            onCompleted()
        }
        player.setOnErrorListener { _, what, extra ->
            // 换表层期间 native 会把中间态当成 -38 错误回吐，别让它影响起播状态
            if (swappingSurface && what == -38) {
                Log.w(logTag, "忽略换表层期间的错误: key=$key what=$what extra=$extra")
                return@setOnErrorListener true
            }
            Log.w(logTag, "MediaPlayer error: key=$key what=$what extra=$extra")
            prepared = false
            onError(what, extra)
            true
        }
        player.setOnVideoSizeChangedListener { _, w, h ->
            Log.i(logTag, "video size: key=$key ${w}x$h sar=$sourceSarNum/$sourceSarDen")
            videoWidth = w
            videoHeight = h
            renderer?.updateVideoSize(w, h, sourceSarNum, sourceSarDen)
            onVideoSize(w, h, sourceSarNum, sourceSarDen)
        }
    }

    /** 在 attach 之前定档：档位和 HDR 结论决定走不走 GL */
    fun configure(level: GainLevel, hdr: Boolean) {
        gainLevel = level
        hdrContent = hdr
        if (hdr) Log.i(logTag, "HDR 内容，旁路提亮: key=$key")
    }

    fun setSource(path: String) {
        pendingPath = path
        // surfaceReady 为 false 时只记路径，绝不提前 prepare
        if (surfaceReady) doPrepare(path)
    }

    // ===== surface 处理 =====

    private fun onSurfaceAvailable(st: SurfaceTexture) {
        if (released) return
        // 同一个 SurfaceTexture 只处理一次：attachView 里的主动检查与系统回调
        // 都可能走到这里，重复时会 release 掉 MediaPlayer 正在用的表层
        val same = boundTexture === st
        if (same && outputSurface != null) {
            if (gainLevel.isActive && !hdrContent && renderer == null) ensureRenderer(st)
            return
        }
        Log.i(logTag, "surface 可用: key=$key 复用=$same")
        boundTexture = st
        outputSurface?.release()
        outputSurface = Surface(st)
        if (gainLevel.isActive && !hdrContent) {
            ensureRenderer(st)
        } else {
            runCatching { player.setSurface(outputSurface) }
            onSurfaceReady("default")
        }
    }

    private fun onSurfaceReady(mode: String) {
        if (surfaceReady) return
        surfaceReady = true
        Log.i(logTag, "surface 就绪($mode): key=$key，开始 prepare")
        pendingPath?.let { doPrepare(it) }
    }

    private var sourceSet = false

    private fun doPrepare(path: String) {
        if (released || prepared || sourceSet) return
        sourceSet = true
        Log.i(logTag, "prepareAsync: key=$key")
        runCatching {
            player.setDataSource(context, Uri.fromFile(File(path)))
            player.prepareAsync()
        }.onFailure {
            Log.w(logTag, "prepare 失败: key=$key err=${it.message}")
            onError(-1, -1)
        }
    }

    private fun ensureRenderer(st: SurfaceTexture) {
        if (renderer != null || released) return
        val r = VideoGlRenderer("SonanceVideo-${key.take(12)}", logTag)
        r.setHdr(hdrContent)
        r.updateGain(gainLevel)
        r.updateViewSize(lastViewWidth, lastViewHeight)
        if (videoWidth > 0 && videoHeight > 0) {
            r.updateVideoSize(videoWidth, videoHeight, sourceSarNum, sourceSarDen)
        }
        r.onTextureReady = { input ->
            main.post {
                if (released) return@post
                inputSurface?.release()
                inputSurface = Surface(input)
                // 顺序：先把 MediaPlayer 切到本渲染器的输入 Surface，再允许出图。
                // 反过来的话同一个 BufferQueue 会同时有两个生产者。
                runCatching { player.setSurface(inputSurface) }
                swappingSurface = false
                r.beginDraw()
                brightnessActive = true
                Log.i(logTag, "提亮渲染接管: key=$key level=${gainLevel.label}")
                onSurfaceReady("gl")
            }
        }
        r.onFailure = { reason -> main.post { fallbackToDefault(reason) } }
        renderer = r
        Log.i(logTag, "渲染层初始化: key=$key level=${gainLevel.label}")
        r.start(st)
    }

    /** 渲染层起不来：绑回 TextureView 默认表层，继续 prepare，宁可没提亮也不能黑屏 */
    private fun fallbackToDefault(reason: String) {
        if (released) return
        // 同样先拆渲染层（断开 EGL 作为生产者的连接），再让 MediaPlayer 绑回默认表层
        releaseGlPath()
        runCatching { player.setSurface(outputSurface) }
        swappingSurface = false
        Log.w(logTag, "GL 回退到默认显示: key=$key reason=$reason")
        onSurfaceReady("fallback")
    }

    private fun releaseGlPath() {
        // 先放 Surface 再销毁渲染器：Surface 是包在 SurfaceTexture 外面的，
        // 反过来在个别机型上会在 release 时崩
        inputSurface?.release()
        inputSurface = null
        renderer?.let { r ->
            r.onTextureReady = null
            r.onFailure = null
            r.dispose()
        }
        renderer = null
        brightnessActive = false
    }

    /**
     * 运行时切档。
     *
     * 这条路径上实测踩过的坑：TextureView 的 SurfaceTexture 只有**一个**生产者槽位。
     * `eglCreateWindowSurface` 要往它里面送帧，而 MediaPlayer 正占着这个槽位时，
     * 初始化会直接失败（日志 `reason=egl_init`），用户看到的就是"切了档没反应"。
     * 所以顺序必须是：解绑解码器 → 建 EGL 输出 → 再把解码器接到渲染器的输入表层。
     *
     * 反方向同理：先拆渲染层断开 EGL 生产者，再让 MediaPlayer 绑回默认表层；
     * 反过来会在同一个 BufferQueue 上出现两个生产者（实测 `state mismatch` + -38）。
     */
    fun setGain(level: GainLevel) {
        if (level == gainLevel) return
        gainLevel = level
        renderer?.updateGain(level)
        if (hdrContent) {
            Log.i(logTag, "HDR 旁路，忽略档位: key=$key level=${level.label}")
            return
        }
        val st = textureView?.surfaceTexture ?: return
        if (!level.isActive) {
            // ① 拆渲染层（eglDestroySurface 断开 EGL 生产者）
            swappingSurface = true
            releaseGlPath()
            // ② 解码器直接接回 TextureView 表层
            runCatching { player.setSurface(outputSurface) }
            swappingSurface = false
            Log.i(logTag, "提亮关闭，回到默认显示: key=$key")
        } else if (renderer == null) {
            // ① 先把解码器从 TextureView 表层摘下来，腾出 BufferQueue 的生产者槽位
            swappingSurface = true
            runCatching { player.setSurface(null) }
            Log.i(logTag, "解绑默认表层，准备接入提亮渲染: key=$key")
            // ② 建 EGL（成功后会回调 onTextureReady，把解码器接到渲染器输入表层）
            ensureRenderer(st)
        }
    }

    // ===== 播放控制（与 VideoView 的调用点对齐）=====

    fun start() {
        if (!prepared) return
        if (!hasFocus) {
            // 拿不到焦点也照常播（降级），下次 start() 会再试
            hasFocus = requestFocus()
        }
        resumeOnFocusGain = false
        runCatching { player.start() }
    }

    fun pause() {
        resumeOnFocusGain = false
        abandonFocus()
        if (prepared) runCatching { player.pause() }
    }

    /* ================= 音频焦点的申请 / 释放 ================= */

    private fun requestFocus(): Boolean {
        val am = audioManager ?: return false
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                            .build()
                    )
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener(focusListener, main)
                    .build()
                    .also { focusRequest = it }
                am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    focusListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
                ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
        }.getOrDefault(false)
    }

    private fun abandonFocus() {
        val had = hasFocus || focusRequest != null
        hasFocus = false
        if (!had) return
        val am = audioManager ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { am.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(focusListener)
            }
        }
    }

    fun seekTo(ms: Int) {
        if (prepared) runCatching { player.seekTo(ms) }
    }

    fun release() {
        if (released) return
        released = true
        Log.i(
            logTag,
            "release player(${if (brightnessActive) "gl" else "default"}): key=$key"
        )
        abandonFocus()
        runCatching { if (prepared) player.stop() }
        runCatching { player.release() }
        prepared = false
        releaseGlPath()
        outputSurface?.release()
        outputSurface = null
        textureView = null
    }
}

/**
 * 解一次容器头拿两样东西：是否 HDR、像素宽高比（SAR）。
 *
 * MediaPlayer 既不暴露色彩信息也不暴露 SAR，只能自己读。任何异常（字段不存在、
 * 老版本没有这个 key、文件异常）一律按 SDR + SAR 1:1 —— 最坏的结果是没提亮或
 * 按方形像素显示，不能因为探测失败而拒绝播放。
 */
internal data class VideoProbe(
    val hdr: Boolean = false,
    val sarNum: Int = 1,
    val sarDen: Int = 1
)

internal fun probeVideo(path: String): VideoProbe = runCatching {
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(path)
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (!mime.startsWith("video/")) continue
            val transfer = if (format.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
                format.getInteger(MediaFormat.KEY_COLOR_TRANSFER)
            } else {
                MediaFormat.COLOR_TRANSFER_SDR_VIDEO
            }
            val standard = if (format.containsKey(MediaFormat.KEY_COLOR_STANDARD)) {
                format.getInteger(MediaFormat.KEY_COLOR_STANDARD)
            } else {
                MediaFormat.COLOR_STANDARD_BT709
            }
            val hdr = transfer != MediaFormat.COLOR_TRANSFER_SDR_VIDEO ||
                standard != MediaFormat.COLOR_STANDARD_BT709
            val sarNum = if (format.containsKey(MediaFormat.KEY_PIXEL_ASPECT_RATIO_WIDTH)) {
                format.getInteger(MediaFormat.KEY_PIXEL_ASPECT_RATIO_WIDTH)
            } else 1
            val sarDen = if (format.containsKey(MediaFormat.KEY_PIXEL_ASPECT_RATIO_HEIGHT)) {
                format.getInteger(MediaFormat.KEY_PIXEL_ASPECT_RATIO_HEIGHT)
            } else 1
            return@runCatching VideoProbe(
                hdr = hdr,
                sarNum = if (sarNum > 0) sarNum else 1,
                sarDen = if (sarDen > 0) sarDen else 1
            )
        }
        VideoProbe()
    } finally {
        runCatching { extractor.release() }
    }
}.getOrDefault(VideoProbe())
