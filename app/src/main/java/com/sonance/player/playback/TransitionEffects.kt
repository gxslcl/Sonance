package com.sonance.player.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.sonance.player.data.Prefs

/**
 * 曲目衔接效果：无缝播放开关 + 淡入淡出，挂在播放服务的 ExoPlayer 上。
 *
 * ## 无缝播放
 * ExoPlayer 对带编码器延迟/填充信息的格式（MP3/AAC 等）本来就是样本级无缝的，
 * 所以「开启」= 保持原生行为，什么都不做；「关闭」= 在**自动续播**发生时
 * 先把下一首暂停 [GAP_MS] 毫秒再放行，人为制造一个可感知的曲间停顿。
 * 手动切歌不受影响（用户主动操作不该被插停顿）。
 *
 * ## 淡入淡出（单播放器实现）
 * 真交叉淡化需要两个播放器重叠出声；这里用「结尾渐弱 + 开头渐强」近似：
 * - 当前曲剩余时长 ≤ 设定时长时，把音量在剩余时间内线性降到 0；
 * - 换曲瞬间把音量置 0，再在设定时长内线性升回用户音量。
 * 听感上就是两首歌之间柔和的音量过渡，且与无缝播放互不冲突。
 *
 * 音量基准 [userVolume] 只在「非淡化且正在播放」时采样，
 * 因此淡化过程不会把用户音量污染成 0；暂停会冻结淡化进度，恢复后继续。
 */
internal class TransitionEffects(
    private val player: Player,
    context: Context
) : Player.Listener {

    private val prefs = Prefs(context.applicationContext)
    private val handler = Handler(Looper.getMainLooper())

    private var userVolume = 1f

    /**
     * 最近一次由本效果写入的音量值。淡化进行中若发现播放器音量与它不同，
     * 说明用户刚拖了音量条 —— 立即采纳为新基准并重新定向淡化，
     * 否则用户的调节会在下一拍（≤50ms）被淡化插值覆盖掉。
     */
    private var lastWrittenVolume = Float.NaN

    private var fading = false
    private var fadeOut = false
    private var fadeStart = 0L
    private var fadeDur = 1L
    private var fadeFrom = 0f
    private var fadeTo = 0f

    /** 淡出已到 0、正等着换曲：期间不再采样音量，也不再起新的淡出 */
    private var awaitingTransition = false

    private var gapPending = false
    private var gapRunnable: Runnable? = null

    private val tick = object : Runnable {
        override fun run() {
            step()
            handler.postDelayed(this, TICK_MS)
        }
    }

    fun start() {
        userVolume = player.volume
        lastWrittenVolume = player.volume
        handler.postDelayed(tick, TICK_MS)
    }

    fun release() {
        handler.removeCallbacks(tick)
        cancelGap()
        if (fading || awaitingTransition) {
            fading = false
            awaitingTransition = false
            writeVolume(userVolume)
        }
    }

    private fun writeVolume(v: Float) {
        player.volume = v
        lastWrittenVolume = v
    }

    private fun step() {
        if (fading) {
            if (player.volume != lastWrittenVolume) {
                // 淡化进行中用户拖了音量条：采纳为新基准，剩余时长按新目标重新定向
                userVolume = player.volume
                val now = SystemClock.uptimeMillis()
                fadeDur = (fadeStart + fadeDur - now).coerceAtLeast(1L)
                fadeStart = now
                fadeFrom = player.volume
                fadeTo = if (fadeOut) 0f else userVolume
            }
            if (!player.isPlaying) return
            val t = ((SystemClock.uptimeMillis() - fadeStart).toFloat() / fadeDur).coerceIn(0f, 1f)
            writeVolume(fadeFrom + (fadeTo - fadeFrom) * t)
            if (t >= 1f) {
                fading = false
                if (fadeOut) awaitingTransition = true
            }
            return
        }
        if (!player.isPlaying || awaitingTransition) return
        userVolume = player.volume
        if (!prefs.crossfadeEnabled) return
        val dur = player.duration
        if (dur <= 0L) return
        val fadeMs = prefs.crossfadeSec * 1000L
        val remaining = dur - player.currentPosition
        // 比淡化时长还短的歌不做结尾淡出（否则整首都在渐弱）；末首无下一曲也不淡出
        if (remaining in 1..fadeMs && player.currentPosition >= fadeMs && player.hasNextMediaItem()) {
            beginFade(player.volume, 0f, remaining, out = true)
        }
    }

    private fun beginFade(from: Float, to: Float, durMs: Long, out: Boolean) {
        fadeFrom = from
        fadeTo = to
        fadeDur = durMs.coerceAtLeast(1L)
        fadeStart = SystemClock.uptimeMillis()
        fadeOut = out
        fading = true
        lastWrittenVolume = from
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        awaitingTransition = false
        if (fading) {
            fading = false
            writeVolume(userVolume)
        }
        cancelGap()
        // 关闭无缝：只在自动续播时插停顿。pause 触发的 playWhenReady 回调由 gapPending 豁免
        if (!prefs.gaplessEnabled &&
            reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO &&
            player.playWhenReady
        ) {
            gapPending = true
            player.pause()
            val r = Runnable {
                gapPending = false
                gapRunnable = null
                if (!player.playWhenReady && player.playbackState == Player.STATE_READY) {
                    player.play()
                }
            }
            gapRunnable = r
            handler.postDelayed(r, GAP_MS)
        }
        if (prefs.crossfadeEnabled && player.playWhenReady) {
            writeVolume(0f)
            beginFade(0f, userVolume, prefs.crossfadeSec * 1000L, out = false)
        }
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        // 插停顿期间的 pause 是自己触发的，不算用户操作
        if (gapPending) return
        cancelGap()
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int
    ) {
        if (reason != Player.DISCONTINUITY_REASON_SEEK) return
        cancelGap()
        awaitingTransition = false
        if (fading) {
            fading = false
            writeVolume(userVolume)
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) {
            cancelGap()
            if (fading || awaitingTransition) {
                fading = false
                awaitingTransition = false
                writeVolume(userVolume)
            }
        }
    }

    private fun cancelGap() {
        gapRunnable?.let { handler.removeCallbacks(it) }
        gapRunnable = null
        gapPending = false
    }

    companion object {
        private const val TICK_MS = 50L

        /** 关闭无缝时的曲间停顿 */
        private const val GAP_MS = 500L
    }
}