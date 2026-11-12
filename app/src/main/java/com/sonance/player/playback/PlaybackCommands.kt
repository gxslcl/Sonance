package com.sonance.player.playback

import androidx.media3.common.Player

/**
 * 播控命令的**唯一实现**：界面按钮、桌面小组件、以及服务被拉起后的兜底入口，
 * 全部走这里，保证「小组件点下一首」和「播放页点下一首」行为完全一致。
 *
 * ## 为什么不能交给 media3 的默认媒体按键处理
 *
 * 之前小组件把 `KEYCODE_MEDIA_NEXT / PREVIOUS` 交给
 * `androidx.media3.session.MediaButtonReceiver`。在 media3 1.5.0 里，这个接收器
 * 对 API 26+ **只放行播放类按键**（MEDIA_PLAY=126、MEDIA_PLAY_PAUSE=85、
 * HEADSETHOOK=79），其余按键直接 return 并打印：
 * `Ignore key event that is not a 'play' command on API 26 or above`。
 * 所以播放键能响、上一首/下一首永远没反应。
 *
 * 而且默认回调用的是 `seekToNextMediaItem()`，不会走本应用自定义的
 * 「队尾绕回 / 单曲循环 / 随机重排队列」逻辑，也不处理「唱了 4 秒按上一首
 * 先回本首开头」。现在这套规则只在这里写一遍。
 *
 * ⚠️ [Player] 的方法必须在应用主线程调用。小组件的 `BroadcastReceiver.onReceive`
 * 和服务的 `onStartCommand` 都在主线程，正好满足。
 */
object PlaybackCommands {

    const val PLAY_PAUSE = "sonance.cmd.play_pause"
    const val PREVIOUS = "sonance.cmd.previous"
    const val NEXT = "sonance.cmd.next"
    const val TOGGLE_LYRICS = "sonance.cmd.toggle_lyrics"
    const val TOGGLE_FAVORITE = "sonance.cmd.toggle_favorite"
    const val CYCLE_PLAY_MODE = "sonance.cmd.cycle_play_mode"

    fun execute(player: Player, command: String?) {
        when (command) {
            PLAY_PAUSE -> togglePlay(player)
            PREVIOUS -> previous(player)
            NEXT -> next(player)
            else -> Unit
        }
    }

    private fun togglePlay(player: Player) {
        if (player.mediaItemCount == 0) return
        val wantsToPlay = activePlaybackIntent(
            player.playWhenReady,
            player.playbackState,
            player.playerError != null
        )
        if (wantsToPlay) {
            player.pause()
        } else {
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            if (player.playbackState == Player.STATE_ENDED) player.seekToDefaultPosition()
            player.play()
        }
    }

    /** 手动切歌按队列下标走，末首绕回第一首；不交给 `seekToNext()` 判断。 */
    private fun next(player: Player) {
        val count = player.mediaItemCount
        if (count <= 0) return
        val nextIndex = if (player.currentMediaItemIndex >= count - 1) 0 else player.currentMediaItemIndex + 1
        player.seekToDefaultPosition(nextIndex)
        if (!player.isPlaying) player.play()
    }

    /** 上一曲就是上一曲：队首再往前绕到队尾，不做「唱过几秒先回本首开头」的惯例。 */
    private fun previous(player: Player) {
        val count = player.mediaItemCount
        if (count <= 0) return
        val prevIndex = if (player.currentMediaItemIndex <= 0) count - 1 else player.currentMediaItemIndex - 1
        player.seekToDefaultPosition(prevIndex)
        if (!player.isPlaying) player.play()
    }
}
