package com.sonance.player.playback

import android.net.TestUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class PlaybackIntentTest {
    @Test
    fun `buffering remains active but does not advance the audio clock`() {
        val state = PlayerUiState(hasItem = true, isPlaying = false, isBuffering = true, playWhenReady = true)
        assertTrue(state.isPlaybackActive)
        assertFalse(state.isPlaying)
        assertTrue(activePlaybackIntent(true, Player.STATE_BUFFERING, false))
    }

    @Test
    fun `explicit pause while buffering shows play rather than ignoring the pause`() {
        assertFalse(PlayerUiState(isBuffering = true, playWhenReady = false).isPlaybackActive)
        assertFalse(activePlaybackIntent(false, Player.STATE_BUFFERING, false))
    }

    @Test
    fun `ended and error override retained autoplay intent`() {
        assertFalse(activePlaybackIntent(true, Player.STATE_ENDED, false))
        assertFalse(activePlaybackIntent(true, Player.STATE_IDLE, true))
        assertTrue(activePlaybackIntent(true, Player.STATE_IDLE, false))
        assertTrue(activePlaybackIntent(true, Player.STATE_READY, false))
    }

    @Test
    fun `playing buffering playing handoff never flips the control icon`() {
        val states = listOf(
            PlayerUiState(isPlaying = true, playWhenReady = true, mediaId = 1),
            PlayerUiState(isPlaying = false, playWhenReady = true, isBuffering = true, mediaId = 2),
            PlayerUiState(isPlaying = true, playWhenReady = true, mediaId = 2)
        )
        assertEquals(listOf(true, true, true), states.map { it.isPlaybackActive })
        assertEquals(listOf(true, false, true), states.map { it.isPlaying })
    }

    @Test
    fun `unresolved stream uri identifies blank and outer url fallbacks only`() {
        assertTrue(PlayerController.isUnresolvedStreamUri(null))
        assertTrue(PlayerController.isUnresolvedStreamUri(""))
        assertTrue(PlayerController.isUnresolvedStreamUri("https://music.163.com/song/media/outer/url?id=447925059.mp3"))
        assertFalse(PlayerController.isUnresolvedStreamUri("https://m801.music.126.net/20261114081726/ebaaaa279/ymusic/1234.flac"))
        assertFalse(PlayerController.isUnresolvedStreamUri("content://media/external/audio/media/42"))
    }

    @Test
    fun `repeat one metadata update on MediaController skips replaceMediaItem`() {
        val item0 = MediaItem.Builder().setMediaId("0").setUri(TestUri("https://cdn.example.com/0.mp3")).build()
        val item1 = MediaItem.Builder().setMediaId("1").setUri(TestUri("https://cdn.example.com/1.mp3")).build()
        assertNotNull(item1.localConfiguration)
        val fake = FakeQueuePlayer(
            items = mutableListOf(item0, item1),
            currentIndex = 1,
            repeatMode = Player.REPEAT_MODE_ONE
        )
        val updated = item1.buildUpon()
            .setMediaMetadata(MediaMetadata.Builder().setTitle("Updated").build())
            .build()

        PlayerController.replaceQueueItemSafely(
            player = fake.asPlayer(),
            index = 1,
            updated = updated,
            isMediaController = true
        )

        assertTrue(fake.calls.isEmpty())
        assertEquals(1, fake.currentIndex)
    }

    @Test
    fun `repeat one metadata update on ExoPlayer replaces in place without changing current index`() {
        val item0 = MediaItem.Builder().setMediaId("0").setUri(TestUri("https://cdn.example.com/0.mp3")).build()
        val item1 = MediaItem.Builder().setMediaId("1").setUri(TestUri("https://cdn.example.com/1.mp3")).build()
        assertNotNull(item1.localConfiguration)
        val fake = FakeQueuePlayer(
            items = mutableListOf(item0, item1),
            currentIndex = 1,
            repeatMode = Player.REPEAT_MODE_ONE
        )
        val updated = item1.buildUpon()
            .setMediaMetadata(MediaMetadata.Builder().setTitle("With Lyrics").build())
            .build()

        PlayerController.replaceQueueItemSafely(
            player = fake.asPlayer(),
            index = 1,
            updated = updated,
            isMediaController = false
        )

        assertEquals(listOf("replaceMediaItem(1)"), fake.calls)
        assertEquals(1, fake.currentIndex)
        assertEquals("With Lyrics", fake.items[1].mediaMetadata.title?.toString())
    }

    @Test
    fun `repeat one stream uri change on current item uses add seek remove to keep index`() {
        val item0 = MediaItem.Builder().setMediaId("0").setUri(TestUri("https://cdn.example.com/0.mp3")).build()
        val item1 = MediaItem.Builder().setMediaId("1")
            .setUri(TestUri("https://music.163.com/song/media/outer/url?id=1.mp3"))
            .build()
        assertNotNull(item1.localConfiguration)
        val fake = FakeQueuePlayer(
            items = mutableListOf(item0, item1),
            currentIndex = 1,
            repeatMode = Player.REPEAT_MODE_ONE,
            currentPositionMs = 42_500L,
            playWhenReady = true,
            playbackState = Player.STATE_READY
        )
        val resolved = item1.buildUpon()
            .setUri(TestUri("https://m801.music.126.net/resolved/1.flac"))
            .build()
        assertNotEquals(item1.localConfiguration, resolved.localConfiguration)

        PlayerController.replaceQueueItemSafely(
            player = fake.asPlayer(),
            index = 1,
            updated = resolved,
            isMediaController = false
        )

        assertEquals(
            listOf("addMediaItem(2)", "seekTo(2,42500)", "removeMediaItem(1)"),
            fake.calls
        )
        assertEquals(1, fake.currentIndex)
        assertFalse(0 in fake.observedIndices)
        assertEquals(2, fake.items.size)
        assertEquals(resolved, fake.items[1])
    }

    @Test
    fun `restore request is skipped on connect when live service already holds playback`() {
        assertTrue(PlayerController.shouldSkipRestoreOnConnect(mediaItemCount = 3, isPlaying = true, playWhenReady = true, requestIsRestore = true))
        assertTrue(PlayerController.shouldSkipRestoreOnConnect(mediaItemCount = 0, isPlaying = false, playWhenReady = true, requestIsRestore = true))
        assertTrue(PlayerController.shouldSkipRestoreOnConnect(mediaItemCount = 1, isPlaying = false, playWhenReady = false, requestIsRestore = true))
        assertFalse(PlayerController.shouldSkipRestoreOnConnect(mediaItemCount = 0, isPlaying = false, playWhenReady = false, requestIsRestore = true))
        assertFalse(PlayerController.shouldSkipRestoreOnConnect(mediaItemCount = 3, isPlaying = true, playWhenReady = true, requestIsRestore = false))
    }

    @Test
    fun `hasActiveServicePlayback detects live background queue before UI restores`() {
        val emptyService = FakeQueuePlayer(mutableListOf(), currentIndex = 0, repeatMode = Player.REPEAT_MODE_OFF).asPlayer()
        assertFalse(PlayerController.hasActiveServicePlayback(uiHasItem = false, servicePlayer = null))
        assertFalse(PlayerController.hasActiveServicePlayback(uiHasItem = false, servicePlayer = emptyService))
        assertTrue(PlayerController.hasActiveServicePlayback(uiHasItem = true, servicePlayer = emptyService))

        val playingItem = MediaItem.Builder().setMediaId("9").setUri(TestUri("https://cdn.example.com/9.mp3")).build()
        val liveService = FakeQueuePlayer(
            items = mutableListOf(playingItem),
            currentIndex = 0,
            repeatMode = Player.REPEAT_MODE_ALL,
            playWhenReady = true,
            isPlaying = true
        ).asPlayer()
        assertTrue(PlayerController.hasActiveServicePlayback(uiHasItem = false, servicePlayer = liveService))
    }

    private class FakeQueuePlayer(
        val items: MutableList<MediaItem>,
        var currentIndex: Int,
        var repeatMode: Int,
        var currentPositionMs: Long = 0L,
        var playWhenReady: Boolean = false,
        var playbackState: Int = Player.STATE_READY,
        var isPlaying: Boolean = false
    ) {
        val calls = mutableListOf<String>()
        val observedIndices = mutableListOf(currentIndex)

        fun asPlayer(): Player = Proxy.newProxyInstance(
            Player::class.java.classLoader,
            arrayOf(Player::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getMediaItemCount" -> items.size
                "getMediaItemAt" -> items[args[0] as Int]
                "getCurrentMediaItemIndex" -> currentIndex
                "getRepeatMode" -> repeatMode
                "getCurrentPosition" -> currentPositionMs
                "getPlayWhenReady" -> playWhenReady
                "isPlaying" -> isPlaying
                "getPlaybackState" -> playbackState
                "getPlayerError" -> null
                "replaceMediaItem" -> {
                    val idx = args[0] as Int
                    val item = args[1] as MediaItem
                    val previous = items[idx]
                    calls += "replaceMediaItem($idx)"
                    items[idx] = item
                    if (idx == currentIndex && repeatMode == Player.REPEAT_MODE_ONE &&
                        item.localConfiguration != previous.localConfiguration
                    ) {
                        currentIndex = 0
                    }
                    observedIndices += currentIndex
                    Unit
                }
                "addMediaItem" -> {
                    val idx = args[0] as Int
                    val item = args[1] as MediaItem
                    calls += "addMediaItem($idx)"
                    items.add(idx, item)
                    if (idx <= currentIndex) currentIndex++
                    observedIndices += currentIndex
                    Unit
                }
                "seekTo" -> {
                    if (args != null && args.size == 2) {
                        val idx = args[0] as Int
                        val pos = args[1] as Long
                        calls += "seekTo($idx,$pos)"
                        currentIndex = idx
                        currentPositionMs = pos
                        observedIndices += currentIndex
                    }
                    Unit
                }
                "removeMediaItem" -> {
                    val idx = args[0] as Int
                    calls += "removeMediaItem($idx)"
                    items.removeAt(idx)
                    when {
                        idx < currentIndex -> currentIndex--
                        idx == currentIndex -> currentIndex = if (items.isEmpty()) 0 else 0
                    }
                    observedIndices += currentIndex
                    Unit
                }
                "prepare" -> {
                    calls += "prepare()"
                    Unit
                }
                "play" -> {
                    calls += "play()"
                    playWhenReady = true
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
