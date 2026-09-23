package com.sonance.player.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import android.widget.RemoteViews
import androidx.annotation.LayoutRes
import androidx.annotation.StringRes
import androidx.media3.session.MediaButtonReceiver
import com.sonance.player.MainActivity
import com.sonance.player.R

/**
 * 小组件的四种样式。
 *
 * 加一套新样式只需要三步：写一个布局、写一个 `res/xml/widget_*_info.xml`、
 * 在这里加一个枚举项并在清单里注册对应的 receiver。渲染逻辑完全共用。
 *
 * @property layoutId    布局
 * @property labelRes    系统小组件选择器里显示的名字
 * @property showCover   是否显示封面（极简样式故意不显示，省内存也省 Binder）
 * @property showPrevNext 是否显示上一首/下一首
 * @property showOpen    是否显示"打开应用"按钮
 * @property coverPx     封面解码的目标边长（像素）
 */
internal enum class WidgetStyle(
    val providerClass: Class<out AppWidgetProvider>,
    @LayoutRes val layoutId: Int,
    @StringRes val labelRes: Int,
    val showCover: Boolean,
    val showPrevNext: Boolean,
    val showOpen: Boolean,
    val coverPx: Int
) {
    /** 紧凑横条 2x1 */
    BAR(
        SonanceWidgetBar::class.java, R.layout.widget_bar, R.string.widget_label_bar,
        showCover = true, showPrevNext = false, showOpen = false, coverPx = 144
    ),

    /** 封面卡片 2x2 */
    CARD(
        SonanceWidgetCard::class.java, R.layout.widget_card, R.string.widget_label_card,
        showCover = true, showPrevNext = true, showOpen = false, coverPx = 288
    ),

    /** 信息宽条 4x1 */
    WIDE(
        SonanceWidgetWide::class.java, R.layout.widget_wide, R.string.widget_label_wide,
        showCover = true, showPrevNext = true, showOpen = true, coverPx = 168
    ),

    /** 极简文字 2x1（不显示封面） */
    MINIMAL(
        SonanceWidgetMinimal::class.java, R.layout.widget_minimal, R.string.widget_label_minimal,
        showCover = false, showPrevNext = false, showOpen = false, coverPx = 0
    ),

    /**
     * 大幅卡片 5x2。
     *
     * 5 格宽才放得下"封面和信息并排"：2x2 那种竖排在这个尺寸上会留出大片空白，
     * 而 4x1 又太扁放不下封面。封面边长给到 320px —— 它在这一套里是主体，
     * 比 CARD（288）还大一号。
     */
    LARGE(
        SonanceWidgetLarge::class.java, R.layout.widget_large, R.string.widget_label_large,
        showCover = true, showPrevNext = true, showOpen = true, coverPx = 320
    );
}

/**
 * 所有小组件样式共用的基类。
 *
 * 按钮不走自定义广播，而是转成**媒体按键**交给系统的媒体会话：
 * `PendingIntent` → `androidx.media3.session.MediaButtonReceiver` →
 * `MediaSessionService`。好处是：
 * - 不用自己维护"命令怎么送达已经休眠的播放服务"
 * - 耳机线控 / 蓝牙按键 / 通知栏走的是同一条路，行为一致
 *
 * 唯一要注意的是 `ACTION_MEDIA_BUTTON` 属于**受保护广播**，普通应用只能发
 * **显式** intent（必须 `setComponent`），否则会被系统拒绝。
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
        // goAsync 把广播的完成时机交给我们自己控制，超时前 finish 即可。
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
        // 尺寸变了（用户拖拽调整）→ 重画一次，让封面按新尺寸重新裁切
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
}

/** 样式 1：紧凑横条 2x1 */
class SonanceWidgetBar : SonanceWidgetProvider(WidgetStyle.BAR)

/** 样式 2：封面卡片 2x2 */
class SonanceWidgetCard : SonanceWidgetProvider(WidgetStyle.CARD)

/** 样式 3：信息宽条 4x1 */
class SonanceWidgetWide : SonanceWidgetProvider(WidgetStyle.WIDE)

/** 样式 4：极简文字 2x1 */
class SonanceWidgetMinimal : SonanceWidgetProvider(WidgetStyle.MINIMAL)

/** 样式 5：大幅卡片 5x2 */
class SonanceWidgetLarge : SonanceWidgetProvider(WidgetStyle.LARGE)

/**
 * 小组件的对外入口：应用侧只跟它打交道。
 */
object SonanceWidgets {

    /** 请求码：同一组件下每个按钮必须不同，否则 PendingIntent 会被判成同一个而互相覆盖 */
    internal const val RC_PLAY = 0x51
    internal const val RC_PREV = 0x52
    internal const val RC_NEXT = 0x53
    internal const val RC_OPEN = 0x54

    /**
     * 播放状态变了。
     *
     * 高频调用是常态（进度每 500ms 推一次），所以先比快照 ——
     * 内容没变就直接返回，不做任何跨进程传输、也不起线程。
     */
    fun onPlaybackChanged(
        context: Context,
        title: String,
        artist: String,
        isPlaying: Boolean,
        albumId: Long,
        sourceUri: String?,
        coverKey: String?
    ) {
        val app = context.applicationContext
        // 先读一次：颜色不归这里管（换肤走 [onSkinChanged]），
        // 构造新快照时必须原样带回去，否则会把皮肤色冲成默认值、
        // 而且每次播放状态跳动都会被判成"内容变了"而白白重绘一次。
        val previous = WidgetState.read(app)
        val snap = WidgetState.Snapshot(
            hasSong = title.isNotBlank(),
            title = title,
            artist = artist,
            isPlaying = isPlaying,
            albumId = albumId,
            sourceUri = sourceUri,
            coverKey = coverKey,
            colorBg = previous.colorBg,
            colorText = previous.colorText,
            colorTextDim = previous.colorTextDim,
            colorAccent = previous.colorAccent,
            colorOnAccent = previous.colorOnAccent
        )
        if (!WidgetState.write(app, snap)) return
        // 封面来源变了才清位图缓存（只是播放/暂停切换的话，缓存还能继续用）
        if (!snap.sameArtwork(previous)) WidgetCover.clear()
        // 渲染要读磁盘，扔到后台线程；这一步只在内容真的变了时才会走到
        Thread { refreshAll(app) }.start()
    }

    /**
     * 皮肤换了 —— 小组件的颜色跟着变。
     *
     * 只更新颜色那几个键，播放内容一个都不碰；颜色没变就什么都不做
     * （换肤面板里来回点会连续触发，不该每次都重画一遍所有小组件）。
     */
    fun onSkinChanged(
        context: Context,
        bg: Int,
        text: Int,
        textDim: Int,
        accent: Int,
        onAccent: Int
    ) {
        val app = context.applicationContext
        if (!WidgetState.writeSkin(app, bg, text, textDim, accent, onAccent)) return
        Thread { refreshAll(app) }.start()
    }

    /** 重画所有已放置的小组件（同步版本，调用方负责不在主线程上跑） */
    fun refreshAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        WidgetStyle.entries.forEach { style ->
            val ids = runCatching {
                manager.getAppWidgetIds(ComponentName(context, style.providerClass))
            }.getOrDefault(IntArray(0))
            ids.forEach { WidgetRenderer.render(context, manager, it, style) }
        }
    }
}

/** 真正拼 `RemoteViews` 的地方。所有样式共用。 */
internal object WidgetRenderer {

    fun render(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int,
        style: WidgetStyle
    ) {
        val snap = WidgetState.read(context)
        val views = RemoteViews(context.packageName, style.layoutId)

        // ---- 皮肤色 ----
        //
        // RemoteViews 只能调 View 上的现成方法，没有"给背景 drawable 染个色"这种
        // 干净办法（`setBackgroundTintList` 要 API 31，minSdk 是 24）。所以：
        // - 底色走 `setBackgroundColor`（会盖掉布局里那个圆角 drawable，
        //   但 Android 12+ 系统会统一给小组件裁剪圆角，实际看不出来）；
        // - 图标走 `setColorFilter`（ImageView.setColorFilter(int)，全版本可用）。
        views.setInt(R.id.widget_root, "setBackgroundColor", snap.colorBg)
        views.setTextColor(R.id.widget_title, snap.colorText)
        views.setTextColor(R.id.widget_artist, snap.colorTextDim)
        // 播放键画在强调色实心圆上，图标必须用"强调色上的前景色"才看得清
        views.setInt(R.id.widget_play, "setColorFilter", snap.colorOnAccent)
        if (style.showPrevNext) {
            views.setInt(R.id.widget_prev, "setColorFilter", snap.colorAccent)
            views.setInt(R.id.widget_next, "setColorFilter", snap.colorAccent)
        }
        if (style.showOpen) {
            views.setInt(R.id.widget_open, "setColorFilter", snap.colorAccent)
        }

        // ---- 文本 ----
        views.setTextViewText(
            R.id.widget_title,
            if (snap.hasSong) snap.title else context.getString(R.string.widget_idle_title)
        )
        val subtitle = when {
            !snap.hasSong -> context.getString(R.string.widget_idle_subtitle)
            snap.artist.isNotBlank() -> snap.artist
            else -> context.getString(R.string.app_name)
        }
        views.setTextViewText(R.id.widget_artist, subtitle)

        // ---- 播放/暂停图标 ----
        views.setImageViewResource(
            R.id.widget_play,
            if (snap.isPlaying) R.drawable.widget_ic_pause else R.drawable.widget_ic_play
        )
        views.setContentDescription(
            R.id.widget_play,
            context.getString(R.string.widget_play_desc)
        )

        // ---- 封面 ----
        if (style.showCover) {
            val bmp = WidgetCover.load(context, snap, style.coverPx)
            if (bmp != null) {
                views.setImageViewBitmap(R.id.widget_cover, bmp)
            } else {
                views.setImageViewResource(R.id.widget_cover, R.drawable.widget_placeholder)
            }
        }

        // ---- 点击 ----
        views.setOnClickPendingIntent(R.id.widget_root, openAppIntent(context))
        views.setOnClickPendingIntent(R.id.widget_play, mediaKeyIntent(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, SonanceWidgets.RC_PLAY))
        if (style.showPrevNext) {
            views.setOnClickPendingIntent(R.id.widget_prev, mediaKeyIntent(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS, SonanceWidgets.RC_PREV))
            views.setOnClickPendingIntent(R.id.widget_next, mediaKeyIntent(context, KeyEvent.KEYCODE_MEDIA_NEXT, SonanceWidgets.RC_NEXT))
        }
        if (style.showOpen) {
            views.setOnClickPendingIntent(R.id.widget_open, openAppIntent(context))
        }

        manager.updateAppWidget(widgetId, views)
    }

    private fun flags(): Int =
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    /** 打开应用 */
    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        return PendingIntent.getActivity(context, SonanceWidgets.RC_OPEN, intent, flags())
    }

    /**
     * 媒体按键。
     *
     * 必须 `setComponent` 指向 media3 的 `MediaButtonReceiver`：`ACTION_MEDIA_BUTTON`
     * 是受保护广播，隐式发送会被系统以 `SecurityException` 拒绝。
     * 不同按键靠 `requestCode` 区分（`PendingIntent` 的相等判定不含 extras）。
     */
    private fun mediaKeyIntent(context: Context, keyCode: Int, requestCode: Int): PendingIntent {
        val intent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
            component = ComponentName(context, MediaButtonReceiver::class.java)
            putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        }
        return PendingIntent.getBroadcast(context, requestCode, intent, flags())
    }
}
