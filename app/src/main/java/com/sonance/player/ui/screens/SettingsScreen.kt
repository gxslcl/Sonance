package com.sonance.player.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sonance.player.SonanceViewModel
import com.sonance.player.data.OnlineDownloader
import com.sonance.player.data.OnlineSources
import com.sonance.player.data.formatDuration
import com.sonance.player.data.formatSize
import com.sonance.player.ui.components.ChipsRow
import com.sonance.player.ui.components.SonanceSlider
import com.sonance.player.data.GlowMode
import com.sonance.player.data.GlowTransition
import com.sonance.player.data.Prefs
import com.sonance.player.ui.components.PillButton
import com.sonance.player.ui.icon.SonanceIcons
import com.sonance.player.ui.theme.AppIconVariant
import com.sonance.player.ui.theme.AppIcons
import com.sonance.player.ui.theme.LocalSkin
import com.sonance.player.ui.theme.Skin
import com.sonance.player.ui.theme.Skins
import com.sonance.player.ui.components.GlassControls
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    vm: SonanceViewModel,
    modifier: Modifier = Modifier,
    onOpenEqualizer: () -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val skin = LocalSkin.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        // ===== 皮肤（默认收起：14 套预览卡展开会把下面所有设置挤到好几屏之外）=====
        var skinExpanded by rememberSaveable { mutableStateOf(false) }
        CollapsibleSection(
            title = "外观皮肤",
            summary = "当前：${vm.skin.name} · 共 ${Skins.all.size} 套",
            icon = SonanceIcons.Palette,
            expanded = skinExpanded,
            onToggle = { skinExpanded = !skinExpanded }
        ) {
            SkinGrid(current = vm.skin, onSelect = { vm.changeSkin(it) })
        }

        // ===== 应用图标（同样默认收起）=====
        var iconExpanded by rememberSaveable { mutableStateOf(false) }
        CollapsibleSection(
            title = "应用图标",
            summary = "${AppIcons.all.size} 款 · 点击更换桌面图标",
            icon = SonanceIcons.Palette,
            expanded = iconExpanded,
            onToggle = { iconExpanded = !iconExpanded }
        ) {
            Text(
                "切换后桌面图标会在几秒内刷新；部分第三方桌面可能需要重启桌面。",
                style = MaterialTheme.typography.labelSmall,
                color = skin.textDim
            )
            Spacer(Modifier.height(12.dp))
            AppIconGrid(currentId = vm.appIconId, onSelect = { vm.changeAppIcon(it) })
        }

        // ===== 媒体库 =====
        SettingsSection(title = "媒体库") {
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
            SettingsActionRow(
                title = "重新扫描本地音乐",
                subtitle = buildLastScanSubtitle(context, vm.songs.size),
                onClick = { vm.scan() },
                actionLabel = if (vm.scanState == com.sonance.player.ScanState.SCANNING) "扫描中…" else "扫描"
            )
        }

        // ===== 格式与兼容性 =====
        FormatCompatSection(vm = vm)

        // ===== 曲库清理 =====
        LibraryCleanupSection(vm = vm)

        // ===== 重复 / 同名歌曲 =====
        DuplicateCleanupSection(vm = vm)

        // ===== 私有格式还原 =====
        RestoreSection(vm = vm)

        // ===== 统计 =====
        SettingsSection(title = "曲库概览") {
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

        // ===== 播放与音效 =====
        PlaybackSettingsSection(
            vm = vm,
            onOpenEqualizer = onOpenEqualizer
        )

        // ===== 桌面小组件 =====
        WidgetSettingsSection(vm = vm)

        // ===== 歌词 =====
        LyricsSettingsSection(vm = vm)

        // ===== 封面 =====
        CoverSettingsSection(vm = vm)

        // ===== 在线音乐 =====
        OnlineMusicSettingsSection(vm = vm)

        // ===== 关于 =====
        SettingsSection(title = "关于") {
            Column(Modifier.padding(vertical = 6.dp)) {
                Text("山水有清音 · 本地音乐播放器", style = MaterialTheme.typography.titleSmall, color = skin.text)
                Spacer(Modifier.height(4.dp))
                Text(
                    "版本 1.0.0 · 播放、封面、歌词全部本地解析，不上传任何数据。\n" +
                        onlineNotice(vm),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (vm.onlineLyricsEnabled || vm.onlineMusicEnabled) skin.accent else skin.textMuted
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

/* ============================================================
 * 播放与音效（均衡器 + 睡眠定时器）
 * ============================================================ */

/** 设置页里可选的倒计时时长（分钟） */
private val SETTINGS_SLEEP_MINUTES = listOf(15, 30, 45, 60, 90)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlaybackSettingsSection(vm: SonanceViewModel, onOpenEqualizer: () -> Unit) {
    val skin = LocalSkin.current
    val timerLabel = vm.sleepTimerLabel

    SettingsSection(title = "播放与音效") {

        // ---- 均衡器 ----
        SettingsActionRow(
            title = "均衡器",
            subtitle = if (vm.eqEnabled) {
                // 括号不能省：Kotlin 里 `a + if (c) x else y + z` 会解析成
                // `a + (if (c) x else (y + z))`，"N 段"会被吞进 else 分支
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
 * 桌面小组件
 * ============================================================ */

/**
 * 小组件的四种样式。
 *
 * 这里刻意只做"介绍 + 一键刷新"，不做"在这里切换样式" ——
 * 小组件是**桌面上独立存在**的，添加/删除由系统的小组件选择器管，
 * 应用内能做的只有把内容刷新一遍。
 */
private data class WidgetStyleInfo(
    val name: String,
    val size: String,
    val desc: String,
    val hasCover: Boolean
)

private val WIDGET_STYLES = listOf(
    WidgetStyleInfo("封面卡片", "2×2", "大封面 + 上一首 / 播放 / 下一首，桌面上的迷你播放器", true),
    WidgetStyleInfo("信息宽条", "4×1", "横排铺开：封面、歌名、三键控制，外加一个打开应用的箭头", true),
    WidgetStyleInfo("紧凑横条", "2×1", "小封面 + 歌名，只有一个播放键，最省空间", true),
    WidgetStyleInfo("极简文字", "2×1", "不显示封面，只有歌名和播放键，最省内存", false)
)

@Composable
private fun WidgetSettingsSection(vm: SonanceViewModel) {
    val skin = LocalSkin.current
    val context = androidx.compose.ui.platform.LocalContext.current
    var expanded by rememberSaveable { mutableStateOf(false) }

    CollapsibleSection(
        title = "桌面小组件",
        summary = "${WIDGET_STYLES.size} 种样式 · 长按桌面 → 小组件 里添加",
        icon = SonanceIcons.GridView,
        expanded = expanded,
        onToggle = { expanded = !expanded }
    ) {
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
                    "小组件上的按钮直接控制播放，不需要先打开应用；" +
                    "封面优先用你下载过的图，没有就退回文件内嵌封面。",
                style = MaterialTheme.typography.labelSmall,
                color = skin.textDim
            )

            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(
                    label = "立即刷新",
                    onClick = {
                        // 渲染要读封面文件，别压在主线程上
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
 * 歌词
 * ============================================================ */

@Composable
private fun LyricsSettingsSection(vm: SonanceViewModel) {
    val skin = LocalSkin.current

    // 进设置页时刷一下缓存统计
    LaunchedEffect(Unit) { vm.refreshLyricsCacheStats() }

    SettingsSection(title = "歌词") {
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

        // 说明请求时机，避免用户以为会批量抓
        Column(Modifier.padding(top = 12.dp)) {
            Text(
                "请求时机",
                style = MaterialTheme.typography.labelMedium,
                color = skin.textMuted
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "• 只在播放中**点开歌词页**时才请求，一次只请求当前这一首\n" +
                    "• 不做预取、不做批量抓取，不会因为歌曲多就打爆接口\n" +
                    "• 拿到后立刻存到本地，下次同一首歌直接读缓存，不再联网\n" +
                    "• 确认没有歌词的也会记下来，不会反复重试",
                style = MaterialTheme.typography.labelSmall,
                color = skin.textDim
            )
        }

        // ===== 显示：简繁 / 字号 =====
        Column(Modifier.padding(top = 12.dp)) {
            Text(
                "显示",
                style = MaterialTheme.typography.labelMedium,
                color = skin.textMuted
            )
            Spacer(Modifier.height(6.dp))
            SettingsSwitchRow(
                title = "繁体转简体",
                subtitle = "在线歌词源常返回繁体，打开后显示时自动转简体。\n" +
                    "只影响显示，缓存里仍存原文，关掉开关无需重新联网。",
                checked = vm.lyricsConvertT2S,
                onCheckedChange = { vm.changeLyricsT2S(it) }
            )

            Spacer(Modifier.height(12.dp))
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

            Spacer(Modifier.height(14.dp))
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

/* ============================================================
 * 在线封面
 * ============================================================ */

@Composable
private fun CoverSettingsSection(vm: SonanceViewModel) {
    val skin = LocalSkin.current
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(Unit) { vm.refreshCoverCacheStats() }

    SettingsSection(title = "封面") {
        // ---- 播放页的颜色晕染 ----
        Column(Modifier.padding(top = 4.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("播放页晕染", style = MaterialTheme.typography.labelMedium, color = skin.textMuted)
                Text(
                    text = "%.2f".format(vm.glowStrength),
                    style = MaterialTheme.typography.labelMedium,
                    color = skin.accent
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = "把封面颜色渗一点到播放页背景上，颜色取自封面下边缘。" +
                    "在播放页双击封面也能调。",
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
            Text("换歌过渡", style = MaterialTheme.typography.labelMedium, color = skin.textMuted)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlowTransition.entries.forEach { t ->
                    val selected = t == vm.glowTransition
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) skin.accentGlow else skin.surface2)
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

            // 时长只在「渐变」下有意义 —— 跟手模式的时间由手指决定，没有可调的时长
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
            Spacer(Modifier.height(10.dp))
        }

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

        SettingsActionRow(
            title = "手动搜索封面",
            subtitle = "自动匹配到错误封面时用。可改歌名/歌手重新搜，搜到多条会列出来让你挑。",
            onClick = { vm.openCoverPicker(vm.player.state.value.mediaId) },
            actionLabel = "搜索"
        )

        SettingsActionRow(
            title = "清除封面缓存",
            subtitle = if (vm.coverCacheCount == 0) {
                "当前没有缓存"
            } else {
                "已缓存 ${vm.coverCacheCount} 张封面，清除后下次会重新联网找"
            },
            onClick = { vm.clearCoverCache() },
            actionLabel = "清除"
        )
    }
    // 手动选封面的对话框统一在 SonanceRoot 里渲染，这里只放入口按钮
}

/* ============================================================
 * 在线音乐
 * ============================================================ */

@Composable
private fun OnlineMusicSettingsSection(vm: SonanceViewModel) {
    val skin = LocalSkin.current

    SettingsSection(title = "在线音乐") {
        SettingsSwitchRow(
            title = "在线搜索与播放",
            subtitle = "开启后可以在「在线音乐」页搜索并播放网络歌曲。默认关闭 —— " +
                "打开后搜索词会发到音源，播放时从音源的 CDN 拉音频流。",
            checked = vm.onlineMusicEnabled,
            onCheckedChange = { vm.changeOnlineMusic(it) }
        )

        // 这个开关**不受上面那个影响** —— 它管的是本地歌的封面 / 歌词补全，
        // 和"要不要能搜歌、放歌"是两件事
        SettingsSwitchRow(
            title = "用网易云补全本地歌",
            subtitle = "本地曲库的封面 / 歌词取不到时，改用网易云再找一次。默认关闭。\n" +
                "它比现有的两个源命中率高：匹配时**带时长校验**，能避开同名翻唱和 Live 版" +
                "（iTunes 只按歌名歌手搜，容易贴错封面）；歌词还带中文翻译。\n" +
                "华语歌会优先问网易云，西洋歌优先问 iTunes，先有结果的先用。",
            checked = vm.neteaseFillEnabled,
            onCheckedChange = { vm.changeNeteaseFill(it) }
        )

    // ===== 播放并下载 =====

    /**
     * Android 9 及以下写公开音乐目录需要 `WRITE_EXTERNAL_STORAGE`。
     *
     * manifest 里已经用 `maxSdkVersion="28"` 声明了，但**运行时还得请求** ——
     * 光声明不请求的话，老系统上写入会失败、而调用方只看到"打不开输出流"，
     * 表现为"下载了但一直失败"，很难查。
     *
     * Android 10 起走 MediaStore 插入自己的媒体文件，不需要任何权限，
     * 所以这里只在 SDK <= 28 时才请求。
     */
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
                "存的是**正在播的那条流**（标准音质），不是官方下载接口的高音质版本 —— " +
                "那些端点要登录 + 会员，这里刻意不碰。\n" +
                "存下的文件会写标签（歌名 / 歌手 / 专辑 / 封面），" +
                "放在「${OnlineDownloader.FOLDER_NAME}」文件夹里 —— " +
                "重新扫描后「文件夹」页会有这个分类。",
            checked = vm.onlineDownloadEnabled,
            onCheckedChange = { on ->
                if (on && Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                    ContextCompat.checkSelfPermission(
                        context, Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    // 先拿到权限再开开关 —— 否则开关亮着但每次都失败
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
                    subtitle = "默认开启 —— 不然在移动网络下随便听几张专辑就悄悄吃掉几百 MB。"
                        + "\n判断不出网络类型时会跳过，不会赌一把。",
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
                    // 明确告诉用户"文件在哪、我们不删" —— 一个播放器批量删用户文件
                    // 是很越界的事，这里只提供清记录
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
                    chips = OnlineSources.all.map { it.label to (it.id == vm.onlineSourceId) },
                    onSelect = { vm.changeOnlineSource(OnlineSources.all[it].id) }
                )
            }

            Spacer(Modifier.height(14.dp))
            Text(
                "• 能播的是音源上免费开放的曲目；标 VIP 的需要会员，取流接口拿不到地址\n" +
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
 * 「关于」里那句联网状态说明。
 *
 * 必须把三个开关都算上。原来只判断在线歌词 —— 加了在线音乐之后，
 * 一个开着在线音乐、关着在线歌词的用户会看到「当前处于完全离线状态」，
 * 那是句明显的假话。
 */
private fun onlineNotice(vm: SonanceViewModel): String {
    val on = buildList {
        if (vm.onlineLyricsEnabled) add("在线歌词")
        if (vm.onlineCoverEnabled) add("在线封面")
        if (vm.onlineMusicEnabled) add("在线音乐")
        if (vm.neteaseFillEnabled) add("网易云补全")
    }
    return if (on.isEmpty()) {
        "联网只在你手动开启「在线歌词」「在线封面」「在线音乐」「网易云补全」后才会发生，" +
            "当前处于完全离线状态。"
    } else {
        "⚠️ 你已开启「${on.joinToString("」「")}」，相关功能会联网。" +
            "搜索只发送你输入的关键词，不发送任何本地路径或文件。"
    }
}

/* ============================================================
 * 重复 / 同名歌曲清理
 * ============================================================ */

@Composable
private fun DuplicateCleanupSection(vm: SonanceViewModel) {
    val skin = LocalSkin.current
    val report = vm.duplicateReport

    SettingsSection(title = "重复与同名歌曲") {
        Column(Modifier.padding(vertical = 4.dp)) {
            Text(
                "只从曲库列表里隐藏，**不会删除或改动任何文件**，随时可以恢复。",
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
                onClick = { vm.cleanDuplicates(includeSameName = false) },
                actionLabel = "清理"
            )

            SettingsActionRow(
                title = "清理同名歌曲",
                subtitle = "同名但时长不同（可能是不同版本），保留时长最长的那份 —— 请先检测确认",
                onClick = { vm.cleanDuplicates(includeSameName = true) },
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
        }
    }
}

/* ============================================================
 * 应用图标网格
 * ============================================================ */

@Composable
private fun AppIconGrid(currentId: String, onSelect: (String) -> Unit) {
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
private fun AppIconCard(
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
 * 设置项
 * ============================================================ */

/**
 * 可折叠的设置区块。
 *
 * 皮肤（14 套）和图标（40 款）这两块的选项卡加起来有几十张，全部展开会把下面
 * **所有**设置项挤到好几屏之外 —— 所以默认收起，点标题栏展开。
 *
 * [summary] 会一直显示在标题下面，收起状态下也能看到「当前是哪套皮肤 / 有多少款图标」，
 * 不至于因为折叠而丢失信息。
 */
@Composable
private fun CollapsibleSection(
    title: String,
    summary: String,
    icon: ImageVector,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit
) {
    val skin = LocalSkin.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(skin.surface)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = skin.accent,
                modifier = Modifier.size(17.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = skin.text
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    summary,
                    style = MaterialTheme.typography.labelSmall,
                    color = skin.textMuted
                )
            }
            Spacer(Modifier.width(10.dp))
            Icon(
                imageVector = SonanceIcons.ChevronDown,
                contentDescription = if (expanded) "收起" else "展开",
                tint = skin.textDim,
                modifier = Modifier
                    .size(18.dp)
                    // 收起时箭头朝下（提示"可展开"），展开后翻上来
                    .rotate(if (expanded) 180f else 0f)
            )
        }
        // 只在展开时组合内容：40 款图标卡每张都要解码一个矢量预览，
        // 收起来就不该付这份钱
        if (expanded) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp, bottom = 14.dp)
            ) {
                content()
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit
) {
    val skin = LocalSkin.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(skin.surface)
            .padding(14.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = skin.textMuted
        )
        Spacer(Modifier.height(10.dp))
        content()
    }
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
 * 在「重新扫描」按钮下面显示缓存新鲜度：有缓存就告诉用户多久前扫的、没有就提示「首次启动会扫」。
 */
internal fun buildLastScanSubtitle(context: android.content.Context, songCount: Int): String {
    val lastAt = com.sonance.player.data.Prefs(context).lastScanAt
    if (lastAt <= 0L) {
        return if (songCount == 0) "首次启动会扫描一次，之后秒开"
        else "未记录扫描时间"
    }
    val mins = ((System.currentTimeMillis() - lastAt) / 60000L).coerceAtLeast(0)
    val rel = when {
        mins < 1 -> "刚刚"
        mins < 60 -> "${mins} 分钟前"
        mins < 24 * 60 -> "${mins / 60} 小时前"
        else -> "${mins / (24 * 60)} 天前"
    }
    return "上次扫描：$rel · $songCount 首"
}
