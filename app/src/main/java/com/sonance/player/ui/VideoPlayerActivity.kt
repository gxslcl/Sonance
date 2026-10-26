package com.sonance.player.ui

import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.sonance.player.data.Prefs
import com.sonance.player.data.bili.BiliEntry
import com.sonance.player.data.bili.BiliRepository
import com.sonance.player.playback.PlayMode
import com.sonance.player.ui.icon.SonanceIcons
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * B 站缓存的全屏播放页。
 *
 * ## 为什么用系统 MediaPlayer 而不是 ExoPlayer
 *
 * B 站缓存的音视频轨先由 [com.sonance.player.data.bili.BiliMp4Writer] 合成一个
 * 标准 MP4。这个文件系统 `MediaExtractor` 能读出两条轨（写完后有自检日志），
 * ffmpeg 也认，但 Media3 的 `Mp4Extractor` 解析出来是 **0 条轨**，导致
 * `index (0) must be less than size (0)`。
 *
 * 既然系统解封装器认可这个文件，就用系统封装好的 `VideoView` 播 —— 它把
 * 「surface 创建 → 绑定播放器 → 尺寸变化 → 销毁」这一串全包了。自己搭
 * SurfaceView + MediaPlayer 实测会在 surfaceCreated 里被调用两次、报 -38。
 *
 * ## 播放列表
 *
 * 这一页接收整个缓存列表的 key（不是条目本身：300 条 JSON 走 Intent 太重），
 * 再从 [BiliRepository] 里按 key 换回条目。拿不到列表（比如直接被唤起）时
 * 退化成单条播放，上下首按钮不出现，不会崩。
 */
class VideoPlayerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val videoPath = intent.getStringExtra(EXTRA_VIDEO)
        if (videoPath.isNullOrBlank()) {
            finish()
            return
        }

        // 横屏 + 常亮 + 沉浸：视频页的默认姿态，用户按住系统手势可以临时唤出状态栏
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setContent {
            MaterialTheme {
                VideoStage(
                    startPath = videoPath,
                    keys = intent.getStringArrayListExtra(EXTRA_KEYS) ?: ArrayList(),
                    startKey = intent.getStringExtra(EXTRA_KEY),
                    onBack = { finish() }
                )
            }
        }
    }

    companion object {
        const val EXTRA_VIDEO = "video_path"
        const val EXTRA_KEY = "entry_key"
        const val EXTRA_KEYS = "entry_keys"
        const val EXTRA_TITLE = "video_title"
        const val EXTRA_OWNER = "video_owner"
        const val EXTRA_AUDIO = "audio_path"
    }
}

private enum class ModeIcon { SHUFFLE, REPEAT_ALL, REPEAT_ONE }

@Composable
private fun VideoStage(
    startPath: String,
    keys: List<String>,
    startKey: String?,
    onBack: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { BiliRepository(context) }
    val prefs = remember { Prefs(context) }

    // 只有能播的才进播放列表，否则上下首会跳到点不开的条目上
    val playlist = remember(keys) {
        if (startKey.isNullOrBlank()) emptyList()
        else keys.mapNotNull { repo.entryByKey(it) }.filter { it.playable }
    }
    val hasList = playlist.size > 1

    var currentEntry by remember { mutableStateOf(playlist.firstOrNull { it.key == startKey }) }
    var currentPath by remember { mutableStateOf(startPath) }
    var videoView by remember { mutableStateOf<VideoView?>(null) }

    var prepared by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var ended by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var controlsVisible by remember { mutableStateOf(true) }
    var sliding by remember { mutableStateOf<Float?>(null) }

    /** 切换条目时要重新合成 MP4，得让用户知道在忙什么 */
    var switching by remember { mutableStateOf(false) }
    var switchProgress by remember { mutableFloatStateOf(0f) }

    /** 三态播放模式，存的是 ordinal，取的时候夹范围防止读到脏值 */
    var modeOrdinal by remember {
        mutableIntStateOf(prefs.biliPlayMode.coerceIn(0, PlayMode.entries.size - 1))
    }

    fun mode(): PlayMode = PlayMode.entries.getOrNull(modeOrdinal) ?: PlayMode.REPEAT_ALL

    fun cycleMode() {
        modeOrdinal = (modeOrdinal + 1) % PlayMode.entries.size
        prefs.biliPlayMode = modeOrdinal
    }

    fun neighbor(delta: Int): BiliEntry? {
        if (!hasList) return null
        val index = playlist.indexOfFirst { it.key == currentEntry?.key }
        if (index < 0) return null
        return playlist[(index + delta + playlist.size) % playlist.size]
    }

    /** 播完/点下一首时该去哪条。单曲循环返回自己，由调用方决定是重播还是不切 */
    fun pickNext(): BiliEntry? = when (mode()) {
        PlayMode.REPEAT_ONE -> currentEntry
        PlayMode.SHUFFLE -> playlist
            .filter { it.key != currentEntry?.key }
            .takeIf { it.isNotEmpty() }
            ?.random()
            ?: currentEntry

        PlayMode.REPEAT_ALL -> neighbor(+1)
    }

    fun switchTo(entry: BiliEntry) {
        if (switching) return
        // 切到正在播的这条（单曲循环点下一首）没必要重新合成
        if (entry.key == currentEntry?.key) {
            runCatching {
                videoView?.seekTo(0)
                videoView?.start()
                ended = false
            }
            return
        }
        scope.launch {
            switching = true
            switchProgress = 0f
            val outcome = repo.prepare(entry) { switchProgress = it }
            outcome.onSuccess { media ->
                error = null
                currentEntry = entry
                currentPath = media.file.absolutePath
            }.onFailure {
                error = "下一条准备失败：${it.message}"
            }
            switching = false
        }
    }

    LaunchedEffect(currentPath, videoView) {
        val view = videoView ?: return@LaunchedEffect
        prepared = false
        ended = false
        error = null
        runCatching {
            view.setVideoURI(Uri.fromFile(java.io.File(currentPath)))
        }.onFailure {
            error = it.message
        }
    }

    LaunchedEffect(videoView) {
        while (true) {
            videoView?.let { vv ->
                runCatching {
                    position = vv.currentPosition.toLong()
                    if (vv.duration > 0) duration = vv.duration.toLong()
                    if (prepared) isPlaying = vv.isPlaying
                }
            }
            delay(400)
        }
    }

    // 暂停时把控制条一直留着；播放中 3 秒后自动收起
    LaunchedEffect(isPlaying) {
        if (!isPlaying) controlsVisible = true
    }
    LaunchedEffect(controlsVisible, isPlaying) {
        if (controlsVisible && isPlaying) {
            delay(3_000)
            controlsVisible = false
        }
    }

    BackHandler { onBack() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { controlsVisible = !controlsVisible }
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                VideoView(ctx).apply {
                    setOnPreparedListener { vv ->
                        prepared = true
                        duration = vv.duration.toLong()
                        vv.start()
                        isPlaying = true
                        error = null
                    }
                    setOnCompletionListener { vv ->
                        if (mode() == PlayMode.REPEAT_ONE) {
                            vv.seekTo(0)
                            vv.start()
                        } else {
                            val next = pickNext()
                            if (next != null && hasList) {
                                switchTo(next)
                            } else {
                                // 只有一条：循环它自己
                                vv.seekTo(0)
                                vv.start()
                            }
                        }
                    }
                    setOnErrorListener { _, what, extra ->
                        error = "what=$what extra=$extra"
                        true
                    }
                    videoView = this
                }
            },
            onRelease = { view ->
                runCatching { view.stopPlayback() }
                videoView = null
            }
        )

        if (controlsVisible) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.5f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = SonanceIcons.ArrowLeft,
                        contentDescription = "返回",
                        tint = Color.White,
                        modifier = Modifier
                            .size(24.dp)
                            .clip(RoundedCornerShape(50))
                            .clickable { onBack() }
                            .padding(2.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = currentEntry?.displayTitle
                                ?: intentTitle(context),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val owner = currentEntry?.owner?.takeIf { it.isNotBlank() }
                        if (owner != null) {
                            Text(
                                text = owner,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.65f),
                                maxLines = 1
                            )
                        }
                    }
                }

                Spacer(Modifier.weight(1f))

                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.5f))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isPlaying) SonanceIcons.Pause else SonanceIcons.Play,
                        contentDescription = if (isPlaying) "暂停" else "播放",
                        tint = Color.White,
                        modifier = Modifier
                            .size(30.dp)
                            .clickable {
                                togglePlay(videoView) { isPlaying = it }
                            }
                            .padding(4.dp)
                    )

                    Text(
                        text = formatClock(position),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )

                    Slider(
                        value = sliding ?: position.toFloat().coerceAtLeast(0f),
                        onValueChange = { sliding = it },
                        onValueChangeFinished = {
                            sliding?.let { target ->
                                runCatching {
                                    videoView?.seekTo(target.toInt())
                                    position = target.toLong()
                                }
                            }
                            sliding = null
                        },
                        valueRange = 0f..(duration.takeIf { it > 0 }?.toFloat() ?: 1f),
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color.White,
                            inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                        ),
                        modifier = Modifier.weight(1f).height(24.dp)
                    )

                    Text(
                        text = formatClock(duration),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }

        // 切换条目的等待。合成一条 400MB 的视频要几秒，不给反馈像是卡死了
        if (switching) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.Black.copy(alpha = 0.72f))
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        progress = { switchProgress },
                        modifier = Modifier.size(34.dp),
                        strokeWidth = 3.dp,
                        color = Color.White
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "准备下一条… ${(switchProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                }
            }
        } else if (!isPlaying && !ended && prepared) {
            // 暂停面板：只在这时候出现，一恢复播放就没了
            PausePanel(
                hasList = hasList,
                mode = mode(),
                onPrev = { neighbor(-1)?.let { switchTo(it) } },
                onNext = {
                    val next = if (mode() == PlayMode.SHUFFLE) pickNext() else neighbor(+1)
                    next?.let { switchTo(it) }
                },
                onPlay = { togglePlay(videoView) { isPlaying = it } },
                onCycleMode = { cycleMode() }
            )
        }

        if (!prepared && error == null && !switching) {
            Box(Modifier.align(Alignment.Center)) {
                Text("正在准备…", color = Color.White.copy(alpha = 0.7f))
            }
        }

        if (ended && !isPlaying && !switching) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable {
                        runCatching {
                            videoView?.seekTo(0)
                            videoView?.start()
                            ended = false
                        }
                    }
                    .padding(horizontal = 18.dp, vertical = 10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = SonanceIcons.Repeat,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Text("重新播放", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        error?.let { message ->
            Box(
                Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(20.dp)
            ) {
                Text(
                    text = "这条缓存无法解码（$message）\n可能是 HEVC 硬解不支持，或缓存文件不完整",
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

/**
 * 暂停时浮在画面上的控制组件。
 *
 * 上下首 + 播放模式是这一页最常按的东西，平时藏起来（播放中不需要），
 * 手指一点暂停立刻出现，再点播放就消失 —— 不留常驻控件挡画面。
 */
@Composable
private fun PausePanel(
    hasList: Boolean,
    mode: PlayMode,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onPlay: () -> Unit,
    onCycleMode: () -> Unit
) {
    val modeIcon = when (mode) {
        PlayMode.SHUFFLE -> SonanceIcons.Shuffle
        PlayMode.REPEAT_ONE -> SonanceIcons.RepeatOne
        PlayMode.REPEAT_ALL -> SonanceIcons.Repeat
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Color.Black.copy(alpha = 0.62f))
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            if (hasList) {
                PanelButton(
                    icon = SonanceIcons.Prev,
                    contentDescription = "上一曲",
                    onClick = onPrev
                )
            }
            PanelButton(
                icon = SonanceIcons.Play,
                contentDescription = "播放",
                size = 34.dp,
                onClick = onPlay
            )
            if (hasList) {
                PanelButton(
                    icon = SonanceIcons.Next,
                    contentDescription = "下一曲",
                    onClick = onNext
                )
                // 竖线分隔：传输控件和模式切换是两类动作，混在一起会按错
                Box(
                    Modifier
                        .width(1.dp)
                        .height(26.dp)
                        .background(Color.White.copy(alpha = 0.22f))
                )
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onCycleMode)
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Icon(
                        imageVector = modeIcon,
                        contentDescription = mode.label,
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        text = mode.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                }
            }
        }
    }
}

@Composable
private fun PanelButton(
    icon: ImageVector,
    contentDescription: String,
    size: androidx.compose.ui.unit.Dp = 26.dp,
    onClick: () -> Unit
) {
    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        tint = Color.White,
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick)
    )
}

private fun togglePlay(view: VideoView?, onState: (Boolean) -> Unit) {
    val vv = view ?: return
    runCatching {
        if (vv.isPlaying) vv.pause() else vv.start()
        onState(vv.isPlaying)
    }
}

/** 拿不到列表（直接被唤起）时退回 Intent 里带的标题 */
@Composable
private fun intentTitle(context: android.content.Context): String {
    return remember {
        (context as? android.app.Activity)
            ?.intent
            ?.getStringExtra(VideoPlayerActivity.EXTRA_TITLE)
            .orEmpty()
    }
}

private fun formatClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
