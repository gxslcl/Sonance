package com.sonance.player.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.util.LruCache
import android.util.SizeF
import android.util.TypedValue
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

internal enum class WidgetProgressMode {
    NONE,
    THIN_BOTTOM,
    RAIL_WITH_DOT
}

/**
 * STYLE A 桌面小组件全家族枚举：
 * 完整保留原有 5 列系列（3×1 / 5×1 / 3×2 / 5×2 / 5×4），
 * 并新增一套独立的 4 列系列（2×1 / 4×1 / 2×2 / 4×2 / 4×4），共 10 套。
 *
 * 所有样式共用 [WidgetRenderer] 渲染链与同一套 `widget_*` view id（未使用的元素在各自 XML 中设为 gone + 0dp）。
 */
internal enum class WidgetStyle(
    val providerClass: Class<out AppWidgetProvider>,
    @LayoutRes val layoutId: Int,
    val coverClass: WidgetCoverClass,
    val isFourCol: Boolean,
    val showCover: Boolean,
    val hasMiniCover: Boolean,
    val hasAccentBar: Boolean,
    val hasHqBadge: Boolean,
    val showBrand: Boolean,
    val supportsLyricToggle: Boolean,
    val isCornerPillControls: Boolean,
    val isPillPlayButton: Boolean,
    val progressMode: WidgetProgressMode,
    val triLineLyrics: Boolean,
    val playBtnWdp: Int,
    val playBtnHdp: Int,
    val baseCoverDp: Float,
    val baseMiniDp: Float,
    val coverPx: Int,
    val miniCoverPx: Int,
    val coverRadiusRatio: Float,
    val defaultWidthDp: Int,
    val defaultHeightDp: Int
) {
    // ===== 保留 · 一行 5 格系列（5 套） =====
    BAR(
        SonanceWidgetBar::class.java, R.layout.widget_bar,
        WidgetCoverClass.BAR, isFourCol = false,
        showCover = true, hasMiniCover = false, hasAccentBar = false, hasHqBadge = false,
        showBrand = false, supportsLyricToggle = true, isCornerPillControls = false,
        isPillPlayButton = false, progressMode = WidgetProgressMode.THIN_BOTTOM, triLineLyrics = false,
        playBtnWdp = 34, playBtnHdp = 34, baseCoverDp = 52f, baseMiniDp = 0f,
        coverPx = 144, miniCoverPx = 0,
        coverRadiusRatio = 0.22f, defaultWidthDp = 340, defaultHeightDp = 72
    ),
    CARD(
        SonanceWidgetCard::class.java, R.layout.widget_card,
        WidgetCoverClass.CARD, isFourCol = false,
        showCover = true, hasMiniCover = true, hasAccentBar = false, hasHqBadge = false,
        showBrand = false, supportsLyricToggle = true, isCornerPillControls = true,
        isPillPlayButton = true, progressMode = WidgetProgressMode.NONE, triLineLyrics = false,
        playBtnWdp = 56, playBtnHdp = 28, baseCoverDp = 60f, baseMiniDp = 28f,
        coverPx = 168, miniCoverPx = 84,
        coverRadiusRatio = 0.20f, defaultWidthDp = 210, defaultHeightDp = 152
    ),
    WIDE(
        SonanceWidgetWide::class.java, R.layout.widget_wide,
        WidgetCoverClass.WIDE, isFourCol = false,
        showCover = true, hasMiniCover = false, hasAccentBar = false, hasHqBadge = true,
        showBrand = false, supportsLyricToggle = true, isCornerPillControls = false,
        isPillPlayButton = false, progressMode = WidgetProgressMode.RAIL_WITH_DOT, triLineLyrics = false,
        playBtnWdp = 36, playBtnHdp = 36, baseCoverDp = 112f, baseMiniDp = 0f,
        coverPx = 256, miniCoverPx = 0,
        coverRadiusRatio = 0.13f, defaultWidthDp = 340, defaultHeightDp = 156
    ),
    MINIMAL(
        SonanceWidgetMinimal::class.java, R.layout.widget_minimal,
        WidgetCoverClass.NONE, isFourCol = false,
        showCover = false, hasMiniCover = false, hasAccentBar = true, hasHqBadge = false,
        showBrand = false, supportsLyricToggle = true, isCornerPillControls = false,
        isPillPlayButton = false, progressMode = WidgetProgressMode.NONE, triLineLyrics = false,
        playBtnWdp = 32, playBtnHdp = 32, baseCoverDp = 0f, baseMiniDp = 0f,
        coverPx = 0, miniCoverPx = 0,
        coverRadiusRatio = 0.20f, defaultWidthDp = 210, defaultHeightDp = 72
    ),
    LARGE(
        SonanceWidgetLarge::class.java, R.layout.widget_large,
        WidgetCoverClass.LARGE, isFourCol = false,
        showCover = true, hasMiniCover = true, hasAccentBar = false, hasHqBadge = false,
        showBrand = true, supportsLyricToggle = true, isCornerPillControls = true,
        isPillPlayButton = false, progressMode = WidgetProgressMode.RAIL_WITH_DOT, triLineLyrics = true,
        playBtnWdp = 44, playBtnHdp = 44, baseCoverDp = 124f, baseMiniDp = 40f,
        coverPx = 288, miniCoverPx = 104,
        coverRadiusRatio = 0.13f, defaultWidthDp = 340, defaultHeightDp = 310
    ),

    // ===== 新增 · 一行 4 格系列（5 套） =====
    BAR_4(
        SonanceWidgetBar4::class.java, R.layout.widget_bar_4,
        WidgetCoverClass.BAR, isFourCol = true,
        showCover = true, hasMiniCover = false, hasAccentBar = false, hasHqBadge = false,
        showBrand = false, supportsLyricToggle = true, isCornerPillControls = false,
        isPillPlayButton = false, progressMode = WidgetProgressMode.THIN_BOTTOM, triLineLyrics = false,
        playBtnWdp = 32, playBtnHdp = 32, baseCoverDp = 48f, baseMiniDp = 0f,
        coverPx = 132, miniCoverPx = 0,
        coverRadiusRatio = 0.22f, defaultWidthDp = 280, defaultHeightDp = 70
    ),
    CARD_4(
        SonanceWidgetCard4::class.java, R.layout.widget_card_4,
        WidgetCoverClass.CARD, isFourCol = true,
        showCover = true, hasMiniCover = true, hasAccentBar = false, hasHqBadge = false,
        showBrand = false, supportsLyricToggle = true, isCornerPillControls = true,
        isPillPlayButton = true, progressMode = WidgetProgressMode.NONE, triLineLyrics = false,
        playBtnWdp = 46, playBtnHdp = 27, baseCoverDp = 54f, baseMiniDp = 25f,
        coverPx = 152, miniCoverPx = 76,
        coverRadiusRatio = 0.20f, defaultWidthDp = 156, defaultHeightDp = 152
    ),
    WIDE_4(
        SonanceWidgetWide4::class.java, R.layout.widget_wide_4,
        WidgetCoverClass.WIDE, isFourCol = true,
        showCover = true, hasMiniCover = false, hasAccentBar = false, hasHqBadge = true,
        showBrand = false, supportsLyricToggle = true, isCornerPillControls = false,
        isPillPlayButton = false, progressMode = WidgetProgressMode.RAIL_WITH_DOT, triLineLyrics = false,
        playBtnWdp = 34, playBtnHdp = 34, baseCoverDp = 98f, baseMiniDp = 0f,
        coverPx = 232, miniCoverPx = 0,
        coverRadiusRatio = 0.13f, defaultWidthDp = 280, defaultHeightDp = 152
    ),
    MINIMAL_4(
        SonanceWidgetMinimal4::class.java, R.layout.widget_minimal_4,
        WidgetCoverClass.NONE, isFourCol = true,
        showCover = false, hasMiniCover = false, hasAccentBar = true, hasHqBadge = false,
        showBrand = false, supportsLyricToggle = false, isCornerPillControls = false,
        isPillPlayButton = false, progressMode = WidgetProgressMode.NONE, triLineLyrics = false,
        playBtnWdp = 31, playBtnHdp = 31, baseCoverDp = 0f, baseMiniDp = 0f,
        coverPx = 0, miniCoverPx = 0,
        coverRadiusRatio = 0.20f, defaultWidthDp = 156, defaultHeightDp = 70
    ),
    LARGE_4(
        SonanceWidgetLarge4::class.java, R.layout.widget_large_4,
        WidgetCoverClass.LARGE, isFourCol = true,
        showCover = true, hasMiniCover = true, hasAccentBar = false, hasHqBadge = false,
        showBrand = true, supportsLyricToggle = true, isCornerPillControls = true,
        isPillPlayButton = false, progressMode = WidgetProgressMode.RAIL_WITH_DOT, triLineLyrics = true,
        playBtnWdp = 41, playBtnHdp = 41, baseCoverDp = 114f, baseMiniDp = 38f,
        coverPx = 264, miniCoverPx = 96,
        coverRadiusRatio = 0.13f, defaultWidthDp = 280, defaultHeightDp = 296
    );
}

/**
 * 所有小组件样式共用的基类。
 *
 * 按钮通过显式 Intent 直达 [WidgetCommandReceiver] 与 [PlaybackCommands]。
 */
abstract class SonanceWidgetProvider internal constructor(
    private val style: WidgetStyle
) : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
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
        newOptions: Bundle
    ) {
        val pending = goAsync()
        val app = context.applicationContext
        Thread {
            try {
                WidgetRenderer.render(app, appWidgetManager, appWidgetId, style, newOptions)
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

// ===== 保留 5 列系列 Receivers =====
class SonanceWidgetBar : SonanceWidgetProvider(WidgetStyle.BAR)
class SonanceWidgetCard : SonanceWidgetProvider(WidgetStyle.CARD)
class SonanceWidgetWide : SonanceWidgetProvider(WidgetStyle.WIDE)
class SonanceWidgetMinimal : SonanceWidgetProvider(WidgetStyle.MINIMAL)
class SonanceWidgetLarge : SonanceWidgetProvider(WidgetStyle.LARGE)

// ===== 新增 4 列系列 Receivers =====
class SonanceWidgetBar4 : SonanceWidgetProvider(WidgetStyle.BAR_4)
class SonanceWidgetCard4 : SonanceWidgetProvider(WidgetStyle.CARD_4)
class SonanceWidgetWide4 : SonanceWidgetProvider(WidgetStyle.WIDE_4)
class SonanceWidgetMinimal4 : SonanceWidgetProvider(WidgetStyle.MINIMAL_4)
class SonanceWidgetLarge4 : SonanceWidgetProvider(WidgetStyle.LARGE_4)

/** 小组件的对外入口：应用侧只跟它打交道。 */
object SonanceWidgets {

    internal const val RC_OPEN = 0x54

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

    fun onFavoriteOrModeChanged(context: Context) {
        val app = context.applicationContext
        val previous = WidgetState.read(app)
        val prefs = Prefs(app)
        val isFav = previous.mediaId > 0L && prefs.favorites.contains(previous.mediaId)
        val mode = prefs.playMode.ifBlank { "REPEAT_ALL" }
        WidgetState.write(app, previous.copy(isFavorite = isFav, playMode = mode))
        Thread { refreshAll(app) }.start()
    }

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

    fun onAppearanceChanged(context: Context, appearance: WidgetAppearance) {
        val app = context.applicationContext
        WidgetState.writeAppearance(app, appearance)
        WidgetRenderer.clearDecorCache()
        Thread { refreshAll(app) }.start()
    }

    /** 完整重画所有已放置的小组件（同步版本，调用方负责不在主线程上跑） */
    fun refreshAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        WidgetStyle.entries.forEach { style ->
            widgetIds(manager, context, style).forEach {
                WidgetRenderer.render(context, manager, it, style)
            }
        }
    }

    /** 增量刷新进度条、时间数字和歌词行，确保浅色/深色外观下颜色始终一致。 */
    private fun refreshLive(context: Context) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val live = WidgetState.readLive(context)
        val snap = WidgetState.read(context)
        val palette = WidgetRenderer.resolveCurrentPalette(context, snap, extractIfMissing = false)
        val curTime = formatTime(live.positionSec)
        val totalTime = if (live.durationSec > 0) formatTime(live.durationSec) else "--:--"
        val lyricCur = lyricText(context, live)

        var thinProgressBmp: Bitmap? = null
        var railProgressBmp: Bitmap? = null

        WidgetStyle.entries.forEach { style ->
            val ids = widgetIds(manager, context, style)
            if (ids.isEmpty()) return@forEach

            val views = RemoteViews(context.packageName, style.layoutId)
            views.setTextViewText(R.id.widget_time_cur, curTime)
            views.setTextViewText(R.id.widget_time_total, totalTime)
            views.setTextViewText(R.id.widget_lyric_prev, live.lyric.prev)
            views.setTextViewText(R.id.widget_lyric_cur, lyricCur)
            views.setTextViewText(
                R.id.widget_lyric_next,
                barLyricNextText(context, snap, live)
            )

            when (style.progressMode) {
                WidgetProgressMode.THIN_BOTTOM -> {
                    val bmp = thinProgressBmp ?: WidgetRenderer.renderProgress(
                        live.positionSec, live.durationSec, palette, thin = true
                    ).also { thinProgressBmp = it }
                    views.setImageViewBitmap(R.id.widget_progress, bmp)
                }
                WidgetProgressMode.RAIL_WITH_DOT -> {
                    val bmp = railProgressBmp ?: WidgetRenderer.renderProgress(
                        live.positionSec, live.durationSec, palette, thin = false
                    ).also { railProgressBmp = it }
                    views.setImageViewBitmap(R.id.widget_progress, bmp)
                }
                WidgetProgressMode.NONE -> Unit
            }

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

private fun barLyricNextText(
    context: Context,
    snap: WidgetState.Snapshot,
    live: WidgetState.Live
): String = when {
    live.lyric.next.isNotBlank() -> live.lyric.next
    snap.hasSong && snap.artist.isNotBlank() -> snap.artist
    snap.hasSong -> snap.title
    else -> context.getString(R.string.app_name)
}

/** 锁 Locale.US，保证进度时钟数字不随系统区域变形。 */
private fun formatTime(totalSec: Int): String {
    val s = totalSec.coerceAtLeast(0)
    return String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60)
}

/**
 * STYLE A `RemoteViews` 统一渲染器（全部 10 套尺寸共用）。
 */
internal object WidgetRenderer {

    private val decorCache = LruCache<String, Bitmap>(40)

    fun clearDecorCache() = decorCache.evictAll()

    fun resolveIsLight(context: Context): Boolean {
        return when (WidgetState.readAppearance(context)) {
            WidgetAppearance.DARK -> false
            WidgetAppearance.LIGHT -> true
            WidgetAppearance.FOLLOW_SKIN -> {
                val skinId = Prefs(context).skinId
                runCatching { Skins.byId(skinId).isLight }.getOrDefault(false)
            }
        }
    }

    fun resolveCurrentPalette(
        context: Context,
        snap: WidgetState.Snapshot,
        extractIfMissing: Boolean
    ): WidgetAuraPalette {
        val isLight = resolveIsLight(context)
        val sig = WidgetState.artworkSignature(snap)
        val (hit, savedRgb) = WidgetState.readExtractedRgb(context, sig)
        val rawRgb = when {
            !snap.hasSong -> null
            hit -> savedRgb
            extractIfMissing -> WidgetCover.extractRawRgb(context, snap)
            else -> null
        }
        return WidgetAuraPalette.fromRgb(rawRgb, isLight)
    }

    fun render(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int,
        style: WidgetStyle,
        optionsOverride: Bundle? = null
    ) {
        val snap = WidgetState.read(context)
        val live = WidgetState.readLive(context)
        val prefs = Prefs(context)
        val isLight = resolveIsLight(context)
        val lyricOn = style.supportsLyricToggle && WidgetState.isLyricOn(context, widgetId)
        val views = RemoteViews(context.packageName, style.layoutId)

        val (widthDp, heightDp) = resolveWidgetSizeDp(context, manager, widgetId, style, optionsOverride)
        val density = context.resources.displayMetrics.density
        val coverDp = if (style.showCover) {
            WidgetSizing.computeCoverDp(style.coverClass, style.baseCoverDp, style.isFourCol, widthDp, heightDp)
        } else 0f
        val coverTargetPx = if (style.showCover) {
            WidgetSizing.computeCoverPx(coverDp, style.coverPx, density)
        } else 0
        val miniDp = if (style.hasMiniCover) {
            WidgetSizing.computeMiniCoverDp(style.coverClass, style.baseMiniDp, coverDp)
        } else 0f
        val miniTargetPx = if (style.hasMiniCover) {
            WidgetSizing.computeCoverPx(miniDp, style.miniCoverPx, density).coerceAtMost(144)
        } else 0

        // ---- 1. 封面解码（仅读一次） + 克制莫兰迪配色提取 ----
        val coverStroke = if (isLight) 0x1A151822 else 0x26FFFFFF
        val coverBundle = if (style.showCover) {
            WidgetCover.loadRoundedCover(context, snap, coverTargetPx, style.coverRadiusRatio, coverStroke)
        } else null
        val rawRgb = when {
            !snap.hasSong -> null
            coverBundle != null -> coverBundle.rawRgb
            else -> WidgetCover.extractRawRgb(context, snap)
        }
        val palette = WidgetAuraPalette.fromRgb(rawRgb, isLight)

        views.setImageViewBitmap(R.id.widget_bg_img, renderAuraBackground(palette, widthDp, heightDp))

        if (style.showCover) {
            val coverBmp = coverBundle?.bitmap ?: renderCoverPlaceholder(
                context, palette, coverTargetPx.coerceIn(96, WidgetSizing.MAX_COVER_PX), style.coverRadiusRatio
            )
            views.setImageViewBitmap(R.id.widget_cover, coverBmp)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                views.setViewLayoutWidth(R.id.widget_cover, coverDp, TypedValue.COMPLEX_UNIT_DIP)
                views.setViewLayoutHeight(R.id.widget_cover, coverDp, TypedValue.COMPLEX_UNIT_DIP)
            }
            views.setViewVisibility(R.id.widget_cover, View.VISIBLE)
        }

        if (style.hasMiniCover) {
            val miniBundle = WidgetCover.loadRoundedCover(
                context, snap, miniTargetPx, 0.22f, coverStroke
            )
            val miniBmp = miniBundle.bitmap ?: renderCoverPlaceholder(
                context, palette, miniTargetPx.coerceIn(72, 160), 0.22f
            )
            views.setImageViewBitmap(R.id.widget_cover_mini, miniBmp)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && miniDp > 0f) {
                views.setViewLayoutWidth(R.id.widget_cover_mini, miniDp, TypedValue.COMPLEX_UNIT_DIP)
                views.setViewLayoutHeight(R.id.widget_cover_mini, miniDp, TypedValue.COMPLEX_UNIT_DIP)
            }
        }

        if (style.hasAccentBar) {
            views.setImageViewBitmap(R.id.widget_minimal_accent, renderMinimalAccentBar(palette.accentBar))
        }

        // ---- 2. 歌曲文本与明暗对比色 ----
        val titleText = if (snap.hasSong) snap.title else context.getString(R.string.widget_idle_title)
        val artistText = when {
            !snap.hasSong -> context.getString(R.string.widget_idle_subtitle)
            snap.artist.isNotBlank() -> snap.artist
            else -> context.getString(R.string.app_name)
        }

        views.setTextViewText(R.id.widget_title, titleText)
        views.setTextViewText(R.id.widget_artist, artistText)
        views.setTextColor(R.id.widget_title, palette.textPrimary)
        views.setTextColor(R.id.widget_artist, palette.textSecondary)

        if (style.hasMiniCover) {
            views.setTextViewText(R.id.widget_lyric_song_title, titleText)
            views.setTextViewText(R.id.widget_lyric_song_artist, artistText)
            views.setTextColor(R.id.widget_lyric_song_title, palette.textPrimary)
            views.setTextColor(R.id.widget_lyric_song_artist, palette.textSecondary)
        } else if (style.hasAccentBar) {
            val subLyric = if (snap.hasSong) "${snap.title} · 词" else artistText
            views.setTextViewText(R.id.widget_lyric_song_title, subLyric)
            views.setTextColor(R.id.widget_lyric_song_title, palette.textSecondary)
        }

        if (style.showBrand) {
            views.setTextViewText(R.id.widget_brand, context.getString(R.string.widget_brand))
            views.setTextColor(R.id.widget_brand, palette.textSecondary)
            views.setImageViewBitmap(R.id.widget_brand_dot, renderBrandDot(context, palette))
            views.setInt(
                R.id.widget_brand_pill_wrap,
                "setBackgroundResource",
                if (isLight) R.drawable.widget_pill_light else R.drawable.widget_pill_dark
            )
            views.setInt(
                R.id.widget_lyric_header_wrap,
                "setBackgroundResource",
                if (isLight) R.drawable.widget_card_inner_light else R.drawable.widget_card_inner_dark
            )
        }

        if (style.hasHqBadge) {
            views.setTextColor(R.id.widget_hq_badge, palette.textDim)
        }

        // ---- 3. 歌词切换（由 XML 各自决定 1 / 2 / 3 行显隐） ----
        views.setViewVisibility(R.id.widget_info_group, if (lyricOn) View.GONE else View.VISIBLE)
        views.setViewVisibility(R.id.widget_lyric_group, if (lyricOn) View.VISIBLE else View.GONE)

        views.setTextViewText(R.id.widget_lyric_prev, live.lyric.prev)
        views.setTextViewText(R.id.widget_lyric_cur, lyricText(context, live))
        views.setTextViewText(R.id.widget_lyric_next, barLyricNextText(context, snap, live))
        views.setTextColor(R.id.widget_lyric_prev, palette.textDim)
        views.setTextColor(R.id.widget_lyric_cur, palette.textPrimary)
        views.setTextColor(
            R.id.widget_lyric_next,
            if (style.triLineLyrics) palette.textDim else palette.textSecondary
        )

        // ---- 4. 进度条（底缘 2.5dp 贴边 / 带点横条） ----
        when (style.progressMode) {
            WidgetProgressMode.THIN_BOTTOM -> {
                views.setViewVisibility(R.id.widget_progress_row, View.VISIBLE)
                views.setImageViewBitmap(
                    R.id.widget_progress,
                    renderProgress(live.positionSec, live.durationSec, palette, thin = true)
                )
            }
            WidgetProgressMode.RAIL_WITH_DOT -> {
                views.setViewVisibility(R.id.widget_progress_row, View.VISIBLE)
                views.setImageViewBitmap(
                    R.id.widget_progress,
                    renderProgress(live.positionSec, live.durationSec, palette, thin = false)
                )
                views.setTextViewText(R.id.widget_time_cur, formatTime(live.positionSec))
                views.setTextViewText(
                    R.id.widget_time_total,
                    if (live.durationSec > 0) formatTime(live.durationSec) else "--:--"
                )
                views.setTextColor(R.id.widget_time_cur, palette.textSecondary)
                views.setTextColor(R.id.widget_time_total, palette.textSecondary)
            }
            WidgetProgressMode.NONE -> {
                views.setViewVisibility(R.id.widget_progress_row, View.GONE)
            }
        }

        // ---- 5. STYLE A 高对比实心播放键 + 克制磨砂功能图标 ----
        views.setImageViewBitmap(
            R.id.widget_play,
            renderPlayButton(
                context = context,
                isPlaying = snap.isPlaying,
                widthDp = style.playBtnWdp,
                heightDp = style.playBtnHdp,
                palette = palette,
                isPill = style.isPillPlayButton
            )
        )
        views.setImageViewBitmap(
            R.id.widget_prev,
            renderIcon(context, R.drawable.widget_ic_prev, 96, palette.textPrimary, 0.88f, pillBg = 0)
        )
        views.setImageViewBitmap(
            R.id.widget_next,
            renderIcon(context, R.drawable.widget_ic_next, 96, palette.textPrimary, 0.88f, pillBg = 0)
        )

        val isFav = snap.mediaId > 0L && (snap.isFavorite || prefs.favorites.contains(snap.mediaId))
        val favIcon = if (isFav) R.drawable.widget_ic_heart_filled else R.drawable.widget_ic_heart_outline
        val cornerBg = if (style.isCornerPillControls) palette.pillBg else 0
        views.setImageViewBitmap(
            R.id.widget_fav,
            renderHeartIcon(context, favIcon, 96, isFav, palette.textPrimary, cornerBg)
        )

        val modeName = prefs.playMode.ifBlank { snap.playMode }
        val modeIcon = when (modeName) {
            "SHUFFLE" -> R.drawable.widget_ic_mode_shuffle
            "REPEAT_ONE" -> R.drawable.widget_ic_mode_repeat_one
            else -> R.drawable.widget_ic_mode_repeat_all
        }
        views.setImageViewBitmap(
            R.id.widget_mode,
            renderIcon(context, modeIcon, 96, palette.textPrimary, 0.86f, pillBg = 0)
        )

        val lyricBg = if (lyricOn || style.isCornerPillControls) palette.pillBg else 0
        views.setImageViewBitmap(
            R.id.widget_lyric_toggle,
            renderIcon(
                context,
                R.drawable.widget_ic_lyric,
                96,
                palette.textPrimary,
                if (lyricOn) 1f else 0.86f,
                pillBg = lyricBg
            )
        )
        views.setContentDescription(
            R.id.widget_lyric_toggle,
            context.getString(if (lyricOn) R.string.widget_lyric_on_desc else R.string.widget_lyric_off_desc)
        )

        // ---- 6. 点击事件（卡片打开 App，控制键直达服务） ----
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
            R.id.widget_lyric_toggle, WidgetCommandReceiver.pendingIntent(context, PlaybackCommands.TOGGLE_LYRICS, widgetId)
        )

        manager.updateAppWidget(widgetId, views)
    }

    private fun resolveWidgetSizeDp(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int,
        style: WidgetStyle,
        optionsOverride: Bundle?
    ): Pair<Int, Int> {
        val opts = optionsOverride ?: runCatching { manager.getAppWidgetOptions(widgetId) }.getOrNull()
        val isLandscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && opts != null) {
            @Suppress("DEPRECATION")
            val sizes: ArrayList<SizeF>? = opts.getParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES)
            if (!sizes.isNullOrEmpty()) {
                WidgetSizing.pickSizeFromPairs(
                    isLandscape = isLandscape,
                    candidates = sizes.map { it.width to it.height }
                )?.let { return it }
            }
        }

        val minW = opts?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) ?: 0
        val maxW = opts?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0) ?: 0
        val minH = opts?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) ?: 0
        val maxH = opts?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0) ?: 0
        return WidgetSizing.pickOrientationSize(
            isLandscape = isLandscape,
            minW = minW,
            maxW = maxW,
            minH = minH,
            maxH = maxH,
            defaultW = style.defaultWidthDp,
            defaultH = style.defaultHeightDp
        )
    }

    /**
     * STYLE A 克制莫兰迪磨砂底图：
     * - 低饱和斜向烟熏/白磨砂底色；
     * - 左上仅叠加极其收敛的 14~17% 封面色调辉光；
     * - 顶沿内侧微弱高光 + 1dp 玻璃描边，确保不喧宾夺主且与任何壁纸自然融入。
     */
    private fun renderAuraBackground(palette: WidgetAuraPalette, widthDp: Int, heightDp: Int): Bitmap {
        val maxSide = maxOf(widthDp, heightDp).coerceAtLeast(1)
        val scale = minOf(1.5f, WidgetSizing.MAX_BG_W_PX.toFloat() / maxSide.toFloat())
        val wPx = (widthDp * scale).toInt().coerceIn(120, WidgetSizing.MAX_BG_W_PX)
        val hPx = (heightDp * scale).toInt().coerceIn(64, WidgetSizing.MAX_BG_H_PX)
        val key = "bg_a|${palette.isLight}|${palette.bgTopLeft}|${palette.bgBottomRight}|$wPx|$hPx"
        decorCache.get(key)?.let { return it }

        val out = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val radius = (22f * scale).coerceAtMost(minOf(wPx, hPx) * 0.44f)
        val inset = 0.75f * scale
        val rect = RectF(inset, inset, wPx - inset, hPx - inset)

        val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, wPx.toFloat(), hPx.toFloat(),
                intArrayOf(palette.bgTopLeft, palette.bgMid, palette.bgBottomRight),
                floatArrayOf(0f, 0.54f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(rect, radius, radius, basePaint)

        // 左上微弱冷暖辉光（不铺满整卡，仅做封面衬托）
        val glow1 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                wPx * 0.18f, hPx * 0.24f, maxOf(wPx, hPx) * 0.72f,
                palette.glowTopLeft, 0x00000000, Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(rect, radius, radius, glow1)

        if (Color.alpha(palette.glowBottomRight) > 0) {
            val glow2 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    wPx * 0.88f, hPx * 0.82f, maxOf(wPx, hPx) * 0.64f,
                    palette.glowBottomRight, 0x00000000, Shader.TileMode.CLAMP
                )
            }
            canvas.drawRoundRect(rect, radius, radius, glow2)
        }

        // 顶部柔和微光边（类似磨砂玻璃上缘反光）
        val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, 0f, hPx * 0.18f,
                palette.topSheen and 0x44FFFFFF, 0x00000000, Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(rect, radius, radius, sheenPaint)

        // 外沿 1dp 内敛玻璃描边
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = maxOf(1f, 1f * scale)
            color = palette.borderColor
        }
        canvas.drawRoundRect(rect, radius, radius, borderPaint)

        decorCache.put(key, out)
        return out
    }

    /** 高对比实心播放/暂停按钮（方形卡片用胶囊形，其余用正圆形）。 */
    private fun renderPlayButton(
        context: Context,
        isPlaying: Boolean,
        widthDp: Int,
        heightDp: Int,
        palette: WidgetAuraPalette,
        isPill: Boolean
    ): Bitmap {
        val scale = 2.6f
        val wPx = (widthDp * scale).toInt().coerceIn(72, 168)
        val hPx = (heightDp * scale).toInt().coerceIn(64, 132)
        val key = "play|$isPlaying|$isPill|$wPx|$hPx|${palette.playBtnBg}|${palette.playBtnFg}"
        decorCache.get(key)?.let { return it }

        val out = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val pad = maxOf(2f, minOf(wPx, hPx) * 0.06f)
        val rect = RectF(pad, pad * 0.7f, wPx - pad, hPx - pad * 1.3f)
        val radius = minOf(rect.width(), rect.height()) * 0.5f

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.playBtnBg
        }
        if (isPill) {
            canvas.drawRoundRect(rect, radius, radius, bgPaint)
        } else {
            canvas.drawCircle(rect.centerX(), rect.centerY(), radius, bgPaint)
        }

        val iconRes = if (isPlaying) R.drawable.widget_ic_pause else R.drawable.widget_ic_play
        val drawable = ContextCompat.getDrawable(context, iconRes)?.mutate()
        if (drawable != null) {
            drawable.setTint(palette.playBtnFg)
            val iconSize = (minOf(rect.width(), rect.height()) * 0.46f).toInt()
            val nudgeX = if (isPlaying) 0 else (iconSize * 0.06f).toInt()
            val left = (rect.centerX() - iconSize / 2f).toInt() + nudgeX
            val top = (rect.centerY() - iconSize / 2f).toInt()
            drawable.setBounds(left, top, left + iconSize, top + iconSize)
            drawable.draw(canvas)
        }

        decorCache.put(key, out)
        return out
    }

    /** 极简条（2×1 / 3×1）左侧竖向氛围细光柱。 */
    private fun renderMinimalAccentBar(color: Int): Bitmap {
        val key = "mbar|$color"
        decorCache.get(key)?.let { return it }
        val out = Bitmap.createBitmap(12, 84, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        canvas.drawRoundRect(RectF(1f, 2f, 11f, 82f), 5f, 5f, paint)
        decorCache.put(key, out)
        return out
    }

    /** 大幅卡片（4×4 / 5×4）左上角品牌音符方徽标。 */
    private fun renderBrandDot(context: Context, palette: WidgetAuraPalette): Bitmap {
        val key = "bdot|${palette.playBtnBg}|${palette.playBtnFg}"
        decorCache.get(key)?.let { return it }
        val size = 42
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.playBtnBg }
        canvas.drawRoundRect(RectF(1f, 1f, size - 1f, size - 1f), 12f, 12f, bg)
        val note = ContextCompat.getDrawable(context, R.drawable.widget_ic_note)?.mutate()
        if (note != null) {
            note.setTint(palette.playBtnFg)
            val inset = 10
            note.setBounds(inset, inset, size - inset, size - inset)
            note.draw(canvas)
        }
        decorCache.put(key, out)
        return out
    }

    /** 细进度条：横条底缘（thin=true）或宽卡/大卡带指示圆点的轨道（thin=false）。 */
    internal fun renderProgress(
        positionSec: Int,
        durationSec: Int,
        palette: WidgetAuraPalette,
        thin: Boolean
    ): Bitmap {
        val ratio = if (durationSec > 0) {
            (positionSec.toFloat() / durationSec.toFloat()).coerceIn(0f, 1f)
        } else 0f

        if (thin) {
            val w = 320
            val h = 8
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            val rect = RectF(2f, 2f, w - 2f, h - 2f)
            val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.trackBg }
            canvas.drawRoundRect(rect, 3f, 3f, track)
            if (ratio > 0f) {
                val fillW = maxOf(4f, (w - 4f) * ratio)
                val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.trackFill }
                canvas.drawRoundRect(RectF(2f, 2f, 2f + fillW, h - 2f), 3f, 3f, fill)
            }
            return out
        }

        val w = 360
        val h = 22
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val cy = h / 2f
        val pad = 7f
        val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = 5f
            strokeCap = Paint.Cap.ROUND
            color = palette.trackBg
        }
        canvas.drawLine(pad, cy, w - pad, cy, trackPaint)

        val endX = pad + (w - pad * 2f) * ratio
        if (ratio > 0f) {
            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                strokeWidth = 5.4f
                strokeCap = Paint.Cap.ROUND
                color = palette.trackFill
            }
            canvas.drawLine(pad, cy, endX, cy, fillPaint)
        }
        val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.trackFill }
        canvas.drawCircle(endX, cy, 5.2f, knobPaint)
        return out
    }

    private fun renderIcon(
        context: Context,
        @DrawableRes iconRes: Int,
        sizePx: Int,
        tintColor: Int,
        alphaFraction: Float,
        pillBg: Int
    ): Bitmap {
        val alpha = (alphaFraction.coerceIn(0f, 1f) * 255f).toInt()
        val finalTint = (tintColor and 0x00FFFFFF) or (alpha shl 24)
        val key = "ic|$iconRes|$sizePx|$finalTint|$pillBg"
        decorCache.get(key)?.let { return it }

        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        if (Color.alpha(pillBg) > 0) {
            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pillBg }
            canvas.drawOval(RectF(2f, 2f, sizePx - 2f, sizePx - 2f), bg)
        }
        val drawable = ContextCompat.getDrawable(context, iconRes)?.mutate()
        if (drawable != null) {
            drawable.setTint(finalTint)
            val inset = if (Color.alpha(pillBg) > 0) (sizePx * 0.25f).toInt() else (sizePx * 0.16f).toInt()
            drawable.setBounds(inset, inset, sizePx - inset, sizePx - inset)
            drawable.draw(canvas)
        }
        decorCache.put(key, out)
        return out
    }

    private fun renderHeartIcon(
        context: Context,
        @DrawableRes iconRes: Int,
        sizePx: Int,
        isFavorite: Boolean,
        normalTint: Int,
        pillBg: Int
    ): Bitmap {
        val tint = if (isFavorite) 0xFFFF4D6D.toInt() else {
            val baseA = Color.alpha(normalTint)
            (normalTint and 0x00FFFFFF) or ((baseA * 0.88f).toInt() shl 24)
        }
        val key = "heart|$iconRes|$sizePx|$isFavorite|$tint|$pillBg"
        decorCache.get(key)?.let { return it }

        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        if (Color.alpha(pillBg) > 0) {
            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pillBg }
            canvas.drawOval(RectF(2f, 2f, sizePx - 2f, sizePx - 2f), bg)
        }
        val drawable = ContextCompat.getDrawable(context, iconRes)?.mutate()
        if (drawable != null) {
            drawable.setTint(tint)
            val inset = if (Color.alpha(pillBg) > 0) (sizePx * 0.25f).toInt() else (sizePx * 0.16f).toInt()
            drawable.setBounds(inset, inset, sizePx - inset, sizePx - inset)
            drawable.draw(canvas)
        }
        decorCache.put(key, out)
        return out
    }

    /** 无封面 / 空闲态圆角方图占位图，与有封面时造型一致。 */
    private fun renderCoverPlaceholder(
        context: Context,
        palette: WidgetAuraPalette,
        sizePx: Int,
        cornerRadiusRatio: Float
    ): Bitmap {
        val side = sizePx.coerceIn(72, 320)
        val key = "ph_a|${palette.isLight}|${palette.placeholderBgTop}|$side|$cornerRadiusRatio"
        decorCache.get(key)?.let { return it }

        val out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val padSide = maxOf(2f, side * 0.032f)
        val padTop = maxOf(1.5f, side * 0.020f)
        val padBottom = maxOf(3f, side * 0.044f)
        val box = RectF(padSide, padTop, side - padSide, side - padBottom)
        val innerSide = minOf(box.width(), box.height())
        val sq = RectF(
            box.centerX() - innerSide * 0.5f,
            box.centerY() - innerSide * 0.5f,
            box.centerX() + innerSide * 0.5f,
            box.centerY() + innerSide * 0.5f
        )
        val radius = (innerSide * cornerRadiusRatio).coerceIn(4f, innerSide * 0.42f)

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                sq.left, sq.top, sq.right, sq.bottom,
                palette.placeholderBgTop, palette.placeholderBgBottom, Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(sq, radius, radius, fill)

        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = maxOf(1.2f, side * 0.010f)
            color = palette.placeholderStroke
        }
        canvas.drawRoundRect(sq, radius, radius, stroke)

        val note = ContextCompat.getDrawable(context, R.drawable.widget_ic_note)?.mutate()
        if (note != null) {
            note.setTint(palette.placeholderIconTint)
            val iconSide = (innerSide * 0.36f).toInt()
            val left = (sq.centerX() - iconSide * 0.5f).toInt()
            val top = (sq.centerY() - iconSide * 0.5f).toInt()
            note.setBounds(left, top, left + iconSide, top + iconSide)
            note.draw(canvas)
        }

        decorCache.put(key, out)
        return out
    }

    private fun flags(): Int =
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        return PendingIntent.getActivity(context, SonanceWidgets.RC_OPEN, intent, flags())
    }
}
