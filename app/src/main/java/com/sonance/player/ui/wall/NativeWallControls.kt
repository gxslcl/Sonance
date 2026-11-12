package com.sonance.player.ui.wall

import android.content.Context
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sonance.player.data.Album
import com.sonance.player.data.Prefs
import com.sonance.player.data.Song
import com.sonance.player.data.formatDuration
import com.sonance.player.playback.PlayerUiState
import com.sonance.player.ui.components.CtrlButton
import com.sonance.player.ui.components.PlayPauseButton
import com.sonance.player.ui.components.rememberPlayButtonPalette
import com.sonance.player.ui.icon.SonanceIcons
import com.sonance.player.ui.theme.LocalSkin
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

private val ControlsText = Color(0xFFF2F2F5)

// ===== 顶栏圆形玻璃按钮 =====
@Composable
internal fun GlassIconButton(icon: String, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, tween(120), label = "glassPress")
    val description = when (icon) {
        "exit" -> "关闭"
        "tune" -> "调参"
        "chevron" -> "收起"
        "tracks" -> "曲目列表"
        else -> icon
    }
    Box(
        Modifier
            .size(38.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .background(Color(0x7316161C))
            .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.09f), Color.Transparent)))
            .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape)
            .semantics { contentDescription = description }
            .clickable(interactionSource = interactionSource, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        WallGlyph(icon, Color.White.copy(alpha = 0.85f), 18.dp)
    }
}

// ===== 随便听听 FAB =====
@Composable
internal fun RandomFab(
    spinning: Boolean,
    mini: Boolean,
    bottomInset: Float,
    nowPlaying: Boolean = false,
    onNowPlaying: () -> Unit = {},
    onClick: () -> Unit
) {
    val density = LocalDensity.current
    var angle by remember { mutableStateOf(0f) }
    // 静止时不请求帧；骰子仅在实际滚筒期间旋转。
    LaunchedEffect(spinning) {
        if (!spinning) {
            angle = 0f
            return@LaunchedEffect
        }
        var last = withFrameNanos { it }
        while (isActive) {
            val now = withFrameNanos { it }
            angle = (angle + (now - last) / 1e9f * 800f) % 360f
            last = now
        }
    }
    Box(
        Modifier.fillMaxSize().padding(bottom = with(density) { bottomInset.toDp() } + 26.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (nowPlaying) {
                Box(
                    Modifier.size(54.dp).clip(CircleShape).background(Color(0x8C202028))
                        .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape)
                        .semantics { contentDescription = "回到正在播放" }
                        .clickable(role = Role.Button, onClick = onNowPlaying),
                    contentAlignment = Alignment.Center
                ) {
                    WallGlyph("disc", Color.White, 24.dp)
                }
            }
            Row(
                Modifier.clip(RoundedCornerShape(27.dp)).background(Color(0x8C202028))
                    .semantics { contentDescription = "随便听听" }
                    .clickable(role = Role.Button, onClick = onClick)
                    .animateContentSize(animationSpec = tween(220))
                    .padding(start = if (mini) 15.dp else 18.dp, end = if (mini) 15.dp else 24.dp, top = 15.dp, bottom = 15.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Box(Modifier.graphicsLayer { rotationZ = if (spinning) angle else 0f }) {
                    WallGlyph("dice", Color.White, 24.dp)
                }
                if (!mini) {
                    Text("随便听听", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

// ===== 空态 =====
@Composable
internal fun EmptyWall(onExit: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        WallGlyph("wall", Color.White.copy(alpha = 0.4f), 56.dp)
        Spacer(Modifier.height(12.dp))
        Text("曲库还是空的", color = ControlsText, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(8.dp))
        Text(
            "先回主界面扫描本地音乐，\n封面墙会把你的专辑铺成一整面墙。",
            color = Color(0xFF8A8A98), fontSize = 13.sp, textAlign = TextAlign.Center, lineHeight = 22.sp
        )
        Spacer(Modifier.height(16.dp))
        Box(
            Modifier.clip(RoundedCornerShape(22.dp)).background(Color(0x1AFFFFFF))
                .clickable(role = Role.Button, onClick = onExit).padding(horizontal = 26.dp, vertical = 12.dp)
        ) {
            Text("返回主界面", color = ControlsText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** 仅封面下方的控件；调用方提供可滚动区域的有限高度、封面及返回手势。 */
@Composable
internal fun WallPlaybackControls(
    state: PlayerUiState,
    album: WallAlbum,
    preview: Boolean,
    coverColor: Color,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onTracks: () -> Unit,
    entranceDelayMillis: Int = 470
) {
    val elapsedMillis = remember(album.key) { Animatable(0f) }
    LaunchedEffect(album.key, entranceDelayMillis) {
        val totalDurationMillis = entranceDelayMillis + 5 * 40 + 420
        elapsedMillis.snapTo(0f)
        elapsedMillis.animateTo(
            targetValue = totalDurationMillis.toFloat(),
            animationSpec = tween(durationMillis = totalDurationMillis, easing = LinearEasing)
        )
    }
    // 仅在图层阶段读取动画值，避免入场动画触发逐帧重组。
    fun Modifier.staggeredEntrance(index: Int): Modifier = graphicsLayer {
        val p = FastOutSlowInEasing.transform(
            ((elapsedMillis.value - entranceDelayMillis - index * 40) / 420f).coerceIn(0f, 1f)
        )
        alpha = p
        translationY = 24.dp.toPx() * (1f - p)
    }

    val showingAlbum = preview || !state.hasItem
    val title = if (showingAlbum) album.name else state.title
    val artist = if (showingAlbum) album.artist else state.artist
    val durationMs = (if (showingAlbum) album.tracks.firstOrNull()?.durationMs ?: 0L else state.durationMs).coerceAtLeast(0L)
    val positionMs = if (showingAlbum) 0L else state.positionMs.coerceIn(0L, durationMs)
    val isPlaying = state.isPlaybackActive && !showingAlbum
    // 水滴播放键与普通播放页同源：配色档位（封面 / 主题 / 原版）、光晕、
    // 图标样式与缩放全部跟随设置；封面色取当前专辑主色。
    val context = LocalContext.current
    val prefs = remember(context) { Prefs(context) }
    val skin = LocalSkin.current
    val playPalette = rememberPlayButtonPalette(
        prefs.playBtnColorMode, listOf(coverColor), skin.accent, skin.onAccent
    )
    // 预览不能 seek 到后台正在播放的另一张专辑。
    val canSeek = !showingAlbum && durationMs > 0L
    val latestDuration by rememberUpdatedState(durationMs)
    val latestCanSeek by rememberUpdatedState(canSeek)
    val latestOnSeek by rememberUpdatedState(onSeek)
    var dragFraction by remember(album.key, state.mediaId, preview) { mutableStateOf<Float?>(null) }
    val playbackFraction = if (durationMs > 0L) (positionMs.toDouble() / durationMs).toFloat().coerceIn(0f, 1f) else 0f
    val progress = dragFraction ?: playbackFraction
    val displayedPosition = dragFraction?.let { (it.toDouble() * durationMs).toLong().coerceIn(0L, durationMs) } ?: positionMs

    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp).padding(top = 8.dp, bottom = 16.dp)
    ) {
        Text(title, color = ControlsText, fontSize = 23.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.staggeredEntrance(0))
        Spacer(Modifier.height(4.dp))
        Text(artist, color = Color.White.copy(alpha = 0.62f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.staggeredEntrance(1))
        Spacer(Modifier.height(14.dp))
        Box(
            Modifier.fillMaxWidth().height(32.dp).staggeredEntrance(2)
                .semantics {
                    contentDescription = "播放进度"
                    progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
                    if (!canSeek) disabled()
                    setProgress { fraction ->
                        if (latestCanSeek && fraction.isFinite()) {
                            latestOnSeek((fraction.coerceIn(0f, 1f).toDouble() * latestDuration).toLong().coerceIn(0L, latestDuration))
                            true
                        } else false
                    }
                }
                .pointerInput(album.key, state.mediaId, preview) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        // 从按下起占有手势，阻止滚动容器/封面返回手势接管 seek。
                        down.consume()
                        fun fraction(x: Float) = (x / size.width.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
                        val seeking = latestCanSeek
                        if (seeking) dragFraction = fraction(down.position.x)
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (change.isConsumed) break
                                if (seeking) dragFraction = fraction(change.position.x)
                                val released = change.changedToUpIgnoreConsumed()
                                change.consume()
                                if (released) {
                                    // 拖动只改变本地 UI；仅正常 release 提交一次，取消不提交。
                                    val duration = latestDuration
                                    if (seeking && latestCanSeek && duration > 0L) {
                                        latestOnSeek(((dragFraction ?: 0f).toDouble() * duration).toLong().coerceIn(0L, duration))
                                    }
                                    break
                                }
                                if (!change.pressed) break
                            }
                        } finally {
                            dragFraction = null
                        }
                    }
                },
            contentAlignment = Alignment.CenterStart
        ) {
            Canvas(Modifier.fillMaxWidth().height(12.dp)) {
                val trackWidth = 4.dp.toPx()
                val y = size.height / 2f
                drawLine(Color.White.copy(alpha = 0.18f), Offset(0f, y), Offset(size.width, y), trackWidth, StrokeCap.Round)
                if (progress > 0f) {
                    drawLine(Color.White, Offset(0f, y), Offset(size.width * progress, y), trackWidth, StrokeCap.Round)
                }
                if (dragFraction != null) {
                    val radius = 5.dp.toPx()
                    val x = (size.width * progress).coerceIn(radius.coerceAtMost(size.width / 2f), (size.width - radius).coerceAtLeast(size.width / 2f))
                    drawCircle(Color.White, radius, Offset(x, y))
                }
            }
        }
        Spacer(Modifier.height(7.dp))
        Row(Modifier.fillMaxWidth().staggeredEntrance(2), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatDuration(displayedPosition), color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
            Text(formatDuration(durationMs), color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
        }
        Spacer(Modifier.height(10.dp))
        // 与普通播放页同款：两侧线性上一首 / 下一首 + 中间液态水滴播放键
        // （光晕 / 图标样式 / 缩放 / 配色档位都跟随设置），不再是白底圆饼。
        Row(
            Modifier.fillMaxWidth().staggeredEntrance(3),
            horizontalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CtrlButton(
                icon = SonanceIcons.Prev,
                contentDescription = "上一首",
                onClick = onPrev,
                size = 46.dp,
                iconSize = 24.dp,
                tint = Color.White.copy(alpha = 0.85f)
            )
            PlayPauseButton(
                isPlaying = isPlaying,
                onClick = onToggle,
                size = 62.dp,
                iconSize = 26.dp,
                palette = playPalette
            )
            CtrlButton(
                icon = SonanceIcons.Next,
                contentDescription = "下一首",
                onClick = onNext,
                size = 46.dp,
                iconSize = 24.dp,
                tint = Color.White.copy(alpha = 0.85f)
            )
        }
        Spacer(Modifier.height(14.dp))
        Row(
            Modifier.align(Alignment.CenterHorizontally).staggeredEntrance(4).clip(RoundedCornerShape(20.dp))
                .background(Color.White.copy(alpha = 0.08f)).clickable(role = Role.Button, onClick = onTracks)
                .padding(horizontal = 18.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            WallGlyph("tracks", Color.White.copy(alpha = 0.75f), 18.dp)
            Text("曲目列表 · ${album.tracks.size} 首", color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp)
        }
        Spacer(Modifier.height(22.dp))
        Text(
            "↓ 向下拖动，把唱片插回墙上", color = Color.White.copy(alpha = 0.4f), fontSize = 12.sp,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().staggeredEntrance(5)
        )
    }
}

/** 可直接参与 3D 翻转的专辑背面，不创建弹层；modifier 必须提供有限的页面 bounds。 */
@Composable
internal fun WallTrackFace(
    album: WallAlbum,
    currentMediaId: Long,
    onPlayTrack: (Int) -> Unit,
    onPlayAll: () -> Unit,
    onQueue: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val totalDuration = remember(album.tracks) { album.tracks.sumOf { it.durationMs.coerceAtLeast(0L) } }
    Column(
        modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(Color(0xFF16161C))
            .padding(horizontal = 18.dp, vertical = 18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(album.name, color = ControlsText, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(album.artist, color = Color.White.copy(alpha = 0.65f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${album.tracks.size} 首 · ${formatDuration(totalDuration)}", color = Color.White.copy(alpha = 0.45f), fontSize = 12.sp)
            }
            GlassIconButton("exit", onClose)
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            itemsIndexed(album.tracks, key = { index, song -> "${song.id}:$index" }) { index, song ->
                val current = song.id == currentMediaId
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                        .background(if (current) Color(0xFFA3E635).copy(alpha = 0.08f) else Color.Transparent)
                        .semantics { selected = current }
                        .clickable(role = Role.Button) { onPlayTrack(index) }
                        .padding(horizontal = 4.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("${index + 1}", color = if (current) Color(0xFFA3E635) else Color.White.copy(alpha = 0.45f), fontSize = 12.sp, modifier = Modifier.width(20.dp), textAlign = TextAlign.Center)
                    Column(Modifier.weight(1f)) {
                        Text(song.title, color = if (current) Color.White else Color.White.copy(alpha = 0.88f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(song.artist, color = Color.White.copy(alpha = 0.45f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(song.durationText, color = Color.White.copy(alpha = 0.45f), fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(20.dp)).background(Color.White)
                    .clickable(enabled = album.tracks.isNotEmpty(), role = Role.Button, onClick = onPlayAll),
                contentAlignment = Alignment.Center
            ) { Text("播放整张", color = Color(0xFF111111), fontSize = 14.sp, fontWeight = FontWeight.Bold) }
            Box(
                Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = 0.14f))
                    .clickable(enabled = album.tracks.isNotEmpty(), role = Role.Button, onClick = onQueue),
                contentAlignment = Alignment.Center
            ) { Text("下一首播放", color = ControlsText, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

// ===== 调参面板：13 个滑杆、5 个开关、1 组震动强度，完整保留 =====
/** onReset/onShuffle 负责修改参数；每次修改后统一调用 onChanged，由 screen 保存并唤醒渲染。 */
@Composable
internal fun TuneSheet(tune: WallTune, onReset: () -> Unit, onShuffle: () -> Unit, onChanged: () -> Unit) {
    val accent = Color(0xFF7C7CF0)
    Column(
        Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp).padding(bottom = 24.dp)
    ) {
        Text("调参 · 原生封面墙", color = ControlsText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text("所有参数即时生效，自动保存", color = Color(0xFF8A8A98), fontSize = 12.sp)
        TuneSection("弧面墙")
        TuneSlider("最大弯曲角", tune.maxAngle, 0f, 50f, "°") { tune.maxAngle = it; onChanged() }
        TuneSlider("静止弯曲度", tune.rest, 0f, 1f, "") { tune.rest = it; onChanged() }
        TuneSlider("达到最大弯曲所需速度", tune.vmax, 1500f, 9000f, " px/s") { tune.vmax = it; onChanged() }
        TuneSlider("纵深半径（越小越立体）", tune.radius, 250f, 1500f, "") { tune.radius = it; onChanged() }
        TuneSlider("回弹刚度", tune.springK, 20f, 300f, "") { tune.springK = it; onChanged() }
        TuneSlider("回弹阻尼（越小越 Q 弹）", tune.zeta, 0.15f, 1f, "") { tune.zeta = it; onChanged() }
        TuneSlider("滑动摩擦", tune.friction, 1f, 6f, "") { tune.friction = it; onChanged() }
        TuneSection("触摸")
        TuneSlider("按压倾斜角", tune.pressTilt, 0f, 20f, "°") { tune.pressTilt = it; onChanged() }
        TuneSlider("按压缩放", tune.pressScale, 0.85f, 1f, "") { tune.pressScale = it; onChanged() }
        TuneSlider("波纹幅度", tune.rippleAmp, 0f, 0.12f, "") { tune.rippleAmp = it; onChanged() }
        TuneSlider("波纹速度", tune.rippleSpeed, 400f, 3000f, " px/s") { tune.rippleSpeed = it; onChanged() }
        TuneSection("布局与光影")
        TuneSlider("封面间距", tune.gap, 0f, 14f, " px") { tune.gap = it; onChanged() }
        TuneSlider("视差角度", tune.gyroDeg, 0f, 10f, "°") { tune.gyroDeg = it; onChanged() }
        TuneBool("陀螺仪视差", tune.parallax) { tune.parallax = it; onChanged() }
        TuneBool("闲置 12 秒后自动漂移", tune.drift) { tune.drift = it; onChanged() }
        TuneSection("反馈与播放")
        Row(
            Modifier.fillMaxWidth().padding(vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("震动强度", color = Color(0xFFDCDCE4), fontSize = 13.sp)
            Row(Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xFF2A2A34)).padding(3.dp)) {
                listOf("off" to "关", "light" to "轻", "strong" to "强").forEach { (id, label) ->
                    val active = tune.haptics == id
                    Box(
                        Modifier.clip(RoundedCornerShape(8.dp)).background(if (active) accent else Color.Transparent)
                            .semantics { selected = active }
                            .clickable(role = Role.RadioButton) {
                                if (tune.haptics != id) {
                                    tune.haptics = id
                                    onChanged()
                                }
                            }.padding(horizontal = 14.dp, vertical = 5.dp)
                    ) { Text(label, color = if (active) Color.White else Color(0xFFAAAAAA), fontSize = 12.sp) }
                }
            }
        }
        TuneBool("黑胶唱片滑出", tune.vinyl) { tune.vinyl = it; onChanged() }
        TuneBool("抽出即播放（联动原生）", tune.autoPlay) { tune.autoPlay = it; onChanged() }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.weight(1f).height(42.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF2A2A34))
                    .clickable(role = Role.Button) { onReset(); onChanged() },
                contentAlignment = Alignment.Center
            ) { Text("恢复默认", color = ControlsText, fontSize = 14.sp) }
            Box(
                Modifier.weight(1f).height(42.dp).clip(RoundedCornerShape(12.dp)).background(accent)
                    .clickable(role = Role.Button) { onShuffle(); onChanged() },
                contentAlignment = Alignment.Center
            ) { Text("换个布局", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
internal fun TuneSection(title: String) {
    Text(title, color = Color(0xFF7C7CF0), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
}

@Composable
internal fun TuneSlider(name: String, value: Float, min: Float, max: Float, unit: String, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(name, color = Color(0xFFDCDCE4), fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text((if (max - min <= 2f) String.format("%.2f", value) else value.roundToInt().toString()) + unit, color = Color(0xFF9A9AAB), fontSize = 12.sp)
        }
        Slider(
            value = value, onValueChange = onChange, valueRange = min..max,
            colors = SliderDefaults.colors(thumbColor = Color(0xFF8B8BFF), activeTrackColor = Color(0xFF8B8BFF), inactiveTrackColor = Color(0xFF3A3A46)),
            modifier = Modifier.fillMaxWidth().height(28.dp).semantics { contentDescription = name }
        )
    }
}

@Composable
internal fun TuneBool(name: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Switch) { onChange(!value) }.padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(name, color = Color(0xFFDCDCE4), fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = value, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF7C7CF0)),
            modifier = Modifier.semantics { contentDescription = name }
        )
    }
}

// ===== 矢量小图标（Canvas 绘制，避免依赖外部图标库） =====
@Composable
internal fun WallGlyph(name: String, tint: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val s = size.toPx()
        val stroke = s * 0.09f
        val paint = Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.argb((tint.alpha * 255).toInt(), (tint.red * 255).toInt(), (tint.green * 255).toInt(), (tint.blue * 255).toInt())
        }
        val canvas = drawContext.canvas.nativeCanvas
        fun line(x1: Float, y1: Float, x2: Float, y2: Float, width: Float) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = width
            paint.strokeCap = Paint.Cap.ROUND
            canvas.drawLine(x1 * s, y1 * s, x2 * s, y2 * s, paint)
        }
        fun dot(cx: Float, cy: Float, radius: Float) {
            paint.style = Paint.Style.FILL
            canvas.drawCircle(cx * s, cy * s, radius * s, paint)
        }
        fun path(build: android.graphics.Path.() -> Unit, fill: Boolean) {
            val path = android.graphics.Path().apply(build)
            paint.style = if (fill) Paint.Style.FILL else Paint.Style.STROKE
            paint.strokeWidth = stroke
            canvas.drawPath(path, paint)
        }
        when (name) {
            "exit" -> { line(0.25f, 0.25f, 0.75f, 0.75f, stroke); line(0.75f, 0.25f, 0.25f, 0.75f, stroke) }
            "chevron" -> { line(0.25f, 0.4f, 0.5f, 0.65f, stroke); line(0.5f, 0.65f, 0.75f, 0.4f, stroke) }
            "tune" -> {
                line(0.15f, 0.3f, 0.85f, 0.3f, stroke * 0.8f); line(0.15f, 0.7f, 0.85f, 0.7f, stroke * 0.8f)
                dot(0.62f, 0.3f, 0.1f); dot(0.38f, 0.7f, 0.1f)
            }
            "dice" -> {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = stroke * 0.8f
                canvas.drawRoundRect(RectF(0.14f * s, 0.14f * s, 0.86f * s, 0.86f * s), 0.2f * s, 0.2f * s, paint)
                dot(0.34f, 0.34f, 0.06f); dot(0.66f, 0.34f, 0.06f); dot(0.5f, 0.5f, 0.06f); dot(0.34f, 0.66f, 0.06f); dot(0.66f, 0.66f, 0.06f)
            }
            "play" -> path({ moveTo(0.32f * s, 0.22f * s); lineTo(0.8f * s, 0.5f * s); lineTo(0.32f * s, 0.78f * s); close() }, true)
            "pause" -> {
                paint.style = Paint.Style.FILL
                canvas.drawRoundRect(RectF(0.3f * s, 0.24f * s, 0.44f * s, 0.76f * s), 0.04f * s, 0.04f * s, paint)
                canvas.drawRoundRect(RectF(0.56f * s, 0.24f * s, 0.7f * s, 0.76f * s), 0.04f * s, 0.04f * s, paint)
            }
            "prev" -> {
                paint.style = Paint.Style.FILL; canvas.drawRect(0.24f * s, 0.26f * s, 0.34f * s, 0.74f * s, paint)
                path({ moveTo(0.74f * s, 0.26f * s); lineTo(0.4f * s, 0.5f * s); lineTo(0.74f * s, 0.74f * s); close() }, true)
            }
            "next" -> {
                paint.style = Paint.Style.FILL; canvas.drawRect(0.66f * s, 0.26f * s, 0.76f * s, 0.74f * s, paint)
                path({ moveTo(0.26f * s, 0.26f * s); lineTo(0.6f * s, 0.5f * s); lineTo(0.26f * s, 0.74f * s); close() }, true)
            }
            "tracks" -> {
                for (y in listOf(0.27f, 0.5f, 0.73f)) {
                    dot(0.18f, y, 0.045f)
                    line(0.35f, y, 0.83f, y, stroke * 0.8f)
                }
            }
            "wall" -> {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = stroke * 0.7f
                canvas.drawRoundRect(RectF(0.12f * s, 0.12f * s, 0.56f * s, 0.56f * s), 0.08f * s, 0.08f * s, paint)
                canvas.drawRoundRect(RectF(0.64f * s, 0.12f * s, 0.88f * s, 0.36f * s), 0.05f * s, 0.05f * s, paint)
                canvas.drawRoundRect(RectF(0.64f * s, 0.44f * s, 0.88f * s, 0.62f * s), 0.05f * s, 0.05f * s, paint)
                canvas.drawRoundRect(RectF(0.12f * s, 0.64f * s, 0.36f * s, 0.88f * s), 0.05f * s, 0.05f * s, paint)
                canvas.drawRoundRect(RectF(0.44f * s, 0.7f * s, 0.88f * s, 0.88f * s), 0.05f * s, 0.05f * s, paint)
            }
            "disc" -> {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = stroke * 0.8f
                canvas.drawCircle(0.5f * s, 0.5f * s, 0.37f * s, paint)
                paint.strokeWidth = stroke * 0.45f
                canvas.drawCircle(0.5f * s, 0.5f * s, 0.24f * s, paint)
                paint.style = Paint.Style.FILL
                canvas.drawCircle(0.5f * s, 0.5f * s, 0.1f * s, paint)
            }
        }
    }
}

// ===== 调参持久化：保持原 prefs 名称与全部键，已有设置无需迁移 =====
private const val TUNE_PREFS = "sonance-wall-native-tune"

internal fun loadTune(context: Context, t: WallTune) {
    val prefs = context.getSharedPreferences(TUNE_PREFS, Context.MODE_PRIVATE)
    if (!prefs.getBoolean("saved", false)) return
    t.maxAngle = prefs.getFloat("maxAngle", t.maxAngle)
    t.rest = prefs.getFloat("rest", t.rest)
    t.vmax = prefs.getFloat("vmax", t.vmax)
    t.radius = prefs.getFloat("radius", t.radius)
    t.springK = prefs.getFloat("springK", t.springK)
    t.zeta = prefs.getFloat("zeta", t.zeta)
    t.friction = prefs.getFloat("friction", t.friction)
    t.pressTilt = prefs.getFloat("pressTilt", t.pressTilt)
    t.pressScale = prefs.getFloat("pressScale", t.pressScale)
    t.rippleAmp = prefs.getFloat("rippleAmp", t.rippleAmp)
    t.rippleSpeed = prefs.getFloat("rippleSpeed", t.rippleSpeed)
    t.gap = prefs.getFloat("gap", t.gap)
    t.gyroDeg = prefs.getFloat("gyroDeg", t.gyroDeg)
    t.parallax = prefs.getBoolean("parallax", t.parallax)
    t.drift = prefs.getBoolean("drift", t.drift)
    t.haptics = prefs.getString("haptics", t.haptics) ?: t.haptics
    t.vinyl = prefs.getBoolean("vinyl", t.vinyl)
    t.autoPlay = prefs.getBoolean("autoPlay", t.autoPlay)
    t.seed = prefs.getInt("seed", t.seed)
}

internal fun saveTune(context: Context, t: WallTune) {
    context.getSharedPreferences(TUNE_PREFS, Context.MODE_PRIVATE).edit()
        .putBoolean("saved", true)
        .putFloat("maxAngle", t.maxAngle).putFloat("rest", t.rest).putFloat("vmax", t.vmax)
        .putFloat("radius", t.radius).putFloat("springK", t.springK).putFloat("zeta", t.zeta)
        .putFloat("friction", t.friction).putFloat("pressTilt", t.pressTilt).putFloat("pressScale", t.pressScale)
        .putFloat("rippleAmp", t.rippleAmp).putFloat("rippleSpeed", t.rippleSpeed).putFloat("gap", t.gap)
        .putFloat("gyroDeg", t.gyroDeg).putBoolean("parallax", t.parallax).putBoolean("drift", t.drift)
        .putString("haptics", t.haptics).putBoolean("vinyl", t.vinyl)
        .putBoolean("autoPlay", t.autoPlay).putInt("seed", t.seed)
        .apply()
}

internal fun resetTune(t: WallTune) {
    val defaults = WallTune()
    t.maxAngle = defaults.maxAngle; t.rest = defaults.rest; t.vmax = defaults.vmax
    t.radius = defaults.radius; t.springK = defaults.springK; t.zeta = defaults.zeta
    t.friction = defaults.friction; t.pressTilt = defaults.pressTilt; t.pressScale = defaults.pressScale
    t.rippleAmp = defaults.rippleAmp; t.rippleSpeed = defaults.rippleSpeed; t.gap = defaults.gap
    t.gyroDeg = defaults.gyroDeg; t.parallax = defaults.parallax; t.drift = defaults.drift
    t.haptics = defaults.haptics; t.vinyl = defaults.vinyl
    t.autoPlay = defaults.autoPlay; t.seed = defaults.seed
}

/**
 * 封面墙的一个墙面单元：一张真实专辑，或曲库专辑太少时按单曲展开的一张「单曲卡」。
 *
 * [tracks] 是翻面列表里展示的曲目；[playContext] 是点播放时交给播放器的队列上下文 ——
 * 专辑卡两者相同（整张专辑入队），单曲卡展示一首、但队列仍是全曲库（下一首有得切）。
 */
data class WallAlbum(
    val key: String,
    val name: String,
    val artist: String,
    val fav: Boolean,
    val tracks: List<Song>,
    val playContext: List<Song>
)

/**
 * 专辑聚合 + 稀疏兜底：聚合专辑少于 24 张时按单曲展开，保证墙面铺满。
 * key 是不透明标识（专辑 = albumId+专辑名，与 groupAlbums 的分组键一致；
 * 单曲 = 歌曲 id），收藏专辑由布局引擎优先排进焦点大格。
 */
fun buildWallAlbums(albums: List<Album>, songs: List<Song>, favorites: Set<Long>): List<WallAlbum> {
    if (songs.isEmpty()) return emptyList()
    if (albums.size >= WALL_ALBUM_THRESHOLD) {
        return albums.map { a ->
            WallAlbum(
                key = "a:${a.albumId}:${a.name}",
                name = a.name,
                artist = a.artist,
                fav = a.songs.any { it.id in favorites },
                tracks = a.songs,
                playContext = a.songs
            )
        }
    }
    return songs.map { s ->
        WallAlbum(
            key = "s:${s.id}",
            name = s.title,
            artist = s.artist,
            fav = s.id in favorites,
            tracks = listOf(s),
            playContext = songs
        )
    }
}

private const val WALL_ALBUM_THRESHOLD = 24

/**
 * 「随便听听」随机挑一个墙面单元：排除当前专辑与最近听过的，
 * 全被排除时退回全体里随机。封面墙与主播放界面「单曲队列手动切歌直接随机」
 * 共用这一份挑选规则，保证两边行为一致。
 */
fun pickRandomWallAlbum(
    all: List<WallAlbum>,
    currentKey: String?,
    recent: Collection<String>
): WallAlbum? {
    if (all.isEmpty()) return null
    val candidates = all.filter { it.key != currentKey && it.key !in recent }
    return candidates.randomOrNull() ?: all.randomOrNull()
}

/**
 * 四级分级震动，波形时长与滚动节流窗口一致：
 *  L1 质感短脉冲（滚动节拍，时长按速度给）/ L2 确认 / L3 冲击 / L4 蓄力序列。
 * 「轻 / 强」两档缩放时长与幅值；不支持幅值控制的马达忽略幅值项仍然会震。
 */
internal fun performWallHaptic(context: Context, level: Int, durMs: Int, strength: String) {
    if (strength == "off" || level !in 1..4) return
    runCatching {
        val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        if (vibrator?.hasVibrator() != true) return
        val strong = strength == "strong"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val effect = when (level) {
                1 -> VibrationEffect.createOneShot(
                    ((if (durMs > 0) durMs else 9) * (if (strong) 1.6f else 1f)).toLong().coerceAtLeast(4L),
                    if (strong) 180 else 110
                )
                2 -> VibrationEffect.createOneShot(if (strong) 26L else 16L, if (strong) 230 else 150)
                3 -> VibrationEffect.createOneShot(if (strong) 50L else 32L, 255)
                else -> {
                    val timings = if (strong) longArrayOf(0, 10, 32, 14, 24, 20, 16, 32)
                    else longArrayOf(0, 6, 36, 9, 28, 13, 20, 22)
                    val amps = if (strong) intArrayOf(0, 120, 0, 170, 0, 210, 0, 255)
                    else intArrayOf(0, 80, 0, 120, 0, 150, 0, 190)
                    VibrationEffect.createWaveform(timings, amps, -1)
                }
            }
            vibrator.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            when (level) {
                1 -> vibrator.vibrate(((if (durMs > 0) durMs else 9) * (if (strong) 1.6f else 1f)).toLong())
                2 -> vibrator.vibrate(if (strong) 26L else 16L)
                3 -> vibrator.vibrate(if (strong) 50L else 32L)
                else -> vibrator.vibrate(
                    if (strong) longArrayOf(10, 32, 14, 24, 20, 16, 32)
                    else longArrayOf(6, 36, 9, 28, 13, 20, 22),
                    -1
                )
            }
        }
    }
}
