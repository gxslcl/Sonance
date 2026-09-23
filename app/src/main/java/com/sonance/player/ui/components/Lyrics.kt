package com.sonance.player.ui.components

import android.os.Build
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sonance.player.data.ArtworkLoader
import com.sonance.player.data.LyricLine
import com.sonance.player.data.Lyrics
import com.sonance.player.data.LyricsRepository
import com.sonance.player.data.LyricsResult
import com.sonance.player.data.LyricsSource
import com.sonance.player.data.OnlineCover
import com.sonance.player.data.Prefs
import com.sonance.player.data.Song
import com.sonance.player.data.indexAt
import com.sonance.player.ui.icon.SonanceIcons
import com.sonance.player.ui.theme.GlassStyle
import com.sonance.player.ui.theme.LocalSkin
import com.sonance.player.ui.theme.Skin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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
    positionMs: Long,
    prefs: Prefs,
    /** 点歌词某一行时把播放进度跳到那一句 */
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val skin = LocalSkin.current
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

    // 开关进 key：用户在设置里打开「网易云补全」后回到歌词页要能立刻生效
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
            // 匹配网易云要靠时长排除同名翻唱 / Live 版
            durationMs = song.durationMs,
            neteaseEnabled = neteaseFill
        )
        loading = false
    }

    val lyrics = result?.lyrics

    Box(modifier.fillMaxSize().background(skin.bg)) {

        // ===== 玻璃背景（可调强度 / 液态玻璃）=====
        GlassBackdrop(
            song = song,
            style = glassStyle,
            blurDp = glassBlur,
            visibility = glassVisibility
        )

        // ===== 三段式内容 =====
        Column(Modifier.fillMaxSize()) {

            // ---- 顶栏 ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = when (result?.source) {
                        LyricsSource.EMBEDDED -> "内嵌歌词"
                        LyricsSource.ONLINE -> "在线歌词 · LrcAPI"
                        null -> "歌词"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (result?.source == LyricsSource.ONLINE) skin.accent else skin.textDim,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // 手动搜索：拿到错误歌词时的补救入口
                if (onlineEnabled) {
                    IconAction(
                        icon = SonanceIcons.Search,
                        contentDescription = "手动搜索歌词",
                        onClick = { showSearch = true },
                        iconSize = 19.dp,
                        tint = skin.textDim
                    )
                }
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

            // ---- 外观调节面板（背景玻璃 + 字号 / 简繁，合成一个）----
            //
            // 加了高度上限 + 可滚动：两个面板叠起来约 300dp，矮屏（横屏、小屏手机）
            // 上会把下面的歌词区挤到几乎没有。这里不滚动的话用户只能靠关掉面板
            // 才能看歌词 —— 而调字号时恰恰需要边看歌词边调。
            if (showAppearance) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState())
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
                        }
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
                }
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

                    lyrics == null -> EmptyLyrics(
                        song = song,
                        onlineEnabled = onlineEnabled,
                        onManualSearch = { showSearch = true }
                    )

                    lyrics is Lyrics.Synced -> SyncedLyrics(
                        lines = lyrics.lines,
                        positionMs = positionMs,
                        fontSizeSp = fontSize,
                        onSeek = onSeek
                    )

                    else -> PlainLyrics((lyrics as Lyrics.Plain).lines, fontSize)
                }
            }

            // ---- 底部歌名 ----
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 28.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
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

    // ===== 手动搜索对话框 =====
    if (showSearch) {
        LyricsSearchDialog(
            initialTitle = song.title,
            initialArtist = song.artist,
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
                        convertToSimplified = convertT2S
                    )
                    result = r
                    searching = false
                    notice = if (r.lyrics == null) {
                        "没找到「$title${if (artist.isNotBlank()) " - $artist" else ""}」的歌词，换个写法再试"
                    } else {
                        null
                    }
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
@Composable
private fun GlassBackdrop(
    song: Song,
    style: GlassStyle,
    blurDp: Int,
    visibility: Int,
    modifier: Modifier = Modifier
) {
    val skin = LocalSkin.current
    val context = LocalContext.current

    if (style == GlassStyle.OFF) {
        // 「关闭」就是真的什么都不画：歌词页只有纯背景色，连渐变也不留
        return
    }

    val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val artAlpha = GlassStyle.artAlpha(visibility)
    val scrim = GlassStyle.scrimAlpha(visibility)

    var art by remember(song.id) { mutableStateOf<ImageBitmap?>(null) }
    // 走和列表 / 播放页**完全相同**的取图链路：专辑封面 → 歌曲内嵌 → 之前下载落盘的封面。
    // 早先这里只传了 albumId，两个兜底都没接上，于是**磁盘上已经下好的封面读不到**：
    // 表现是「列表里明明有封面，歌词页背景却是空的」，冷启动后尤其明显
    // （那时内存里的 onlineCovers 是空的）。sourceUri 兜内嵌封面，coverKey 兜磁盘缓存。
    LaunchedEffect(song.albumId, song.id, song.title, song.artist) {
        art = ArtworkLoader.load(
            context = context,
            albumId = song.albumId,
            sizePx = 320,
            sourceUri = song.uri,
            coverKey = OnlineCover.keyOf(song.title, song.artist)
        ) ?: ArtworkLoader.online(song.id)
    }

    art?.let { bmp ->
        val matrix = remember(style.liquid, visibility) {
            ColorMatrix().apply {
                setToSaturation(GlassStyle.saturation(style.liquid, visibility))
            }
        }
        Image(
            bitmap = bmp,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            colorFilter = if (style.liquid) ColorFilter.colorMatrix(matrix) else null,
            // clipToBounds 必须放在 scale **之前**：1.35 倍放大是为了把模糊后发虚的边缘
            // 推出可视区，但 Modifier.scale 默认**不裁剪**内容，于是图会向左右各溢出
            // 17.5% 的页面宽度。播放页三层横向轮播时相邻层同时参与组合，这些溢出就盖到了
            // 邻层上（表现为一条只在中途出现的竖条）。裁剪矩形就是本层页面矩形，
            // 所以落定后的观感和原来完全一致。
            modifier = modifier
                .fillMaxSize()
                .clipToBounds()
                .scale(1.35f)
                .then(if (canBlur && blurDp > 0) Modifier.blur(blurDp.dp) else Modifier)
                .alpha(if (canBlur) artAlpha else (artAlpha * 0.45f))
        )
    }

    // 遮罩
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        skin.bg.copy(alpha = scrim),
                        skin.bg.copy(alpha = (scrim + 0.10f).coerceAtMost(0.97f)),
                        skin.bg.copy(alpha = (scrim + 0.22f).coerceAtMost(0.99f))
                    )
                )
            )
    )

    if (style.liquid) {
        // 顶部镜面高光
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.White.copy(alpha = 0.20f),
                        0.18f to Color.White.copy(alpha = 0.07f),
                        0.40f to Color.Transparent,
                        1.0f to Color.Transparent
                    )
                )
        )
        // 上边缘折射亮线
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    drawLine(
                        brush = Brush.horizontalGradient(
                            listOf(
                                Color.Transparent,
                                Color.White.copy(alpha = 0.30f),
                                Color.White.copy(alpha = 0.42f),
                                Color.Transparent
                            )
                        ),
                        start = Offset(0f, 0.5f),
                        end = Offset(size.width, 0.5f),
                        strokeWidth = 1.2.dp.toPx()
                    )
                }
        )
    }
}

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
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(skin.surface2)
            .padding(horizontal = 14.dp, vertical = 11.dp),
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
private fun SliderLine(
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
                positionMs = positionMs,
                fontSizeSp = fontSize,
                onSeek = onSeek
            )

            else -> PlainLyrics((lyrics as Lyrics.Plain).lines, fontSize)
        }
    }
}

/* ============================================================
 * 带时间轴：跟随播放高亮 + 自动居中
 * ============================================================ */

@Composable
internal fun SyncedLyrics(
    lines: List<LyricLine>,
    positionMs: Long,
    fontSizeSp: Int,
    onSeek: (Long) -> Unit
) {
    val skin = LocalSkin.current
    val listState = rememberLazyListState()
    val current = remember(positionMs, lines) { lines.indexAt(positionMs) }

    // 用户最近一次触摸屏幕的时间。
    //
    // 为什么需要它：`positionMs` 每 500ms 变一次，当前行一变就 animateScrollToItem，
    // 用户根本没法手动翻到别处 —— 刚滑走就被拽回来。
    // 这里记录触摸时刻，触摸后 6 秒内不自动跟随；之后就自然恢复（当前行还在继续变）。
    //
    // 用数组而不是 Compose state：它每帧都可能被写，但没有任何 composable 读它，
    // 走快照系统纯属浪费。
    val lastTouch = remember { longArrayOf(0L) }

    // 当前行变化时滚到中间（contentPadding 让首尾也能居中），但用户正在浏览时不打扰
    LaunchedEffect(current) {
        if (current < 0) return@LaunchedEffect
        if (android.os.SystemClock.uptimeMillis() - lastTouch[0] < 6_000L) return@LaunchedEffect
        runCatching { listState.animateScrollToItem(current) }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            // 只**观察**指针事件，不消费 —— LazyColumn 自己的滚动不受影响。
            // 用 PointerEventPass.Initial 在分发链最前面拿到，确保不会漏。
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        if (e.changes.any { it.pressed }) {
                            lastTouch[0] = android.os.SystemClock.uptimeMillis()
                        }
                    }
                }
            }
    ) {
        val centerPad = maxHeight * 0.34f
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 28.dp,
                end = 28.dp,
                top = centerPad,
                bottom = centerPad
            ),
            verticalArrangement = Arrangement.spacedBy((fontSizeSp * 0.62f).dp)
        ) {
            itemsIndexed(lines) { index, line ->
                val isCurrent = index == current
                // 有时间轴的行才可点：点了把播放进度跳到那一句
                val seekable = line.timeMs >= 0
                Text(
                    text = line.text,
                    fontSize = (fontSizeSp + if (isCurrent) 2 else 0).sp,
                    lineHeight = (fontSizeSp * 1.5f).sp,
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                    color = when {
                        isCurrent -> skin.accent
                        index < current -> skin.textMuted
                        else -> skin.textDim
                    },
                    textAlign = TextAlign.Center,
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
                )
            }
        }
    }
}

/* ============================================================
 * 纯文本
 * ============================================================ */

@Composable
internal fun PlainLyrics(lines: List<String>, fontSizeSp: Int) {
    val skin = LocalSkin.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy((fontSizeSp * 0.5f).dp)
    ) {
        itemsIndexed(lines) { _, line ->
            Text(
                text = line,
                fontSize = fontSizeSp.sp,
                lineHeight = (fontSizeSp * 1.5f).sp,
                color = skin.textDim,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
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
                if (onlineEnabled) {
                    PillButton(label = "手动搜索", onClick = onManualSearch)
                }
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
    onDismiss: () -> Unit,
    onConfirm: (title: String, artist: String) -> Unit
) {
    val skin = LocalSkin.current
    var title by remember { mutableStateOf(initialTitle) }
    var artist by remember { mutableStateOf(initialArtist) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = skin.surface2,
        titleContentColor = skin.text,
        textContentColor = skin.textDim,
        title = {
            Text("手动搜索歌词", style = MaterialTheme.typography.titleMedium, color = skin.text)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "默认填的是当前歌曲的标签信息。如果之前匹配到错误歌词，\n" +
                        "改一下歌名或歌手再搜一次，搜到会记住这次的写法。",
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
            }
        },
        confirmButton = {
            PillButton(
                label = "搜索",
                onClick = {
                    if (title.isNotBlank()) onConfirm(title.trim(), artist.trim())
                }
            )
        },
        dismissButton = {
            PillButton(label = "取消", onClick = onDismiss, filled = false)
        }
    )
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

    LaunchedEffect(song.id, song.uri, onlineEnabled, apiBase, convertT2S, neteaseFill) {
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
