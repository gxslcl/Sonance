package com.sonance.player.ui.components

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sonance.player.data.ArtworkLoader
import com.sonance.player.data.LyricLine
import com.sonance.player.data.LyricAdaptMode
import com.sonance.player.data.LyricStyle
import com.sonance.player.data.LyricPlaybarStyle
import com.sonance.player.data.Lyrics
import com.sonance.player.data.LyricsRepository
import com.sonance.player.data.LyricsResult
import com.sonance.player.data.OnlineCover
import com.sonance.player.data.Prefs
import com.sonance.player.data.Song
import com.sonance.player.data.indexAt
import com.sonance.player.playback.PlayerUiState
import com.sonance.player.ui.icon.SonanceIcons
import com.sonance.player.ui.theme.GlassStyle
import com.sonance.player.ui.theme.LocalSkin
import com.sonance.player.ui.theme.MiniPlayerStyle
import com.sonance.player.ui.theme.MiniProgressStyle
import com.sonance.player.ui.theme.Skin
import com.sonance.player.ui.theme.solidSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 全屏歌词页。
 *
 * 布局是「顶栏 / 内容 / 底部歌名」三段式，**顶栏不再和内容叠在一起** ——
 * 之前的写法把关闭按钮和内容区同时塞进一个 Box，内容区的 LazyColumn 是可滚动的，
 * 会盖住按钮下半部分并吞掉点击，表现就是"叉号点了没反应"。
 *
 * 数据来自 [LyricsRepository]：内嵌歌词 → 本地缓存 → 在线（需用户开启）。
 */
@Composable
fun LyricsOverlay(
    song: Song,
    state: PlayerUiState,
    prefs: Prefs,
    /** 点歌词某一行时把播放进度跳到那一句 */
    onSeek: (Long) -> Unit,
    /** 底部播放控制（设置里开）用的三个控制回调 */
    onPrev: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    /** 与列表页点击卡片打开播放器一致：从歌词层返回播放主界面。 */
    onOpenPlayer: () -> Unit,
    /** 是否隐藏系统状态栏（**只作用于歌词页**；状态由 SonanceRoot 统一落地） */
    hideStatusBar: Boolean = false,
    /** 改「隐藏状态栏」开关 */
    onHideStatusBarChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val skin = LocalSkin.current
    val positionMs = state.positionMs
    val isPlaying = state.isPlaying
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var result by remember(song.id) { mutableStateOf<LyricsResult?>(null) }
    var loading by remember(song.id) { mutableStateOf(true) }
    var showSearch by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    /**
     * 「外观」面板是否展开。
     *
     * 原来背景玻璃和字号是两个按钮 + 两个面板（Layers / Sort），
     * 用户要求合成一个：这两件事都是"把这一页调成我看着舒服的样子"，
     * 分开放只是让顶栏多一个认不出来的图标。
     */
    var showAppearance by remember { mutableStateOf(false) }

    // 打开「隐藏状态栏」后，顶栏图标 5 秒后淡到几乎不可见（alpha 0.15），但仍可点击。
    var chromeDimmed by remember { mutableStateOf(false) }
    LaunchedEffect(hideStatusBar) {
        if (hideStatusBar) {
            delay(5000)
            chromeDimmed = true
        } else {
            chromeDimmed = false
        }
    }

    // 背景玻璃：本地状态，改了立刻写回 prefs
    var glassStyle by remember(song.id) { mutableStateOf(GlassStyle.of(prefs.glassStyle)) }
    var glassBlur by remember(song.id) { mutableIntStateOf(prefs.glassBlurDp) }
    var glassVisibility by remember(song.id) { mutableIntStateOf(prefs.glassVisibility) }

    fun applyStyle(s: GlassStyle) {
        glassStyle = s
        glassBlur = s.defaultBlurDp
        glassVisibility = s.defaultVisibility
        prefs.glassStyle = s.ordinal
        prefs.glassBlurDp = s.defaultBlurDp
        prefs.glassVisibility = s.defaultVisibility
    }

    val onlineEnabled = prefs.onlineLyricsEnabled
    val apiBase = prefs.lyricsApiBase
    // 网易云兜底：LrcAPI 没拿到时再问它一次
    val neteaseFill = prefs.neteaseFillEnabled

    // 字号 / 简体开关在页面内可调，用本地状态驱动重组；换歌时回到全局设置值
    var fontSize by remember(song.id) { mutableIntStateOf(prefs.lyricsFontSizeSp) }
    var convertT2S by remember(song.id) { mutableStateOf(prefs.lyricsConvertToSimplified) }

    // 自动适配档位。和字号不一样，它是**全局设置**、和当前这首歌无关，
    // 所以不用 song.id 当 key（换歌时不该重新读一遍、也不该丢掉页面里的改动）。
    var adaptMode by remember { mutableStateOf(prefs.lyricsAdaptMode) }

    // 全屏展现模式：全局设置，页内切换即时生效
    var style by remember { mutableStateOf(prefs.lyricsStyle) }

    // 歌词进度偏移：**按歌记忆**。页内 ± 调整即时生效并写回 prefs，
    // 换歌时回到这首歌自己的偏移值
    var offsetMs by remember(song.id) {
        mutableLongStateOf(prefs.lyricsOffsetMs(song.title, song.artist))
    }
    fun stepOffset(dir: Int) {
        offsetMs = (offsetMs + dir * Prefs.LYRICS_OFFSET_STEP_MS)
            .coerceIn(-Prefs.MAX_LYRICS_OFFSET_MS, Prefs.MAX_LYRICS_OFFSET_MS)
        prefs.setLyricsOffsetMs(song.title, song.artist, offsetMs)
    }

    /**
     * 点击某句歌词：跳到那一句；如果当前没有播放意图（暂停 / 播完 / 出错），顺手开始播放。
     *
     * 只给「点歌词」用。拖动进度条仍然只 seek —— 否则拖一下就会开始播放。
     * `isPlaybackActive` 把缓冲也算作"在播"，所以切歌缓冲期间点歌词不会反而暂停。
     */
    val seekFromLyric: (Long) -> Unit = { lineMs ->
        onSeek((lineMs + offsetMs).coerceAtLeast(0L))
        if (!state.isPlaybackActive) onPlayPause()
    }

    val lyricsRev by LyricsRepository.revision.collectAsState()

    // 开关或导入/删除版本进 key：任何改动回到歌词页都会即时同步
    LaunchedEffect(song.id, song.uri, onlineEnabled, apiBase, convertT2S, neteaseFill, lyricsRev) {
        loading = true
        result = LyricsRepository.load(
            context = context,
            songId = song.id,
            uri = song.uri,
            title = song.title,
            artist = song.artist,
            album = song.album,
            onlineEnabled = onlineEnabled,
            apiBase = apiBase,
            convertToSimplified = convertT2S,
            // 匹配网易云要靠时长排除同名翻唱 / Live 版
            durationMs = song.durationMs,
            neteaseEnabled = neteaseFill
        )
        loading = false
    }

    val lyrics = result?.lyrics

    // 封面：背景玻璃和「歌词自动适配背景」用的是同一张图，所以在这里加载一次往下传。
    // 早先这张图是 GlassBackdrop 自己在内部加载的，外面拿不到，也就没法据它算歌词颜色。
    var art by remember(song.id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(song.albumId, song.id, song.title, song.artist) {
        art = ArtworkLoader.load(
            context = context,
            albumId = song.albumId,
            sizePx = 320,
            sourceUri = song.uri,
            coverKey = OnlineCover.keyOf(song.title, song.artist)
        ) ?: ArtworkLoader.online(song.id)
    }

    // 封面的分段平均色。像素读取放在后台线程、只在换图时算一次 ——
    // 下面的色带要跟着「透出」滑杆实时重算，那一步必须是纯数值运算，不能再读像素。
    var artBands by remember(song.id) { mutableStateOf<List<Color>?>(null) }
    LaunchedEffect(art) {
        val bmp = art
        artBands = if (bmp == null) null else withContext(Dispatchers.Default) {
            artBandColors(bmp)
        }
    }

    BoxWithConstraints(modifier.fillMaxSize().background(skin.bg)) {

        // ===== 歌词自动适配背景 =====
        // 把「封面 + 遮罩」折算成一条自上而下的等效背景色带；取不到（没有封面，
        // 或者背景玻璃关着 —— 那时背景就是纯皮肤底色，皮肤自己的配色本来就是照它配的）
        // 就整条置空，歌词退回原本的颜色。
        val density = LocalDensity.current
        val boxW = with(density) { maxWidth.toPx() }
        val boxH = with(density) { maxHeight.toPx() }
        val bands = artBands
        val artW = art?.width ?: 0
        val artH = art?.height ?: 0
        val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        // 色带只在"真的会用到"时才算：关掉自适应、或背景玻璃关着（那时背景就是纯皮肤底色，
        // 皮肤自己的配色本来就是照它配的）都直接置空。
        // 「只调当前句」档色带照算，只是非当前句不走它（见 shouldAdapt）。
        val bgProfile = remember(bands, artW, artH, boxW, boxH, skin, glassStyle, glassVisibility, canBlur, adaptMode) {
            if (bands == null || glassStyle == GlassStyle.OFF || adaptMode == LyricAdaptMode.OFF) {
                null
            } else {
                // liquid 要传下去：液态玻璃多叠了一道顶部镜面高光，色带得跟着算，
                // 否则顶部那几行的背景会被低估、歌词颜色浅一档。
                buildBgProfile(
                    bands, artW, artH, boxW, boxH, skin, glassVisibility, canBlur,
                    liquid = glassStyle.liquid
                )
            }
        }

        // ===== 玻璃背景（可调强度 / 液态玻璃）=====
        GlassBackdrop(
            art = art,
            style = glassStyle,
            blurDp = glassBlur,
            visibility = glassVisibility,
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds()
        )

        // ===== 三段式内容 =====
        Column(Modifier.fillMaxSize()) {

            // ---- 顶栏 ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .graphicsLayer { alpha = if (chromeDimmed) 0.15f else 1f }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 顶栏只有「搜索 + 外观」两个图标（歌词来源提示已按需求去掉），
                // Spacer 把图标推到右侧。
                Spacer(Modifier.weight(1f))

                // 手动搜索 & 导入 / 删除歌词
                IconAction(
                    icon = SonanceIcons.Search,
                    contentDescription = "搜索或管理歌词",
                    onClick = { showSearch = true },
                    iconSize = 19.dp,
                    tint = skin.textDim
                )
                // 外观：背景玻璃 + 字号 + 简繁，**合并成一个入口**。
                //
                // 原来是两个图标（Layers 背景 / Sort 字号），两个都不好认，
                // 而它们干的是同一件事 —— 把这一页调成我看着舒服的样子。
                //
                // 顶栏到这里就只剩「搜索 + 外观」两个图标。**关闭按钮去掉了**：
                // 收起播放页可以左右滑动切层、也可以按系统返回键（[FullPlayer] 里有
                // BackHandler），顶栏没必要再占一格。
                IconAction(
                    icon = SonanceIcons.Layers,
                    contentDescription = "外观（背景与字号）",
                    onClick = { showAppearance = !showAppearance },
                    iconSize = 19.dp,
                    tint = if (showAppearance) skin.accent else skin.textDim
                )
            }

            notice?.let { msg ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(skin.surface2)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(msg, style = MaterialTheme.typography.bodySmall, color = skin.textDim)
                }
            }

            // ---- 歌词主体 ----
            // 这里原来挂了一个"轻点/长按唤出关闭按钮"的手势，关闭按钮去掉之后
            // 手势也没有意义了，一并删掉 —— 留着会白白拦一层 pointerInput。
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when {
                    searching -> Text(
                        "正在按你填的信息搜索…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = skin.textMuted,
                        modifier = Modifier.align(Alignment.Center)
                    )

                    loading -> Text(
                        if (onlineEnabled) "正在读取歌词（本地 → 在线）…" else "正在读取歌词…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = skin.textMuted,
                        modifier = Modifier.align(Alignment.Center)
                    )

                    lyrics == null -> if (style == LyricStyle.VERTICAL) {
                        VerticalEmptyLyrics(
                            song = song,
                            onlineEnabled = onlineEnabled,
                            fontSizeSp = fontSize,
                            onManualSearch = { showSearch = true }
                        )
                    } else {
                        EmptyLyrics(
                            song = song,
                            onlineEnabled = onlineEnabled,
                            onManualSearch = { showSearch = true }
                        )
                    }

                    lyrics is Lyrics.Synced -> when (style) {
                        LyricStyle.CLASSIC -> SyncedLyrics(
                            lines = lyrics.lines,
                            positionMs = positionMs - offsetMs,
                            isPlaying = isPlaying,
                            fontSizeSp = fontSize,
                            onSeek = seekFromLyric,
                            bgProfile = bgProfile,
                            adaptMode = adaptMode,
                            timelineOffsetMs = offsetMs
                        )
                        LyricStyle.VERTICAL -> VerticalSyncedLyrics(
                            lines = lyrics.lines,
                            song = song,
                            positionMs = positionMs - offsetMs,
                            durationMs = (song.durationMs - offsetMs).coerceAtLeast(0L),
                            timelineOffsetMs = offsetMs,
                            isPlaying = isPlaying,
                            fontSizeSp = fontSize,
                            onSeek = seekFromLyric,
                            bgProfile = bgProfile,
                            adaptMode = adaptMode,
                            skin = skin
                        )
                        else -> FullscreenThemedLyrics(
                            style = style,
                            lines = lyrics.lines,
                            positionMs = positionMs - offsetMs,
                            durationMs = (song.durationMs - offsetMs).coerceAtLeast(0L),
                            timelineOffsetMs = offsetMs,
                            isPlaying = isPlaying,
                            fontSizeSp = fontSize,
                            onSeek = seekFromLyric,
                            bgProfile = bgProfile,
                            adaptMode = adaptMode,
                            skin = skin
                        )
                    }

                    else -> if (style == LyricStyle.VERTICAL) {
                        VerticalPlainLyrics(
                            lines = (lyrics as Lyrics.Plain).lines,
                            fontSizeSp = fontSize,
                            bgProfile = bgProfile,
                            adaptMode = adaptMode
                        )
                    } else {
                        PlainLyrics(
                            lines = (lyrics as Lyrics.Plain).lines,
                            fontSizeSp = fontSize,
                            bgProfile = bgProfile,
                            adaptMode = adaptMode
                        )
                    }
                }
            }

            // ---- 底部播放控制：原有三个按钮 / 复用列表页悬浮播放器 ----
            if (prefs.lyricsPlaybar && prefs.lyricsPlaybarStyle == LyricPlaybarStyle.FLOAT) {
                // 不给卡片再包横向内边距，也不重复绘制歌名；卡片自身负责间距与元信息。
                // 原组件消费横向拖动，卡片上的左右滑动用于切歌，不触发外层切换页面。
                MiniPlayer(
                    state = state,
                    song = song,
                    onOpen = onOpenPlayer,
                    onPlayPause = onPlayPause,
                    onNext = onNext,
                    onPrev = onPrev,
                    style = MiniPlayerStyle.FLOAT,
                    glass = GlassStyle.of(prefs.miniGlassStyle),
                    glassBlur = prefs.miniGlassBlurDp,
                    glassVisibility = prefs.miniGlassVisibility,
                    cardOpacity = prefs.miniCardOpacity,
                    progressStyle = MiniProgressStyle.of(prefs.miniProgressStyle),
                    swipeLeftPct = prefs.miniSwipeLeftPct,
                    swipeRightPct = prefs.miniSwipeRightPct,
                    heightScale = prefs.miniHeightScale / 100f,
                    coverScale = prefs.miniCoverScale / 100f,
                    modifier = Modifier.navigationBarsPadding().padding(top = 8.dp)
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 28.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    if (prefs.lyricsPlaybar) {
                        LyricsPlayBar(
                            isPlaying = state.isPlaybackActive,
                            onPrev = onPrev,
                            onPlayPause = onPlayPause,
                            onNext = onNext,
                            skin = skin
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                    Text(
                        text = song.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = skin.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = song.artist,
                        style = MaterialTheme.typography.labelSmall,
                        color = skin.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        // ---- 外观调节面板（悬浮卡片，不再挤压歌词区）----
        // 原来嵌在三段式 Column 里，一展开就把歌词区挤掉一截；现在直接盖在
        // 歌词上方（顶栏之下）：展开时歌词照常滚动、面板下缘以下的当前句依旧
        // 可见。高度按内容撑开，矮屏给 72% 屏高上限 + 滚动兜底。
        // 面板打开时，先铺一层全屏触摸拦截：面板以外的区域既不响应点击、
        // 也不会触发任何「点外部关闭」。拦截层写在面板之前（绘制在其下），
        // 面板自身仍可交互；顶栏 54dp 不拦，保证「外观」按钮可关闭面板。
        if (showAppearance) {
            Box(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(top = 54.dp)
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent(PointerEventPass.Initial)
                                    .changes.forEach { it.consume() }
                            }
                        }
                    }
            )
        }

        if (showAppearance) {
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 54.dp, start = 12.dp, end = 12.dp)
                    .fillMaxWidth()
                    .heightIn(max = maxHeight * 0.72f)
                    .shadow(12.dp, RoundedCornerShape(16.dp))
                    .clip(RoundedCornerShape(16.dp))
                    .background(skin.surface.copy(alpha = 0.97f))
                    .border(1.dp, skin.border, RoundedCornerShape(16.dp))
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 6.dp)
            ) {
                GlassControls(
                    style = glassStyle,
                    blurDp = glassBlur,
                    visibility = glassVisibility,
                    onStyleChange = ::applyStyle,
                    onBlurChange = {
                        glassBlur = it
                        prefs.glassBlurDp = it
                    },
                    onVisibilityChange = {
                        glassVisibility = it
                        prefs.glassVisibility = it
                    },
                    // GlassControls 不自带内边距（避免双层背景）—— 这里补出与
                    // 下方各行卡片一致的 20dp 缩进，内容不再顶到面板边框
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
                FontSizeRow(
                    fontSize = fontSize,
                    onFontSizeChange = {
                        fontSize = it
                        prefs.lyricsFontSizeSp = it
                    },
                    convertT2S = convertT2S,
                    onT2SChange = {
                        convertT2S = it
                        prefs.lyricsConvertToSimplified = it
                    },
                    skin = skin
                )
                AdaptModeRow(
                    mode = adaptMode,
                    onModeChange = {
                        adaptMode = it
                        prefs.lyricsAdaptMode = it
                    },
                    skin = skin
                )
                StyleRow(
                    style = style,
                    onStyleChange = {
                        style = it
                        prefs.lyricsStyle = it
                    },
                    skin = skin
                )
                StatusBarRow(
                    hide = hideStatusBar,
                    onHideChange = onHideStatusBarChange,
                    skin = skin
                )
                OffsetRow(
                    offsetMs = offsetMs,
                    onStep = ::stepOffset,
                    onReset = {
                        offsetMs = 0L
                        prefs.setLyricsOffsetMs(song.title, song.artist, 0L)
                    },
                    skin = skin
                )
            }
        }
    }

    // ===== 手动搜索 / 导入 / 删除歌词对话框 =====
    val importFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { pickedUri ->
        if (pickedUri == null) return@rememberLauncherForActivityResult
        showSearch = false
        searching = true
        notice = null
        scope.launch {
            val rawText = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(pickedUri)?.use { stream ->
                        val bytes = stream.readBytes()
                        if (bytes.size > 2 * 1024 * 1024) return@use ""
                        LyricsRepository.decodeLyricsBytes(bytes)
                    }
                }.getOrNull()
            }
            if (rawText.isNullOrBlank()) {
                searching = false
                notice = "导入失败：文件为空或无法读取"
                return@launch
            }
            val imported = LyricsRepository.importManual(
                context = context,
                songId = song.id,
                title = song.title,
                artist = song.artist,
                apiBase = apiBase,
                rawText = rawText,
                convertToSimplified = convertT2S
            )
            searching = false
            if (imported.lyrics == null) {
                notice = "导入失败：未解析到有效歌词内容"
            } else {
                result = imported
                notice = "已导入本地歌词"
            }
        }
    }

    if (showSearch) {
        LyricsSearchDialog(
            initialTitle = song.title,
            initialArtist = song.artist,
            onlineEnabled = onlineEnabled,
            hasLyrics = lyrics != null,
            onDismiss = { showSearch = false },
            onConfirm = { title, artist ->
                showSearch = false
                searching = true
                notice = null
                scope.launch {
                    val r = LyricsRepository.searchOnline(
                        context = context,
                        title = title,
                        artist = artist,
                        album = song.album,
                        apiBase = apiBase,
                        convertToSimplified = convertT2S,
                        // 同时按原始元数据 / songId 落一份，重启后 load 才能命中
                        originTitle = song.title,
                        originArtist = song.artist,
                        originSongId = song.id
                    )
                    result = r
                    searching = false
                    notice = if (r.lyrics == null) {
                        "没找到「$title${if (artist.isNotBlank()) " - $artist" else ""}」的歌词，换个写法再试"
                    } else {
                        null
                    }
                }
            },
            onImportFile = {
                importFileLauncher.launch(arrayOf("text/*", "application/octet-stream", "*/*"))
            },
            onImportText = { rawText ->
                showSearch = false
                searching = true
                notice = null
                scope.launch {
                    val imported = LyricsRepository.importManual(
                        context = context,
                        songId = song.id,
                        title = song.title,
                        artist = song.artist,
                        apiBase = apiBase,
                        rawText = rawText,
                        convertToSimplified = convertT2S
                    )
                    searching = false
                    if (imported.lyrics == null) {
                        notice = "导入失败：内容为空或无法解析歌词"
                    } else {
                        result = imported
                        notice = "已导入歌词"
                    }
                }
            },
            onDeleteLyrics = {
                showSearch = false
                scope.launch {
                    LyricsRepository.deleteLyrics(
                        context = context,
                        songId = song.id,
                        title = song.title,
                        artist = song.artist,
                        apiBase = apiBase
                    )
                    result = LyricsResult(null, null)
                    notice = "已删除当前歌曲歌词"
                }
            }
        )
    }
}

/* ============================================================
 * 关闭图标的唤出手势
 * ============================================================ */

/* ============================================================
 * 毛玻璃背景
 * ============================================================ */

/**
 * 可调强度的玻璃背景。
 *
 * 三层叠加：
 *  1) 放大 + 模糊 + 提饱和度的专辑封面（可见度控制透多少出来）
 *  2) 一层渐变遮罩，保证文字对比度（可见度越高遮得越薄，但保留下限）
 *  3) 仅液态玻璃：顶部镜面高光 + 一道上边缘亮线，模拟玻璃的反射与折射
 *
 * API 31+ 才有 RenderEffect 真模糊；低于 31 只能靠降不透明度 + 加厚遮罩近似。
 */
/**
 * 歌词页的玻璃背景就是公共实现 [GlassBackdrop] 铺满整页。
 *
 * 封面由本页加载后传进去（「歌词自动适配背景」要用同一张图算色带）；
 * `clipToBounds` 不能省 —— 封面放大 1.35 倍后的溢出要裁在本页矩形内，
 * 否则会盖到播放页左右相邻的层上。
 */

/* ============================================================
 * 背景玻璃调节行（样式 + 模糊 + 可见度）
 * ============================================================ */

@Composable
fun GlassControls(
    style: GlassStyle,
    blurDp: Int,
    visibility: Int,
    onStyleChange: (GlassStyle) -> Unit,
    onBlurChange: (Int) -> Unit,
    onVisibilityChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val skin = LocalSkin.current
    // 不自带卡片底/内边距 —— 调用方的容器（设置面板、悬浮卡片）已有自己的背景，
    // 这里再画一层就是「双层背景」，还会把内容宽度吃掉一圈导致文字收起。
    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        // 样式
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            GlassStyle.entries.forEach { s ->
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (s == style) skin.accentGlow else skin.surface3)
                        .clickable { onStyleChange(s) }
                        .padding(vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        s.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (s == style) skin.accent else skin.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 10.sp
                    )
                }
            }
        }

        if (style != GlassStyle.OFF) {
            SliderLine("模糊", "${blurDp}dp", (blurDp / 80f)) { f ->
                onBlurChange((f * 80).roundToInt())
            }
            SliderLine("透出", "$visibility%", visibility / 100f) { f ->
                onVisibilityChange((f * 100).roundToInt())
            }
        }
    }
}

@Composable
internal fun SliderLine(
    label: String,
    valueText: String,
    value: Float,
    onChange: (Float) -> Unit
) {
    val skin = LocalSkin.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = skin.textMuted, modifier = Modifier.width(28.dp))
        SonanceSlider(
            value = value,
            onValueChange = onChange,
            modifier = Modifier
                .weight(1f)
                .height(26.dp),
            trackHeight = 3.dp,
            thumbSize = 11.dp,
            alwaysShowThumb = true
        )
        Text(
            valueText,
            style = MaterialTheme.typography.labelSmall,
            color = skin.accent,
            modifier = Modifier.width(42.dp),
            textAlign = TextAlign.End
        )
    }
}

/* ============================================================
 * 字号 / 简繁调节
 * ============================================================ */

@Composable
private fun FontSizeRow(
    fontSize: Int,
    onFontSizeChange: (Int) -> Unit,
    convertT2S: Boolean,
    onT2SChange: (Boolean) -> Unit,
    skin: Skin
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(skin.surface2)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("A", style = MaterialTheme.typography.labelSmall, color = skin.textMuted)
        SonanceSlider(
            value = (fontSize - Prefs.MIN_LYRICS_FONT).toFloat() /
                (Prefs.MAX_LYRICS_FONT - Prefs.MIN_LYRICS_FONT).toFloat(),
            onValueChange = { f ->
                val v = Prefs.MIN_LYRICS_FONT +
                    (f * (Prefs.MAX_LYRICS_FONT - Prefs.MIN_LYRICS_FONT)).roundToInt()
                onFontSizeChange(v.coerceIn(Prefs.MIN_LYRICS_FONT, Prefs.MAX_LYRICS_FONT))
            },
            modifier = Modifier
                .weight(1f)
                .height(28.dp),
            trackHeight = 3.dp,
            thumbSize = 12.dp,
            alwaysShowThumb = true
        )
        Text(
            "$fontSize",
            style = MaterialTheme.typography.labelSmall,
            color = skin.accent,
            modifier = Modifier.width(24.dp),
            textAlign = TextAlign.End
        )
        Box(Modifier.width(1.dp).height(20.dp).background(skin.border))
        Text(
            "简",
            style = MaterialTheme.typography.labelSmall,
            color = if (convertT2S) skin.accent else skin.textMuted
        )
        androidx.compose.material3.Switch(
            checked = convertT2S,
            onCheckedChange = onT2SChange,
            modifier = Modifier.height(24.dp),
            colors = androidx.compose.material3.SwitchDefaults.colors(
                checkedThumbColor = skin.onAccent,
                checkedTrackColor = skin.accent,
                uncheckedThumbColor = skin.textMuted,
                uncheckedTrackColor = skin.surface3
            )
        )
    }
}

/* ============================================================
 * 歌词自动适配背景（档位）
 * ============================================================ */

/**
 * 「歌词跟着背景自动调色」的三档。
 *
 * 为什么不是简单的开关：这套调色再克制，也是**动了**用户选的那套配色 ——
 * 有人只在意当前唱到的那一句，有人想要原样。给三档，让他自己决定干预到什么程度。
 */
@Composable
fun AdaptModeRow(
    mode: LyricAdaptMode,
    onModeChange: (LyricAdaptMode) -> Unit,
    skin: Skin,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(skin.surface2)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        ChipsRow(
            chips = LyricAdaptMode.entries.map { it.label to (it == mode) },
            onSelect = { onModeChange(LyricAdaptMode.entries[it]) }
        )
        // 当前档位是干什么的，写在下面一行 —— 标签只有两个字，光看标签猜不出差别
        Text(
            mode.hint,
            style = MaterialTheme.typography.labelSmall,
            color = skin.textMuted,
            fontSize = 10.sp
        )
    }
}

/**
 * 全屏歌词模式选择：经典列表与三种空间舞台，共用现有背景与外观设置。
 * 同一份歌词、同一套调色逻辑，只是排版不同。
 */
@Composable
private fun StyleRow(
    style: LyricStyle,
    onStyleChange: (LyricStyle) -> Unit,
    skin: Skin,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(skin.surface2)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        // 长模式名逐行展示，避免四个选项在手机宽度下被挤掉。
        LyricStyle.entries.forEach { entry ->
            Text(
                text = entry.label,
                color = if (entry == style) skin.accent else skin.textDim,
                fontWeight = if (entry == style) FontWeight.SemiBold else FontWeight.Normal,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (entry == style) skin.accent.copy(alpha = 0.12f) else Color.Transparent)
                    .clickable { onStyleChange(entry) }
                    .padding(horizontal = 12.dp, vertical = 12.dp)
            )
        }
        Text(
            when (style) {
                LyricStyle.CLASSIC -> "逐行列表，当前句高亮滚动，偏「跟唱」"
                LyricStyle.VERTICAL -> "古风右起竖排诗卷，支持中英混排自适应与横向滑动浏览"
                LyricStyle.FLUID -> "左对齐景深，当前句放大，流光细条跟随本句播放进度"
                LyricStyle.VINYL -> "半露黑胶与唱针，歌词沿扇形声轨轮转"
                LyricStyle.SPATIAL -> "未唱从上方迎来，已唱向下方深空沉降，当前句居中悬浮"
            },
            style = MaterialTheme.typography.labelSmall,
            color = skin.textMuted,
            fontSize = 10.sp
        )
    }
}

/**
 * 「隐藏状态栏」开关行：全屏歌词页顶到屏幕上沿，纯沉浸浏览。
 *
 * 状态落地在 SonanceRoot（`PlayerStatusBarImmersive`），这里只上报开关值 ——
 * 因为隐藏/恢复状态栏要在**播放页外层**做，歌词页自己管不了窗口 insets。
 * 开关即时生效；下拉屏幕仍能临时呼出状态栏看时间/通知。
 */
@Composable
private fun StatusBarRow(
    hide: Boolean,
    onHideChange: (Boolean) -> Unit,
    skin: Skin,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(skin.surface2)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "隐藏状态栏",
                style = MaterialTheme.typography.labelLarge,
                color = skin.text
            )
            Text(
                "仅对全屏歌词页生效；下拉可临时呼出",
                style = MaterialTheme.typography.labelSmall,
                color = skin.textMuted,
                fontSize = 10.sp
            )
        }
        androidx.compose.material3.Switch(
            checked = hide,
            onCheckedChange = onHideChange,
            modifier = Modifier.height(24.dp),
            colors = androidx.compose.material3.SwitchDefaults.colors(
                checkedThumbColor = skin.onAccent,
                checkedTrackColor = skin.accent,
                uncheckedThumbColor = skin.textMuted,
                uncheckedTrackColor = skin.surface3
            )
        )
    }
}

/**
 * 「歌词偏移」微调行：歌词和音频对不上时整体提前 / 推迟。
 * ±0.1 秒步进，**按歌记忆** —— 这首歌调好了，别的歌不受影响。
 * 「复原」一键归零（只在调过的时候出现）。
 */
@Composable
private fun OffsetRow(
    offsetMs: Long,
    onStep: (Int) -> Unit,
    onReset: () -> Unit,
    skin: Skin,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(skin.surface2)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("歌词偏移", style = MaterialTheme.typography.labelLarge, color = skin.text)
            Text(
                "歌词慢了往提前调，快了往推迟调；只对这首歌生效",
                style = MaterialTheme.typography.labelSmall,
                color = skin.textMuted,
                fontSize = 10.sp
            )
        }
        // − / +：0.1 秒步进。用文字而不是图标 —— 加减号本身就够清楚
        StepDot("−", skin) { onStep(-1) }
        Text(
            text = String.format("%+.1fs", offsetMs / 1000.0),
            style = MaterialTheme.typography.labelLarge,
            color = if (offsetMs == 0L) skin.textMuted else skin.accent,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp)
        )
        StepDot("+", skin) { onStep(1) }
        if (offsetMs != 0L) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = "复原",
                style = MaterialTheme.typography.labelMedium,
                color = skin.accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onReset() }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            )
        }
    }
}

/** 偏移行的 ± 小圆钮 */
@Composable
private fun StepDot(symbol: String, skin: Skin, onClick: () -> Unit) {
    Text(
        text = symbol,
        fontSize = 18.sp,
        fontWeight = FontWeight.SemiBold,
        color = skin.text,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(skin.surface3)
            .clickable { onClick() }
            .wrapContentSize(Alignment.Center)
    )
}

/**
 * 歌词页底部的三个播放按钮（上一曲 / 播放暂停 / 下一曲）。
 * 显不显示由设置里的「歌词页播放按钮」开关控制，四种全屏模式共用。
 */
@Composable
private fun LyricsPlayBar(
    isPlaying: Boolean,
    onPrev: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    skin: Skin,
    modifier: Modifier = Modifier
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onPrev, modifier = Modifier.size(52.dp)) {
            Icon(SonanceIcons.Prev, "上一曲", tint = skin.text, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(18.dp))
        IconButton(onClick = onPlayPause, modifier = Modifier.size(60.dp)) {
            Icon(
                if (isPlaying) SonanceIcons.Pause else SonanceIcons.Play,
                if (isPlaying) "暂停" else "播放",
                tint = skin.text,
                modifier = Modifier.size(30.dp)
            )
        }
        Spacer(Modifier.width(18.dp))
        IconButton(onClick = onNext, modifier = Modifier.size(52.dp)) {
            Icon(SonanceIcons.Next, "下一曲", tint = skin.text, modifier = Modifier.size(24.dp))
        }
    }
}

/* ============================================================
 * 增强型 LRC：逐字（卡拉 OK）高亮
 * ============================================================ */

/**
 * 最后一段没有「下一段」时间时，用它当一个虚拟的结束时间来铺开扫光。
 * 真实文件里最后一段通常就是一两个字，450ms 足够自然。
 */
private const val KARAOKE_TAIL_MS = 450L

/**
 * 把播放进度变成**平滑**的毫秒位置。
 *
 * 现有进度（[positionMs]）每 500ms 才更新一次，直接拿它逐字高亮会看到
 * 「每半秒跳一批字」。这里以它当锚点：播放时用 `withFrameNanos` 按帧外推，
 * 每来一次新进度就重新对齐 —— 不是定时器，不累积漂移。
 *
 * 三个必须立即对齐的场景（不靠 500ms 轮询）：
 * - 暂停（`isPlaying = false`）：直接采用 [positionMs]，不再外推；
 * - seek / 拖进度条：位置跳变超过正常刷新步长，直接采用新值；
 * - positionMs 更新：只要跳变在正常范围内就保持单调，避免进度采样稍晚
 *   造成的零点几秒回跳。
 */
@Composable
internal fun rememberSmoothPosition(
    positionMs: Long,
    isPlaying: Boolean,
    enabled: Boolean = true,
    alignmentKey: Any? = null
): LongState {
    // A user-adjusted offset must realign even when it is smaller than the polling interval.
    val smooth = remember(alignmentKey) { mutableLongStateOf(positionMs) }
    LaunchedEffect(positionMs, isPlaying, enabled, smooth) {
        val prev = smooth.longValue
        val jump = positionMs - prev
        // 正常 500ms 刷新之间：保持单调（进度采样偶尔晚几十毫秒，不能回跳）；
        // 明显的前跳/后跳（seek、拖进度条）直接采用新值，高亮立刻重算。
        smooth.longValue = if (isPlaying && jump in -600L..899L) {
            maxOf(prev, positionMs)
        } else {
            positionMs
        }
        // 普通 LRC（整份文件都没有逐字片段）不需要逐帧推进，省掉这个循环
        if (!isPlaying || !enabled) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            smooth.longValue += ((now - last) / 1_000_000L).coerceIn(0L, 80L)
            last = now
        }
    }
    return smooth
}

/**
 * 按片段把一整行拆成 [AnnotatedString]，每个字按自己的时间从「未唱色」过渡到
 * 「已唱色」。同一片段里有多个字时，按字数把片段时长均分 —— 这样即使标签是
 * 整词而不是逐字，高亮也是连续扫过去的，不会整块跳。
 *
 * 过渡用 smoothstep 缓动，避免线性色带看起来像硬切。
 */
private fun buildKaraokeText(
    line: LyricLine,
    positionMs: Long,
    unsungColor: Color,
    sungColor: Color
): AnnotatedString {
    val segments = line.segments ?: return AnnotatedString(line.text)
    val builder = AnnotatedString.Builder()
    for (k in segments.indices) {
        val seg = segments[k]
        val segStart = seg.timeMs
        val segEnd = (segments.getOrNull(k + 1)?.timeMs ?: (segStart + KARAOKE_TAIL_MS))
            .coerceAtLeast(segStart + 1L)
        val text = seg.text
        if (text.isEmpty()) continue
        val count = text.length
        val span = segEnd - segStart
        for (ci in 0 until count) {
            val charStart = segStart + span * ci / count
            val charEnd = segStart + span * (ci + 1) / count
            val charSpan = charEnd - charStart
            val progress = if (charSpan <= 0L) {
                if (positionMs >= charStart) 1f else 0f
            } else {
                ((positionMs - charStart).toFloat() / charSpan).coerceIn(0f, 1f)
            }
            val eased = progress * progress * (3f - 2f * progress)
            val start = builder.length
            builder.append(text[ci])
            builder.addStyle(SpanStyle(color = lerp(unsungColor, sungColor, eased)), start, builder.length)
        }
    }
    return builder.toAnnotatedString()
}

/**
 * 当前行有逐字片段时才走这里 —— 它自己读 [smoothPos]，所以每帧只重组这**一行**
 * 文本，不会带着整面歌词（[FullscreenThemedLyrics] / [SyncedLyrics]）一起重组。
 */
@Composable
internal fun KaraokeLineText(
    line: LyricLine,
    smoothPos: LongState,
    unsungColor: Color,
    sungColor: Color,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    fontWeight: FontWeight,
    textAlign: TextAlign = TextAlign.Start
) {
    val pos = smoothPos.longValue
    val text = remember(line, pos, unsungColor, sungColor) {
        buildKaraokeText(line, pos, unsungColor, sungColor)
    }
    Text(
        text = text,
        fontSize = fontSize,
        lineHeight = lineHeight,
        fontWeight = fontWeight,
        color = unsungColor,
        textAlign = textAlign
    )
}

/* ============================================================
 * 播放页主区域内的歌词（不是全屏歌词页）
 * ============================================================ */

/**
 * 播放页主区域里的歌词 —— 就是主流播放器在播放页上放歌词的那种：
 * 封面那一块直接换成歌词，多行居中，当前句高亮，随播放滚动，点一句跳到那一句。
 *
 * 与全屏歌词页共用 [LyricsRepository] 加载、共用 [SyncedLyrics] / [PlainLyrics] 渲染，
 * 所以内嵌 / 缓存 / 在线三条来源、字号设置、简繁转换都保持一致；
 * 全屏歌词页仍然保留（那里能看完整歌词、调字号、手动搜索）。
 */
@Composable
fun InlineLyrics(
    song: Song,
    positionMs: Long,
    prefs: Prefs,
    onSeek: (Long) -> Unit,
    /** 播放状态：逐字歌词据此在播放时按帧推进、暂停时立即定格 */
    isPlaying: Boolean = false,
    /** 当前页的晕染烘焙位图 —— 歌词颜色跟随全屏歌词页的调色设置时，拿它折算背景色带 */
    glowImage: ImageBitmap? = null,
    /** 紧凑模式：只渲染「上一句 / 当前句 / 下一句」三行，行距收紧、两侧字号与透明度降级 */
    compact: Boolean = false,
    modifier: Modifier = Modifier
) {
    val skin = LocalSkin.current
    val context = LocalContext.current

    var result by remember(song.id) { mutableStateOf<LyricsResult?>(null) }
    var loading by remember(song.id) { mutableStateOf(true) }

    val onlineEnabled = prefs.onlineLyricsEnabled
    val apiBase = prefs.lyricsApiBase
    val convertT2S = prefs.lyricsConvertToSimplified
    val fontSize = prefs.lyricsFontSizeSp
    val neteaseFill = prefs.neteaseFillEnabled

    LaunchedEffect(song.id, song.uri, onlineEnabled, apiBase, convertT2S, neteaseFill) {
        loading = true
        result = LyricsRepository.load(
            context = context,
            songId = song.id,
            uri = song.uri,
            title = song.title,
            artist = song.artist,
            album = song.album,
            onlineEnabled = onlineEnabled,
            apiBase = apiBase,
            convertToSimplified = convertT2S,
            durationMs = song.durationMs,
            neteaseEnabled = neteaseFill
        )
        loading = false
    }

    val lyrics = result?.lyrics

    // 字体大小、调色档位都跟全屏歌词页的设置走。这里的背景是晕染层不是封面模糊，
    // 色带从晕染烘焙位图直接采样（见 [buildInlineBgProfile]），档位关了就不调。
    val adaptMode = prefs.lyricsAdaptMode
    val bgProfile = remember(glowImage, skin, adaptMode) {
        if (adaptMode == LyricAdaptMode.OFF) null else buildInlineBgProfile(glowImage, skin)
    }
    // 歌词偏移同样跟全屏歌词页按歌记忆的值走。直接读 prefs（走内存缓存）：
    // positionMs 每 500ms 驱动重组，在歌词页调完关掉这里最多半秒就跟上
    val offsetMs = prefs.lyricsOffsetMs(song.title, song.artist)

    if (compact) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when (lyrics) {
                is Lyrics.Synced -> ThreeLineLyrics(lyrics.lines, positionMs - offsetMs, fontSize, skin)
                is Lyrics.Plain -> ThreeLinePlain(lyrics.lines, fontSize, skin)
                else -> Unit
            }
        }
        return
    }

    Box(modifier.fillMaxSize()) {
        when {
            loading -> Text(
                "正在读取歌词…",
                style = MaterialTheme.typography.bodyMedium,
                color = skin.textMuted,
                modifier = Modifier.align(Alignment.Center)
            )

            lyrics == null -> Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    "这首歌还没有歌词",
                    style = MaterialTheme.typography.bodyMedium,
                    color = skin.textMuted
                )
                if (onlineEnabled) {
                    Text(
                        "打开歌词页可以按歌名搜索",
                        style = MaterialTheme.typography.labelSmall,
                        color = skin.textDim
                    )
                }
            }

            lyrics is Lyrics.Synced -> SyncedLyrics(
                lines = lyrics.lines,
                positionMs = positionMs - offsetMs,
                isPlaying = isPlaying,
                fontSizeSp = fontSize,
                onSeek = onSeek,
                bgProfile = bgProfile,
                adaptMode = adaptMode
            )

            else -> PlainLyrics((lyrics as Lyrics.Plain).lines, fontSize, bgProfile, adaptMode)
        }
    }
}

/** 紧凑三行：上一句 / 当前句 / 下一句，字号与透明度向两侧递减，行距 6dp。 */
@Composable
private fun ThreeLineLyrics(lines: List<LyricLine>, positionMs: Long, fontSizeSp: Int, skin: Skin) {
    if (lines.isEmpty()) return
    val idx = lines.indexAt(positionMs).coerceIn(0, lines.size - 1)
    val side = (fontSizeSp - 4).coerceAtLeast(11)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        lines.getOrNull(idx - 1)?.let {
            Text(
                it.text,
                fontSize = side.sp,
                color = skin.textMuted.copy(alpha = 0.55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
        Text(
            lines[idx].text,
            fontSize = fontSizeSp.sp,
            fontWeight = FontWeight.W500,
            color = skin.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
        lines.getOrNull(idx + 1)?.let {
            Text(
                it.text,
                fontSize = side.sp,
                color = skin.textMuted.copy(alpha = 0.4f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ThreeLinePlain(lines: List<String>, fontSizeSp: Int, skin: Skin) {
    if (lines.isEmpty()) return
    val side = (fontSizeSp - 4).coerceAtLeast(11)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        lines.take(3).forEachIndexed { i, text ->
            Text(
                text,
                fontSize = if (i == 0) fontSizeSp.sp else side.sp,
                fontWeight = if (i == 0) FontWeight.W500 else FontWeight.Normal,
                color = if (i == 0) skin.text else skin.textMuted.copy(alpha = if (i == 1) 0.55f else 0.4f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

/* ============================================================
 * 带时间轴：跟随播放高亮 + 自动居中
 * ============================================================ */

/**
 * 歌词区里「当前句」停在屏幕的什么位置。
 *
 * LazyColumn 上下各留这么一块 contentPadding，`animateScrollToItem(当前句)`
 * 会把当前句推到距顶这么高的地方。这个值同时管两件事：
 *  - 首尾两句也能滚到偏中间来（上下留白）；
 *  - 每行歌词换算"自己在屏幕哪一段"时的锚点（见 [SyncedLyrics] 的 `bgProfile`）。
 */
private const val LYRIC_ANCHOR_FRACTION = 0.34f

/** 触摸后多久恢复自动跟随（毫秒）。与 FLUID / VINYL / SPATIAL 三种主题保持一致，都是 5 秒。 */
private const val LYRIC_SCROLL_HOLD_MS = 5_000L

/** 手动滚动的触觉窗口：触摸后这段时间内认为"是用户在滚"，跨行才给震动 */
private const val SCROLL_HAPTIC_WINDOW_MS = 1_500L

@Composable
internal fun SyncedLyrics(
    lines: List<LyricLine>,
    positionMs: Long,
    isPlaying: Boolean,
    fontSizeSp: Int,
    onSeek: (Long) -> Unit,
    /**
     * 背景色带。给了就按每行**在屏幕上的位置**取该处的背景色，把歌词颜色调到看得清
     * （见 [lyricColor]）；不给（播放页里的歌词、律动柱那一页）就维持皮肤原本的配色。
     */
    bgProfile: BgProfile? = null,
    /**
     * 调色的档位（见 [LyricAdaptMode]）。[LyricAdaptMode.CURRENT_ONLY] 时只有当前句
     * 走色带，其余行保持皮肤原色。[bgProfile] 为 null 时这个参数不起作用。
     */
    adaptMode: LyricAdaptMode = LyricAdaptMode.ALL,
    /**
     * 是否允许用户手动上下滚动。
     *
     * 平板右侧面板是**只读展示层**：那里上下滑要留给切歌，不能被歌词列表吃掉，
     * 所以面板传 false。手机播放页保持默认 true，行为不变。
     */
    userScrollEnabled: Boolean = true,
    /**
     * 点某一行是否跳转进度。面板同样只做展示，传 false 后整行不再挂 clickable。
     */
    seekEnabled: Boolean = true,
    timelineOffsetMs: Long = 0L
) {
    val skin = LocalSkin.current
    val density = LocalDensity.current
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val current = remember(positionMs, lines) { lines.indexAt(positionMs) }
    val smoothPos = rememberSmoothPosition(
        positionMs,
        isPlaying,
        enabled = remember(lines) { lines.any { it.segments != null } },
        alignmentKey = timelineOffsetMs
    )

    // 用户最近一次触摸屏幕的时间。
    //
    // 为什么需要它：`positionMs` 每 500ms 变一次，当前行一变就 animateScrollToItem，
    // 用户根本没法手动翻到别处 —— 刚滑走就被拽回来。
    // 这里记录触摸时刻，触摸后 5 秒内不自动跟随；之后就自然恢复（当前行还在继续变）。
    //
    // 用数组而不是 Compose state：它每帧都可能被写，但没有任何 composable 读它，
    // 走快照系统纯属浪费。
    val lastTouch = remember { longArrayOf(0L) }

    // 触摸后需要「到点自动回位」：用户可能停在翻到的那一屏不再动（暂停时分外明显）。
    // 用普通数组而不是 state —— 只有下面的定时循环读它，重绘没有意义。
    val needsReturn = remember { booleanArrayOf(false) }
    val latestCurrent by rememberUpdatedState(current)

    // 当前行变化时滚到中间（contentPadding 让首尾也能居中），但用户正在浏览时不打扰
    LaunchedEffect(current) {
        if (current < 0) return@LaunchedEffect
        if (android.os.SystemClock.uptimeMillis() - lastTouch[0] < LYRIC_SCROLL_HOLD_MS) return@LaunchedEffect
        runCatching { listState.animateScrollToItem(current) }
    }

    // 5 秒到点自动回位。只靠 LaunchedEffect(current) 的话，暂停或当前句很长时
    // 用户会一直停在翻到的地方；这里每 200ms 检查一次，满 5 秒且列表已停稳就回去。
    LaunchedEffect(listState) {
        while (true) {
            delay(200)
            if (!needsReturn[0] || listState.isScrollInProgress) continue
            if (android.os.SystemClock.uptimeMillis() - lastTouch[0] < LYRIC_SCROLL_HOLD_MS) continue
            needsReturn[0] = false
            if (latestCurrent >= 0) runCatching { listState.animateScrollToItem(latestCurrent) }
        }
    }

    // 手动滚动时每跨过一行轻震一次；自动跟随不震，否则播放时会一直嗡嗡响。
    // firstVisibleItemIndex 每变一次 ≈ 一行滚过顶部，节拍和看到的内容一致。
    LaunchedEffect(listState, userScrollEnabled) {
        if (!userScrollEnabled) return@LaunchedEffect
        var previous = listState.firstVisibleItemIndex
        snapshotFlow { listState.firstVisibleItemIndex }.collect { index ->
            if (index != previous) {
                previous = index
                if (android.os.SystemClock.uptimeMillis() - lastTouch[0] < SCROLL_HAPTIC_WINDOW_MS) {
                    hapticTick(context)
                }
            }
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            // 只**观察**指针事件，不消费 —— LazyColumn 自己的滚动不受影响。
            // 用 PointerEventPass.Initial 在分发链最前面拿到，确保不会漏。
            // 面板只读（不允许手动滚动）时不需要这套"触摸后暂停自动跟随"，干脆不挂。
            .then(
                if (userScrollEnabled) {
                    Modifier.pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val e = awaitPointerEvent(PointerEventPass.Initial)
                                if (e.changes.any { it.pressed }) {
                                    lastTouch[0] = android.os.SystemClock.uptimeMillis()
                                    needsReturn[0] = true
                                }
                            }
                        }
                    }
                } else {
                    Modifier
                }
            )
    ) {
        val centerPad = maxHeight * LYRIC_ANCHOR_FRACTION

        // 「行号差 → 屏幕纵向比例」的换算：当前句停在锚点处，往上 / 往下每行移动
        // linePx（行高 + 行距 + 上下各 2dp 的 padding）。有了它，某一行歌词就能
        // 拿到**自己那一块**的背景色，只在这一块背景偏亮时才提亮自己。
        //
        // 注意：这个换算默认"当前句是居中的"（自动跟随滚动时的状态）。
        // 用户手动翻到别处时会有偏差 —— 但那种时候他正在读的也不是当前句，
        // 颜色差一点点看不出来，不值得为此挂一层逐项布局回调。
        val containerPx = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)
        val linePx = with(density) {
            (fontSizeSp * 1.5f).sp.toPx() + (fontSizeSp * 0.62f).dp.toPx() + 4.dp.toPx()
        }
        val anchorPx = containerPx * LYRIC_ANCHOR_FRACTION

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 28.dp,
                end = 28.dp,
                top = centerPad,
                bottom = centerPad
            ),
            verticalArrangement = Arrangement.spacedBy((fontSizeSp * 0.62f).dp),
            userScrollEnabled = userScrollEnabled
        ) {
            itemsIndexed(lines) { index, line ->
                val isCurrent = index == current
                // 有时间轴的行才可点：点了把播放进度跳到那一句。
                // 只读展示（平板面板）时不挂 clickable，避免看起来能编辑却只是翻页。
                val seekable = seekEnabled && line.timeMs >= 0
                val baseColor = when {
                    isCurrent -> skin.accent
                    index < current -> skin.textMuted
                    else -> skin.textDim
                }
                val color = if (bgProfile == null || !shouldAdapt(isCurrent, adaptMode)) {
                    baseColor
                } else {
                    lyricColor(
                        base = baseColor,
                        screenFraction = (anchorPx + (index - current) * linePx) / containerPx,
                        isCurrent = isCurrent,
                        profile = bgProfile
                    )
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (seekable) {
                                Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onSeek(line.timeMs) }
                            } else {
                                Modifier
                            }
                        )
                        .padding(vertical = 2.dp)
                ) {
                    val lineFontSize = (fontSizeSp + if (isCurrent) 2 else 0).sp
                    val lineLineHeight = (fontSizeSp * 1.5f).sp
                    val lineWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal
                    if (isCurrent && line.segments != null) {
                        // 增强型 LRC：当前句逐字高亮
                        KaraokeLineText(
                            line = line,
                            smoothPos = smoothPos,
                            unsungColor = color.copy(alpha = 0.38f),
                            sungColor = color,
                            fontSize = lineFontSize,
                            lineHeight = lineLineHeight,
                            fontWeight = lineWeight,
                            textAlign = TextAlign.Center
                        )
                    } else {
                        Text(
                            text = line.text,
                            fontSize = lineFontSize,
                            lineHeight = lineLineHeight,
                            fontWeight = lineWeight,
                            color = color,
                            textAlign = TextAlign.Center
                        )
                    }
                    // 译文：网易云 tlyric 拆出来的（见 LyricLine.translation），
                    // 画在原文下方、小一号、淡一档 —— 当前句最清楚，远处几乎隐去
                    line.translation?.let { tr ->
                        Text(
                            text = tr,
                            fontSize = (fontSizeSp * 0.72f).sp,
                            lineHeight = (fontSizeSp * 0.72f * 1.4f).sp,
                            fontWeight = if (isCurrent) FontWeight.Medium else FontWeight.Normal,
                            color = color.copy(alpha = if (isCurrent) 0.85f else 0.55f),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

/* ============================================================
 * 纯文本
 * ============================================================ */

@Composable
internal fun PlainLyrics(
    lines: List<String>,
    fontSizeSp: Int,
    /** 同 [SyncedLyrics]：给了就按背景把颜色调到看得清 */
    bgProfile: BgProfile? = null,
    /**
     * 同 [SyncedLyrics]。纯文本歌词**每一行都是"非当前句"**（没有高亮句），
     * 所以 [LyricAdaptMode.CURRENT_ONLY] 在这一页等同关闭 —— 由 [shouldAdapt] 自动成立。
     */
    adaptMode: LyricAdaptMode = LyricAdaptMode.ALL,
    /** 同 [SyncedLyrics]：平板面板传 false，把上下滑留给切歌 */
    userScrollEnabled: Boolean = true
) {
    val skin = LocalSkin.current

    // 纯文本歌词没有时间轴，推不出"第几行在屏幕哪一块"；行高也不齐（长句会折行），
    // 所以不能像 [SyncedLyrics] 那样用"当前句 + 行号差"算。
    // 这里直接问 LazyColumn 要它刚量出来的位置，让每一行按**自己那一块**背景取色。
    // 量不到位置的（视口高度拿不到、或刚进视口还没来得及量）退回"整页按中段取色"。
    val listState = rememberLazyListState()
    val layout = listState.layoutInfo
    val fractions = remember(layout) {
        visibleFractions(
            boxes = layout.visibleItemsInfo.map { VisibleLine(it.index, it.offset, it.size) },
            viewportHeightPx = layout.viewportSize.height
        )
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy((fontSizeSp * 0.5f).dp),
        userScrollEnabled = userScrollEnabled
    ) {
        itemsIndexed(lines) { index, line ->
            val color = if (bgProfile == null || !shouldAdapt(isCurrent = false, mode = adaptMode)) {
                skin.textDim
            } else {
                lyricColor(skin.textDim, fractions[index] ?: 0.5f, false, bgProfile)
            }
            Text(
                text = line,
                fontSize = fontSizeSp.sp,
                lineHeight = (fontSizeSp * 1.5f).sp,
                color = color,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/* ============================================================
 * 平板右侧面板：只读歌词层
 * ============================================================ */

/**
 * 右侧面板的歌词层。
 *
 * 解析和渲染与手机播放页完全同一套（[LyricsRepository] + [SyncedLyrics]，
 * 含增强型 LRC 的逐字高亮），但**没有任何设置入口**：没有搜索 / 外观 / 字号 /
 * 对齐 / 偏移入口，也不允许手动滚动或点行 seek —— 面板里上下滑永远留给切歌。
 *
 * 字号跟随全局设置，但夹在 13~22sp：右侧面板只有 300 多 dp 宽，再大就会一行
 * 只剩一两个字。文字本身按 `fillMaxWidth` 换行居中，不会横向溢出。
 */
@Composable
fun PanelLyrics(
    song: Song,
    positionMs: Long,
    prefs: Prefs,
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    val skin = LocalSkin.current
    val context = LocalContext.current

    var result by remember(song.id) { mutableStateOf<LyricsResult?>(null) }
    var loading by remember(song.id) { mutableStateOf(true) }

    // 这些是**全局设置**（手机设置页里的开关），面板只读，只按它去取歌词，不提供入口
    val onlineEnabled = prefs.onlineLyricsEnabled
    val apiBase = prefs.lyricsApiBase
    val neteaseFill = prefs.neteaseFillEnabled
    val convertT2S = prefs.lyricsConvertToSimplified

    LaunchedEffect(song.id, song.uri, onlineEnabled, apiBase, convertT2S, neteaseFill) {
        loading = true
        result = LyricsRepository.load(
            context = context,
            songId = song.id,
            uri = song.uri,
            title = song.title,
            artist = song.artist,
            album = song.album,
            onlineEnabled = onlineEnabled,
            apiBase = apiBase,
            convertToSimplified = convertT2S,
            durationMs = song.durationMs,
            neteaseEnabled = neteaseFill
        )
        loading = false
    }

    val lyrics = result?.lyrics
    // 偏移是手机歌词页里按歌记忆的设置；面板不改它，只跟着它走，保证两边同一时间轴
    val offsetMs = prefs.lyricsOffsetMs(song.title, song.artist)
    val fontSizeSp = prefs.lyricsFontSizeSp.coerceIn(13, 22)

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            loading -> Text(
                "正在读取歌词…",
                style = MaterialTheme.typography.bodySmall,
                color = skin.textMuted
            )

            lyrics == null -> Text(
                "这首歌没有歌词",
                style = MaterialTheme.typography.bodySmall,
                color = skin.textMuted
            )

            lyrics is Lyrics.Synced -> SyncedLyrics(
                lines = lyrics.lines,
                positionMs = positionMs - offsetMs,
                isPlaying = isPlaying,
                fontSizeSp = fontSizeSp,
                onSeek = {},
                userScrollEnabled = false,
                seekEnabled = false
            )

            else -> PlainLyrics(
                lines = (lyrics as Lyrics.Plain).lines,
                fontSizeSp = fontSizeSp,
                userScrollEnabled = false
            )
        }
    }
}

/* ============================================================
 * 没有歌词
 * ============================================================ */

@Composable
private fun EmptyLyrics(song: Song, onlineEnabled: Boolean, onManualSearch: () -> Unit) {
    val skin = LocalSkin.current
    val context = LocalContext.current

    Box(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(skin.surface2)
                    .padding(horizontal = 20.dp, vertical = 18.dp)
            ) {
                androidx.compose.material3.Icon(
                    imageVector = SonanceIcons.Music,
                    contentDescription = null,
                    tint = skin.textMuted,
                    modifier = Modifier.size(30.dp)
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                if (onlineEnabled) "本地和在线都没找到歌词" else "这首歌没有内嵌歌词",
                style = MaterialTheme.typography.titleMedium,
                color = skin.text,
                textAlign = TextAlign.Center
            )
            Text(
                if (onlineEnabled) {
                    "已尝试：① 文件内嵌歌词（ID3 USLT / FLAC LYRICS / M4A ©lyr）\n" +
                        "② 在线歌词接口（LrcAPI）\n" +
                        "歌名/歌手标签不准的话，可以手动填一次。"
                } else {
                    "歌词从音频文件里直接读取（ID3 的 USLT、FLAC 的 LYRICS、M4A 的 ©lyr）。\n" +
                        "纯本地解析，不联网、不上传。\n" +
                        "想要联网补歌词，可以到「设置 → 歌词」里打开在线歌词。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = skin.textMuted,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                PillButton(
                    label = if (onlineEnabled) "搜索 / 导入歌词" else "导入歌词",
                    onClick = onManualSearch
                )
                PillButton(
                    label = "清除本首缓存",
                    onClick = {
                        val base = Prefs(context).lyricsApiBase
                        LyricsRepository.forget(context, song.title, song.artist, base)
                    },
                    filled = false
                )
            }
        }
    }
}

/* ============================================================
 * 手动搜索对话框
 * ============================================================ */

@Composable
private fun LyricsSearchDialog(
    initialTitle: String,
    initialArtist: String,
    onlineEnabled: Boolean,
    hasLyrics: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (title: String, artist: String) -> Unit,
    onImportFile: () -> Unit,
    onImportText: (rawText: String) -> Unit,
    onDeleteLyrics: () -> Unit
) {
    val skin = LocalSkin.current
    var title by remember { mutableStateOf(initialTitle) }
    var artist by remember { mutableStateOf(initialArtist) }
    var pasteMode by remember { mutableStateOf(false) }
    var pasteText by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = skin.surface2.solidSurface(),
        titleContentColor = skin.text,
        textContentColor = skin.textDim,
        title = {
            Text(
                text = if (pasteMode) "粘贴导入歌词" else "搜索与管理歌词",
                style = MaterialTheme.typography.titleMedium,
                color = skin.text
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (pasteMode) {
                    Text(
                        "粘贴 LRC（带 [mm:ss.xx] 时间轴）或纯文本歌词，保存后立即覆盖当前显示。",
                        style = MaterialTheme.typography.bodySmall,
                        color = skin.textMuted
                    )
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp, max = 210.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(skin.surface)
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        if (pasteText.isEmpty()) {
                            Text(
                                "[00:12.34] 在此粘贴歌词内容…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = skin.textDim
                            )
                        }
                        BasicTextField(
                            value = pasteText,
                            onValueChange = { pasteText = it },
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = skin.text),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(skin.accent),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillButton(
                            label = "返回搜索",
                            onClick = { pasteMode = false },
                            filled = false
                        )
                        PillButton(
                            label = "确认导入",
                            onClick = {
                                if (pasteText.isNotBlank()) onImportText(pasteText)
                            }
                        )
                    }
                } else {
                    Text(
                        if (onlineEnabled) {
                            "默认填的是当前歌曲的标签信息。改一下歌名或歌手再搜一次，也可以直接导入或删除歌词。"
                        } else {
                            "当前未开启在线歌词（可在「设置 → 歌词」开启），可在此直接导入外部 .lrc / 粘贴歌词或删除当前歌词。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = skin.textMuted
                    )
                    LabeledField(
                        label = "歌曲名",
                        value = title,
                        onValueChange = { title = it },
                        skin = skin
                    )
                    LabeledField(
                        label = "歌手",
                        value = artist,
                        onValueChange = { artist = it },
                        skin = skin
                    )

                    Spacer(Modifier.height(2.dp))
                    Text(
                        "本地歌词管理",
                        style = MaterialTheme.typography.labelSmall,
                        color = skin.textMuted
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        DialogActionChip(
                            label = "导入文件",
                            onClick = onImportFile,
                            modifier = Modifier.weight(1f),
                            skin = skin
                        )
                        DialogActionChip(
                            label = "粘贴文本",
                            onClick = { pasteMode = true },
                            modifier = Modifier.weight(1f),
                            skin = skin
                        )
                    }
                    if (hasLyrics) {
                        DialogActionChip(
                            label = if (confirmDelete) "再点一次确认删除当前歌词" else "删除当前歌词",
                            onClick = {
                                if (confirmDelete) onDeleteLyrics() else confirmDelete = true
                            },
                            modifier = Modifier.fillMaxWidth(),
                            destructive = true,
                            highlighted = confirmDelete,
                            skin = skin
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (!pasteMode && onlineEnabled) {
                PillButton(
                    label = "搜索",
                    onClick = {
                        if (title.isNotBlank()) onConfirm(title.trim(), artist.trim())
                    }
                )
            }
        },
        dismissButton = {
            PillButton(label = "关闭", onClick = onDismiss, filled = false)
        }
    )
}

@Composable
private fun DialogActionChip(
    label: String,
    onClick: () -> Unit,
    skin: Skin,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
    highlighted: Boolean = false
) {
    val bg = when {
        highlighted && destructive -> skin.accent.copy(alpha = 0.22f)
        destructive -> skin.surface.copy(alpha = 0.85f)
        else -> skin.surface
    }
    val fg = when {
        highlighted && destructive -> skin.accent
        destructive -> skin.textMuted
        else -> skin.text
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(
                width = 0.8.dp,
                color = if (highlighted) skin.accent else skin.border,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp, horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun LabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    skin: Skin
) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = skin.textMuted)
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(skin.surface)
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = skin.text),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(skin.accent),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/* ============================================================
 * 律动柱页面：歌名上方那条「单行歌词」
 * ============================================================ */

/**
 * 律动柱页面里、歌名正上方那条**单行歌词**（用户要求单行）。
 *
 * 只填掉歌名上方本来就空着的那条带子：显示**当前句**，
 * 不滚、不点、**不吃任何手势** —— 律动柱那一页的左右滑动要留给"切层"。
 *
 * 只认**带时间轴**的歌词（[Lyrics.Synced]）。纯文本歌词没有时间信息，
 * 摆在这儿就是一段和播放无关的静态文字，所以宁可不显示（那块本来就是空的，
 * 不显示不会留下占位文字或空格）。取词链路和歌词页完全共用（本地内嵌 →
 * 本地缓存 → 在线 / 网易云），设置里的字号、简繁转换在这里一样生效。
 *
 * @param fontSizeSp 已经算好的字号（比歌词页小几号，免得压到律动柱主体）
 */
@Composable
fun LyricStrip(
    song: Song,
    positionMs: Long,
    prefs: Prefs,
    fontSizeSp: Int,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var lines by remember(song.id) { mutableStateOf<List<LyricLine>?>(null) }

    val onlineEnabled = prefs.onlineLyricsEnabled
    val apiBase = prefs.lyricsApiBase
    val convertT2S = prefs.lyricsConvertToSimplified
    val neteaseFill = prefs.neteaseFillEnabled
    val lyricsRev by LyricsRepository.revision.collectAsState()

    LaunchedEffect(song.id, song.uri, onlineEnabled, apiBase, convertT2S, neteaseFill, lyricsRev) {
        lines = null
        val loaded = LyricsRepository.load(
            context = context,
            songId = song.id,
            uri = song.uri,
            title = song.title,
            artist = song.artist,
            album = song.album,
            onlineEnabled = onlineEnabled,
            apiBase = apiBase,
            convertToSimplified = convertT2S,
            durationMs = song.durationMs,
            neteaseEnabled = neteaseFill
        )
        lines = (loaded?.lyrics as? Lyrics.Synced)?.lines
    }

    // 还没取到 / 不是同步歌词 / 当前还在前奏（indexAt 返回 -1）→ 什么都不画。
    // 三种情况都返回而不是显示占位文字：那块地方是律动柱的留白，
    // 写"正在读取歌词…"这类字反而比空着更吵。
    val all = lines ?: return
    val current = remember(positionMs, all) { all.indexAt(positionMs) }
    if (current < 0) return

    val skin = LocalSkin.current
    Text(
        text = all[current].text,
        fontSize = fontSizeSp.sp,
        // 行高写死且比字号大一截：句子切换时高度不变，整条不抖
        lineHeight = (fontSizeSp * 1.45f).sp,
        fontWeight = FontWeight.SemiBold,
        color = skin.accent,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.fillMaxWidth()
    )
}
