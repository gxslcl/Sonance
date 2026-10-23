package com.sonance.player.ui

import android.content.pm.ActivityInfo

import android.os.Bundle


import android.view.WindowManager
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.sonance.player.ui.icon.SonanceIcons
import kotlinx.coroutines.delay

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
 * 试过 `MergingMediaSource`（直接播原始 m4s）、`MediaMuxer` 重封装、以及
 * 完全自写的容器 —— 前两条路在平台层就崩，自写容器产出的文件系统能读、
 * Media3 不认。既然系统解封装器认可这个文件，就用系统的 `MediaPlayer` 播，
 * 少一层不可控的解析器。
 *
 * 功能上够用：Surface 渲染、seek、时长、进度回调都有；只有倍速和音轨切换这类
 * 高级能力没有，本地看缓存视频不需要。
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
                    path = videoPath,
                    title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
                    owner = intent.getStringExtra(EXTRA_OWNER).orEmpty(),
                    onBack = { finish() }
                )
            }
        }
    }

    companion object {
        const val EXTRA_VIDEO = "video_path"
        const val EXTRA_AUDIO = "audio_path"
        const val EXTRA_TITLE = "video_title"
        const val EXTRA_OWNER = "video_owner"
    }
}

@Composable
private fun VideoStage(
    path: String,
    title: String,
    owner: String,
    onBack: () -> Unit
) {
    var videoView by remember { mutableStateOf<android.widget.VideoView?>(null) }
    var prepared by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var ended by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var controlsVisible by remember { mutableStateOf(true) }
    var sliding by remember { mutableStateOf<Float?>(null) }

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
        // VideoView 而不是自己搭 SurfaceView + MediaPlayer：
        // 前者把「surface 创建 → 绑定播放器 → 尺寸变化 → 销毁」这一串全封装好了，
        // 自己搭实测会在 surfaceCreated 里被调用两次、报 MediaPlayer error -38。
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                android.widget.VideoView(ctx).apply {
                    // 必须给它带 scheme 的 URI：setVideoPath("…/x.mp4") 内部会
                    // 走 ContentResolver.openAssetFileDescriptor，纯路径没有 scheme
                    // 会直接报 "Error setting data source via ContentResolver"
                    setVideoURI(android.net.Uri.fromFile(java.io.File(path)))
                    setOnPreparedListener { vv ->
                        prepared = true
                        duration = vv.duration.toLong()
                        vv.start()
                        isPlaying = true
                        error = null
                    }
                    setOnCompletionListener {
                        ended = true
                        isPlaying = false
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
                            text = title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (owner.isNotBlank()) {
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
                                val vv = videoView ?: return@clickable
                                runCatching {
                                    if (ended) {
                                        vv.seekTo(0)
                                        vv.start()
                                        ended = false
                                    } else if (vv.isPlaying) {
                                        vv.pause()
                                    } else {
                                        vv.start()
                                    }
                                    isPlaying = vv.isPlaying
                                }
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

        if (!prepared && error == null) {
            Box(Modifier.align(Alignment.Center)) {
                Text("正在准备…", color = Color.White.copy(alpha = 0.7f))
            }
        }

        if (ended && !isPlaying) {
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

        error?.let {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(20.dp)
            ) {
                Text(
                    text = "这条缓存无法解码（$it）\n可能是 HEVC 硬解不支持，或缓存文件不完整",
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

private fun formatClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
