package com.sonance.player.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.sonance.player.R
import com.sonance.player.SonanceViewModel
import com.sonance.player.data.GlowMode
import com.sonance.player.data.GlowTransition
import com.sonance.player.data.LyricPlaybarStyle
import com.sonance.player.data.OnlineDownloader
import com.sonance.player.data.netease.NeteaseLevel
import com.sonance.player.data.PlayButtonColorMode
import com.sonance.player.data.Prefs
import com.sonance.player.data.formatDuration
import com.sonance.player.data.formatSize
import com.sonance.player.ui.components.AdaptModeRow
import com.sonance.player.ui.components.ChipsRow
import com.sonance.player.ui.components.TagChip
import com.sonance.player.ui.components.rememberRemoteCover
import com.sonance.player.ui.components.GlassControls
import com.sonance.player.ui.components.PillButton
import com.sonance.player.ui.components.SliderLine
import com.sonance.player.ui.components.SonanceSlider
import com.sonance.player.ui.icon.SonanceIcons
import com.sonance.player.ui.sheets.NeteaseLoginSheet
import com.sonance.player.ui.sheets.ScanScopeSheet
import com.sonance.player.ui.theme.AppIconVariant
import com.sonance.player.ui.theme.AppIcons
import com.sonance.player.ui.theme.GlassStyle
import com.sonance.player.ui.theme.ListCoverScale
import com.sonance.player.ui.theme.ListRowStyle
import com.sonance.player.ui.theme.LocalSkin
import com.sonance.player.ui.theme.MiniPlayerStyle
import com.sonance.player.ui.theme.MiniProgressStyle
import com.sonance.player.ui.theme.MiniSwipeStyle
import com.sonance.player.ui.theme.Skin
import com.sonance.player.ui.theme.Skins
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 设置页顶部分类。
 *
 * 将原本平铺混排的 20 余项设置收敛为五大分类：
 * - [ALL]：按分类分组标题顺序展示全部模块；
 * - 其余分类：仅筛选展示对应分组，减少长页面翻找成本。
 */
private enum class SettingsCategory(
    val label: String,
    val subtitle: String,
    val icon: ImageVector
) {
    ALL("全部", "全部设置分组", SonanceIcons.GridView),
    APPEARANCE("外观界面", "主题皮肤、背景玻璃、图标、列表与播放条", SonanceIcons.Palette),
    PLAYBACK("播放音效", "均衡器、睡眠定时、播放键触感与黑胶视觉", SonanceIcons.Equalizer),
    LYRICS_COVER("歌词封面", "歌词样式、锁屏歌词、在线歌词与封面补全", SonanceIcons.Lyrics),
    LIBRARY_ONLINE("曲库在线", "本地扫描过滤、曲库去重、兼容性与在线音源", SonanceIcons.Folder),
    ABOUT("关于支持", "版本信息、基线导出与支持作者", SonanceIcons.Info)
}

@Composable
fun SettingsScreen(
    vm: SonanceViewModel,
    modifier: Modifier = Modifier,
    onOpenEqualizer: () -> Unit = {}
) {
    val context = LocalContext.current
    val skin = LocalSkin.current
    // 「扫描」不再占列表的 ⁝ 菜单：它是设置类操作，入口收进媒体库分组。
    // 这里点开的是同一个 ScanScopeSheet（沿用 Prefs 里记住的目录勾选）
    var showScanScope by remember { mutableStateOf(false) }
    var selectedCategory by rememberSaveable { mutableStateOf(SettingsCategory.ALL) }
    val scope = rememberCoroutineScope()

    // 进设置页时刷一下歌词与封面缓存统计，让折叠摘要直接显示最新数字
    LaunchedEffect(Unit) {
        vm.refreshLyricsCacheStats()
        vm.refreshCoverCacheStats()
    }

    // 解锁窗口到点自动收起。放一个 5 秒的轻量轮询，而不是在 ViewModel 里挂常驻定时器 ——
    // 这个区块只在设置页可见时才有意义，离开设置页就该停下
    LaunchedEffect(Unit) {
        while (true) {
            vm.refreshConversionUnlock()
            kotlinx.coroutines.delay(5_000L)
        }
    }

    val pageCategories = SettingsCategory.entries
    val pageScrolls = remember { pageCategories.map { ScrollState(0) } }
    val pagerState = rememberPagerState(initialPage = selectedCategory.ordinal) { pageCategories.size }

    // 横滑与点页签共用一份选中态：滑到哪一页，顶部就亮到哪一页
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            val cat = pageCategories[page.coerceIn(0, pageCategories.lastIndex)]
            if (cat != selectedCategory) selectedCategory = cat
        }
    }

    Column(modifier.fillMaxSize()) {
        Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
            SettingsCategoryBar(
                selected = selectedCategory,
                onSelect = { cat -> scope.launch { pagerState.animateScrollToPage(cat.ordinal) } },
                hasAnyExpanded = FoldState.expanded.values.any { it },
                onCollapseAll = { FoldState.collapseAll() }
            )
        }
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f)
        ) { page ->
            val safe = page.coerceIn(0, pageCategories.lastIndex)
            SettingsCategoryPage(
                category = pageCategories[safe],
                vm = vm,
                onOpenEqualizer = onOpenEqualizer,
                openScanScope = { showScanScope = true },
                scrollState = pageScrolls[safe]
            )
        }
    }

    // 面板与列表页原本用的是同一个 ScanScopeSheet，这里只是把入口换到了设置里
    if (showScanScope) {
        ScanScopeSheet(vm = vm, onDismiss = { showScanScope = false })
    }
}

/**
 * 单个设置分类页。
 *
 * 内容与原来的垂直长页一致，只是按分类拆开、每页各自持有滚动位置 ——
 * 左右滑页时不会把上一页的滚动偏移带过来。
 */
@Composable
private fun SettingsCategoryPage(
    category: SettingsCategory,
    vm: SonanceViewModel,
    onOpenEqualizer: () -> Unit,
    openScanScope: () -> Unit,
    scrollState: ScrollState
) {
    val skin = LocalSkin.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ============================================================
        // 一、外观与界面
        // ============================================================
        if (category == SettingsCategory.ALL || category == SettingsCategory.APPEARANCE) {
            SettingsCategoryHeader(
                category = SettingsCategory.APPEARANCE,
                topSpacing = if (category == SettingsCategory.ALL) 4.dp else 0.dp
            )

            CollapsibleSection(
                id = "skin",
                title = "外观皮肤",
                summary = "当前：${vm.skin.name} · 共 ${Skins.all.size} 套主题",
                icon = SonanceIcons.Palette
            ) {
                SkinGrid(current = vm.skin, onSelect = { vm.changeSkin(it) })
            }

            ShellBackgroundSection(vm = vm)

            IconSetSettingsSection(vm = vm)

            CollapsibleSection(
                id = "appIcon",
                title = "桌面应用图标",
                summary = "${AppIcons.all.size} 款图标 · 点击更换系统桌面图标",
                icon = SonanceIcons.GridView
            ) {
                Text(
                    "切换后桌面图标会在几秒内刷新；部分第三方桌面可能需要重启桌面。",
                    style = MaterialTheme.typography.labelSmall,
                    color = skin.textDim
                )
                Spacer(Modifier.height(12.dp))
                AppIconGrid(currentId = vm.appIconId, onSelect = { vm.changeAppIcon(it) })
            }

            ListAppearanceSection(vm = vm)

            MiniPlayerSettingsSection(vm = vm)

            CollapsibleSection(
                id = "widget",
                title = "桌面小组件",
                summary = "${WIDGET_STYLES.size} 种尺寸样式 · 长按桌面 → 小组件 添加",
                icon = SonanceIcons.Maximize
            ) {
                WidgetSettingsContent(vm = vm)
            }
        }

        // ============================================================
        // 二、播放与音效
        // ============================================================
        if (category == SettingsCategory.ALL || category == SettingsCategory.PLAYBACK) {
            SettingsCategoryHeader(
                category = SettingsCategory.PLAYBACK,
                topSpacing = if (category == SettingsCategory.ALL) 10.dp else 0.dp
            )

            PlaybackSettingsSection(
                vm = vm,
                onOpenEqualizer = onOpenEqualizer
            )

            AudioOutputSettingsSection(vm = vm)

            PlayControlSettingsSection(vm = vm)

            PlayerVisualsSettingsSection(vm = vm)
        }

        // ============================================================
        // 三、歌词与封面
        // ============================================================
        if (category == SettingsCategory.ALL || category == SettingsCategory.LYRICS_COVER) {
            SettingsCategoryHeader(
                category = SettingsCategory.LYRICS_COVER,
                topSpacing = if (category == SettingsCategory.ALL) 10.dp else 0.dp
            )

            LyricsDisplaySettingsSection(vm = vm)

            LyricsOnlineSettingsSection(vm = vm)

            CoverSettingsSection(vm = vm)
        }

        // ============================================================
        // 四、曲库与在线
        // ============================================================
        if (category == SettingsCategory.ALL || category == SettingsCategory.LIBRARY_ONLINE) {
            SettingsCategoryHeader(
                category = SettingsCategory.LIBRARY_ONLINE,
                topSpacing = if (category == SettingsCategory.ALL) 10.dp else 0.dp
            )

            MediaLibraryScanSection(
                vm = vm,
                onOpenScanScope = openScanScope
            )

            CollapsibleSection(
                id = "cleanup",
                title = "曲库清理与去重",
                summary = "扫描排除 ${vm.lastScanOutcome?.excludedTotal ?: 0} 项 · 已隐藏 ${vm.hiddenCount} 首 · 排除目录 ${vm.excludedDirs.size} 个",
                icon = SonanceIcons.Refresh,
                defaultExpanded = false
            ) {
                SettingsGroupLabel("曲库清理")
                LibraryCleanupContent(vm = vm)
                Spacer(Modifier.height(18.dp))
                SettingsGroupLabel("重复与同名歌曲")
                DuplicateCleanupContent(vm = vm)
            }

            CollapsibleSection(
                id = "overview",
                title = "曲库统计概览",
                summary = "${vm.songs.size} 首 · ${vm.albums.size} 张专辑 · ${vm.artists.size} 位艺术家 · ${formatSize(vm.totalSizeBytes)}",
                icon = SonanceIcons.Layers
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    StatBox("歌曲", "${vm.songs.size}", Modifier.weight(1f))
                    StatBox("专辑", "${vm.albums.size}", Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    StatBox("艺术家", "${vm.artists.size}", Modifier.weight(1f))
                    StatBox("收藏", "${vm.favorites.size}", Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    StatBox("总时长", formatDuration(vm.totalDurationMs), Modifier.weight(1f))
                    StatBox("占用空间", formatSize(vm.totalSizeBytes), Modifier.weight(1f))
                }
            }

            CollapsibleSection(
                id = "formatCompat",
                title = "格式与兼容性检查",
                summary = "系统解码支持范围 · 私有加密容器扫描",
                icon = SonanceIcons.Waveform,
                defaultExpanded = false
            ) {
                FormatCompatContent(vm = vm)
            }

            val onlineSummary = buildString {
                append(if (vm.onlineMusicEnabled) "在线搜索已开启" else "在线搜索关闭")
                if (vm.neteaseFillEnabled) append(" · 网易云补全")
                if (vm.onlineDownloadEnabled) append(" · 边听边存")
            }
            CollapsibleSection(
                id = "online",
                title = "在线音乐与音源插件",
                summary = onlineSummary,
                icon = SonanceIcons.Cloud
            ) {
                OnlineMusicContent(vm = vm)
            }
        }

        // ===== 私有格式还原 & 音乐缓存嗅探转换 =====
        // 默认隐藏。在「关于」里连点署名 10 次才会出现，且只保持一小时
        // （见 SonanceViewModel.tapAuthor / refreshConversionUnlock）
        if (vm.conversionUnlocked && (category == SettingsCategory.ALL ||
                category == SettingsCategory.LIBRARY_ONLINE || category == SettingsCategory.ABOUT)) {
            SettingsCategoryHeader(
                title = "格式还原与缓存嗅探",
                subtitle = "已临时解锁隐藏工具 · 1 小时后自动收起",
                icon = SonanceIcons.Refresh,
                topSpacing = 10.dp
            )
            RestoreSection(vm = vm)
            NeteaseCacheSection(vm = vm)
        }

        // ============================================================
        // 五、关于与支持
        // ============================================================
        if (category == SettingsCategory.ALL || category == SettingsCategory.ABOUT) {
            SettingsCategoryHeader(
                category = SettingsCategory.ABOUT,
                topSpacing = if (category == SettingsCategory.ALL) 10.dp else 0.dp
            )

            AboutSettingsSection(vm = vm)

            SystemControlsSection()

            CollapsibleSection(
                id = "donate",
                title = "支持作者",
                summary = "微信扫一扫 · 请作者喝杯饮料",
                icon = SonanceIcons.Heart,
                defaultExpanded = false
            ) {
                DonateCard()
            }
        }


        Spacer(Modifier.height(24.dp))
    }
}

/* ============================================================
 * 通知、播控与灵动岛
 * ============================================================ */

private data class SystemControlGuide(
    val brand: String,
    val steps: List<Pair<String, String>>
)

/**
 * 按本机品牌给出「怎么让播放信息出现在系统胶囊上」的路径。
 *
 * 各家的胶囊（小米超级岛、vivo 原子岛、OPPO 流体云、荣耀灵动胶囊）读的都是
 * 同一条标准媒体通知，所以 App 侧没有额外开关可加；能做的只有把通知本身做对，
 * 剩下的全是系统设置里的开关。这些路径分散在两三层菜单里，直接写出来更省事。
 */
private fun systemControlGuide(): SystemControlGuide {
    val vendor = (Build.MANUFACTURER + " " + Build.BRAND).lowercase()
    return when {
        vendor.contains("xiaomi") || vendor.contains("redmi") || vendor.contains("poco") ->
            SystemControlGuide(
                brand = "小米 / Redmi",
                steps = listOf(
                    "超级岛" to
                        "设置 → 通知与控制中心 → 应用通知 → Sonance → 允许通知，并打开「焦点通知」。标准媒体通知接入后系统会自动上岛。",
                    "妙享背屏" to
                        "设置 → 妙享背屏 → 打开音乐 / 通知显示（仅支持背屏的机型）。锁屏可见性已经设为公开，背屏才能拿到歌名和封面。"
                )
            )

        vendor.contains("vivo") || vendor.contains("iqoo") ->
            SystemControlGuide(
                brand = "vivo / iQOO",
                steps = listOf(
                    "原子岛总开关" to
                        "设置 → 通知与状态栏 → 原子通知（原子岛）→ 打开总开关，并在场景列表里勾上「音乐播放」。",
                    "把 Sonance 加进音源" to
                        "播放时点开原子随身听卡片 → 右上角设置 → 个性化设置 → 更多设置 → 音源应用管理 → 全部应用 → 勾选 Sonance。OriginOS 7 起开放了手动添加；更早的系统只认官方音源白名单，没有这个入口时只能等系统把本应用加入白名单。",
                    "应用通知" to
                        "设置 → 通知与状态栏 → 应用通知 → Sonance → 允许通知、允许锁屏通知。"
                )
            )

        vendor.contains("oppo") || vendor.contains("oneplus") || vendor.contains("realme") ->
            SystemControlGuide(
                brand = "OPPO / 一加 / realme",
                steps = listOf(
                    "流体云" to
                        "设置 → 通知与状态栏 → 流体云 → 允许 Sonance 在流体云显示。部分系统版本需要先允许「实时活动」。"
                )
            )

        vendor.contains("honor") ->
            SystemControlGuide(
                brand = "荣耀",
                steps = listOf(
                    "灵动胶囊" to
                        "设置 → 通知和状态栏 → 灵动胶囊 / YOYO 建议 → 允许 Sonance 显示。"
                )
            )

        vendor.contains("huawei") || vendor.contains("harmony") ->
            SystemControlGuide(
                brand = "华为",
                steps = listOf(
                    "实况窗 / 媒体卡片" to
                        "设置 → 通知和状态栏 → 应用通知 → Sonance → 允许通知。媒体播控由系统媒体卡片接管，部分版本需要打开「实况窗」。"
                )
            )

        else -> SystemControlGuide(
            brand = "标准媒体通知",
            steps = listOf(
                "系统媒体卡片" to
                    "Android 11 起状态栏媒体控件、锁屏播控都直接读取标准媒体通知；允许 Sonance 发送通知即可，不需要额外设置。"
            )
        )
    }
}

@Composable
private fun SystemControlsSection() {
    val context = LocalContext.current
    val skin = LocalSkin.current
    val notificationsOn = remember(context) {
        runCatching {
            androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
        }.getOrDefault(true)
    }
    val guide = remember { systemControlGuide() }

    CollapsibleSection(
        id = "system_controls",
        title = "通知、播控与灵动岛",
        summary = if (notificationsOn) "${guide.brand} · 查看开启路径"
        else "通知已关闭，系统胶囊读不到播放状态",
        icon = SonanceIcons.Waveform,
        defaultExpanded = false
    ) {
        Column(Modifier.padding(vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(if (notificationsOn) skin.accent else skin.danger)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (notificationsOn) "通知已开启，系统胶囊可以读取播放状态"
                    else "通知被关闭，请先允许 Sonance 发送通知",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (notificationsOn) skin.textMuted else skin.danger
                )
            }

            guide.steps.forEach { (title, body) ->
                Spacer(Modifier.height(12.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = skin.text
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    body,
                    style = MaterialTheme.typography.bodySmall,
                    color = skin.textMuted
                )
            }

            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(
                    label = "通知设置",
                    onClick = { openNotificationSettings(context) },
                    filled = false
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "开关改完不会立刻生效：切一次歌会重建通知，胶囊随后跟上。",
                style = MaterialTheme.typography.labelSmall,
                color = skin.textMuted.copy(alpha = 0.6f)
            )
        }
    }
}

private fun openNotificationSettings(context: android.content.Context) {
    val candidates = listOf(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}")),
        Intent(Settings.ACTION_SETTINGS)
    )
    for (intent in candidates) {
        val ok = runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        if (ok) return
    }
}

/* ============================================================
 * 分类导航栏与分类标题
 * ============================================================ */

@Composable
private fun SettingsCategoryBar(
    selected: SettingsCategory,
    onSelect: (SettingsCategory) -> Unit,
    hasAnyExpanded: Boolean,
    onCollapseAll: () -> Unit
) {
    val skin = LocalSkin.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SettingsCategory.entries.forEach { cat ->
                val isSelected = cat == selected
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (isSelected) skin.accent else skin.surface)
                        .border(
                            width = 1.dp,
                            color = if (isSelected) skin.accent else skin.border,
                            shape = RoundedCornerShape(20.dp)
                        )
                        .clickable { onSelect(cat) }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = cat.icon,
                        contentDescription = null,
                        tint = if (isSelected) skin.onAccent else skin.textMuted,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = cat.label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isSelected) skin.onAccent else skin.textDim
                    )
                }
            }
        }

        if (hasAnyExpanded) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(skin.surface2)
                    .border(1.dp, skin.border, RoundedCornerShape(20.dp))
                    .clickable(onClick = onCollapseAll)
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "收起",
                    style = MaterialTheme.typography.labelSmall,
                    color = skin.textMuted
                )
            }
        }
    }
}

@Composable
private fun SettingsCategoryHeader(
    category: SettingsCategory,
    topSpacing: androidx.compose.ui.unit.Dp = 8.dp
) {
    SettingsCategoryHeader(
        title = category.label,
        subtitle = category.subtitle,
        icon = category.icon,
        topSpacing = topSpacing
    )
}

@Composable
private fun SettingsCategoryHeader(
    title: String,
    subtitle: String,
    icon: ImageVector,
    topSpacing: androidx.compose.ui.unit.Dp = 8.dp
) {
    val skin = LocalSkin.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = topSpacing, start = 4.dp, end = 4.dp, bottom = 2.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                Modifier
                    .width(3.dp)
                    .height(14.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(skin.accent)
            )
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = skin.accent,
                modifier = Modifier.size(15.dp)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = skin.text
            )
            Text(
                text = "·  $subtitle",
                style = MaterialTheme.typography.labelSmall,
                color = skin.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/* ============================================================
 * 一、外观与界面：主界面背景、图标风格、列表外观、底部播放条
 * ============================================================ */

/**
 * 主界面背景与切歌过渡设置（合并原「主界面背景」与「背景切换」）。
 */
@Composable
private fun ShellBackgroundSection(vm: SonanceViewModel) {
    val skin = LocalSkin.current
    val transLabel = if (vm.artTransitionMode == "fade") "渐变 (${vm.artFadeMs}ms)" else "滑动切换"
    val summary = if (skin.immersive) {
        "封面玻璃：${vm.shellGlassStyle.label} · 切歌$transLabel"
    } else {
        "纯色背景（非沉浸皮肤） · 切歌$transLabel"
    }

    CollapsibleSection(
        id = "shellBg",
        title = "主界面背景与过渡",
        summary = summary,
        icon = SonanceIcons.Sun
    ) {
        // ---- 换歌时的背景过渡 ----
        SettingsGroupLabel("切歌背景过渡")
        Text(
            "换歌时主界面玻璃背景怎么过渡：左右滑动（播放页轮播同款）或直接渐变。",
            style = MaterialTheme.typography.bodySmall,
            color = skin.textMuted
        )
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("slide" to "滑动切换", "fade" to "渐变切换").forEach { (id, label) ->
                val on = vm.artTransitionMode == id
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (on) skin.accentGlow else skin.surface2)
                        .border(
                            width = if (on) 1.dp else 0.dp,
                            color = if (on) skin.accent else Color.Transparent,
                            shape = RoundedCornerShape(10.dp)
                        )
                        .clickable { vm.changeArtTransitionMode(id) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (on) skin.accent else skin.textMuted
                    )
                }
            }
        }
        if (vm.artTransitionMode == "fade") {
            Spacer(Modifier.height(14.dp))
            Text(
                "渐变过渡时长：${vm.artFadeMs} 毫秒",
                style = MaterialTheme.typography.bodySmall,
                color = skin.textMuted
            )
            SonanceSlider(
                // SonanceSlider 只吃 0~1 归一化值 —— 之前直接传毫秒被钳到 1.0，
                // 拖动换算回 0 又被 coerce 弹回下限，表现为「滑杆无法调整」
                value = (vm.artFadeMs - Prefs.MIN_ART_FADE_MS).toFloat() /
                    (Prefs.MAX_ART_FADE_MS - Prefs.MIN_ART_FADE_MS).toFloat(),
                onValueChange = { f ->
                    val ms = Prefs.MIN_ART_FADE_MS +
                        (f * (Prefs.MAX_ART_FADE_MS - Prefs.MIN_ART_FADE_MS)).toInt()
                    vm.changeArtFadeMs(ms / 50 * 50) // 步进 50ms，数值干净
                },
                modifier = Modifier.fillMaxWidth(),
                activeColor = skin.accent,
                inactiveColor = skin.surface3
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "快（200ms）",
                    style = MaterialTheme.typography.labelSmall,
                    color = skin.textMuted
                )
                Text(
                    "慢（1500ms）",
                    style = MaterialTheme.typography.labelSmall,
                    color = skin.textMuted
                )
            }
        }

        // ---- 沉浸皮肤：主界面封面玻璃 ----
        Spacer(Modifier.height(18.dp))
        SettingsGroupLabel("主界面封面玻璃")
        if (skin.immersive) {
            Text(
                "主界面铺的这层封面玻璃：样式决定质感（液态玻璃 = 更强模糊 + 更饱和），" +
                    "模糊调细节，可见度调封面透出来的浓淡。\n" +
                    "选「关闭」退回纯色深底。无论怎么调，背景亮度都会被统一压到恒定的深色档，" +
                    "组件和文字不会跟着变。",
                style = MaterialTheme.typography.bodySmall,
                color = skin.textMuted
            )
            Spacer(Modifier.height(8.dp))
            GlassControls(
                style = vm.shellGlassStyle,
                blurDp = vm.shellGlassBlur,
                visibility = vm.shellGlassVisibility,
                onStyleChange = { vm.changeShellGlassStyle(it) },
                onBlurChange = { vm.changeShellGlassBlur(it) },
                onVisibilityChange = { vm.changeShellGlassVisibility(it) }
            )
        } else {
            Text(
                "当前使用的是非沉浸式纯色皮肤。切换到沉浸式皮肤后，可在此调节主界面封面毛玻璃 / 液态玻璃的模糊度与透出强度。",
                style = MaterialTheme.typography.bodySmall,
                color = skin.textDim
            )
        }
    }
}

/**
 * 底部那个「正在播放」的形态与质感。
 */
@Composable
private fun MiniPlayerSettingsSection(vm: SonanceViewModel) {
    val skin = LocalSkin.current
    val float = vm.miniPlayerStyle == MiniPlayerStyle.FLOAT
    val summary = buildString {
        append("当前：").append(vm.miniPlayerStyle.label)
        if (float && vm.miniGlassStyle != GlassStyle.OFF) {
            append(" · ").append(vm.miniGlassStyle.label)
        }
        if (float) {
            val prog = when (vm.miniProgressStyle) {
                MiniProgressStyle.RING -> "圆环进度"
                MiniProgressStyle.BAR -> "长条进度"
                MiniProgressStyle.BOTH -> "圆环+长条"
            }
            val swipe = when (vm.miniSwipeStyle) {
                MiniSwipeStyle.WHOLE_CARD -> "整卡滑动"
                MiniSwipeStyle.INNER_SLOT -> "槽内滑动"
            }
            append(" · ").append(prog).append(" · ").append(swipe)
        }
    }
    CollapsibleSection(
        id = "miniPlayer",
        title = "底部播放条",
        summary = summary,
        icon = SonanceIcons.Music
    ) {
        Text(
            "「悬浮卡片」离屏边有间距、大圆角带投影，可以叠毛玻璃 / 液态玻璃；" +
                "「经典贴底」是原来那条通栏。两种都能用，随时可切。",
            style = MaterialTheme.typography.labelSmall,
            color = skin.textDim
        )
        Spacer(Modifier.height(12.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MiniPlayerStyle.entries.forEach { s ->
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (s == vm.miniPlayerStyle) skin.accentGlow else skin.surface2)
                        .border(
                            width = if (s == vm.miniPlayerStyle) 1.dp else 0.dp,
                            color = if (s == vm.miniPlayerStyle) skin.accent else Color.Transparent,
                            shape = RoundedCornerShape(10.dp)
                        )
                        .clickable { vm.changeMiniPlayerStyle(s) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        s.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (s == vm.miniPlayerStyle) skin.accent else skin.textMuted
                    )
                }
            }
        }

        if (float) {
            Spacer(Modifier.height(14.dp))
            GlassControls(
                style = vm.miniGlassStyle,
                blurDp = vm.miniGlassBlur,
                visibility = vm.miniGlassVisibility,
                onStyleChange = vm::applyMiniGlassStyle,
                onBlurChange = vm::changeMiniGlassBlur,
                onVisibilityChange = vm::changeMiniGlassVisibility,
                modifier = Modifier.fillMaxWidth()
            )
            SliderLine(
                label = "不透明度",
                valueText = "${vm.miniCardOpacity}%",
                value = vm.miniCardOpacity / 100f
            ) { f -> vm.changeMiniCardOpacity((f * 100).roundToInt()) }

            // ---- 进度显示：环 / 线 / 两者 ----
            Spacer(Modifier.height(14.dp))
            Text("进度显示", style = MaterialTheme.typography.titleSmall, color = skin.text)
            Spacer(Modifier.height(2.dp))
            Text(
                "圆环贴在播放键上，长条贴在卡片下沿；同时开两者会重复。",
                style = MaterialTheme.typography.bodySmall,
                color = skin.textMuted
            )
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MiniProgressStyle.entries.forEach { s ->
                    val on = s == vm.miniProgressStyle
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (on) skin.accentGlow else skin.surface2)
                            .border(
                                width = if (on) 1.dp else 0.dp,
                                color = if (on) skin.accent else Color.Transparent,
                                shape = RoundedCornerShape(10.dp)
                            )
                            .clickable { vm.changeMiniProgressStyle(s) }
                            .padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = when (s) {
                                MiniProgressStyle.RING -> "圆环"
                                MiniProgressStyle.BAR -> "长条"
                                MiniProgressStyle.BOTH -> "两者"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (on) skin.accent else skin.textMuted
                        )
                    }
                }
            }

            // ---- 卡片高度 / 封面大小 ----
            Spacer(Modifier.height(14.dp))
            Text("卡片尺寸", style = MaterialTheme.typography.titleSmall, color = skin.text)
            Spacer(Modifier.height(2.dp))
            Text(
                "高度只加卡片上下的留白，字和按钮大小不变；封面单独缩放。",
                style = MaterialTheme.typography.bodySmall,
                color = skin.textMuted
            )
            Spacer(Modifier.height(8.dp))
            SliderLine(
                label = "高度",
                valueText = String.format("%.2f×", vm.miniHeightScale / 100f),
                value = (vm.miniHeightScale - 100) / 100f
            ) { f -> vm.changeMiniHeightScale((100 + f * 100).roundToInt()) }
            SliderLine(
                label = "封面",
                valueText = String.format("%.2f×", vm.miniCoverScale / 100f),
                value = (vm.miniCoverScale - 100) / 100f
            ) { f -> vm.changeMiniCoverScale((100 + f * 100).roundToInt()) }

            // ---- 滑动切歌动效（整卡 / 槽内）----
            Spacer(Modifier.height(14.dp))
            Text("滑动切歌动效", style = MaterialTheme.typography.titleSmall, color = skin.text)
            Spacer(Modifier.height(2.dp))
            Text(
                "「整卡滑动」整张卡片带投影飞出；「槽内滑动」保留底座外框，卡片在槽内带圆角渐隐滑动。",
                style = MaterialTheme.typography.bodySmall,
                color = skin.textMuted
            )
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MiniSwipeStyle.entries.forEach { s ->
                    val on = s == vm.miniSwipeStyle
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (on) skin.accentGlow else skin.surface2)
                            .border(
                                width = if (on) 1.dp else 0.dp,
                                color = if (on) skin.accent else Color.Transparent,
                                shape = RoundedCornerShape(10.dp)
                            )
                            .clickable { vm.changeMiniSwipeStyle(s) }
                            .padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = when (s) {
                                MiniSwipeStyle.WHOLE_CARD -> "整卡滑动（默认）"
                                MiniSwipeStyle.INNER_SLOT -> "槽内滑动"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (on) skin.accent else skin.textMuted
                        )
                    }
                }
            }

            // ---- 滑动切歌阈值（左右分开）----
            Spacer(Modifier.height(14.dp))
            Text("滑动切歌阈值", style = MaterialTheme.typography.titleSmall, color = skin.text)
            Spacer(Modifier.height(2.dp))
            Text(
                "拖动多远才切歌（占卡片宽度的比例）。右手操作时把「右滑」调低会更好切。",
                style = MaterialTheme.typography.bodySmall,
                color = skin.textMuted
            )
            Spacer(Modifier.height(8.dp))
            SliderLine(
                label = "左滑",
                valueText = "${vm.miniSwipeLeftPct}%",
                value = (vm.miniSwipeLeftPct - 5) / 55f
            ) { f -> vm.changeMiniSwipeLeftPct((5 + f * 55).roundToInt()) }
            SliderLine(
                label = "右滑",
                valueText = "${vm.miniSwipeRightPct}%",
                value = (vm.miniSwipeRightPct - 5) / 55f
            ) { f -> vm.changeMiniSwipeRightPct((5 + f * 55).roundToInt()) }
        }
    }
}

/**
 * 全局图标套系：原型原版 / 极简线条 / 实心圆润。
 */
@Composable
private fun IconSetSettingsSection(vm: SonanceViewModel) {
    val skin = LocalSkin.current
    val styleName = when (vm.iconSet) {
        1 -> "极简线条"
        2 -> "实心圆润"
        else -> "原型原版"
    }
    CollapsibleSection(
        id = "iconSet",
        title = "应用内图标风格",
        summary = "当前：$styleName · 播放页、列表、队列、导航共用",
        icon = SonanceIcons.Layers
    ) {
        Text(
            "原型播放页里的图标做了三套：默认按 HTML 原样；极简线条去掉细节；" +
                "实心圆润加重块面。切换后播放页、列表、队列、导航一起换，立即生效。",
            style = MaterialTheme.typography.bodySmall,
            color = skin.textMuted
        )
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                0 to "原型原版",
                1 to "极简线条",
                2 to "实心圆润"
            ).forEach { (id, label) ->
                val on = id == vm.iconSet
                val (prev, heart, queue) = SonanceIcons.previewSet(id)
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (on) skin.accentGlow else skin.surface2)
                        .border(
                            width = if (on) 1.dp else 0.dp,
                            color = if (on) skin.accent else Color.Transparent,
                            shape = RoundedCornerShape(10.dp)
                        )
                        .clickable { vm.changeIconSet(id) }
                        .padding(vertical = 9.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(prev, null, tint = skin.text, modifier = Modifier.size(16.dp))
                        Icon(heart, null, tint = skin.text, modifier = Modifier.size(16.dp))
                        Icon(queue, null, tint = skin.text, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.height(5.dp))
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (on) skin.accent else skin.textMuted
                    )
                }
            }
        }
    }
}

/**
 * 列表外观：行与行之间怎么分开、封面大小与右侧快速索引。
 */
@Composable
private fun ListAppearanceSection(vm: SonanceViewModel) {
    val skin = LocalSkin.current
    val summary = "${vm.listRowStyle.label} · 封面${vm.listCoverScale.label} · " +
        if (vm.rightRailLetters) "A–Z 字母索引" else "右侧滑块"

    CollapsibleSection(
        id = "listAppearance",
        title = "列表外观与索引",
        summary = summary,
        icon = SonanceIcons.ListView
    ) {
        Text(
            "行数多的时候容易看串行。默认不加线，保留原来那种靠留白区分的清爽感。",
            style = MaterialTheme.typography.bodySmall,
            color = skin.textMuted
        )
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ListRowStyle.entries.forEach { s ->
                val on = s == vm.listRowStyle
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (on) skin.accentGlow else skin.surface2)
                        .border(
                            width = if (on) 1.dp else 0.dp,
                            color = if (on) skin.accent else Color.Transparent,
                            shape = RoundedCornerShape(10.dp)
                        )
                        .clickable { vm.changeListRowStyle(s) }
                        .padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        s.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (on) skin.accent else skin.textMuted
                    )
                }
            }
        }

        // ---- 封面大小 ----
        Spacer(Modifier.height(16.dp))
        Text("封面大小", style = MaterialTheme.typography.titleSmall, color = skin.text)
        Spacer(Modifier.height(2.dp))
        Text(
            "放大后行会跟着变高，文字仍然居中、左右位置不变。",
            style = MaterialTheme.typography.bodySmall,
            color = skin.textMuted
        )
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ListCoverScale.entries.forEach { s ->
                val on = s == vm.listCoverScale
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (on) skin.accentGlow else skin.surface2)
                        .border(
                            width = if (on) 1.dp else 0.dp,
                            color = if (on) skin.accent else Color.Transparent,
                            shape = RoundedCornerShape(10.dp)
                        )
                        .clickable { vm.changeListCoverScale(s) }
                        .padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        s.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (on) skin.accent else skin.textMuted
                    )
                }
            }
        }

        // ---- 右侧轨道：字母索引 / 比例滑块 ----
        Spacer(Modifier.height(16.dp))
        SettingsSwitchRow(
            title = "字母索引",
            subtitle = "全部歌曲右侧显示 A–Z，点击或滑动直接跳到该字母；关闭则改用滑块拖动",
            checked = vm.rightRailLetters,
            onCheckedChange = vm::changeRightRailLetters
        )

        // ---- 滚动条震动反馈 ----
        Spacer(Modifier.height(8.dp))
        SettingsSwitchRow(
            title = "滚动条震动",
            subtitle = "按住右侧滑块上下拖动时，每跨过一段轻震一下",
            checked = vm.scrollHaptic,
            onCheckedChange = vm::changeScrollHaptic
        )
    }
}

/* ============================================================
 * 二、播放与音效：均衡器/睡眠定时、播放键控制、播放页晕染与黑胶
 * ============================================================ */

/** 设置页里可选的倒计时时长（分钟） */
private val SETTINGS_SLEEP_MINUTES = listOf(15, 30, 45, 60, 90)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlaybackSettingsSection(vm: SonanceViewModel, onOpenEqualizer: () -> Unit) {
    val skin = LocalSkin.current
    val timerLabel = vm.sleepTimerLabel
    val eqSummary = if (vm.eqEnabled) {
        "均衡器：${vm.eqPreset.label}" + (if (vm.eqCustom) "（微调）" else "")
    } else {
        "均衡器关闭"
    }
    val sleepSummary = timerLabel?.let { "睡眠定时：剩余 $it" } ?: "睡眠定时未开启"

    CollapsibleSection(
        id = "playback",
        title = "均衡器与睡眠定时",
        summary = "$eqSummary · $sleepSummary",
        icon = SonanceIcons.Equalizer
    ) {
        // ---- 均衡器 ----
        SettingsActionRow(
            title = "均衡器",
            subtitle = if (vm.eqEnabled) {
                "已开启 · ${vm.eqPreset.label}" +
                    (if (vm.eqCustom) "（手动微调）" else "") +
                    " · ${vm.eqCenterFreqs.size} 段"
            } else {
                "10 段软件均衡器 · 全机型可用，不影响原始文件"
            },
            onClick = onOpenEqualizer,
            actionLabel = "调节"
        )

        Spacer(Modifier.height(16.dp))

        // ---- 睡眠定时器 ----
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("睡眠定时器", style = MaterialTheme.typography.titleSmall, color = skin.text)
                Spacer(Modifier.height(2.dp))
                Text(
                    timerLabel?.let { "计时中 · 剩余 $it，到点自动暂停" }
                        ?: "到点自动暂停播放，适合睡前听",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (timerLabel != null) skin.accent else skin.textMuted
                )
            }
            if (timerLabel != null) {
                PillButton(
                    label = "取消",
                    onClick = {
                        vm.clearSleepTimer()
                        vm.showToast("已取消睡眠定时器")
                    },
                    filled = false
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SETTINGS_SLEEP_MINUTES.forEach { m ->
                val active =
                    vm.sleepTimerMode == SonanceViewModel.SleepTimerMode.COUNTDOWN &&
                        vm.sleepTimerTotalMinutes == m
                SleepChip(
                    label = if (m >= 60) "${m / 60} 小时" else "$m 分钟",
                    selected = active
                ) { vm.setSleepTimer(m) }
            }
            SleepChip(
                label = "播完当前",
                selected = vm.sleepTimerMode == SonanceViewModel.SleepTimerMode.AFTER_CURRENT
            ) { vm.setSleepAfterCurrent() }
        }
    }
}

/**
 * 播放输出与衔接：USB 独占（bit-perfect）、无缝播放、淡入淡出、播放提示。
 */
@Composable
private fun AudioOutputSettingsSection(vm: SonanceViewModel) {
    val summary = buildString {
        append(if (vm.usbExclusiveEnabled) "USB 独占开" else "USB 独占关")
        append(if (vm.gaplessEnabled) " · 无缝" else " · 曲间停顿")
        if (vm.crossfadeEnabled) append(" · 淡化 ${vm.crossfadeSec} 秒")
        if (vm.playToastEnabled) append(" · 播放提示开")
    }
    CollapsibleSection(
        id = "audioOutput",
        title = "USB 独占 · 无缝 · 淡入淡出",
        summary = summary,
        icon = SonanceIcons.Volume
    ) {
        SettingsSwitchRow(
            title = "USB 独占输出",
            subtitle = "bit-perfect 直通 USB DAC / 耳机，绕过系统混音器（需 Android 14+，参考海贝音乐）",
            checked = vm.usbExclusiveEnabled,
            onCheckedChange = { vm.changeUsbExclusive(it) }
        )
        SettingsSwitchRow(
            title = "无缝播放",
            subtitle = "曲目之间无间隙衔接；关闭后自动续播时插入短暂停顿",
            checked = vm.gaplessEnabled,
            onCheckedChange = { vm.changeGapless(it) }
        )
        SettingsSwitchRow(
            title = "淡入淡出",
            subtitle = "曲目结束前音量渐弱、新曲渐强，切歌更柔和",
            checked = vm.crossfadeEnabled,
            onCheckedChange = { vm.changeCrossfade(it) }
        )
        if (vm.crossfadeEnabled) {
            Spacer(Modifier.height(4.dp))
            SliderLine(
                label = "淡化时长",
                valueText = "${vm.crossfadeSec} 秒",
                value = (vm.crossfadeSec - 1) / 9f
            ) { f -> vm.changeCrossfadeSec((1 + f * 9).roundToInt()) }
        }
        SettingsSwitchRow(
            title = "播放提示",
            subtitle = "切歌 / 起播时弹出「正在播放 · 歌名」提示",
            checked = vm.playToastEnabled,
            onCheckedChange = { vm.changePlayToast(it) }
        )
    }
}

/**
 * 播放控制：播放/暂停键的触感与外观（图标样式 / 图标大小 / 光晕 / 震动）。
 */
@Composable
private fun PlayControlSettingsSection(vm: SonanceViewModel) {
    val skin = LocalSkin.current
    val styleName = when (vm.playBtnIconStyle) {
        1 -> "液态"
        2 -> "极简"
        else -> "经典"
    }
    val summary = "${vm.playBtnColorMode.label}配色 · ${styleName}图标 (${vm.playBtnIconScale}%)" +
        if (vm.playBtnHaptics) " · 震动开启" else ""

    CollapsibleSection(
        id = "playControl",
        title = "播放按钮与触感",
        summary = summary,
        icon = SonanceIcons.Play
    ) {
        Text(
            "全屏播放页的播放/暂停键：点击有震动反馈（可关），" +
                "播放时光晕呼吸、图标在播放与暂停间弹簧变形。",
            style = MaterialTheme.typography.bodySmall,
            color = skin.textMuted
        )
        Spacer(Modifier.height(10.dp))

        Text("播放／暂停按钮配色", style = MaterialTheme.typography.titleSmall, color = skin.text)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PlayButtonColorMode.entries.forEach { mode ->
                val selected = mode == vm.playBtnColorMode
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (selected) skin.accentGlow else skin.surface2)
                        .border(
                            if (selected) 1.dp else 0.dp,
                            if (selected) skin.accent else Color.Transparent,
                            RoundedCornerShape(10.dp)
                        )
                        .clickable { vm.changePlayBtnColorMode(mode) }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        mode.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) skin.accent else skin.textMuted
                    )
                }
            }
        }
        Text(
            "播放主界面、列表页与歌词悬浮播放器共用配色。当前风格保留蓝紫粉渐变；主题和封面模式生成协调渐变，切换时平滑过渡。",
            style = MaterialTheme.typography.labelSmall,
            color = skin.textDim,
            modifier = Modifier.padding(top = 6.dp, bottom = 12.dp)
        )

        // ---- 图标样式（三种）----
        Text("图标样式", style = MaterialTheme.typography.titleSmall, color = skin.text)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                0 to "经典",
                1 to "液态",
                2 to "极简"
            ).forEach { (id, label) ->
                val on = id == vm.playBtnIconStyle
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (on) skin.accentGlow else skin.surface2)
                        .border(
                            width = if (on) 1.dp else 0.dp,
                            color = if (on) skin.accent else Color.Transparent,
                            shape = RoundedCornerShape(10.dp)
                        )
                        .clickable { vm.changePlayBtnIconStyle(id) }
                        .padding(vertical = 9.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    PlayIconStylePreview(style = id, color = skin.text)
                    Spacer(Modifier.height(5.dp))
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (on) skin.accent else skin.textMuted
                    )
                }
            }
        }

        // ---- 图标大小 ----
        Spacer(Modifier.height(12.dp))
        SliderLine(
            label = "图标",
            valueText = "${vm.playBtnIconScale}%",
            value = (vm.playBtnIconScale - 60f) / 110f
        ) { f -> vm.changePlayBtnIconScale((60 + f * 110).roundToInt()) }

        // ---- 光晕 ----
        Spacer(Modifier.height(4.dp))
        SettingsSwitchRow(
            title = "光晕",
            subtitle = "播放键外圈的渐变光斑，播放时随节奏呼吸",
            checked = vm.playBtnGlow,
            onCheckedChange = { vm.changePlayBtnGlow(it) }
        )

        // ---- 震动反馈 ----
        SettingsSwitchRow(
            title = "震动反馈",
            subtitle = "点击播放/暂停时触发手机的触感震动",
            checked = vm.playBtnHaptics,
            onCheckedChange = { vm.changePlayBtnHaptics(it) }
        )
    }
}

/**
 * 播放页视觉与黑胶模式（从原「封面」模块中拆出，专管播放页背景晕染、黑碟样式与机械音效）。
 */
@Composable
private fun PlayerVisualsSettingsSection(vm: SonanceViewModel) {
    val skin = LocalSkin.current
    val summary = buildString {
        append("晕染：").append(vm.glowMode.label)
        append(" · ")
        append(if (vm.vinylMode) "黑碟旋转已开启" else "标准方角封面")
        if (vm.vinylSfxEnabled) append(" · 机械音效")
    }

    CollapsibleSection(
        id = "playerVisuals",
        title = "播放页视觉与黑胶",
        summary = summary,
        icon = SonanceIcons.Album
    ) {
        // ---- 播放页的颜色晕染 ----
        SettingsGroupLabel("封面颜色晕染")
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("晕染强度", style = MaterialTheme.typography.labelMedium, color = skin.textMuted)
            Text(
                text = "%.2f".format(vm.glowStrength),
                style = MaterialTheme.typography.labelMedium,
                color = skin.accent
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = "把封面颜色渗一点到播放页背景上，颜色取自封面下边缘。在播放页双击封面也能调。",
            style = MaterialTheme.typography.labelSmall,
            color = skin.textDim
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlowMode.entries.forEach { m ->
                val selected = m == vm.glowMode
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (selected) skin.accentGlow else skin.surface2)
                        .border(
                            width = if (selected) 1.dp else 0.dp,
                            color = if (selected) skin.accent else Color.Transparent,
                            shape = RoundedCornerShape(10.dp)
                        )
                        .clickable { vm.changeGlowMode(m) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = m.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) skin.accent else skin.textDim
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = vm.glowMode.hint,
            style = MaterialTheme.typography.labelSmall,
            color = skin.textDim
        )
        Slider(
            value = vm.glowStrength,
            onValueChange = { vm.changeGlowStrength(it) },
            valueRange = Prefs.MIN_GLOW_STRENGTH..Prefs.MAX_GLOW_STRENGTH,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
        )

        // ---- 换歌时的过渡方式 ----
        Spacer(Modifier.height(14.dp))
        Text("换歌晕染过渡", style = MaterialTheme.typography.labelMedium, color = skin.textMuted)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlowTransition.entries.forEach { t ->
                val selected = t == vm.glowTransition
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (selected) skin.accentGlow else skin.surface2)
                        .border(
                            width = if (selected) 1.dp else 0.dp,
                            color = if (selected) skin.accent else Color.Transparent,
                            shape = RoundedCornerShape(10.dp)
                        )
                        .clickable { vm.changeGlowTransition(t) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = t.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) skin.accent else skin.textDim
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = vm.glowTransition.hint,
            style = MaterialTheme.typography.labelSmall,
            color = skin.textDim
        )

        if (vm.glowTransition == GlowTransition.FADE) {
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("渐变时长", style = MaterialTheme.typography.labelMedium, color = skin.textMuted)
                Text(
                    text = "${vm.glowFadeMs}ms",
                    style = MaterialTheme.typography.labelMedium,
                    color = skin.accent
                )
            }
            Slider(
                value = vm.glowFadeMs.toFloat(),
                onValueChange = { vm.changeGlowFadeMs(it.roundToInt()) },
                valueRange = Prefs.MIN_GLOW_FADE_MS.toFloat()..Prefs.MAX_GLOW_FADE_MS.toFloat(),
                modifier = Modifier.fillMaxWidth()
            )
        }

        // ===== 黑碟播放器与机械音效 =====
        Spacer(Modifier.height(16.dp))
        SettingsGroupLabel("黑胶模式与音效")
        SettingsSwitchRow(
            title = "黑碟播放器",
            subtitle = "封面变圆形黑胶唱片（原色），外圈裹一层黑色黑碟，播放时缓慢旋转，" +
                "参考网易云音乐的黑胶样式。关闭则恢复方角封面。",
            checked = vm.vinylMode,
            onCheckedChange = { vm.changeVinylMode(it) }
        )

        SettingsSwitchRow(
            title = "黑胶机械音效",
            subtitle = "沉浸黑胶页里的按键「啪嗒」声、唱针落片与炒豆底噪。" +
                "切歌抬臂时音乐会做 150ms 短淡出，与音效衔接更温润。",
            checked = vm.vinylSfxEnabled,
            onCheckedChange = { vm.changeVinylSfxEnabled(it) }
        )
        if (vm.vinylSfxEnabled) {
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("音效音量", style = MaterialTheme.typography.labelMedium, color = skin.textMuted)
                Text(
                    text = "${(vm.vinylSfxVolume * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = skin.accent
                )
            }
            Slider(
                value = vm.vinylSfxVolume,
                onValueChange = { vm.changeVinylSfxVolume(it) },
                valueRange = 0f..1f,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** 「图标样式」选项卡里的小预览图标（暂停态的双杠形状，静态绘制） */
@Composable
private fun PlayIconStylePreview(style: Int, color: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(width = 20.dp, height = 20.dp)) {
        val w = size.width
        val h = size.height
        val params = when (style) {
            2 -> Triple(0.12f, 0.5f, 0.12f)   // 极简：细杆
            1 -> Triple(0.20f, 0.72f, 0.5f)   // 液态：粗圆杆
            else -> Triple(0.18f, 0.66f, 0.3f) // 经典
        }
        val (barWk, hk, rk) = params
        val barW = w * barWk
        drawRoundRect(
            color,
            topLeft = androidx.compose.ui.geometry.Offset(w * 0.24f, h * (1f - hk) / 2f + h * 0.05f),
            size = androidx.compose.ui.geometry.Size(barW, h * hk),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW * rk)
        )
        drawRoundRect(
            color,
            topLeft = androidx.compose.ui.geometry.Offset(w * 0.58f, h * (1f - hk) / 2f + h * 0.05f),
            size = androidx.compose.ui.geometry.Size(barW, h * hk),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW * rk)
        )
    }
}

/** 睡眠定时器的小胶囊选项（设置页用，比面板里的窄一点） */
@Composable
private fun SleepChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val skin = LocalSkin.current
    Box(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) skin.accent else skin.surface2)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) skin.onAccent else skin.textDim
        )
    }
}

/* ============================================================
 * 三、歌词与封面：歌词显示样式、在线歌词、在线封面补全
 * ============================================================ */

/**
 * 歌词显示与外观（字号、繁简转换、锁屏歌词、歌词页底部播放器、背景玻璃与对比度适配）。
 */
@Composable
private fun LyricsDisplaySettingsSection(vm: SonanceViewModel) {
    val skin = LocalSkin.current
    val summary = buildString {
        append("字号 ${vm.lyricsFontSizeSp}sp")
        append(" · 背景${vm.glassStyle.label}")
        if (vm.lockScreenLyrics) append(" · 锁屏歌词开")
    }

    CollapsibleSection(
        id = "lyricsDisplay",
        title = "歌词显示与外观",
        summary = summary,
        icon = SonanceIcons.Lyrics
    ) {
        // ---- 字号 ----
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "歌词字号",
                    style = MaterialTheme.typography.titleSmall,
                    color = skin.text
                )
                Text(
                    "${vm.lyricsFontSizeSp} sp（${Prefs.MIN_LYRICS_FONT}–${Prefs.MAX_LYRICS_FONT}）",
                    style = MaterialTheme.typography.bodySmall,
                    color = skin.textMuted
                )
            }
            Text("A", style = MaterialTheme.typography.labelSmall, color = skin.textMuted)
            SonanceSlider(
                value = (vm.lyricsFontSizeSp - Prefs.MIN_LYRICS_FONT).toFloat() /
                    (Prefs.MAX_LYRICS_FONT - Prefs.MIN_LYRICS_FONT).toFloat(),
                onValueChange = { f ->
                    val v = Prefs.MIN_LYRICS_FONT +
                        (f * (Prefs.MAX_LYRICS_FONT - Prefs.MIN_LYRICS_FONT)).toInt()
                    vm.changeLyricsFontSize(v)
                },
                modifier = Modifier
                    .weight(1f)
                    .height(28.dp),
                trackHeight = 3.dp,
                thumbSize = 12.dp,
                alwaysShowThumb = true
            )
            Text(
                "A",
                style = MaterialTheme.typography.titleSmall,
                color = skin.textMuted
            )
        }

        Spacer(Modifier.height(8.dp))
        SettingsSwitchRow(
            title = "繁体转简体",
            subtitle = "在线歌词源常返回繁体，打开后显示时自动转简体。\n" +
                "只影响显示，缓存里仍存原文，关掉开关无需重新联网。",
            checked = vm.lyricsConvertT2S,
            onCheckedChange = { vm.changeLyricsT2S(it) }
        )

        Spacer(Modifier.height(8.dp))
        SettingsSwitchRow(
            title = "锁屏歌词",
            subtitle = "把逐行歌词交给系统锁屏显示（ColorOS / OPlus 支持；其他机型会自动忽略）",
            checked = vm.lockScreenLyrics,
            onCheckedChange = vm::changeLockScreenLyrics
        )

        Spacer(Modifier.height(8.dp))
        SettingsSwitchRow(
            title = "歌词页播放器",
            subtitle = "在歌词页底部显示播放控制，可切换三个按钮或悬浮播放器。" +
                "四种全屏歌词模式都遵循这一个开关。",
            checked = vm.lyricsPlaybarEnabled,
            onCheckedChange = { vm.changeLyricsPlaybar(it) }
        )
        if (vm.lyricsPlaybarEnabled) {
            Text(
                "播放器样式",
                style = MaterialTheme.typography.labelMedium,
                color = skin.textMuted,
                modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LyricPlaybarStyle.entries.forEach { style ->
                    val selected = style == vm.lyricsPlaybarStyle
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) skin.accentGlow else skin.surface2)
                            .border(
                                if (selected) 1.dp else 0.dp,
                                if (selected) skin.accent else Color.Transparent,
                                RoundedCornerShape(10.dp)
                            )
                            .clickable { vm.changeLyricsPlaybarStyle(style) }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            style.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) skin.accent else skin.textMuted
                        )
                    }
                }
            }
            if (vm.lyricsPlaybarStyle == LyricPlaybarStyle.FLOAT) {
                Text(
                    "与列表页悬浮播放器共用外观、进度显示及滑动阈值设置；左滑下一首、右滑上一首，点击卡片返回播放主界面。",
                    style = MaterialTheme.typography.labelSmall,
                    color = skin.textDim,
                    modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)
                )
            }
        }

        // ---- 歌词适配背景 ----
        Spacer(Modifier.height(14.dp))
        SettingsGroupLabel("背景玻璃与文字可读性")
        Text(
            "歌词适配背景",
            style = MaterialTheme.typography.titleSmall,
            color = skin.text
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "歌词页的背景是封面模糊后透出来的。有些封面透出来正好和歌词撞色，" +
                "屏幕上某一段的歌词就糊进背景里了。\n" +
                "打开后按每行在屏幕上的位置取那一段的背景色，把歌词明度挪到刚好看得清 ——" +
                "色相不动，所以还是你选的皮肤那套颜色。",
            style = MaterialTheme.typography.bodySmall,
            color = skin.textMuted
        )
        Spacer(Modifier.height(8.dp))
        AdaptModeRow(
            mode = vm.lyricsAdaptMode,
            onModeChange = { vm.changeLyricsAdaptMode(it) },
            skin = skin
        )

        Spacer(Modifier.height(14.dp))
        Text(
            "歌词页背景玻璃",
            style = MaterialTheme.typography.titleSmall,
            color = skin.text
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "「液态玻璃」是仿 Apple 的观感：更强模糊 + 颜色更饱和，" +
                "并叠加顶部镜面高光与上边缘折射亮线。\n" +
                "模糊需要 Android 12 以上；更低的系统会自动用「低透明度 + 加厚遮罩」近似。",
            style = MaterialTheme.typography.bodySmall,
            color = skin.textMuted
        )
        Spacer(Modifier.height(8.dp))
        GlassControls(
            style = vm.glassStyle,
            blurDp = vm.glassBlur,
            visibility = vm.glassVisibility,
            onStyleChange = { vm.changeGlassStyle(it) },
            onBlurChange = { vm.changeGlassBlur(it) },
            onVisibilityChange = { vm.changeGlassVisibility(it) },
            modifier = Modifier.padding(horizontal = 0.dp)
        )
    }
}

/**
 * 在线歌词（LrcAPI）与歌词缓存管理。
 */
@Composable
private fun LyricsOnlineSettingsSection(vm: SonanceViewModel) {
    val skin = LocalSkin.current
    val summary = (if (vm.onlineLyricsEnabled) "已开启 LrcAPI" else "在线歌词已关闭") +
        " · 已缓存 ${vm.lyricsCacheCount} 首"

    CollapsibleSection(
        id = "lyricsOnline",
        title = "在线歌词与缓存",
        summary = summary,
        icon = SonanceIcons.Cloud
    ) {
        SettingsSwitchRow(
            title = "在线歌词（LrcAPI）",
            subtitle = "本地内嵌歌词找不到时，联网向歌词接口要一份。默认关闭 —— 打开后才会联网，" +
                "只发送歌名 / 歌手 / 专辑，不发送任何本地路径或文件。",
            checked = vm.onlineLyricsEnabled,
            onCheckedChange = { vm.changeOnlineLyrics(it) }
        )

        if (vm.onlineLyricsEnabled) {
            Column(Modifier.padding(top = 10.dp, bottom = 4.dp)) {
                Text(
                    "歌词服务地址",
                    style = MaterialTheme.typography.labelMedium,
                    color = skin.textMuted
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = vm.lyricsApiBase,
                    onValueChange = { vm.changeLyricsApi(it) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = skin.accent,
                        unfocusedBorderColor = skin.border2,
                        focusedTextColor = skin.text,
                        unfocusedTextColor = skin.text,
                        cursorColor = skin.accent
                    )
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "默认用 LrcAPI 的公开实例 https://api.lrc.cx/lyrics。\n" +
                        "公开实例走第三方接口、响应可能偏慢；如果你自建了 LrcAPI，把地址换成自己的即可。",
                    style = MaterialTheme.typography.labelSmall,
                    color = skin.textDim
                )
            }
        }

        Column(Modifier.padding(top = 12.dp)) {
            Text(
                "请求时机",
                style = MaterialTheme.typography.labelMedium,
                color = skin.textMuted
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "• 只在播放中点开歌词页时才请求，一次只请求当前这一首\n" +
                    "• 不做预取、不做批量抓取，不会因为歌曲多就打爆接口\n" +
                    "• 拿到后立刻存到本地，下次同一首歌直接读缓存，不再联网\n" +
                    "• 确认没有歌词的也会记下来，不会反复重试",
                style = MaterialTheme.typography.labelSmall,
                color = skin.textDim
            )
        }

        SettingsActionRow(
            title = "清除歌词缓存",
            subtitle = if (vm.lyricsCacheCount == 0) {
                "当前没有缓存"
            } else {
                "已缓存 ${vm.lyricsCacheCount} 首的查询结果，清除后下次会重新联网查"
            },
            onClick = { vm.clearLyricsCache() },
            actionLabel = "清除"
        )
    }
}

/**
 * 封面在线补全、手动搜索与缓存管理。
 */
@Composable
private fun CoverSettingsSection(vm: SonanceViewModel) {
    val skin = LocalSkin.current
    val summary = (if (vm.onlineCoverEnabled) "自动补全已开启" else "自动补全已关闭") +
        " · 已缓存 ${vm.coverCacheCount} 张"

    CollapsibleSection(
        id = "cover",
        title = "封面补全与缓存",
        summary = summary,
        icon = SonanceIcons.Image
    ) {
        SettingsSwitchRow(
            title = "在线补全封面（iTunes Search API）",
            subtitle = "本地没有内嵌封面时，联网按「歌名 + 歌手」搜一张。默认关闭 —— " +
                "打开后播放到无封面的歌才会查一次；只发送歌名/歌手/专辑，不发送文件或路径。\n" +
                "华语歌建议同时打开「在线音乐 → 用网易云补全本地歌」，iTunes 对华语曲库" +
                "覆盖差、还容易返回同名翻唱的封面。",
            checked = vm.onlineCoverEnabled,
            onCheckedChange = { vm.changeOnlineCover(it) }
        )

        // ===== 一键补齐全部封面 =====
        val batch = vm.coverBatch
        if (batch == null) {
            SettingsActionRow(
                title = "一键补齐全部封面",
                subtitle = "立刻给曲库里所有还缺封面的歌补一张，点一下就开始（不挑网络）。\n" +
                    "本地内嵌 / 之前下载过的会自动跳过，只补真正缺的那些；" +
                    "华语歌建议先打开「在线音乐 → 用网易云补全本地歌」，命中率会高很多。",
                onClick = { vm.fetchAllMissingCovers() },
                actionLabel = "开始"
            )
        } else {
            Column(Modifier.padding(vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "正在补齐封面",
                            style = MaterialTheme.typography.bodyMedium,
                            color = skin.text
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "${batch.done}/${batch.total} · 已补 ${batch.found} 张" +
                                if (batch.current.isBlank()) "" else " · ${batch.current}",
                            style = MaterialTheme.typography.bodySmall,
                            color = skin.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "停止",
                        style = MaterialTheme.typography.labelLarge,
                        color = skin.accent,
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .clickable { vm.cancelCoverBatch() }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { (batch.done.toFloat() / batch.total.toFloat()).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = skin.accent,
                    trackColor = skin.surface3
                )
            }
        }

        SettingsActionRow(
            title = "手动搜索封面",
            subtitle = "自动匹配到错误封面时用。可改歌名/歌手重新搜，搜到多条会列出来让你挑。",
            onClick = { vm.openCoverPicker(vm.player.state.value.mediaId) },
            actionLabel = "搜索"
        )

        SettingsActionRow(
            title = "清除封面缓存",
            subtitle = if (vm.coverCacheCount == 0) {
                "当前没有下载缓存；手选封面会保留"
            } else {
                "已缓存 ${vm.coverCacheCount} 张封面，清除后可重新联网找；手选封面会保留"
            },
            onClick = { vm.clearCoverCache() },
            actionLabel = "清除"
        )

        Column(Modifier.padding(top = 12.dp)) {
            Text(
                "抓取时机",
                style = MaterialTheme.typography.labelMedium,
                color = skin.textMuted
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "• 播放到某首歌、且本地/内嵌都没有封面时，才查这一次\n" +
                    "• 一次只查当前这一首，不做预取、不做批量抓\n" +
                    "• 拿到后落盘，同一首以后直接读本地\n" +
                    "• 查不到也会记下来，不会每次播放都重试",
                style = MaterialTheme.typography.labelSmall,
                color = skin.textDim
            )
        }
    }
}

/* ============================================================
 * 四、曲库与在线：本地扫描过滤、在线音乐、重复清理
 * ============================================================ */

@Composable
private fun MediaLibraryScanSection(
    vm: SonanceViewModel,
    onOpenScanScope: () -> Unit
) {
    val context = LocalContext.current
    val scopeText = if (vm.scanScopeSelection.isNotEmpty()) {
        "指定 ${vm.scanScopeSelection.size} 个目录"
    } else {
        "全部文件夹"
    }
    CollapsibleSection(
        id = "mediaLibrary",
        title = "本地扫描与过滤规则",
        summary = "共 ${vm.songs.size} 首 · $scopeText",
        icon = SonanceIcons.Folder
    ) {
        SettingsActionRow(
            title = "重新扫描本地音乐",
            subtitle = buildLastScanSubtitle(
                context = context,
                songCount = vm.songs.size,
                scopeCount = vm.scanScopeSelection.size
            ),
            onClick = onOpenScanScope,
            actionLabel = if (vm.scanState == com.sonance.player.ScanState.SCANNING) "扫描中…" else "扫描"
        )
        Spacer(Modifier.height(10.dp))
        SettingsGroupLabel("扫描自动过滤")
        SettingsSwitchRow(
            title = "忽略短音频",
            subtitle = "扫描时过滤掉铃声、提示音等 15 秒以下的音频",
            checked = vm.filterShortAudio,
            onCheckedChange = { vm.changeIgnoreShort(it) }
        )
        SettingsSwitchRow(
            title = "排除加密文件",
            subtitle = "识别 .kgm / .kgma / .vpr 等加密容器（按文件头判断，已转换的成品不会被误伤）",
            checked = vm.scanExcludeLocked,
            onCheckedChange = { vm.changeScanExcludeLocked(it) }
        )
        SettingsSwitchRow(
            title = "排除系统不支持的格式",
            subtitle = "ape / wma / dsf 等本机解码器放不了的格式",
            checked = vm.scanExcludeUnsupported,
            onCheckedChange = { vm.changeScanExcludeUnsupported(it) }
        )
        SettingsSwitchRow(
            title = "排除录音与系统提示音",
            subtitle = "铃声、通知音、录音等不是歌曲的音频",
            checked = vm.scanExcludeNonSongFolders,
            onCheckedChange = { vm.changeScanExcludeNonSongFolders(it) }
        )
    }
}

/* ============================================================
 * 桌面小组件
 * ============================================================ */

private data class WidgetStyleInfo(
    val name: String,
    val size: String,
    val desc: String,
    val hasCover: Boolean
)

private val WIDGET_STYLES = listOf(
    WidgetStyleInfo("横向控制条", "5×1", "取封面配色的渐变胶囊，圆形唱片 + 歌名 + 上下曲 / 播放 / 歌词", true),
    WidgetStyleInfo("封面卡片", "3×2", "大圆形唱片居中，歌名与播控键，可切成三行歌词", true),
    WidgetStyleInfo("信息宽卡", "5×2", "左唱片右信息，带真实进度条、上下曲与歌词切换", true),
    WidgetStyleInfo("极简横条", "3×1", "不显示封面，只留歌名与四个控制键，最省资源", false),
    WidgetStyleInfo("大幅卡片", "5×4", "大圆形唱片 + 三行歌词 + 进度条，桌面上的完整播放器", true)
)

@Composable
private fun WidgetSettingsContent(vm: SonanceViewModel) {
    val skin = LocalSkin.current
    val context = LocalContext.current

    Column(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            WIDGET_STYLES.forEach { w ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(skin.surface2)
                        .padding(12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = if (w.hasCover) SonanceIcons.Image else SonanceIcons.Lyrics,
                        contentDescription = null,
                        tint = skin.accent,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .size(16.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                w.name,
                                style = MaterialTheme.typography.titleSmall,
                                color = skin.text
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                w.size,
                                style = MaterialTheme.typography.labelSmall,
                                color = skin.accent
                            )
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(
                            w.desc,
                            style = MaterialTheme.typography.bodySmall,
                            color = skin.textMuted
                        )
                    }
                }
            }

            Spacer(Modifier.height(2.dp))
            Text(
                "添加方式：长按桌面空白处 → 小组件 → 找到「山水有清音」。\n" +
                    "每张卡片都带上一曲 / 播放 / 下一曲和歌词键：点一下歌词键才显示，\n" +
                    "默认关闭，各张卡片各自记住开关；没有同步歌词时会如实说明。\n" +
                    "卡片颜色跟着当前封面走，点卡片空白处打开应用。",
                style = MaterialTheme.typography.labelSmall,
                color = skin.textDim
            )

            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(
                    label = "立即刷新",
                    onClick = {
                        Thread {
                            runCatching { com.sonance.player.widget.SonanceWidgets.refreshAll(context) }
                        }.start()
                        vm.showToast("已刷新桌面小组件")
                    },
                    filled = false
                )
            }
        }
    }
}

/* ============================================================
 * 在线音乐
 * ============================================================ */

@Composable
private fun OnlineMusicContent(vm: SonanceViewModel) {
    val skin = LocalSkin.current

    Column(Modifier.fillMaxWidth()) {
        // 账号卡放在最上面：它决定了下面几项的实际效果（会员曲库 / 音质 / 下载）
        NeteaseAccountCard(vm)
        Spacer(Modifier.height(10.dp))

        SettingsSwitchRow(
            title = "在线搜索与播放",
            subtitle = "开启后可以在「在线音乐」页搜索并播放网络歌曲。默认关闭 —— " +
                "打开后搜索词会发到音源，播放时从音源的 CDN 拉音频流。",
            checked = vm.onlineMusicEnabled,
            onCheckedChange = { vm.changeOnlineMusic(it) }
        )

        SettingsSwitchRow(
            title = "用网易云补全本地歌",
            subtitle = "本地曲库的封面 / 歌词取不到时，改用网易云再找一次。默认关闭。\n" +
                "它比现有的两个源命中率高：匹配时带时长校验，能避开同名翻唱和 Live 版" +
                "（iTunes 只按歌名歌手搜，容易贴错封面）；歌词还带中文翻译。\n" +
                "华语歌会优先问网易云，西洋歌优先问 iTunes，先有结果的先用。",
            checked = vm.neteaseFillEnabled,
            onCheckedChange = { vm.changeNeteaseFill(it) }
        )

        // ===== 播放并下载 =====
        val context = LocalContext.current
        val storageLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                vm.changeOnlineDownload(true)
            } else {
                vm.showToast("需要存储权限才能保存下载的文件")
            }
        }

        SettingsSwitchRow(
            title = "播放并下载",
            subtitle = "播放在线歌时自动存一份到本地。默认关闭。\n" +
                "存的是和播放同一条流、同一个音质档位（上面选的播放音质），" +
                "登录会员时可能就是无损；无损存为 flac，标准音质存为 mp3 并写入标签" +
                "（歌名 / 歌手 / 专辑 / 封面）。\n" +
                "文件放在「${OnlineDownloader.FOLDER_NAME}」文件夹里 —— " +
                "重新扫描后「文件夹」页会有这个分类。",
            checked = vm.onlineDownloadEnabled,
            onCheckedChange = { on ->
                if (on && Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                    ContextCompat.checkSelfPermission(
                        context, Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    storageLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                } else {
                    vm.changeOnlineDownload(on)
                }
            }
        )

        if (vm.onlineDownloadEnabled) {
            Column(Modifier.padding(top = 10.dp, bottom = 4.dp)) {
                SettingsSwitchRow(
                    title = "只在 Wi-Fi 下下载",
                    subtitle = "默认开启 —— 不然在移动网络下随便听几张专辑就悄悄吃掉几百 MB。" +
                        "\n判断不出网络类型时会跳过，不会赌一把。",
                    checked = vm.onlineDownloadWifiOnly,
                    onCheckedChange = { vm.changeDownloadWifiOnly(it) }
                )
                Text(
                    "已下载 ${vm.downloadedCount} 首",
                    style = MaterialTheme.typography.bodySmall,
                    color = skin.textMuted
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "文件在系统音乐目录的「${OnlineDownloader.FOLDER_NAME}」里，本应用不会删除它们。",
                    style = MaterialTheme.typography.labelSmall,
                    color = skin.textDim
                )
            }
        }

        if (vm.onlineMusicEnabled) {
            Column(Modifier.padding(top = 14.dp)) {
                Text(
                    "音源",
                    style = MaterialTheme.typography.labelMedium,
                    color = skin.textMuted
                )
                Spacer(Modifier.height(8.dp))
                ChipsRow(
                    chips = vm.onlineSources.map { it.label to (it.id == vm.onlineSourceId) },
                    onSelect = { vm.changeOnlineSource(vm.onlineSources[it].id) }
                )
                PluginSourceRows(vm)
            }

            Spacer(Modifier.height(14.dp))
            Text(
                "• 未登录时只有音源上免费开放的曲目能播；登录会员账号后 VIP 曲目也可以\n" +
                    "• 音质以音源实际返回的档位为准，标「无损」却只给 320k 时会如实显示 320k\n" +
                    "• 搜索只发送你输入的关键词，不发送任何本地路径或文件\n" +
                    "• 在线歌不进本地曲库，也不会写进「最近播放」「播放次数」\n" +
                    "• 队列里的在线歌在重启后不会恢复（播放地址是临时的，要重新解析）",
                style = MaterialTheme.typography.labelSmall,
                color = skin.textDim
            )
        }
    }
}

/**
 * 网易云账号卡：未登录显示入口，已登录显示头像 / 昵称 / 会员态，下面是音质档位。
 *
 * 为什么把音质放在这张卡里而不是单独一段：音质和账号权益是同一件事的两面
 * （会员才有无损），分开放用户要来回对照才知道"为什么无损是灰的"。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NeteaseAccountCard(vm: SonanceViewModel) {
    val skin = LocalSkin.current
    var showLogin by remember { mutableStateOf(false) }
    val account = vm.neteaseAccount

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(skin.surface2)
            .padding(14.dp)
    ) {
        if (account == null) {
            Text(
                "网易云账号",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = skin.text
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "有会员的话登录自己的账号，就能搜到并播放会员曲库、取无损音质、下载会员歌曲。" +
                    "不登录也能用，只是限于音源上免费开放的曲目。",
                style = MaterialTheme.typography.labelSmall,
                color = skin.textMuted
            )
            Spacer(Modifier.height(10.dp))
            PillButton(label = "登录网易云", onClick = { showLogin = true })
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val avatar = rememberRemoteCover(
                    account.avatarUrl.takeIf { it.isNotBlank() },
                    120
                )
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(skin.surface),
                    contentAlignment = Alignment.Center
                ) {
                    if (avatar != null) {
                        Image(
                            bitmap = avatar,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Text(
                            account.nickname.take(1).ifBlank { "云" },
                            style = MaterialTheme.typography.titleMedium,
                            color = skin.textMuted
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        account.nickname.ifBlank { "网易云用户" },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = skin.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        if (account.isVip) "黑胶 VIP · 可播会员曲库" else "普通账号 · 无损需要会员",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (account.isVip) skin.accent else skin.textDim
                    )
                }
                PillButton(label = "退出", filled = false, onClick = { vm.logoutNetease() })
            }
        }

        Spacer(Modifier.height(14.dp))
        Text("播放音质", style = MaterialTheme.typography.labelMedium, color = skin.textMuted)
        Spacer(Modifier.height(8.dp))
        // 非会员不展示无损两档：摆在那里点不动，比不摆更让人困惑
        val levels = if (account?.isVip == true) NeteaseLevel.entries else NeteaseLevel.freeTier
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            levels.forEach { level ->
                TagChip(
                    label = level.label,
                    selected = vm.onlineQuality == level,
                    onClick = { vm.changeOnlineQuality(level) }
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            if (account?.isVip == true) {
                "下载跟随这里的档位；实际以音源返回的档位为准（只给到 320k 时会显示 320k）。"
            } else {
                "未登录 / 非会员最高到 320k，无损与 Hi-Res 需要黑胶 VIP。" +
                    "下载跟随这里的档位。"
            },
            style = MaterialTheme.typography.labelSmall,
            color = skin.textDim
        )
    }

    if (showLogin) {
        NeteaseLoginSheet(vm = vm, onDismiss = { showLogin = false })
    }
}

/* ============================================================
 * 重复 / 同名歌曲清理
 * ============================================================ */

@Composable
private fun DuplicateCleanupContent(vm: SonanceViewModel) {
    val skin = LocalSkin.current
    val report = vm.duplicateReport
    var cleanDialog by remember { mutableStateOf<Boolean?>(null) }
    var excludedDirsOpen by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 4.dp)) {
            Text(
                "默认只从曲库列表里隐藏，随时可以恢复；点「清理」时也可以选择删除文件。",
                style = MaterialTheme.typography.bodySmall,
                color = skin.textMuted
            )

            if (report != null && !report.isEmpty) {
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    StatBox("重复文件", "${report.exactCount}", Modifier.weight(1f))
                    StatBox("同名歌曲", "${report.sameNameCount}", Modifier.weight(1f))
                }
            }

            Spacer(Modifier.height(10.dp))
            SettingsActionRow(
                title = "检测重复与同名",
                subtitle = if (report == null) {
                    "扫描曲库，找出重复文件和同名歌曲"
                } else if (report.isEmpty) {
                    "上次检测：没有发现问题"
                } else {
                    "上次检测：${report.exactCount} 首重复 · ${report.sameNameCount} 首同名"
                },
                onClick = { vm.scanDuplicates() },
                actionLabel = "检测"
            )

            SettingsActionRow(
                title = "清理重复文件",
                subtitle = "标题、歌手、时长完全一致的多份文件，保留体积最大的那份",
                onClick = { cleanDialog = false },
                actionLabel = "清理"
            )

            SettingsActionRow(
                title = "清理同名歌曲",
                subtitle = "同名但时长不同（可能是不同版本），保留时长最长的那份 —— 请先检测确认",
                onClick = { cleanDialog = true },
                actionLabel = "清理"
            )

            SettingsActionRow(
                title = "恢复被隐藏的曲目",
                subtitle = if (vm.hiddenCount == 0) {
                    "当前没有隐藏任何曲目"
                } else {
                    "已隐藏 ${vm.hiddenCount} 首，点此全部恢复"
                },
                onClick = { vm.restoreHiddenSongs() },
                actionLabel = "恢复"
            )

            SettingsActionRow(
                title = "已排除的文件夹",
                subtitle = if (vm.excludedDirs.isEmpty()) {
                    "没有排除任何文件夹（排除入口在文件夹详情页 ⁝ 菜单）"
                } else {
                    "${vm.excludedDirs.size} 个文件夹，点此查看与恢复"
                },
                onClick = { excludedDirsOpen = true },
                actionLabel = "管理"
            )
        }
    }

    cleanDialog?.let { includeSameName ->
        val count = if (includeSameName) report?.sameNameCount ?: 0 else report?.exactCount ?: 0
        val kind = if (includeSameName) "同名歌曲" else "重复文件"
        AlertDialog(
            onDismissRequest = { cleanDialog = null },
            title = { Text(if (includeSameName) "清理同名歌曲" else "清理重复文件") },
            text = {
                Text(
                    "发现 $count 首$kind。\n\n" +
                        "· 仅从列表隐藏 —— 文件保留，随时可在下方恢复\n" +
                        "· 删除文件 —— 从设备上永久删除，不可恢复"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    cleanDialog = null
                    vm.cleanDuplicates(includeSameName = includeSameName, deleteFiles = true)
                }) { Text("删除文件") }
            },
            dismissButton = {
                TextButton(onClick = {
                    cleanDialog = null
                    vm.cleanDuplicates(includeSameName = includeSameName)
                }) { Text("仅隐藏") }
            }
        )
    }

    if (excludedDirsOpen) {
        AlertDialog(
            onDismissRequest = { excludedDirsOpen = false },
            title = { Text("已排除的文件夹") },
            text = {
                if (vm.excludedDirs.isEmpty()) {
                    Text("当前没有排除任何文件夹")
                } else {
                    Column(
                        Modifier
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        vm.excludedDirs.sorted().forEach { path ->
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    path,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = skin.text,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = { vm.restoreExcludedFolder(path) }) {
                                    Text("恢复")
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { excludedDirsOpen = false }) { Text("关闭") }
            }
        )
    }
}

/* ============================================================
 * 五、关于与支持
 * ============================================================ */

@Composable
private fun AboutSettingsSection(vm: SonanceViewModel) {
    val context = LocalContext.current
    val skin = LocalSkin.current
    val versionLabel = remember(context) {
        runCatching {
            context.packageManager
                .getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }

    CollapsibleSection(
        id = "about",
        title = "关于应用",
        summary = "山水有清音 · 版本 ${versionLabel.ifBlank { "—" }}",
        icon = SonanceIcons.Info,
        defaultExpanded = true
    ) {
        Column(Modifier.padding(vertical = 4.dp)) {
            Text(
                "山水有清音 · 本地音乐播放器",
                style = MaterialTheme.typography.titleSmall,
                color = skin.text
            )
            Spacer(Modifier.height(6.dp))
            // 版本号 + 基线导出入口：长按版本号把当前设置导出成出厂基线 JSON。
            Column(
                Modifier.pointerInput(Unit) {
                    detectTapGestures(
                        onLongPress = {
                            val file = vm.exportSettingsBaseline()
                            Toast.makeText(
                                context,
                                if (file != null) "已导出设置基线：${file.absolutePath}"
                                else "导出失败",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    )
                }
            ) {
                Text(
                    "版本 ${versionLabel.ifBlank { "—" }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = skin.text.copy(alpha = 0.45f)
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "长按版本号可导出当前设置为基线文件",
                    style = MaterialTheme.typography.labelSmall,
                    color = skin.text.copy(alpha = 0.30f)
                )
            }
            Spacer(Modifier.height(10.dp))
            // 署名。连点 10 次会解锁「私有格式还原」区块 —— 彩蛋，界面上不做任何暗示
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text(
                    "陈陆",
                    style = MaterialTheme.typography.bodySmall,
                    color = skin.textMuted,
                    letterSpacing = 2.sp,
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { vm.tapAuthor() }
                )
            }
        }
    }
}

/* ============================================================
 * 应用图标网格
 * ============================================================ */

@Composable
internal fun AppIconGrid(currentId: String, onSelect: (String) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = (maxWidth / 86.dp).toInt().coerceIn(3, 6)
        val gap = 12.dp
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            AppIcons.all.chunked(columns).forEach { rowItems ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    rowItems.forEach { variant ->
                        AppIconCard(
                            variant = variant,
                            selected = variant.id == currentId,
                            onClick = { onSelect(variant.id) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    repeat(columns - rowItems.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
internal fun AppIconCard(
    variant: AppIconVariant,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val skin = LocalSkin.current
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
            .background(variant.bgColor)
            .then(
                if (selected) Modifier.border(2.dp, skin.accent, RoundedCornerShape(16.dp))
                else Modifier.border(1.dp, skin.border, RoundedCornerShape(16.dp))
            )
            .clickable(onClick = onClick)
            .padding(9.dp),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(variant.preview),
            contentDescription = variant.label,
            modifier = Modifier.fillMaxSize()
        )
        if (selected) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(skin.accent)
            )
        }
    }
}

/* ============================================================
 * 皮肤网格
 * ============================================================ */

@Composable
private fun SkinGrid(current: Skin, onSelect: (Skin) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = (maxWidth / 170.dp).toInt().coerceIn(2, 4)
        val gap = 12.dp
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            Skins.all.chunked(columns).forEach { rowSkins ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    rowSkins.forEach { s ->
                        SkinCard(
                            skin = s,
                            selected = s.id == current.id,
                            onClick = { onSelect(s) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    repeat(columns - rowSkins.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun SkinCard(
    skin: Skin,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val active = LocalSkin.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(active.surface)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) active.accent else active.border,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(10.dp)
    ) {
        // 迷你界面预览
        Box(
            Modifier
                .fillMaxWidth()
                .height(74.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(skin.bg)
        ) {
            Box(
                Modifier
                    .width(18.dp)
                    .fillMaxHeight()
                    .background(skin.surface)
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 26.dp, end = 10.dp, top = 14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    Modifier
                        .width(34.dp)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(skin.textMuted.copy(alpha = 0.5f))
                )
                Box(
                    Modifier
                        .width(58.dp)
                        .height(7.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(skin.accent)
                )
                Box(
                    Modifier
                        .width(44.dp)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(skin.surface3)
                )
            }
            Row(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(14.dp)
                    .background(skin.surface2)
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    Modifier.size(6.dp).clip(CircleShape).background(skin.accent)
                )
                Box(
                    Modifier.width(38.dp).height(3.dp).clip(RoundedCornerShape(2.dp))
                        .background(skin.surface3)
                )
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.width(10.dp).height(3.dp).clip(RoundedCornerShape(2.dp))
                        .background(skin.textDim)
                )
            }
            if (selected) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(5.dp)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(active.accent),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = SonanceIcons.Check,
                        contentDescription = null,
                        tint = active.onAccent,
                        modifier = Modifier.size(11.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(9.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = skin.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (selected) active.accent else active.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = skin.tagline,
            style = MaterialTheme.typography.labelSmall,
            color = active.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/* ============================================================
 * 设置项通用容器与行组件
 * ============================================================ */

/** 折叠状态：进程内共享的展开记忆，页面重建 / 来回切页后不会回到默认值 */
private object FoldState {
    val expanded = mutableStateMapOf<String, Boolean>()

    fun collapseAll() {
        val keys = expanded.keys.toList()
        keys.forEach { expanded[it] = false }
        // 默认展开的「关于应用」也显式收起
        expanded["about"] = false
    }
}

@Composable
private fun CollapsibleSection(
    id: String,
    title: String,
    summary: String,
    icon: ImageVector,
    defaultExpanded: Boolean = false,
    content: @Composable () -> Unit
) {
    val skin = LocalSkin.current
    val expanded = FoldState.expanded[id] ?: defaultExpanded
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(skin.surface)
            .border(
                width = if (expanded) 1.dp else 0.dp,
                color = if (expanded) skin.border else Color.Transparent,
                shape = RoundedCornerShape(12.dp)
            )
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { FoldState.expanded[id] = !expanded }
                .padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (expanded) skin.accentGlow else skin.surface2),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (expanded) skin.accent else skin.textDim,
                    modifier = Modifier.size(16.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = skin.text
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    summary,
                    style = MaterialTheme.typography.labelSmall,
                    color = skin.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(10.dp))
            Icon(
                imageVector = SonanceIcons.ChevronDown,
                contentDescription = if (expanded) "收起" else "展开",
                tint = if (expanded) skin.accent else skin.textDim,
                modifier = Modifier
                    .size(18.dp)
                    .rotate(if (expanded) 180f else 0f)
            )
        }
        // 高度过渡用动画；收起后内容会被移除，重内容（图标网格 / 预览卡）不付组合成本
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(
                animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing)
            ) + fadeIn(tween(180)),
            exit = shrinkVertically(
                animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing)
            ) + fadeOut(tween(120))
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp, bottom = 14.dp)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .height(1.dp)
                        .background(skin.border)
                )
                content()
            }
        }
    }
}

/** 折叠模块内部的分组小标题（例如「曲库清理」/「重复与同名歌曲」） */
@Composable
internal fun SettingsGroupLabel(text: String) {
    val skin = LocalSkin.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            Modifier
                .size(4.dp)
                .clip(CircleShape)
                .background(skin.accent)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = skin.textDim
        )
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val skin = LocalSkin.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = skin.text)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = skin.textMuted)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = skin.onAccent,
                checkedTrackColor = skin.accent,
                checkedBorderColor = skin.accent,
                uncheckedThumbColor = skin.textMuted,
                uncheckedTrackColor = skin.surface3,
                uncheckedBorderColor = skin.border2
            )
        )
    }
}

@Composable
private fun SettingsActionRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    actionLabel: String
) {
    val skin = LocalSkin.current
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = skin.text)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = skin.textMuted)
        }
        Spacer(Modifier.width(12.dp))
        PillButton(label = actionLabel, onClick = onClick, filled = false)
    }
}

/**
 * 「支持作者」：赞赏码卡片。
 */
@Composable
private fun DonateCard() {
    val skin = LocalSkin.current
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(R.drawable.donate_qrcode),
            contentDescription = "微信赞赏码",
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "微信扫一扫，请作者喝杯饮料",
            style = MaterialTheme.typography.bodySmall,
            color = skin.textMuted
        )
    }
}

@Composable
private fun StatBox(label: String, value: String, modifier: Modifier = Modifier) {
    val skin = LocalSkin.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(skin.surface2)
            .padding(horizontal = 12.dp, vertical = 12.dp)
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            color = skin.accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(3.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = skin.textMuted)
    }
}

/**
 * 在「重新扫描」这一行下面显示缓存新鲜度与扫描范围。
 */
internal fun buildLastScanSubtitle(
    context: android.content.Context,
    songCount: Int,
    scopeCount: Int = 0
): String {
    val lastAt = Prefs(context).lastScanAt
    val scopeText = if (scopeCount > 0) "范围 $scopeCount 个文件夹" else "范围：全部文件夹"
    if (lastAt <= 0L) {
        return if (songCount == 0) "首次启动会扫描一次，之后秒开 · $scopeText"
        else "未记录扫描时间 · $scopeText"
    }
    val mins = ((System.currentTimeMillis() - lastAt) / 60000L).coerceAtLeast(0)
    val rel = when {
        mins < 1 -> "刚刚"
        mins < 60 -> "${mins} 分钟前"
        mins < 24 * 60 -> "${mins / 60} 小时前"
        else -> "${mins / (24 * 60)} 天前"
    }
    return "上次扫描：$rel · $songCount 首 · $scopeText"
}

/**
 * 内容源插件的导入与管理。
 */
@Composable
private fun PluginSourceRows(vm: SonanceViewModel) {
    val skin = LocalSkin.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)
                        ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                }.getOrNull()
            }
            vm.importPluginScript(text.orEmpty())
        }
    }

    Spacer(Modifier.height(12.dp))
    Text("内容源插件", style = MaterialTheme.typography.labelMedium, color = skin.textMuted)
    Spacer(Modifier.height(4.dp))

    vm.pluginRecords.forEach { record ->
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    record.meta.platform,
                    style = MaterialTheme.typography.bodyMedium,
                    color = skin.text
                )
                Text(
                    "v${record.meta.version} · ${if (record.enabled) "已启用" else "已停用"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = skin.textMuted
                )
            }
            Switch(
                checked = record.enabled,
                onCheckedChange = { vm.setPluginEnabled(record.meta.id, it) },
                colors = SwitchDefaults.colors(checkedThumbColor = skin.accent)
            )
            TextButton(onClick = { vm.removePlugin(record.meta.id) }) {
                Text("删除", color = skin.textMuted, style = MaterialTheme.typography.labelSmall)
            }
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { launcher.launch("*/*") }) {
            Text("导入插件 (.js)", color = skin.accent, style = MaterialTheme.typography.labelMedium)
        }
        vm.pluginHint?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = skin.textMuted,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }

    Text(
        "• 只支持 CommonJS 内容源插件（module.exports 导出 search / getMediaSource）；" +
            "洛雪自定义源脚本是另一套协议，不能在这里导入\n" +
            "• axios 垫片支持 params / headers / timeout / JSON 响应；" +
            "依赖 cheerio / crypto-js 等第三方 npm 包的插件暂不支持\n" +
            "• 插件若用 http 明文接口，域名需在明文白名单（network_security_config）里，" +
            "不在名单里的域名会被系统直接拦掉\n" +
            "• 本期打通搜索与在线播放，下载暂不走插件取流\n" +
            "• 插件会在应用内发起网络请求，请只导入你信任且已获授权的内容源；" +
            "应用不内置任何音源，也不提供插件获取渠道\n" +
            "• 切换上方「音源」后，搜索结果就来自所选插件",
        style = MaterialTheme.typography.labelSmall,
        color = skin.textDim
    )
}
