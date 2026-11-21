package com.sonance.player.playback

import android.app.PendingIntent
import android.content.Context
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.sonance.player.R
import com.sonance.player.data.Prefs
import com.sonance.player.widget.WidgetState

/**
 * 小米超级岛歌词（HyperOS 3「岛通知」）。
 *
 * 标准媒体通知本身会自动上岛显示播放控制（见 [PlaybackNotification]），
 * 但岛上只有歌名/歌手，没有歌词。要在岛上逐行显示歌词，走小米公开的
 * 「焦点通知」协议：给一条独立通知的 extras 塞 `miui.focus.param`（JSON）
 * 与 `miui.focus.pics`（图标 Bundle），系统把它渲染成岛上的图文卡片。
 *
 * 协议字段对照小米澎湃 OS 开发者平台《岛通知构建方式》与开源实现
 * HyperIsland-ToolKit（param_v2 / param_island / bigIslandArea / textInfo）。
 *
 * 三道门槛全过才发（任何一道不过都静默跳过，绝不退化成普通通知打扰用户）：
 * 1. 小米 / Redmi / POCO 机型；
 * 2. 系统焦点通知权限 `canShowFocus`（MIUI 通知公共 provider 查询）；
 * 3. 设备支持岛特性 `persist.sys.feature.island`。
 *
 * 随「锁屏歌词」开关联动：关闭锁屏歌词即撤掉岛歌词；无歌词的曲目不显示。
 * 只在歌词行真正变化时更新（sequence 用 orderId 标识同一首歌），避免逐帧刷通知。
 */
object XiaomiLyricIsland {

    /** 与媒体通知（media3 默认 1001）错开的独立通知 id。 */
    private const val NOTIFY_ID = 1002

    private var supportedCache: Boolean? = null
    private var lastSig: String? = null
    private var posted = false

    fun isSupported(context: Context): Boolean {
        supportedCache?.let { return it }
        val brand = (Build.MANUFACTURER + " " + Build.BRAND).lowercase()
        val isXiaomi = brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco")
        val result = isXiaomi && hasFocusPermission(context) && hasIslandFeature()
        supportedCache = result
        return result
    }

    private fun hasFocusPermission(context: Context): Boolean = runCatching {
        val args = Bundle().apply { putString("package", context.packageName) }
        val reply = context.contentResolver.call(
            Uri.parse("content://miui.statusbar.notification.public"),
            "canShowFocus",
            null,
            args
        )
        reply?.getBoolean("canShowFocus", false) ?: false
    }.getOrDefault(false)

    private fun hasIslandFeature(): Boolean = runCatching {
        val method = Class.forName("android.os.SystemProperties")
            .getDeclaredMethod("getBoolean", String::class.java, Boolean::class.java)
        method.invoke(null, "persist.sys.feature.island", false) as Boolean
    }.getOrDefault(false)

    /**
     * 播放心跳入口（息屏也调用：岛 / AOD 不依赖手机亮屏）。
     * 不满足条件时把已发出的岛歌词撤掉，保证开关与曲目状态即时收敛。
     */
    fun onTick(context: Context, title: String, artist: String, triplet: WidgetState.LyricTriplet) {
        val line = triplet.cur.trim()
        val active = Prefs(context).lockScreenLyrics &&
            triplet.hasLyrics &&
            line.isNotEmpty() &&
            isSupported(context)
        if (!active) {
            if (posted) cancel(context)
            return
        }
        val sig = "$title|$artist|$line"
        if (sig == lastSig) return
        lastSig = sig
        runCatching { post(context, title, artist, line) }
    }

    fun cancel(context: Context) {
        lastSig = null
        posted = false
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFY_ID) }
    }

    private fun post(context: Context, title: String, artist: String, line: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val contentIntent = launch?.let {
            PendingIntent.getActivity(
                context, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val pics = Bundle().apply {
            putParcelable("miui.focus.pic_cover", Icon.createWithResource(context, R.drawable.ic_stat_music))
        }
        val extras = Bundle().apply { putBundle("miui.focus.pics", pics) }
        val notification = NotificationCompat.Builder(context, PlaybackNotification.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_music)
            .setContentTitle(title)
            .setContentText(line)
            .setSubText(artist)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setContentIntent(contentIntent)
            .addExtras(extras)
            .build()
        notification.extras.putString("miui.focus.param", buildFocusParamJson(title, line))
        nm.notify(NOTIFY_ID, notification)
        posted = true
    }

    /**
     * 岛通知 JSON（param_v2）。展开态左侧图文：封面图标 + 歌名 + 当前歌词行；
     * 收起态只有图标；AOD 显示当前歌词行。enableFloat=false：逐行更新不反复弹岛。
     */
    internal fun buildFocusParamJson(title: String, line: String): String {
        val t = escapeJson(title)
        val l = escapeJson(line)
        return "{\"param_v2\":{" +
            "\"protocol\":3," +
            "\"business\":\"music\"," +
            "\"updatable\":true," +
            "\"ticker\":\"$t\"," +
            "\"enableFloat\":false," +
            "\"isShowNotification\":true," +
            "\"islandFirstFloat\":false," +
            "\"timeout\":720," +
            "\"orderId\":\"$t\"," +
            "\"aodTitle\":\"$l\"," +
            "\"param_island\":{" +
            "\"islandProperty\":1," +
            "\"islandPriority\":2," +
            "\"bigIslandArea\":{\"imageTextInfoLeft\":{\"type\":1," +
            "\"picInfo\":{\"type\":1,\"pic\":\"miui.focus.pic_cover\"}," +
            "\"textInfo\":{\"title\":\"$t\",\"content\":\"$l\"}}}," +
            "\"smallIslandArea\":{\"picInfo\":{\"type\":1,\"pic\":\"miui.focus.pic_cover\"}}" +
            "}," +
            "\"baseInfo\":{\"type\":1,\"title\":\"$t\",\"content\":\"$l\"}" +
            "}}"
    }

    internal fun escapeJson(s: String): String = buildString(s.length) {
        for (c in s) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
    }
}