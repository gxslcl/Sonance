package com.sonance.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import com.sonance.player.data.formatDuration
import com.sonance.player.data.formatSize
import com.sonance.player.ui.components.SonanceSlider
import com.sonance.player.data.Prefs
import com.sonance.player.ui.components.PillButton
import com.sonance.player.ui.icon.SonanceIcons
import com.sonance.player.ui.theme.AppIconVariant
import com.sonance.player.ui.theme.AppIcons
import com.sonance.player.ui.theme.LocalSkin
import com.sonance.player.ui.theme.Skin
import com.sonance.player.ui.theme.Skins
import com.sonance.player.ui.components.GlassControls

@Composable
fun SettingsScreen(vm: SonanceViewModel, modifier: Modifier = Modifier) {
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

        // ===== 歌词 =====
        LyricsSettingsSection(vm = vm)

        // ===== 封面 =====
        CoverSettingsSection(vm = vm)

        // ===== 关于 =====
        SettingsSection(title = "关于") {
            Column(Modifier.padding(vertical = 6.dp)) {
                Text("山水有清音 · 本地音乐播放器", style = MaterialTheme.typography.titleSmall, color = skin.text)
                Spacer(Modifier.height(4.dp))
                Text(
                    "版本 1.0.0 · 播放、封面、歌词全部本地解析，不上传任何数据。\n" +
                        if (vm.onlineLyricsEnabled) {
                            "⚠️ 你已开启「在线歌词」：本地找不到歌词时会向歌词接口发一次请求（只带歌名/歌手/专辑，不带任何本地路径或文件）。"
                        } else {
                            "联网仅在你手动开启「在线歌词」后才会发生，当前处于完全离线状态。"
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (vm.onlineLyricsEnabled) skin.accent else skin.textMuted
                )
            }
        }

        Spacer(Modifier.height(24.dp))
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
        SettingsSwitchRow(
            title = "在线补全封面（iTunes Search API）",
            subtitle = "本地没有内嵌封面时，联网按「歌名 + 歌手」搜一张。默认关闭 —— " +
                "打开后播放到无封面的歌才会查一次；只发送歌名/歌手/专辑，不发送文件或路径。",
            checked = vm.onlineCoverEnabled,
            onCheckedChange = { vm.changeOnlineCover(it) }
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
