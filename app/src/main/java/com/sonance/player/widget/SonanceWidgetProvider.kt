package com.sonance.player.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.LruCache
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.DrawableRes
import androidx.annotation.LayoutRes
import androidx.core.content.ContextCompat
import com.sonance.player.MainActivity
import com.sonance.player.R
import com.sonance.player.data.Prefs
import com.sonance.player.playback.PlaybackCommands
import com.sonance.player.ui.theme.Skin
import com.sonance.player.ui.theme.Skins

/**
 * 小组件的五种样式。
 *
 * 加一套新样式只需要三步：写一个布局、写一个 `res/xml/widget_*_info.xml`、
 * 在这里加一个枚举项并在清单里注册对应的 receiver。渲染逻辑完全共用 ——
 * 前提是**新布局含同一套 view id**（用不到的元素以 gone + 0dp 占位）。
 */
internal enum class WidgetStyle(
    val providerClass: Class<out AppWidgetProvider>,
    @LayoutRes val layoutId: Int,
    /** 是否显示圆形唱片（极简样式故意不显示，省内存也省 Binder） */
    val showCover: Boolean,
    val showProgress: Boolean,
    val showBrand: Boolean,
    /** 歌词区是否显示前后句；横条太矮只能放当前一句 */
    val triLineLyrics: Boolean,
    val coverPx: Int,
    val defaultWidthDp: Int,
    val defaultHeightDp: Int,
    /**
     * 卡片渐变的固定种子色（酷狗概念版同款：每种布局一个专属色，不随封面变化）。
     * 唱片外环、占位唱片也用它，整张卡始终同一色系。
     */
    val seedColor: Int
) {
    /** 横向控制条 5x1 · 翡翠绿 */
    BAR(
        SonanceWidgetBar::class.java, R.layout.widget_bar,
        showCover = true, showProgress = false, showBrand = false, triLineLyrics = false,
        coverPx = 144, defaultWidthDp = 320, defaultHeightDp = 70,
        seedColor = 0xFF00B870.toInt()
    ),

    /** 封面卡片 3x2 · 长春花蓝紫 */
    CARD(
        SonanceWidgetCard::class.java, R.layout.widget_card,
        showCover = true, showProgress = false, showBrand = false, triLineLyrics = true,
        coverPx = 256, defaultWidthDp = 210, defaultHeightDp = 140,
        seedColor = 0xFF7581DD.toInt()
    ),

    /** 信息宽卡 5x2 · 晴空蓝 */
    WIDE(
        SonanceWidgetWide::class.java, R.layout.widget_wide,
        showCover = true, showProgress = true, showBrand = false, triLineLyrics = true,
        coverPx = 280, defaultWidthDp = 320, defaultHeightDp = 140,
        seedColor = 0xFF2E9EDD.toInt()
    ),

    /** 极简横条 3x1 · 紫罗兰（与其余四种同属清透冷色系） */
    MINIMAL(
        SonanceWidgetMinimal::class.java, R.layout.widget_minimal,
        showCover = false, showProgress = false, showBrand = false, triLineLyrics = false,
        coverPx = 0, defaultWidthDp = 250, defaultHeightDp = 70,
        seedColor = 0xFF8A6FE0.toInt()
    ),

    /** 大幅卡片 5x4：圆形唱片是主体，位图给大一号 · 青碧 */
    LARGE(
        SonanceWidgetLarge::class.java, R.layout.widget_large,
        showCover = true, showProgress = true, showBrand = true, triLineLyrics = true,
        coverPx = 400, defaultWidthDp = 360, defaultHeightDp = 280,
        seedColor = 0xFF1CBCCE.toInt()
    );
}

/**
 * 所有小组件样式共用的基类。
 *
 * 按钮不再走 `androidx.media3.session.MediaButtonReceiver`（见 [WidgetCommandReceiver]
 * 与 [PlaybackCommands] 的说明），而是显式命令直达自己的播放服务。
 */
abstract class SonanceWidgetProvider internal constructor(
    private val style: WidgetStyle
) : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // 渲染要读磁盘（封面缓存 / 音频文件），不能压在 onReceive 的主线程上。
        val pending = goAsync()
        val app = context.applicationContext
        val ids = appWidgetIds.copyOf()
        Thread {
            try {
                ids.forEach { WidgetRenderer.render(app, appWidgetManager, it, style) }
            } finally {
                runCatching { pending.finish() }
            }
        }.start()
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle
    ) {
        // 尺寸变了（用户拖拽调整）→ 按新比例重画渐变背景
        val pending = goAsync()
        val app = context.applicationContext
        Thread {
            try {
                WidgetRenderer.render(app, appWidgetManager, appWidgetId, style)
            } finally {
                runCatching { pending.finish() }
            }
        }.start()
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        WidgetState.clearWidget(context.applicationContext, appWidgetIds)
        super.onDeleted(context, appWidgetIds)
    }
}

/** 样式 1：横向控制条 5x1 */
class SonanceWidgetBar : SonanceWidgetProvider(WidgetStyle.BAR)

/** 样式 2：封面卡片 3x2 */
class SonanceWidgetCard : SonanceWidgetProvider(WidgetStyle.CARD)

/** 样式 3：信息宽卡 5x2 */
class SonanceWidgetWide : SonanceWidgetProvider(WidgetStyle.WIDE)

/** 样式 4：极简横条 3x1 */
class SonanceWidgetMinimal : SonanceWidgetProvider(WidgetStyle.MINIMAL)

/** 样式 5：大幅卡片 5x4 */
class SonanceWidgetLarge : SonanceWidgetProvider(WidgetStyle.LARGE)

/** 小组件的对外入口：应用侧只跟它打交道。 */
object SonanceWidgets {

    internal const val RC_OPEN = 0x54

    /**
     * 播放状态变了。
     *
     * 高频调用是常态（进度每 500ms 推一次），所以先比快照 ——
     * 内容没变就直接返回，不做任何跨进程传输、也不起线程。
     */
    fun onPlaybackChanged(
        context: Context,
        mediaId: Long = -1L,
        title: String,
        artist: String,
        isPlaying: Boolean,
        albumId: Long,
        sourceUri: String?,
        coverKey: String?
    ) {
        val app = context.applicationContext
        val previous = WidgetState.read(app)
        val prefs = Prefs(app)
        val resolvedMediaId = if (mediaId != -1L) mediaId else previous.mediaId
        val isFav = resolvedMediaId > 0L && prefs.favorites.contains(resolvedMediaId)
        val mode = prefs.playMode.ifBlank { "REPEAT_ALL" }
        val snap = WidgetState.Snapshot(
            hasSong = title.isNotBlank(),
            mediaId = resolvedMediaId,
            title = title,
            artist = artist,
            isPlaying = isPlaying,
            albumId = albumId,
            sourceUri = sourceUri,
            coverKey = coverKey,
            isFavorite = isFav,
            playMode = mode,
            colorBg = previous.colorBg,
            colorText = previous.colorText,
            colorTextDim = previous.colorTextDim,
            colorAccent = previous.colorAccent,
            colorOnAccent = previous.colorOnAccent
        )
        if (!WidgetState.write(app, snap)) return
        if (!snap.sameArtwork(previous)) WidgetCover.clear()
        Thread { refreshAll(app) }.start()
    }

    /** App 内切换收藏或播放模式时同步刷新小组件图标状态。 */
    fun onFavoriteOrModeChanged(context: Context) {
        val app = context.applicationContext
        val previous = WidgetState.read(app)
        val prefs = Prefs(app)
        val isFav = previous.mediaId > 0L && prefs.favorites.contains(previous.mediaId)
        val mode = prefs.playMode.ifBlank { "REPEAT_ALL" }
        WidgetState.write(app, previous.copy(isFavorite = isFav, playMode = mode))
        Thread { refreshAll(app) }.start()
    }

    /**
     * 播放进度与当前歌词行。
     *
     * [WidgetState.writeLive] 按「秒 + 歌词内容」判重，所以播放器 500ms 的回调
     * 最多换来每秒一次**增量**更新：只推进度条和几行文字，不重传封面/背景位图。
     */
    fun onTick(
        context: Context,
        positionMs: Long,
        durationMs: Long,
        lyric: WidgetState.LyricTriplet
    ) {
        val app = context.applicationContext
        if (!WidgetState.writeLive(app, positionMs, durationMs, lyric)) return
        Thread { refreshLive(app) }.start()
    }

    /** 皮肤换了 —— 渐变底色的兜底种子色跟着变。 */
    fun onSkinChanged(
        context: Context,
        bg: Int,
        text: Int,
        textDim: Int,
        accent: Int,
        onAccent: Int
    ) {
        val app = context.applicationContext
        WidgetState.writeSkin(app, bg, text, textDim, accent, onAccent)
        WidgetRenderer.clearDecorCache()
        Thread { refreshAll(app) }.start()
    }

    /** 重画所有已放置的小组件（同步版本，调用方负责不在主线程上跑） */
    fun refreshAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        WidgetStyle.entries.forEach { style ->
            widgetIds(manager, context, style).forEach {
                WidgetRenderer.render(context, manager, it, style)
            }
        }
    }

    /** 只更新进度条、时间数字和歌词文字。 */
    private fun refreshLive(context: Context) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val live = WidgetState.readLive(context)
        val curTime = formatTime(live.positionSec)
        val totalTime = if (live.durationSec > 0) formatTime(live.durationSec) else "--:--"
        val lyricCur = lyricText(context, live)
        val progress = WidgetRenderer.renderProgress(live.positionSec, live.durationSec)

        WidgetStyle.entries.forEach { style ->
            val ids = widgetIds(manager, context, style)
            if (ids.isEmpty()) return@forEach
            val views = RemoteViews(context.packageName, style.layoutId)
            views.setTextViewText(R.id.widget_time_cur, curTime)
            views.setTextViewText(R.id.widget_time_total, totalTime)
            views.setImageViewBitmap(R.id.widget_progress, progress)
            views.setTextViewText(R.id.widget_lyric_prev, live.lyric.prev)
            views.setTextViewText(R.id.widget_lyric_cur, lyricCur)
            views.setTextViewText(R.id.widget_lyric_next, live.lyric.next)
            // 少数启动器对增量更新支持不好，失败就退回完整重画
            val ok = runCatching { manager.partiallyUpdateAppWidget(ids, views) }.isSuccess
            if (!ok) {
                ids.forEach { WidgetRenderer.render(context, manager, it, style) }
            }
        }
    }

    private fun widgetIds(
        manager: AppWidgetManager,
        context: Context,
        style: WidgetStyle
    ): IntArray = runCatching {
        manager.getAppWidgetIds(ComponentName(context, style.providerClass))
    }.getOrDefault(IntArray(0))

}

private fun lyricText(context: Context, live: WidgetState.Live): String = when {
    !live.lyric.hasLyrics -> context.getString(R.string.widget_no_lyrics)
    live.lyric.cur.isBlank() -> "♪"
    else -> live.lyric.cur
}

/** 锁 Locale.US：某些地区会把 `%d` 渲染成当地数字，进度数字不该跟着变。 */
private fun formatTime(totalSec: Int): String {
    val s = totalSec.coerceAtLeast(0)
    return String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60)
}

/**
 * 真正拼 `RemoteViews` 的地方。所有样式共用。
 *
 * 视觉原则（对齐参考图）：整张卡片是一块**每种样式固定专属色的渐变**（不随封面取色变化），
 * 不是深色面板；圆形唱片带同色系发光圆环；所有文字与图标统一纯白（背景亮度被压在中低区间，保证对比度）；
 * 进度条是细线，不画假滑块。
 *
 * 为什么背景仍是一张位图而不是 `setBackgroundColor`：在不自动裁切小组件圆角的
 * 启动器（OriginOS / ColorOS / HyperOS / 第三方桌面）上，`setBackgroundColor`
 * 会直接变成一块直角方框；渐变和柔光也没法用 `RemoteViews` 的普通属性表达。
 */
internal object WidgetRenderer {

    private val decorCache = LruCache<String, Bitmap>(24)

    fun clearDecorCache() = decorCache.evictAll()

    fun render(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int,
        style: WidgetStyle
    ) {
        val snap = WidgetState.read(context)
        val live = WidgetState.readLive(context)
        val lyricOn = WidgetState.isLyricOn(context, widgetId)
        val prefs = Prefs(context)
        val skin = runCatching { Skins.byId(prefs.skinId) }.getOrDefault(Skins.MidnightAmber)
        val views = RemoteViews(context.packageName, style.layoutId)

        // ---- 1. 封面 + 固定配色渐变卡片（颜色只跟样式走，不随封面变化） ----
        val seed = style.seedColor
        val art = if (style.showCover) WidgetCover.load(context, snap, style.coverPx, seed) else null
        val (widthDp, heightDp) = resolveWidgetSizeDp(manager, widgetId, style)
        views.setImageViewBitmap(R.id.widget_bg_img, renderCardBackground(seed, widthDp, heightDp))
        if (style.showCover) {
            views.setImageViewBitmap(
                R.id.widget_cover,
                art ?: renderCoverPlaceholder(context, skin, seed, style.coverPx.coerceAtMost(360))
            )
        }

        // ---- 2. 歌曲信息 ----
        val titleText = if (snap.hasSong) snap.title else context.getString(R.string.widget_idle_title)
        val artistText = when {
            !snap.hasSong -> context.getString(R.string.widget_idle_subtitle)
            snap.artist.isNotBlank() -> snap.artist
            else -> context.getString(R.string.app_name)
        }
        // 在横条 5x1、大幅 5x4、极简 3x1 中，若歌手栏处于隐藏态，直接展示「歌名-歌手」组合样式（复刻酷狗概念版）
        val combinedTitle = if (
            snap.hasSong && snap.artist.isNotBlank() &&
            (style == WidgetStyle.BAR || style == WidgetStyle.LARGE || style == WidgetStyle.MINIMAL)
        ) {
            "${snap.title}-${snap.artist}"
        } else {
            titleText
        }
        views.setTextViewText(R.id.widget_title, combinedTitle)
        views.setTextViewText(R.id.widget_artist, artistText)

        // ---- 3. 歌词区（每个 widget 独立开关，打开时充分利用宽度与多行空间展示完整歌词） ----
        views.setViewVisibility(
            R.id.widget_info_group,
            if (lyricOn) View.GONE else View.VISIBLE
        )
        views.setViewVisibility(
            R.id.widget_lyric_group,
            if (lyricOn) View.VISIBLE else View.GONE
        )
        views.setViewVisibility(
            R.id.widget_lyric_prev,
            if (style.triLineLyrics) View.VISIBLE else View.GONE
        )
        views.setViewVisibility(
            R.id.widget_lyric_next,
            if (style.triLineLyrics) View.VISIBLE else View.GONE
        )
        views.setTextViewText(R.id.widget_lyric_prev, live.lyric.prev)
        views.setTextViewText(R.id.widget_lyric_cur, lyricText(context, live))
        views.setTextViewText(R.id.widget_lyric_next, live.lyric.next)

        // 3x2 卡片开启歌词时，适度收起超大唱片为多行完整歌词腾出充足空间；关闭歌词时恢复大唱片展示
        if (style == WidgetStyle.CARD) {
            views.setViewVisibility(R.id.widget_cover, if (lyricOn) View.GONE else View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.widget_cover, if (style.showCover) View.VISIBLE else View.GONE)
        }

        // ---- 4. 真实进度条（时长未知时显示 --:--，不编一个数字） ----
        views.setViewVisibility(
            R.id.widget_progress_row,
            if (style.showProgress) View.VISIBLE else View.GONE
        )
        views.setViewVisibility(R.id.widget_brand, if (style.showBrand) View.VISIBLE else View.GONE)
        views.setImageViewBitmap(R.id.widget_progress, renderProgress(live.positionSec, live.durationSec))
        views.setTextViewText(R.id.widget_time_cur, formatTime(live.positionSec))
        views.setTextViewText(
            R.id.widget_time_total,
            if (live.durationSec > 0) formatTime(live.durationSec) else "--:--"
        )

        // ---- 5. 纯白播控、红心收藏与播放模式图标 ----
        val playIcon = if (snap.isPlaying) R.drawable.widget_ic_pause else R.drawable.widget_ic_play
        views.setImageViewBitmap(R.id.widget_play, renderIcon(context, playIcon, 132, 1f, false))
        views.setImageViewBitmap(R.id.widget_prev, renderIcon(context, R.drawable.widget_ic_prev, 108, 0.94f, false))
        views.setImageViewBitmap(R.id.widget_next, renderIcon(context, R.drawable.widget_ic_next, 108, 0.94f, false))

        val isFav = snap.mediaId > 0L && (snap.isFavorite || prefs.favorites.contains(snap.mediaId))
        val favIcon = if (isFav) R.drawable.widget_ic_heart_filled else R.drawable.widget_ic_heart_outline
        views.setImageViewBitmap(
            R.id.widget_fav,
            renderHeartIcon(context, favIcon, 108, isFav)
        )

        val modeName = prefs.playMode.ifBlank { snap.playMode }
        val modeIcon = when (modeName) {
            "SHUFFLE" -> R.drawable.widget_ic_mode_shuffle
            "REPEAT_ONE" -> R.drawable.widget_ic_mode_repeat_one
            else -> R.drawable.widget_ic_mode_repeat_all
        }
        views.setImageViewBitmap(
            R.id.widget_mode,
            renderIcon(context, modeIcon, 104, 0.88f, false)
        )

        views.setImageViewBitmap(
            R.id.widget_lyric_toggle,
            renderIcon(context, R.drawable.widget_ic_lyric, 108, if (lyricOn) 1f else 0.86f, lyricOn)
        )
        views.setContentDescription(
            R.id.widget_lyric_toggle,
            context.getString(
                if (lyricOn) R.string.widget_lyric_on_desc else R.string.widget_lyric_off_desc
            )
        )

        // ---- 6. 点击：命令直达自己的服务 ----
        views.setOnClickPendingIntent(R.id.widget_root, openAppIntent(context))
        views.setOnClickPendingIntent(
            R.id.widget_play, WidgetCommandReceiver.pendingIntent(context, PlaybackCommands.PLAY_PAUSE, widgetId)
        )
        views.setOnClickPendingIntent(
            R.id.widget_prev, WidgetCommandReceiver.pendingIntent(context, PlaybackCommands.PREVIOUS, widgetId)
        )
        views.setOnClickPendingIntent(
            R.id.widget_next, WidgetCommandReceiver.pendingIntent(context, PlaybackCommands.NEXT, widgetId)
        )
        views.setOnClickPendingIntent(
            R.id.widget_fav, WidgetCommandReceiver.pendingIntent(context, PlaybackCommands.TOGGLE_FAVORITE, widgetId)
        )
        views.setOnClickPendingIntent(
            R.id.widget_mode, WidgetCommandReceiver.pendingIntent(context, PlaybackCommands.CYCLE_PLAY_MODE, widgetId)
        )
        views.setOnClickPendingIntent(
            R.id.widget_lyric_toggle,
            WidgetCommandReceiver.pendingIntent(context, PlaybackCommands.TOGGLE_LYRICS, widgetId)
        )

        manager.updateAppWidget(widgetId, views)
    }

    private fun resolveWidgetSizeDp(
        manager: AppWidgetManager,
        widgetId: Int,
        style: WidgetStyle
    ): Pair<Int, Int> {
        val opts = runCatching { manager.getAppWidgetOptions(widgetId) }.getOrNull()
        val w = opts?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
            ?.takeIf { it > 20 } ?: style.defaultWidthDp
        val h = opts?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
            ?.takeIf { it > 20 } ?: style.defaultHeightDp
        return w.coerceIn(110, 520) to h.coerceIn(56, 420)
    }

    /**
     * 酷狗概念版风格流体弥散渐变卡片：
     * - 高饱和清透主色 + 邻近色相丝滑过渡（不做黑灰压暗，保持亮丽通透）
     * - 唱片区霓虹高光晕染 + 右上柔和冷暖撞色氛围光
     * - 精致大圆角（24dp）与微光玻璃内描边
     */
    private fun renderCardBackground(seed: Int, widthDp: Int, heightDp: Int): Bitmap {
        val scale = 2.0f
        val wPx = (widthDp * scale).toInt().coerceIn(220, 900)
        val hPx = (heightDp * scale).toInt().coerceIn(120, 700)
        val key = "bg_v2|$seed|$wPx|$hPx"
        decorCache.get(key)?.let { return it }

        val out = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val radius = (24f * scale).coerceAtMost(minOf(wPx, hPx) * 0.45f)
        val rect = RectF(1.5f, 1.5f, wPx - 1.5f, hPx - 1.5f)

        val hsv = FloatArray(3)
        Color.colorToHSV(seed, hsv)
        val h = hsv[0]
        val s = hsv[1].coerceIn(0.55f, 0.88f)
        val v = hsv[2].coerceIn(0.68f, 0.90f)

        val topLeft = Color.HSVToColor(
            floatArrayOf((h - 10f + 360f) % 360f, (s * 0.86f).coerceIn(0.45f, 0.82f), minOf(v * 1.08f, 0.95f))
        )
        val midTone = Color.HSVToColor(
            floatArrayOf(h, s, v)
        )
        val bottomRight = Color.HSVToColor(
            floatArrayOf((h + 18f) % 360f, minOf(s * 1.06f, 0.92f), maxOf(v * 0.80f, 0.56f))
        )

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, wPx.toFloat(), hPx.toFloat(),
                intArrayOf(topLeft, midTone, bottomRight),
                floatArrayOf(0f, 0.52f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(rect, radius, radius, fill)

        // 左上/中心偏左的亮色霓虹弥散光晕（呼应发光黑胶圆环）
        val bloomColor = Color.HSVToColor(
            115,
            floatArrayOf((h - 14f + 360f) % 360f, (s * 0.55f).coerceIn(0.25f, 0.60f), 1f)
        )
        val discBloom = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                wPx * 0.28f, hPx * 0.36f, maxOf(wPx, hPx) * 0.65f,
                bloomColor, 0x00FFFFFF, Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(rect, radius, radius, discBloom)

        // 右上柔和氛围光（模拟酷狗概念版右上角的微紫/暖灰丝滑弥散）
        val accentGlowColor = Color.HSVToColor(
            75,
            floatArrayOf((h + 38f) % 360f, (s * 0.48f).coerceIn(0.22f, 0.55f), 0.96f)
        )
        val cornerGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                wPx * 0.88f, hPx * 0.18f, maxOf(wPx, hPx) * 0.62f,
                accentGlowColor, 0x00FFFFFF, Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(rect, radius, radius, cornerGlow)

        // 顶部清透丝光
        val sheen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, 0f, hPx * 0.45f, 0x22FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        }
        canvas.drawRoundRect(rect, radius, radius, sheen)

        // 精致半透明高光边框
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.1f * scale
            color = 0x48FFFFFF
        }
        canvas.drawRoundRect(rect, radius, radius, stroke)

        decorCache.put(key, out)
        return out
    }

    /** 细线进度条：轨道半透明白、已播放段纯白。fitXY 横向拉伸不会改变线宽。 */
    internal fun renderProgress(positionSec: Int, durationSec: Int): Bitmap {
        val w = 360
        val h = 12
        val ratio = if (durationSec > 0) {
            (positionSec.toFloat() / durationSec.toFloat()).coerceIn(0f, 1f)
        } else 0f
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val cy = h / 2f
        val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = 3.2f
            strokeCap = Paint.Cap.ROUND
            color = 0x52FFFFFF
        }
        canvas.drawLine(4f, cy, w - 4f, cy, track)
        if (ratio > 0f) {
            val played = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                strokeWidth = 3.6f
                strokeCap = Paint.Cap.ROUND
                color = Color.WHITE
            }
            canvas.drawLine(4f, cy, 4f + (w - 8f) * ratio, cy, played)
        }
        return out
    }

    /**
     * 纯图标按钮：默认无圆底，只在歌词键打开时叠一层半透明白圆作出状态反馈。
     * 图标统一染成白色，所以任何皮肤、任何取色渐变下都一致。
     */
    private fun renderIcon(
        context: Context,
        @DrawableRes iconRes: Int,
        sizePx: Int,
        alphaFraction: Float,
        active: Boolean
    ): Bitmap {
        val alpha = (alphaFraction.coerceIn(0f, 1f) * 255f).toInt()
        val key = "icon|$iconRes|$sizePx|$alpha|$active"
        decorCache.get(key)?.let { return it }

        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        if (active) {
            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF }
            canvas.drawOval(RectF(3f, 3f, sizePx - 3f, sizePx - 3f), bg)
        }
        val drawable = ContextCompat.getDrawable(context, iconRes)?.mutate()
        if (drawable != null) {
            drawable.setTint(Color.argb(alpha, 255, 255, 255))
            val inset = (sizePx * 0.18f).toInt()
            drawable.setBounds(inset, inset, sizePx - inset, sizePx - inset)
            drawable.draw(canvas)
        }
        decorCache.put(key, out)
        return out
    }

    /**
     * 红心收藏按钮：未收藏时为酷狗概念版同款柔白心形，已收藏时为鲜亮珊瑚粉红心形。
     */
    private fun renderHeartIcon(
        context: Context,
        @DrawableRes iconRes: Int,
        sizePx: Int,
        isFavorite: Boolean
    ): Bitmap {
        val key = "heart|$iconRes|$sizePx|$isFavorite"
        decorCache.get(key)?.let { return it }

        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val drawable = ContextCompat.getDrawable(context, iconRes)?.mutate()
        if (drawable != null) {
            drawable.setTint(if (isFavorite) 0xFFFF4D6D.toInt() else 0xF0FFFFFF.toInt())
            val inset = (sizePx * 0.18f).toInt()
            drawable.setBounds(inset, inset, sizePx - inset, sizePx - inset)
            drawable.draw(canvas)
        }
        decorCache.put(key, out)
        return out
    }

    /** 无封面时的圆形占位唱片，与有封面时保持同一套果冻发光彩胶环语言。 */
    private fun renderCoverPlaceholder(
        context: Context,
        skin: Skin,
        seed: Int,
        sizePx: Int
    ): Bitmap {
        val side = sizePx.coerceIn(120, 360)
        val key = "ph_v2|${skin.id}|$seed|$side"
        decorCache.get(key)?.let { return it }

        val out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val r = side / 2f
        val discRadius = r * 0.615f
        val ringOuterRadius = r * 0.895f
        val ringMidRadius = (discRadius + ringOuterRadius) * 0.5f
        val ringWidth = ringOuterRadius - discRadius

        val hsv = FloatArray(3)
        Color.colorToHSV(seed, hsv)
        val s = hsv[1].coerceIn(0.55f, 0.88f)
        val v = hsv[2].coerceIn(0.68f, 0.90f)

        val rimLight = Color.HSVToColor(235, floatArrayOf((hsv[0] - 6f + 360f) % 360f, s * 0.48f, 1f))
        val rimDeep = Color.HSVToColor(225, floatArrayOf((hsv[0] + 10f) % 360f, s * 0.80f, 0.90f))
        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = ringWidth
            shader = LinearGradient(
                r - ringOuterRadius, r - ringOuterRadius,
                r + ringOuterRadius, r + ringOuterRadius,
                rimLight, rimDeep, Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(r, r, ringMidRadius, ringPaint)

        val outerEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = maxOf(1.2f, side * 0.008f)
            color = 0x80FFFFFF.toInt()
        }
        canvas.drawCircle(r, r, ringOuterRadius - outerEdge.strokeWidth * 0.5f, outerEdge)

        val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, side.toFloat(), side.toFloat(),
                Color.HSVToColor(floatArrayOf(hsv[0], s * 0.82f, minOf(v * 1.02f, 0.92f))),
                Color.HSVToColor(floatArrayOf((hsv[0] + 18f) % 360f, s, maxOf(v * 0.55f, 0.32f))),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(r, r, discRadius, disc)

        val innerEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = maxOf(1.2f, side * 0.008f)
            color = 0x99FFFFFF.toInt()
        }
        canvas.drawCircle(r, r, discRadius, innerEdge)

        val note = ContextCompat.getDrawable(context, R.drawable.widget_ic_note)?.mutate()
        if (note != null) {
            note.setTint(0xDDFFFFFF.toInt())
            val inset = (side * 0.39f).toInt()
            note.setBounds(inset, inset, side - inset, side - inset)
            note.draw(canvas)
        }
        decorCache.put(key, out)
        return out
    }

    private fun flags(): Int =
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    /** 点卡片空白处打开应用（控制键自己吃掉点击，不会误触） */
    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        return PendingIntent.getActivity(context, SonanceWidgets.RC_OPEN, intent, flags())
    }
}
