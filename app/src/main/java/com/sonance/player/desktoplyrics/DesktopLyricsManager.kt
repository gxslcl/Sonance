package com.sonance.player.desktoplyrics

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.view.isVisible
import com.sonance.player.R
import com.sonance.player.data.Prefs
import com.sonance.player.playback.PlaybackCommands
import com.sonance.player.playback.PlaybackService
import com.sonance.player.widget.WidgetState
import kotlin.math.hypot

/**
 * 桌面悬浮歌词管理器。
 *
 * 功能特性：
 * 1. 系统级悬浮窗口，支持三种背景选择：没有背景（纯透明立体字）、浅色半透明、深色半透明。
 * 2. 自由拖动定位并记忆坐标位置。
 * 3. 单击展开迷你快捷播控（上曲、播暂、下曲、背景样式循环、字号循环、手势完全穿透锁定、关闭）。
 * 4. 锁定后手势完全穿透（FLAG_NOT_TOUCHABLE），通知栏常驻一键解锁通知。
 * 5. 随媒体播放服务 250ms 实时同步逐行歌词与延迟偏移，毫秒级平滑切句。
 */
object DesktopLyricsManager {

    val TEXT_COLOR_OPTIONS: List<Pair<Int, String>> = listOf(
        0 to "自动",
        0xFFFFFFFF.toInt() to "纯白",
        0xFFFFD98A.toInt() to "暖黄",
        0xFF9BE8C1.toInt() to "薄荷",
        0xFF9CD3FF.toInt() to "天蓝",
        0xFFFFAFA4.toInt() to "珊瑚",
        0xFFC7AEFF.toInt() to "紫罗兰"
    )

    @Volatile
    var onTextColorChanged: ((Int) -> Unit)? = null

    /** 悬浮窗字号加减按钮的步长：设置页滑杆仍可细调 12–120sp */
    private const val FONT_SIZE_STEP_SP = 4

    /** 写入字号偏好并立即刷新悬浮窗（设置页滑杆 / 悬浮窗按钮共用） */
    fun applyFontSize(context: Context, sizeSp: Int) {
        val size = sizeSp.coerceIn(12, 120)
        Prefs(context).desktopLyricsFontSizeSp = size
        mainHandler.post {
            tvCurrent?.textSize = size.toFloat()
            tvNext?.textSize = (size - 4).coerceAtLeast(12).toFloat()
        }
    }

    /** 悬浮窗上加号/减号直接放大或缩小字号 */
    fun adjustFontSize(context: Context, deltaSp: Int) {
        if (deltaSp == 0) return
        val cur = Prefs(context.applicationContext).desktopLyricsFontSizeSp
        val next = (cur + deltaSp).coerceIn(12, 120)
        applyFontSize(context, next)
        Toast.makeText(context, "桌面歌词字号: ${next}sp", Toast.LENGTH_SHORT).show()
    }

    private const val CHANNEL_ID = "sonance_desktop_lyrics"
    private const val NOTIFICATION_ID = 20048

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var windowManager: WindowManager? = null

    @Volatile
    private var floatingView: View? = null

    @Volatile
    private var layoutParams: WindowManager.LayoutParams? = null

    private var rootView: LinearLayout? = null
    private var tvCurrent: TextView? = null
    private var tvNext: TextView? = null
    private var controlsBar: LinearLayout? = null
    private var controlsDivider: View? = null
    private var btnPlayPause: ImageButton? = null
    private var btnPrev: ImageButton? = null
    private var btnNext: ImageButton? = null
    private var btnBgStyle: ImageButton? = null
    private var btnTextColor: ImageButton? = null
    private var btnFontMinus: ImageButton? = null
    private var btnFontPlus: ImageButton? = null
    private var btnLock: ImageButton? = null
    private var btnClose: ImageButton? = null

    @Volatile
    private var isPlayingState: Boolean = false
    @Volatile
    private var lastTitle: String = ""
    @Volatile
    private var lastArtist: String = ""
    @Volatile
    private var lastCurLyric: String = ""
    @Volatile
    private var lastNextLyric: String = ""

    private val autoHideRunnable = Runnable {
        hideControls()
    }

    /** 悬浮歌词是否正在展示 */
    fun isShowing(): Boolean = floatingView != null

    /** 检查是否有悬浮窗权限 */
    fun hasOverlayPermission(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    /** 是否已开启桌面歌词 */
    fun isEnabled(context: Context): Boolean {
        return Prefs(context).desktopLyricsEnabled
    }

    /** 是否处于锁定（手势穿透）状态 */
    fun isLocked(context: Context): Boolean {
        return Prefs(context).desktopLyricsLocked
    }

    /** 设置桌面歌词开关 */
    fun setEnabled(context: Context, enabled: Boolean) {
        Prefs(context).desktopLyricsEnabled = enabled
        if (enabled) {
            show(context)
            com.sonance.player.playback.PlaybackService.refreshLyricsIfEmpty()
        } else {
            hide(context)
        }
    }

    /** 设置锁定状态（穿透触摸） */
    fun setLocked(context: Context, locked: Boolean) {
        val app = context.applicationContext
        Prefs(app).desktopLyricsLocked = locked
        mainHandler.post {
            hideControls()
            val view = floatingView ?: return@post
            val params = layoutParams ?: return@post
            val wm = windowManager ?: return@post

            if (locked) {
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                showUnlockNotification(app)
            } else {
                params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                cancelUnlockNotification(app)
                Toast.makeText(app, "桌面歌词已解锁", Toast.LENGTH_SHORT).show()
            }
            runCatching { wm.updateViewLayout(view, params) }
        }
    }

    /** 循环切换背景样式 */
    fun cycleBgMode(context: Context) {
        val app = context.applicationContext
        val prefs = Prefs(app)
        val cur = DesktopLyricsBgMode.fromId(prefs.desktopLyricsBgMode)
        val next = cur.next()
        prefs.desktopLyricsBgMode = next.id
        mainHandler.post {
            applyBgMode(app, next)
            Toast.makeText(app, "桌面歌词背景: ${next.label}", Toast.LENGTH_SHORT).show()
        }
    }

    /** 设置特定背景样式 */
    fun setBgMode(context: Context, mode: DesktopLyricsBgMode) {
        val app = context.applicationContext
        Prefs(app).desktopLyricsBgMode = mode.id
        mainHandler.post {
            applyBgMode(app, mode)
        }
    }

    /** 用户在设置里改了歌词颜色：按当前背景模式整份重刷一遍 */
    fun applyTextColor(context: Context) {
        val app = context.applicationContext
        mainHandler.post {
            applyBgMode(app, DesktopLyricsBgMode.fromId(Prefs(app).desktopLyricsBgMode))
        }
    }

    /** 在桌面控制栏直接循环切换桌面歌词字体颜色 */
    fun cycleTextColor(context: Context) {
        val app = context.applicationContext
        val prefs = Prefs(app)
        val cur = prefs.desktopLyricsTextColor
        val curIdx = TEXT_COLOR_OPTIONS.indexOfFirst { it.first == cur }
        val nextIdx = if (curIdx < 0) 0 else (curIdx + 1) % TEXT_COLOR_OPTIONS.size
        val (nextColor, label) = TEXT_COLOR_OPTIONS[nextIdx]
        prefs.desktopLyricsTextColor = nextColor
        mainHandler.post {
            onTextColorChanged?.invoke(nextColor)
            applyBgMode(app, DesktopLyricsBgMode.fromId(prefs.desktopLyricsBgMode))
            Toast.makeText(app, "桌面歌词颜色: $label", Toast.LENGTH_SHORT).show()
        }
    }

    /** 应用背景模式到当前悬浮窗 */
    private fun applyBgMode(context: Context, mode: DesktopLyricsBgMode) {
        val root = rootView ?: return
        val curTv = tvCurrent ?: return
        val nextTv = tvNext ?: return
        val cBar = controlsBar ?: return
        val divider = controlsDivider

        when (mode) {
            DesktopLyricsBgMode.NONE -> {
                // 没有背景：纯悬浮透明，立体高对比阴影保证在任何桌面与应用上都清晰可见
                root.background = null
                root.elevation = 0f
                cBar.setBackgroundResource(R.drawable.desktop_lyrics_controls_bg)

                curTv.setTextColor(Color.WHITE)
                curTv.setShadowLayer(8f, 0f, 2f, 0xE6000000.toInt())

                nextTv.setTextColor(0xD9FFFFFF.toInt())
                nextTv.setShadowLayer(6f, 0f, 1f, 0xD9000000.toInt())

                val tintWhite = Color.WHITE
                btnPrev?.setColorFilter(tintWhite, PorterDuff.Mode.SRC_IN)
                btnPlayPause?.setColorFilter(tintWhite, PorterDuff.Mode.SRC_IN)
                btnNext?.setColorFilter(tintWhite, PorterDuff.Mode.SRC_IN)
                btnBgStyle?.setColorFilter(tintWhite, PorterDuff.Mode.SRC_IN)
                btnTextColor?.setColorFilter(tintWhite, PorterDuff.Mode.SRC_IN)
                btnFontMinus?.setColorFilter(tintWhite, PorterDuff.Mode.SRC_IN)
                btnFontPlus?.setColorFilter(tintWhite, PorterDuff.Mode.SRC_IN)
                btnLock?.setColorFilter(tintWhite, PorterDuff.Mode.SRC_IN)
                divider?.setBackgroundColor(0x33FFFFFF)
            }
            DesktopLyricsBgMode.LIGHT -> {
                // 浅色半透明：磨砂浅白圆角底板，深色文字高清晰可读性
                root.setBackgroundResource(R.drawable.desktop_lyrics_bg_light)
                root.elevation = 8f
                cBar.background = null

                curTv.setTextColor(0xDE1A1A1E.toInt())
                curTv.setShadowLayer(3f, 0f, 1f, 0x26000000.toInt())

                nextTv.setTextColor(0x8A2A2A32.toInt())
                nextTv.setShadowLayer(0f, 0f, 0f, 0)

                val tintDark = 0xFF2A2A32.toInt()
                btnPrev?.setColorFilter(tintDark, PorterDuff.Mode.SRC_IN)
                btnPlayPause?.setColorFilter(tintDark, PorterDuff.Mode.SRC_IN)
                btnNext?.setColorFilter(tintDark, PorterDuff.Mode.SRC_IN)
                btnBgStyle?.setColorFilter(tintDark, PorterDuff.Mode.SRC_IN)
                btnTextColor?.setColorFilter(tintDark, PorterDuff.Mode.SRC_IN)
                btnFontMinus?.setColorFilter(tintDark, PorterDuff.Mode.SRC_IN)
                btnFontPlus?.setColorFilter(tintDark, PorterDuff.Mode.SRC_IN)
                btnLock?.setColorFilter(tintDark, PorterDuff.Mode.SRC_IN)
                divider?.setBackgroundColor(0x26000000)
            }
            DesktopLyricsBgMode.DARK -> {
                // 深色半透明：45% 轻透黑磨砂，柔和自然不压抑
                root.setBackgroundResource(R.drawable.desktop_lyrics_bg_dark)
                root.elevation = 8f
                cBar.background = null

                curTv.setTextColor(Color.WHITE)
                curTv.setShadowLayer(4f, 0f, 1f, 0x80000000.toInt())

                nextTv.setTextColor(0xB3FFFFFF.toInt())
                nextTv.setShadowLayer(2f, 0f, 1f, 0x66000000.toInt())

                val tintWhite = Color.WHITE
                btnPrev?.setColorFilter(tintWhite, PorterDuff.Mode.SRC_IN)
                btnPlayPause?.setColorFilter(tintWhite, PorterDuff.Mode.SRC_IN)
                btnNext?.setColorFilter(tintWhite, PorterDuff.Mode.SRC_IN)
                btnBgStyle?.setColorFilter(tintWhite, PorterDuff.Mode.SRC_IN)
                btnTextColor?.setColorFilter(tintWhite, PorterDuff.Mode.SRC_IN)
                btnFontMinus?.setColorFilter(tintWhite, PorterDuff.Mode.SRC_IN)
                btnFontPlus?.setColorFilter(tintWhite, PorterDuff.Mode.SRC_IN)
                btnLock?.setColorFilter(tintWhite, PorterDuff.Mode.SRC_IN)
                divider?.setBackgroundColor(0x33FFFFFF)
            }
        }
        btnClose?.setColorFilter(0xFFEF5350.toInt(), PorterDuff.Mode.SRC_IN)

        // 用户自定义文字颜色覆盖背景模式自带的配色。下一句用同样的色相降透明度，
        // 保留「当前 / 即将播放」的主次关系；没有背景时给阴影兜住对比度。
        val custom = Prefs(context).desktopLyricsTextColor
        if (custom != 0) {
            curTv.setTextColor(custom)
            val alpha = (Color.alpha(custom) * 0.68f).toInt().coerceIn(80, 255)
            nextTv.setTextColor(Color.argb(alpha, Color.red(custom), Color.green(custom), Color.blue(custom)))
            val tint = custom and 0x00FFFFFF or (0xD9 shl 24)
            listOf(btnPrev, btnPlayPause, btnNext, btnBgStyle, btnTextColor, btnFontMinus, btnFontPlus, btnLock)
                .forEach { it?.setColorFilter(tint, PorterDuff.Mode.SRC_IN) }
        }
    }

    /** 显示桌面悬浮歌词 */
    @SuppressLint("ClickableViewAccessibility")
    fun show(context: Context) {
        val app = context.applicationContext
        if (!hasOverlayPermission(app)) return

        mainHandler.post {
            if (floatingView != null) return@post

            val wm = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return@post
            windowManager = wm

            val prefs = Prefs(app)
            val locked = prefs.desktopLyricsLocked

            val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            if (locked) {
                flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }

            val dm = app.resources.displayMetrics
            val savedX = prefs.desktopLyricsX
            val savedY = prefs.desktopLyricsY
            val initX = if (savedX >= 0) savedX else ((dm.widthPixels - 640) / 2).coerceAtLeast(30)
            val initY = if (savedY >= 0) savedY else (dm.heightPixels * 0.16f).toInt()

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                windowType,
                flags,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = initX
                y = initY
            }
            layoutParams = params

            val view = LayoutInflater.from(app).inflate(R.layout.view_desktop_lyrics, null)
            floatingView = view

            rootView = view.findViewById(R.id.desktop_lyrics_root)
            tvCurrent = view.findViewById(R.id.tv_lyric_current)
            tvNext = view.findViewById(R.id.tv_lyric_next)
            controlsBar = view.findViewById(R.id.desktop_lyrics_controls)
            controlsDivider = view.findViewById(R.id.controls_divider)
            btnPrev = view.findViewById(R.id.btn_prev)
            btnPlayPause = view.findViewById(R.id.btn_play_pause)
            btnNext = view.findViewById(R.id.btn_next)
            btnBgStyle = view.findViewById(R.id.btn_bg_style)
            btnTextColor = view.findViewById(R.id.btn_text_color)
            btnFontMinus = view.findViewById(R.id.btn_font_minus)
            btnFontPlus = view.findViewById(R.id.btn_font_plus)
            btnLock = view.findViewById(R.id.btn_lock)
            btnClose = view.findViewById(R.id.btn_close)

            val fontSize = prefs.desktopLyricsFontSizeSp
            tvCurrent?.textSize = fontSize.toFloat()
            tvNext?.textSize = (fontSize - 4).coerceAtLeast(12).toFloat()

            // 应用用户偏好的背景模式（默认没有背景）
            val bgMode = DesktopLyricsBgMode.fromId(prefs.desktopLyricsBgMode)
            applyBgMode(app, bgMode)

            // 恢复/渲染最新文本
            renderLyricsInternal(lastTitle, lastArtist, isPlayingState, lastCurLyric, lastNextLyric)

            // 触摸与拖动手势
            var initialX = 0
            var initialY = 0
            var initialTouchX = 0f
            var initialTouchY = 0f
            var isDragging = false
            val touchSlop = ViewConfiguration.get(app).scaledTouchSlop

            view.setOnTouchListener { _, event ->
                if (Prefs(app).desktopLyricsLocked) return@setOnTouchListener false
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isDragging = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - initialTouchX
                        val dy = event.rawY - initialTouchY
                        if (!isDragging && hypot(dx.toDouble(), dy.toDouble()) > touchSlop) {
                            isDragging = true
                            hideControls()
                        }
                        if (isDragging) {
                            params.x = (initialX + dx).toInt()
                            params.y = (initialY + dy).toInt()
                            runCatching { wm.updateViewLayout(view, params) }
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (isDragging) {
                            prefs.desktopLyricsX = params.x
                            prefs.desktopLyricsY = params.y
                        } else {
                            toggleControls(app)
                        }
                        true
                    }
                    else -> false
                }
            }

            // 控制条按键事件
            btnPrev?.setOnClickListener {
                PlaybackService.executeWidgetCommand(PlaybackCommands.PREVIOUS)
                scheduleAutoHide()
            }
            btnPlayPause?.setOnClickListener {
                PlaybackService.executeWidgetCommand(PlaybackCommands.PLAY_PAUSE)
                scheduleAutoHide()
            }
            btnNext?.setOnClickListener {
                PlaybackService.executeWidgetCommand(PlaybackCommands.NEXT)
                scheduleAutoHide()
            }
            btnBgStyle?.setOnClickListener {
                cycleBgMode(app)
                scheduleAutoHide()
            }
            btnTextColor?.setOnClickListener {
                cycleTextColor(app)
                scheduleAutoHide()
            }
            btnFontMinus?.setOnClickListener {
                adjustFontSize(app, -FONT_SIZE_STEP_SP)
                scheduleAutoHide()
            }
            btnFontPlus?.setOnClickListener {
                adjustFontSize(app, +FONT_SIZE_STEP_SP)
                scheduleAutoHide()
            }
            btnLock?.setOnClickListener {
                setLocked(app, true)
                Toast.makeText(app, "桌面歌词已锁定（手势穿透），可从通知栏解锁", Toast.LENGTH_SHORT).show()
            }
            btnClose?.setOnClickListener {
                setEnabled(app, false)
            }

            runCatching { wm.addView(view, params) }
            PlaybackService.wakePlaybackTicker()

            if (locked) {
                showUnlockNotification(app)
            }
        }
    }

    /** 隐藏桌面悬浮歌词 */
    fun hide(context: Context? = null) {
        val app = context?.applicationContext
        mainHandler.post {
            hideControls()
            val view = floatingView
            val wm = windowManager
            if (view != null && wm != null) {
                runCatching { wm.removeView(view) }
            }
            floatingView = null
            layoutParams = null
            windowManager = null
            rootView = null
            tvCurrent = null
            tvNext = null
            controlsBar = null
            controlsDivider = null
            btnPrev = null
            btnPlayPause = null
            btnNext = null
            btnBgStyle = null
            btnTextColor = null
            btnFontMinus = null
            btnFontPlus = null
            btnLock = null
            btnClose = null

            app?.let { cancelUnlockNotification(it) }
        }
    }

    /** 切换控制面板可见性 */
    private fun toggleControls(context: Context) {
        val bar = controlsBar ?: return
        if (bar.isVisible) {
            hideControls()
        } else {
            bar.isVisible = true
            scheduleAutoHide()
        }
    }

    private fun hideControls() {
        mainHandler.removeCallbacks(autoHideRunnable)
        controlsBar?.isVisible = false
    }

    private fun scheduleAutoHide() {
        mainHandler.removeCallbacks(autoHideRunnable)
        mainHandler.postDelayed(autoHideRunnable, 4500L)
    }

    /** 随播放进度与状态刷新歌词 */
    @Synchronized
    fun onTick(
        context: Context,
        isPlaying: Boolean,
        title: String,
        artist: String,
        lyricTriplet: WidgetState.LyricTriplet
    ) {
        val cur = lyricTriplet.cur.ifBlank {
            if (title.isNotBlank()) {
                if (artist.isNotBlank()) "$title · $artist" else title
            } else "山水有清音"
        }
        val next = lyricTriplet.next

        val textChanged = cur != lastCurLyric || next != lastNextLyric
        val playStateChanged = isPlaying != isPlayingState

        lastTitle = title
        lastArtist = artist
        isPlayingState = isPlaying
        lastCurLyric = cur
        lastNextLyric = next

        if (floatingView == null) {
            val app = context.applicationContext
            if (Prefs(app).desktopLyricsEnabled && hasOverlayPermission(app)) {
                show(app)
            }
            return
        }

        // 仅在文本或播放状态发生实质改变时才 post 到主线程重绘，避免高频无谓刷新
        if (textChanged || playStateChanged) {
            mainHandler.post {
                renderLyricsInternal(lastTitle, lastArtist, isPlayingState, lastCurLyric, lastNextLyric)
            }
        }
    }

    private fun renderLyricsInternal(
        title: String,
        artist: String,
        isPlaying: Boolean,
        cur: String,
        next: String
    ) {
        tvCurrent?.text = cur
        if (next.isNotBlank()) {
            tvNext?.text = next
            tvNext?.isVisible = true
        } else {
            tvNext?.isVisible = false
        }
        btnPlayPause?.setImageResource(
            if (isPlaying) R.drawable.widget_ic_pause else R.drawable.widget_ic_play
        )
    }

    /** 发送锁定状态的通知 */
    private fun showUnlockNotification(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "桌面歌词",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "桌面歌词手势锁定与快捷解锁"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }

        val unlockIntent = Intent(context, DesktopLyricsReceiver::class.java).apply {
            action = DesktopLyricsReceiver.ACTION_UNLOCK
        }
        val unlockPendingIntent = PendingIntent.getBroadcast(
            context,
            1002,
            unlockIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val closeIntent = Intent(context, DesktopLyricsReceiver::class.java).apply {
            action = DesktopLyricsReceiver.ACTION_CLOSE
        }
        val closePendingIntent = PendingIntent.getBroadcast(
            context,
            1003,
            closeIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_music)
            .setContentTitle("桌面歌词已锁定")
            .setContentText("已开启全屏手势穿透，点击此处随时解锁")
            .setOngoing(true)
            .setContentIntent(unlockPendingIntent)
            .addAction(0, "点击解锁", unlockPendingIntent)
            .addAction(0, "关闭歌词", closePendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        nm.notify(NOTIFICATION_ID, notification)
    }

    /** 清除锁定通知 */
    private fun cancelUnlockNotification(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching { nm.cancel(NOTIFICATION_ID) }
    }
}
