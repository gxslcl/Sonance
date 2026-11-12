package com.sonance.player.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSessionService
import com.sonance.player.R

/**
 * 播放通知的统一配置。
 *
 * 为什么不自己拼 Notification，而是用 media3 的
 * [DefaultMediaNotificationProvider]：
 *
 * 各家系统的「胶囊」认的都是**同一条标准媒体通知** —— 小米 HyperOS 的超级岛
 * 与妙享背屏、vivo OriginOS 的原子岛 / 原子随身听、OPPO 流体云、荣耀灵动胶囊，
 * 判断依据都是 `MediaStyle` + `MediaSession` + 完整元数据这套公开协议。
 * 自行拼一条普通通知反而拿不到这些识别依据。
 *
 * 所以这里只做两件事：
 * 1. 固定一条专用通知渠道（名称、锁屏可见性、无声无震动）；
 * 2. 给通知换一个干净的白色小图标。
 *
 * 内容（歌名 / 歌手 / 封面 / 上一曲、播放、下一曲）由 media3 依据
 * MediaSession 的 metadata 与 PlaybackState 自动生成。
 */
object PlaybackNotification {

    /**
     * 渠道 id。
     *
     * 带 `_v2` 是因为**渠道重要性在创建之后就改不了**：老渠道是
     * [NotificationManager.IMPORTANCE_LOW]，要提重要性只能换 id，见 [ensureChannel]。
     */
    const val CHANNEL_ID = "sonance_playback_v2"

    /** 上一版用的渠道，仅用于迁移时清理，免得通知设置里出现两条同名渠道。 */
    private const val LEGACY_CHANNEL_ID = "sonance_playback"

    /**
     * 构建通知 provider。
     *
     * 注意 `setMediaNotificationProvider` 在 MediaSessionService 里是 **protected**，
     * 只能由子类自己调用，所以这里只负责造对象，装配交给 PlaybackService。
     */
    fun provider(service: MediaSessionService): DefaultMediaNotificationProvider =
        DefaultMediaNotificationProvider.Builder(service)
            .setChannelId(CHANNEL_ID)
            .setChannelName(R.string.playback_channel_name)
            .build()

    /**
     * 渠道属性决定了通知在锁屏、胶囊、背屏上的可见性：
     *
     * - **DEFAULT 而不是 LOW**：LOW 的通知会被系统归进「静默通知」分区。
     *   小米超级岛、vivo 原子岛、OPPO 流体云这类胶囊基本只抓普通分区的媒体通知 ——
     *   静默媒体通知在部分机型上连状态栏图标都不给，更谈不上上岛。
     *   DEFAULT 本来会让切歌弹出提示音，这里用 `setSound(null, null)` + 关震动消掉，
     *   实际听感仍是无声，只是分区不同。
     * - **PUBLIC**：锁屏与背屏能显示歌名封面（这一条对背屏尤其关键）。
     * - 不显示角标，避免每次播放都往图标上堆数字。
     */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.playback_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "锁屏、通知栏、超级岛、原子岛与背屏的播放控制"
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
        }
        manager.createNotificationChannel(channel)
        // 老渠道留着只会让用户在通知设置里看到两条「正在播放」。
        // 用户在里面改过的开关会随渠道一起消失，但那条渠道本来也上不了岛。
        runCatching { manager.deleteNotificationChannel(LEGACY_CHANNEL_ID) }
    }
}
