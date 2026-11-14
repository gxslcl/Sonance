package com.sonance.player.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.sonance.player.data.Prefs

internal interface TransitionConfig {
    val crossfadeEnabled: Boolean
    val crossfadeSec: Int
    val gaplessEnabled: Boolean
}

internal interface TransitionScheduler {
    fun uptimeMillis(): Long
    fun postDelayed(runnable: Runnable, delayMs: Long)
    fun removeCallbacks(runnable: Runnable)
}

private class PrefsTransitionConfig(private val prefs: Prefs) : TransitionConfig {
    override val crossfadeEnabled: Boolean get() = prefs.crossfadeEnabled
    override val crossfadeSec: Int get() = prefs.crossfadeSec
    override val gaplessEnabled: Boolean get() = prefs.gaplessEnabled
}

private class MainHandlerScheduler : TransitionScheduler {
    private val handler = Handler(Looper.getMainLooper())
    override fun uptimeMillis(): Long = SystemClock.uptimeMillis()
    override fun postDelayed(runnable: Runnable, delayMs: Long) {
        handler.postDelayed(runnable, delayMs)
    }
    override fun removeCallbacks(runnable: Runnable) {
        handler.removeCallbacks(runnable)
    }
}

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
 * ## 音量基准与手动切歌淡出（单一职责）
 * 音量基准 [userVolume] 只在用户显式设音量或检测到非本类写入的外部音量变动时更新；
 * 无论交叉淡化还是黑胶模式等手动切歌前的短淡出，均统一在本类内通过 [writeVolume] 驱动，
 * 避免跨组件瞬时降音把 [userVolume] 永久污染成 0。
 */
internal class TransitionEffects internal constructor(
    private val player: Player,
    private val config: TransitionConfig,
    private val scheduler: TransitionScheduler
) : Player.Listener {

    constructor(player: Player, context: Context) : this(
        player = player,
        config = PrefsTransitionConfig(Prefs(context.applicationContext)),
        scheduler = MainHandlerScheduler()
    )

    private var userVolume = 1f

    /**
     * 最近一次由本效果写入的音量值。淡化进行中或空闲期若发现播放器音量与它不同，
     * 说明外部（如音量条/MediaController）改过音量 —— 采纳为新基准。
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

    // 手动切歌前短淡出（如黑胶模式 150ms 淡出后再 seek+play）
    private var switchFading = false
    private var switchFadeStart = 0L
    private var switchFadeDur = 1L
    private var switchFadeFrom = 0f
    private var switchCommit: (() -> Unit)? = null
    private var committingSwitchSeek = false

    private var gapPending = false
    private var gapRunnable: Runnable? = null

    private val tick = object : Runnable {
        override fun run() {
            step()
            scheduler.postDelayed(this, TICK_MS)
        }
    }

    private val switchTick = object : Runnable {
        override fun run() {
            stepSwitchFade()
        }
    }

    fun start() {
        userVolume = player.volume
        lastWrittenVolume = player.volume
        scheduler.postDelayed(tick, TICK_MS)
    }

    fun release() {
        scheduler.removeCallbacks(tick)
        scheduler.removeCallbacks(switchTick)
        cancelGap()
        val hadActiveState = fading || awaitingTransition || switchFading
        fading = false
        awaitingTransition = false
        switchFading = false
        switchCommit = null
        if (hadActiveState) {
            writeVolume(userVolume)
        }
    }

    /** 由播控层在用户明确调音量时同步写入基准，避免与异步 binder 调音或淡出冲突。 */
    fun setUserVolume(volume: Float) {
        val v = volume.coerceIn(0f, 1f)
        userVolume = v
        if (switchFading) {
            cancelSwitchFade(restoreVolume = true)
            return
        }
        if (fading) {
            retargetCrossfade(player.volume)
            lastWrittenVolume = player.volume
        } else if (!awaitingTransition) {
            writeVolume(v)
        }
    }

    /**
     * 先把当前曲目音量在 [durMs] 内渐降到 0，完成后恢复 [userVolume] 再执行 [onCommitSwitch]。
     * 整个淡出期间锁住 [userVolume]，杜绝 50ms 轮询把 0 误采样为用户音量。
     */
    fun beginSwitchFade(durMs: Long, onCommitSwitch: () -> Unit) {
        scheduler.removeCallbacks(switchTick)
        cancelGap()
        // 非淡化期间若有外部改动（例如未走 setUserVolume 的 MediaController 调音），先吸收
        if (!switchFading && !fading && !awaitingTransition &&
            !lastWrittenVolume.isNaN() && player.volume != lastWrittenVolume
        ) {
            userVolume = player.volume
            lastWrittenVolume = player.volume
        }
        fading = false
        awaitingTransition = false
        switchFading = false
        switchCommit = null

        val clampedDur = durMs.coerceAtLeast(0L)
        if (clampedDur == 0L) {
            writeVolume(userVolume)
            commitSwitch(onCommitSwitch)
            return
        }

        switchFadeFrom = player.volume
        switchFadeDur = clampedDur
        switchFadeStart = scheduler.uptimeMillis()
        switchCommit = onCommitSwitch
        switchFading = true
        lastWrittenVolume = switchFadeFrom
        scheduler.postDelayed(switchTick, SWITCH_TICK_MS)
    }

    fun cancelSwitchFade(restoreVolume: Boolean = true) {
        if (!switchFading) return
        scheduler.removeCallbacks(switchTick)
        switchFading = false
        switchCommit = null
        if (restoreVolume) {
            writeVolume(userVolume)
        }
    }

    private fun writeVolume(v: Float) {
        player.volume = v
        lastWrittenVolume = v
    }

    private fun retargetCrossfade(currentVolume: Float) {
        val now = scheduler.uptimeMillis()
        fadeDur = (fadeStart + fadeDur - now).coerceAtLeast(1L)
        fadeStart = now
        fadeFrom = currentVolume
        fadeTo = if (fadeOut) 0f else userVolume
    }

    private fun stepSwitchFade() {
        if (!switchFading) return
        val elapsed = scheduler.uptimeMillis() - switchFadeStart
        val t = (elapsed.toFloat() / switchFadeDur).coerceIn(0f, 1f)
        if (t >= 1f) {
            val commit = switchCommit
            switchCommit = null
            switchFading = false
            // 切歌前必须先把瞬时音量归还到 userVolume，否则随后的换曲淡入（若启用）
            // 或直通播放会读到/停留在 0
            writeVolume(userVolume)
            commitSwitch(commit)
            return
        }
        writeVolume(switchFadeFrom * (1f - t))
        scheduler.postDelayed(switchTick, SWITCH_TICK_MS)
    }

    private fun commitSwitch(commit: (() -> Unit)?) {
        if (commit == null) return
        committingSwitchSeek = true
        try {
            commit()
        } finally {
            committingSwitchSeek = false
        }
    }

    private fun step() {
        if (switchFading) return
        if (fading) {
            if (player.volume != lastWrittenVolume) {
                // 淡化进行中用户拖了音量条：采纳为新基准，剩余时长按新目标重新定向
                userVolume = player.volume
                retargetCrossfade(player.volume)
            }
            if (!player.isPlaying) return
            val t = ((scheduler.uptimeMillis() - fadeStart).toFloat() / fadeDur).coerceIn(0f, 1f)
            writeVolume(fadeFrom + (fadeTo - fadeFrom) * t)
            if (t >= 1f) {
                fading = false
                if (fadeOut) awaitingTransition = true
            }
            return
        }
        if (!player.isPlaying || awaitingTransition) return
        // 只在播放器音量与上次本类写入值不一致时才采纳，避免无意义的跨状态重写
        if (lastWrittenVolume.isNaN() || player.volume != lastWrittenVolume) {
            userVolume = player.volume
            lastWrittenVolume = player.volume
        }
        if (!config.crossfadeEnabled) return
        val dur = player.duration
        if (dur <= 0L) return
        val fadeMs = config.crossfadeSec * 1000L
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
        fadeStart = scheduler.uptimeMillis()
        fadeOut = out
        fading = true
        lastWrittenVolume = from
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (switchFading && !committingSwitchSeek) {
            // 若旧曲刚好在 150ms 手动切歌淡出窗口内自然播完自动换曲：保留本次手动切歌目标，
            // 不让过渡曲目的交叉淡化/停顿打断正在进行的淡出。
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                cancelGap()
                return
            }
            cancelSwitchFade(restoreVolume = true)
        }
        awaitingTransition = false
        if (fading) {
            fading = false
            writeVolume(userVolume)
        }
        cancelGap()
        // 关闭无缝：只在自动续播时插停顿。pause 触发的 playWhenReady 回调由 gapPending 豁免
        if (!config.gaplessEnabled &&
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
            scheduler.postDelayed(r, GAP_MS)
        }
        if (config.crossfadeEnabled && player.playWhenReady) {
            writeVolume(0f)
            beginFade(0f, userVolume, config.crossfadeSec * 1000L, out = false)
        }
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (!playWhenReady && switchFading) {
            // 淡出期间被暂停（如耳机按键/通知栏）：放弃延迟切歌并恢复用户音量
            cancelSwitchFade(restoreVolume = true)
        }
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
        if (switchFading && !committingSwitchSeek) {
            cancelSwitchFade(restoreVolume = true)
        }
        cancelGap()
        awaitingTransition = false
        if (fading) {
            fading = false
            writeVolume(userVolume)
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_IDLE && switchFading && player.playerError == null) {
            // 外部显式 stop() / 清空队列，而非旧曲自身播放报错：取消延迟切歌并恢复音量
            cancelSwitchFade(restoreVolume = true)
        }
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
        gapRunnable?.let { scheduler.removeCallbacks(it) }
        gapRunnable = null
        gapPending = false
    }

    companion object {
        internal const val TICK_MS = 50L
        internal const val SWITCH_TICK_MS = 15L

        /** 关闭无缝时的曲间停顿 */
        internal const val GAP_MS = 500L
    }
}