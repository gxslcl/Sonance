package com.sonance.player.playback

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.PriorityQueue
import java.util.concurrent.CopyOnWriteArrayList

class TransitionEffectsTest {

    @Test
    fun `switch fade with crossfade enabled preserves userVolume across repeated ticks and switches`() {
        val scheduler = FakeScheduler()
        val config = MutableTransitionConfig(crossfadeEnabled = true, crossfadeSec = 3, gaplessEnabled = true)
        val harness = FakePlayerHarness(initialVolume = 1f)
        val fx = TransitionEffects(harness.player, config, scheduler)
        harness.listeners.add(fx)
        fx.start()

        // 稳态切歌 1：与 50ms 轮询交错，验证 150ms 淡出后切入新曲并升回 1.0f
        fx.beginSwitchFade(150L) {
            harness.player.seekToDefaultPosition(1)
            harness.player.play()
        }
        scheduler.advanceBy(150L)
        assertEquals(listOf(1), harness.seekIndices)

        // 换曲瞬间交叉淡化刚起步为 0；半途（600ms，音量约 0.2f）再次用黑胶模式切到第 2 首
        scheduler.advanceBy(600L)
        assertTrue("fade-in should have started climbing, was ${harness.volume}", harness.volume in 0.1f..0.3f)

        fx.beginSwitchFade(150L) {
            harness.player.seekToDefaultPosition(2)
            harness.player.play()
        }
        scheduler.advanceBy(150L)
        assertEquals(listOf(1, 2), harness.seekIndices)

        // 完整走完 3 秒淡入，音量必须回到最初的 1.0f（不能被淡出阶段的低音量/0 锁死）
        scheduler.advanceBy(3100L)
        assertEquals(1.0f, harness.volume, 1e-3f)
    }

    @Test
    fun `switch fade with crossfade disabled restores exact userVolume on commit`() {
        val scheduler = FakeScheduler()
        val config = MutableTransitionConfig(crossfadeEnabled = false, crossfadeSec = 3, gaplessEnabled = true)
        val harness = FakePlayerHarness(initialVolume = 0.75f)
        val fx = TransitionEffects(harness.player, config, scheduler)
        harness.listeners.add(fx)
        fx.start()

        fx.beginSwitchFade(150L) {
            harness.player.seekToDefaultPosition(4)
            harness.player.play()
        }
        scheduler.advanceBy(90L)
        assertTrue("volume should fade out below baseline, was ${harness.volume}", harness.volume < 0.5f)
        assertTrue(harness.seekIndices.isEmpty())

        scheduler.advanceBy(60L)
        assertEquals(listOf(4), harness.seekIndices)
        assertEquals(0.75f, harness.volume, 1e-3f)
    }

    @Test
    fun `external pause during switch fade cancels pending switch and restores volume`() {
        val scheduler = FakeScheduler()
        val config = MutableTransitionConfig(crossfadeEnabled = true, crossfadeSec = 3, gaplessEnabled = true)
        val harness = FakePlayerHarness(initialVolume = 0.85f)
        val fx = TransitionEffects(harness.player, config, scheduler)
        harness.listeners.add(fx)
        fx.start()

        fx.beginSwitchFade(150L) {
            harness.player.seekToDefaultPosition(3)
            harness.player.play()
        }
        scheduler.advanceBy(60L)
        assertTrue(harness.volume < 0.7f)

        harness.player.pause()
        assertEquals(0.85f, harness.volume, 1e-3f)

        scheduler.advanceBy(300L)
        assertTrue("paused switch must not commit stale seek", harness.seekIndices.isEmpty())
        assertEquals(0.85f, harness.volume, 1e-3f)
    }

    @Test
    fun `rapid second switch fade supersedes first without corrupting target volume`() {
        val scheduler = FakeScheduler()
        val config = MutableTransitionConfig(crossfadeEnabled = true, crossfadeSec = 3, gaplessEnabled = true)
        val harness = FakePlayerHarness(initialVolume = 0.9f)
        val fx = TransitionEffects(harness.player, config, scheduler)
        harness.listeners.add(fx)
        fx.start()

        fx.beginSwitchFade(150L) {
            harness.player.seekToDefaultPosition(1)
            harness.player.play()
        }
        scheduler.advanceBy(60L)

        fx.beginSwitchFade(150L) {
            harness.player.seekToDefaultPosition(2)
            harness.player.play()
        }
        scheduler.advanceBy(150L)
        assertEquals(listOf(2), harness.seekIndices)

        scheduler.advanceBy(3100L)
        assertEquals(0.9f, harness.volume, 1e-3f)
    }

    @Test
    fun `auto rollover inside 150ms switch window keeps chosen target song and recovers volume`() {
        val scheduler = FakeScheduler()
        val config = MutableTransitionConfig(crossfadeEnabled = true, crossfadeSec = 3, gaplessEnabled = true)
        val harness = FakePlayerHarness(initialVolume = 1.0f)
        val fx = TransitionEffects(harness.player, config, scheduler)
        harness.listeners.add(fx)
        fx.start()

        fx.beginSwitchFade(150L) {
            harness.player.seekToDefaultPosition(3)
            harness.player.play()
        }
        scheduler.advanceBy(60L)
        harness.simulateAutoTransition(1)

        scheduler.advanceBy(100L)
        assertEquals(listOf(3), harness.seekIndices)

        scheduler.advanceBy(3100L)
        assertEquals(1.0f, harness.volume, 1e-3f)
    }

    @Test
    fun `old track ending during 150ms switch still switches cleanly and restores volume`() {
        val scheduler = FakeScheduler()
        val config = MutableTransitionConfig(crossfadeEnabled = false, crossfadeSec = 3, gaplessEnabled = true)
        val harness = FakePlayerHarness(initialVolume = 0.8f)
        val fx = TransitionEffects(harness.player, config, scheduler)
        harness.listeners.add(fx)
        fx.start()

        fx.beginSwitchFade(150L) {
            harness.player.seekToDefaultPosition(2)
            harness.player.play()
        }
        scheduler.advanceBy(60L)
        harness.simulateEnded()

        scheduler.advanceBy(100L)
        assertEquals(listOf(2), harness.seekIndices)
        assertEquals(0.8f, harness.volume, 1e-3f)
    }

    @Test
    fun `setUserVolume mid switch fade cancels pending switch and applies new baseline`() {
        val scheduler = FakeScheduler()
        val config = MutableTransitionConfig(crossfadeEnabled = true, crossfadeSec = 3, gaplessEnabled = true)
        val harness = FakePlayerHarness(initialVolume = 1.0f)
        val fx = TransitionEffects(harness.player, config, scheduler)
        harness.listeners.add(fx)
        fx.start()

        fx.beginSwitchFade(150L) {
            harness.player.seekToDefaultPosition(2)
            harness.player.play()
        }
        scheduler.advanceBy(60L)

        fx.setUserVolume(0.45f)
        assertEquals(0.45f, harness.volume, 1e-3f)

        scheduler.advanceBy(300L)
        assertTrue(harness.seekIndices.isEmpty())
        assertEquals(0.45f, harness.volume, 1e-3f)
    }

    @Test
    fun `disabled crossfade schedules zero background ticks and absorbs onVolumeChanged`() {
        val scheduler = FakeScheduler()
        val config = MutableTransitionConfig(crossfadeEnabled = false, crossfadeSec = 3, gaplessEnabled = true)
        val harness = FakePlayerHarness(initialVolume = 0.8f)
        val fx = TransitionEffects(harness.player, config, scheduler)
        harness.listeners.add(fx)
        fx.start()

        assertEquals(0, scheduler.pendingCount())
        harness.player.volume = 0.6f
        assertEquals(0, scheduler.pendingCount())

        fx.beginSwitchFade(150L) {
            harness.player.seekToDefaultPosition(1)
            harness.player.play()
        }
        scheduler.advanceBy(200L)
        assertEquals(0.6f, harness.volume, 1e-3f)
        assertEquals(0, scheduler.pendingCount())
    }

    @Test
    fun `pausing cancels crossfade monitoring timer and playing re-arms it`() {
        val scheduler = FakeScheduler()
        val config = MutableTransitionConfig(crossfadeEnabled = true, crossfadeSec = 3, gaplessEnabled = true)
        val harness = FakePlayerHarness(initialVolume = 1f)
        val fx = TransitionEffects(harness.player, config, scheduler)
        harness.listeners.add(fx)
        fx.start()

        assertTrue("playing with crossfade should arm future check", scheduler.pendingCount() > 0)
        harness.player.pause()
        assertEquals("paused player must keep zero pending timers", 0, scheduler.pendingCount())

        harness.player.play()
        assertTrue("resuming playback should re-arm monitoring timer", scheduler.pendingCount() > 0)
    }

    @Test
    fun `single and rapid consecutive track switches preserve full userVolume under real ExoPlayer volume callbacks`() {
        val scheduler = FakeScheduler()
        val config = MutableTransitionConfig(crossfadeEnabled = true, crossfadeSec = 3, gaplessEnabled = true)
        val harness = FakePlayerHarness(initialVolume = 1.0f)
        val fx = TransitionEffects(harness.player, config, scheduler)
        harness.listeners.add(fx)
        fx.start()

        // 单次切歌：进 50ms 淡入首帧后仍必须顺利恢复到 1.0f
        PlaybackCommands.execute(harness.player, PlaybackCommands.NEXT)
        scheduler.advanceBy(50L)
        scheduler.advanceBy(3_100L)
        assertEquals(1.0f, harness.volume, 1e-3f)

        // 快速连续切歌 10 次，覆盖淡入半途切歌及 flushEvents 批量回调
        val gaps = longArrayOf(40L, 90L, 50L, 120L, 35L, 75L, 60L, 100L, 45L, 80L)
        for (gap in gaps) {
            PlaybackCommands.execute(harness.player, PlaybackCommands.NEXT)
            scheduler.advanceBy(gap)
        }
        scheduler.advanceBy(3_200L)
        assertEquals(1.0f, harness.volume, 1e-3f)
    }

    @Test
    fun `rapid switch fades interrupted by manual next keep exact userVolume`() {
        val scheduler = FakeScheduler()
        val config = MutableTransitionConfig(crossfadeEnabled = true, crossfadeSec = 3, gaplessEnabled = true)
        val harness = FakePlayerHarness(initialVolume = 0.85f)
        val fx = TransitionEffects(harness.player, config, scheduler)
        harness.listeners.add(fx)
        fx.start()

        fx.beginSwitchFade(150L) {
            harness.player.seekToDefaultPosition(1)
            harness.player.play()
        }
        scheduler.advanceBy(50L)
        fx.cancelSwitchFade(restoreVolume = true)
        PlaybackCommands.execute(harness.player, PlaybackCommands.NEXT)
        scheduler.advanceBy(40L)
        fx.beginSwitchFade(150L) {
            harness.player.seekToDefaultPosition(4)
            harness.player.play()
        }
        scheduler.advanceBy(45L)
        fx.cancelSwitchFade(restoreVolume = true)
        PlaybackCommands.execute(harness.player, PlaybackCommands.NEXT)

        scheduler.advanceBy(3_200L)
        assertEquals(0.85f, harness.volume, 1e-3f)
    }

    private class MutableTransitionConfig(
        override var crossfadeEnabled: Boolean,
        override var crossfadeSec: Int,
        override var gaplessEnabled: Boolean
    ) : TransitionConfig

    private class FakeScheduler : TransitionScheduler {
        private data class Task(
            val runAt: Long,
            val seq: Long,
            val runnable: Runnable
        ) : Comparable<Task> {
            override fun compareTo(other: Task): Int =
                compareValuesBy(this, other, { it.runAt }, { it.seq })
        }

        private var nowMs = 1_000L
        private var nextSeq = 0L
        private val queue = PriorityQueue<Task>()

        override fun uptimeMillis(): Long = nowMs

        override fun postDelayed(runnable: Runnable, delayMs: Long) {
            queue.add(Task(nowMs + delayMs.coerceAtLeast(0L), nextSeq++, runnable))
        }

        override fun removeCallbacks(runnable: Runnable) {
            queue.removeIf { it.runnable === runnable }
        }

        fun pendingCount(): Int = queue.size

        fun advanceBy(ms: Long) {
            val target = nowMs + ms.coerceAtLeast(0L)
            while (true) {
                val head = queue.peek() ?: break
                if (head.runAt > target) break
                queue.poll()
                nowMs = head.runAt
                head.runnable.run()
            }
            nowMs = target
        }
    }

    private class FakePlayerHarness(initialVolume: Float = 1f) {
        var volume: Float = initialVolume
        var playWhenReady: Boolean = true
        var playbackState: Int = Player.STATE_READY
        var currentIndex: Int = 0
        var mediaItemCount: Int = 6
        var positionMs: Long = 10_000L
        var durationMs: Long = 180_000L
        var playerError: PlaybackException? = null
        val listeners = CopyOnWriteArrayList<Player.Listener>()
        val seekIndices = mutableListOf<Int>()
        private var flushDepth = 0
        private val deferredEvents = ArrayDeque<() -> Unit>()

        private val isPlayingNow: Boolean
            get() = playWhenReady && playbackState == Player.STATE_READY && playerError == null

        private fun flushListeners(block: () -> Unit) {
            flushDepth++
            try {
                block()
            } finally {
                flushDepth--
                if (flushDepth == 0) {
                    while (deferredEvents.isNotEmpty()) {
                        deferredEvents.removeFirst().invoke()
                    }
                }
            }
        }

        fun simulateAutoTransition(newIndex: Int) {
            currentIndex = newIndex
            flushListeners {
                listeners.forEach {
                    it.onMediaItemTransition(null, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
                }
            }
        }

        fun simulateEnded() {
            playbackState = Player.STATE_ENDED
            flushListeners {
                listeners.forEach { it.onPlaybackStateChanged(Player.STATE_ENDED) }
            }
        }

        val player: Player = Proxy.newProxyInstance(
            Player::class.java.classLoader,
            arrayOf(Player::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getVolume" -> volume
                "setVolume" -> {
                    val clamped = (args[0] as Float).coerceIn(0f, 1f)
                    if (volume != clamped) {
                        volume = clamped
                        if (flushDepth > 0) {
                            deferredEvents.addLast {
                                listeners.forEach { it.onVolumeChanged(clamped) }
                            }
                        } else {
                            listeners.forEach { it.onVolumeChanged(clamped) }
                        }
                    }
                    Unit
                }
                "isPlaying" -> isPlayingNow
                "getPlayWhenReady" -> playWhenReady
                "getPlaybackState" -> playbackState
                "getCurrentMediaItemIndex" -> currentIndex
                "getMediaItemCount" -> mediaItemCount
                "getCurrentPosition" -> positionMs
                "getDuration" -> durationMs
                "getPlayerError" -> playerError
                "hasNextMediaItem" -> currentIndex < mediaItemCount - 1
                "addListener" -> {
                    listeners.add(args[0] as Player.Listener)
                    Unit
                }
                "removeListener" -> {
                    listeners.remove(args[0] as Player.Listener)
                    Unit
                }
                "seekToDefaultPosition" -> {
                    val idx = if (args != null && args.isNotEmpty()) args[0] as Int else currentIndex
                    currentIndex = idx
                    seekIndices.add(idx)
                    playbackState = Player.STATE_READY
                    val dummyPos = Player.PositionInfo(null, idx, null, null, idx, 0L, 0L, -1, -1)
                    flushListeners {
                        listeners.forEach {
                            it.onPositionDiscontinuity(dummyPos, dummyPos, Player.DISCONTINUITY_REASON_SEEK)
                        }
                        listeners.forEach {
                            it.onMediaItemTransition(null, Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
                        }
                    }
                    Unit
                }
                "play" -> {
                    val changed = !playWhenReady
                    playWhenReady = true
                    if (changed) {
                        flushListeners {
                            listeners.forEach {
                                it.onPlayWhenReadyChanged(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                            }
                        }
                    }
                    Unit
                }
                "pause" -> {
                    val changed = playWhenReady
                    playWhenReady = false
                    if (changed) {
                        flushListeners {
                            listeners.forEach {
                                it.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                            }
                        }
                    }
                    Unit
                }
                "prepare" -> {
                    if (playbackState == Player.STATE_IDLE) {
                        playbackState = Player.STATE_READY
                        flushListeners {
                            listeners.forEach { it.onPlaybackStateChanged(Player.STATE_READY) }
                        }
                    }
                    Unit
                }
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    java.lang.Float.TYPE -> 0f
                    java.lang.Double.TYPE -> 0.0
                    else -> null
                }
            }
        } as Player
    }
}
