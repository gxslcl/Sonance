package com.sonance.player.ui.sheets

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sonance.player.SonanceViewModel
import com.sonance.player.playback.AudioOutputRoute
import com.sonance.player.playback.EQ_BAND_COUNT
import com.sonance.player.playback.EQ_CENTER_FREQS
import com.sonance.player.playback.EQ_MAX_DB
import com.sonance.player.playback.EQ_MIN_DB
import com.sonance.player.playback.EqPreset
import com.sonance.player.playback.EqResponse
import com.sonance.player.playback.SoundFxParams
import com.sonance.player.playback.SoundPreset
import com.sonance.player.playback.SoundPresetCategory
import com.sonance.player.playback.SoundPresets
import com.sonance.player.playback.effectPreampBudgetDb
import com.sonance.player.playback.eqAutoPreampDb
import com.sonance.player.playback.eqBandLabel
import com.sonance.player.playback.eqDbFromSlider
import com.sonance.player.playback.presetNetCurve
import com.sonance.player.ui.components.PillButton
import com.sonance.player.ui.components.SonanceSlider
import com.sonance.player.ui.components.hapticTick
import com.sonance.player.ui.icon.SonanceIcons
import com.sonance.player.ui.theme.LocalSkin
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

private enum class StudioTab(val label: String) {
    PRESETS("音色预设"),
    EQUALIZER("均衡器"),
    EFFECTS("声场效果"),
    KARAOKE("伴奏消音")
}

private enum class PresetFilter(val label: String, val category: SoundPresetCategory?) {
    ALL("全部", null),
    SONANCE("自然", SoundPresetCategory.SONANCE),
    HEADPHONE("耳机", SoundPresetCategory.HEADPHONE),
    SPEAKER("外放", SoundPresetCategory.SPEAKER),
    SPACE("空间", SoundPresetCategory.SPACE),
    ORIGINAL("原声", SoundPresetCategory.ORIGINAL)
}

/**
 * 全屏音效工作室：整合精选音色预设、实时 10 段图形均衡器（竖向混音台/精细滑条双视图）、
 * DSP 空间微调、输出通道自动记忆与伴奏消音。
 */
@Composable
fun EqualizerStudioScreen(
    vm: SonanceViewModel,
    onDismiss: () -> Unit
) {
    val skin = LocalSkin.current
    var selectedTab by rememberSaveable { mutableStateOf(StudioTab.PRESETS) }
    var presetFilter by rememberSaveable { mutableStateOf(PresetFilter.ALL) }
    var horizontalEqMode by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = true) { onDismiss() }

    // 面板销毁时确保退出 A/B 旁通，防止用户按住对比时关闭而停留在原声。
    DisposableEffect(Unit) {
        onDispose { vm.setSoundFxBypass(false) }
    }

    val hasAnyProcessing = vm.soundFxEnabled || vm.eqEnabled
    val scrollState = rememberScrollState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(skin.bg)
            // 吸收底层页面点击，避免退出动画或空白区域的点击穿透到底层播放页或曲库列表。
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = {}
            )
    ) {
        // 顶部柔和工作室氛围光晕
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            skin.accent.copy(alpha = if (skin.isLight) 0.09f else 0.16f),
                            skin.accentGlow.copy(alpha = if (skin.isLight) 0.04f else 0.06f),
                            Color.Transparent
                        )
                    )
                )
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 820.dp)
                    .fillMaxSize()
            ) {
                StudioTopHeader(
                    route = vm.currentOutputRoute,
                    processingActive = hasAnyProcessing,
                    bypassing = vm.soundFxBypassed,
                    onBypassHold = { vm.setSoundFxBypass(it) },
                    onDismiss = onDismiss
                )

                StudioSegmentedTabs(
                    selected = selectedTab,
                    onSelect = { selectedTab = it },
                    presetsActive = vm.soundFxEnabled && vm.soundPreset.id != SoundPresets.OFF.id,
                    eqActive = vm.eqEnabled,
                    fxActive = vm.soundFxEnabled && !vm.soundFxParams.isNeutral,
                    karaokeActive = vm.karaokeEnabled,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(scrollState)
                        .padding(horizontal = 16.dp)
                        .padding(top = 8.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    when (selectedTab) {
                        StudioTab.PRESETS -> PresetsTabContent(
                            vm = vm,
                            filter = presetFilter,
                            onFilterChange = { presetFilter = it }
                        )

                        StudioTab.EQUALIZER -> EqualizerTabContent(
                            vm = vm,
                            horizontalMode = horizontalEqMode,
                            onToggleMode = { horizontalEqMode = !horizontalEqMode }
                        )

                        StudioTab.EFFECTS -> EffectsTabContent(vm = vm)

                        StudioTab.KARAOKE -> KaraokeTabContent(vm = vm)
                    }

                    Text(
                        text = SoundPreset.ORIGIN_SELF,
                        style = MaterialTheme.typography.labelSmall,
                        color = skin.textMuted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                    )
                }
            }
        }
    }
}

/* ============================================================
 * 顶部导航与分段页签
 * ============================================================ */

@Composable
private fun StudioTopHeader(
    route: AudioOutputRoute,
    processingActive: Boolean,
    bypassing: Boolean,
    onBypassHold: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val skin = LocalSkin.current
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(skin.surface2.copy(alpha = 0.9f))
                .border(1.dp, skin.border2, CircleShape)
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = SonanceIcons.ArrowLeft,
                contentDescription = "返回",
                tint = skin.text,
                modifier = Modifier.size(18.dp)
            )
        }

        Column(Modifier.weight(1f)) {
            Text(
                text = "音效工作室",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = skin.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                bypassing -> skin.textMuted
                                processingActive -> skin.accent
                                else -> skin.textMuted.copy(alpha = 0.55f)
                            }
                        )
                )
                Text(
                    text = when {
                        bypassing -> "${route.label} · 正在对比原声"
                        processingActive -> "${route.label} · 软 DSP 实时处理"
                        else -> "${route.label} · 原声直通"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (bypassing) skin.accent else skin.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        val abBg by animateColorAsState(
            targetValue = when {
                bypassing -> skin.accent
                processingActive -> skin.surface2
                else -> skin.surface2.copy(alpha = 0.55f)
            },
            animationSpec = tween(150),
            label = "abHoldBg"
        )
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(abBg)
                .border(
                    width = 1.dp,
                    color = if (bypassing) skin.accent else skin.border2,
                    shape = RoundedCornerShape(18.dp)
                )
                .alpha(if (processingActive) 1f else 0.45f)
                .semantics {
                    contentDescription = "按住对比原声"
                    stateDescription = if (bypassing) "当前为原声" else "当前为已处理音效"
                    if (processingActive) {
                        onClick(label = "切换原声对比") {
                            onBypassHold(!bypassing)
                            true
                        }
                    }
                }
                .pointerInput(processingActive) {
                    if (!processingActive) return@pointerInput
                    detectTapGestures(
                        onPress = {
                            hapticTick(context)
                            onBypassHold(true)
                            tryAwaitRelease()
                            onBypassHold(false)
                        }
                    )
                }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (bypassing) "听原声中" else "按住对比",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (bypassing) skin.onAccent else skin.text
            )
        }
    }
}

@Composable
private fun StudioSegmentedTabs(
    selected: StudioTab,
    onSelect: (StudioTab) -> Unit,
    presetsActive: Boolean,
    eqActive: Boolean,
    fxActive: Boolean,
    karaokeActive: Boolean,
    modifier: Modifier = Modifier
) {
    val skin = LocalSkin.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(skin.surface2.copy(alpha = 0.9f))
            .border(1.dp, skin.border, RoundedCornerShape(16.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        StudioTab.entries.forEach { tab ->
            val isSelected = tab == selected
            val isActiveDot = when (tab) {
                StudioTab.PRESETS -> presetsActive
                StudioTab.EQUALIZER -> eqActive
                StudioTab.EFFECTS -> fxActive
                StudioTab.KARAOKE -> karaokeActive
            }
            val bgColor by animateColorAsState(
                targetValue = if (isSelected) skin.surface else Color.Transparent,
                animationSpec = tween(180),
                label = "tabBg"
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(bgColor)
                    .then(
                        if (isSelected) {
                            Modifier.border(1.dp, skin.border2, RoundedCornerShape(12.dp))
                        } else {
                            Modifier
                        }
                    )
                    .selectable(
                        selected = isSelected,
                        role = Role.Tab,
                        onClick = { onSelect(tab) }
                    )
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (isActiveDot) {
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(skin.accent)
                        )
                    }
                    Text(
                        text = tab.label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) skin.text else skin.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/* ============================================================
 * 页签 1：音色预设（总览仪表盘 + 输出硬件记忆 + 分类精选卡片）
 * ============================================================ */

@Composable
private fun PresetsTabContent(
    vm: SonanceViewModel,
    filter: PresetFilter,
    onFilterChange: (PresetFilter) -> Unit
) {
    StudioHeroCard(vm = vm)
    OutputRouteAutoCard(vm = vm)
    PresetFilterBar(selected = filter, onSelect = onFilterChange)

    val presets = remember(filter) {
        val cat = filter.category
        if (cat == null) SoundPresets.ALL else SoundPresets.ALL.filter { it.category == cat }
    }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = when {
            maxWidth >= 560.dp -> 3
            maxWidth >= 320.dp -> 2
            else -> 1
        }
        val rows = remember(presets, columns) { presets.chunked(columns) }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            rows.forEach { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    rowItems.forEach { preset ->
                        val selected = preset.id == vm.soundPreset.id &&
                            (preset.id == SoundPresets.OFF.id || vm.soundFxEnabled)
                        PresetStudioCard(
                            preset = preset,
                            selected = selected,
                            onClick = { vm.chooseSoundPreset(preset) },
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StudioHeroCard(vm: SonanceViewModel) {
    val skin = LocalSkin.current
    val fxOn = vm.soundFxEnabled
    val eqOn = vm.eqEnabled
    val preset = vm.soundPreset
    val isPurePassThrough = !fxOn && !eqOn
    val isCustomCurve = eqOn && !vm.eqFromSoundPreset

    val activeTitle = when {
        vm.soundFxBypassed -> "原声旁通对比中"
        isPurePassThrough -> "原声直通"
        fxOn && preset.id != SoundPresets.OFF.id -> preset.name
        isCustomCurve && vm.eqPreset != EqPreset.OFF -> "均衡器 · ${vm.eqPreset.label}"
        eqOn -> "自定义频响曲线"
        else -> "仅效果引擎开启"
    }

    val statusBadge = when {
        vm.soundFxBypassed -> "临时旁通"
        vm.soundFxCustom || (eqOn && vm.eqCustom) -> "已微调"
        !isPurePassThrough -> "工作中"
        else -> "未开启"
    }

    val netCurve = remember(vm.eqBands, eqOn, vm.soundFxParams, fxOn) {
        computeLiveNetCurve(
            eqBands = vm.eqBands,
            eqEnabled = eqOn,
            fxParams = vm.soundFxParams,
            fxEnabled = fxOn,
            points = 56
        )
    }

    val toneBands = remember(vm.eqBands, eqOn, vm.soundFxParams, fxOn) {
        computeLiveToneBands(
            eqBands = vm.eqBands,
            eqEnabled = eqOn,
            fxParams = vm.soundFxParams,
            fxEnabled = fxOn
        )
    }

    val moduleTags = remember(vm.soundFxParams, fxOn, eqOn) {
        buildActiveDspTags(vm.soundFxParams, fxOn, eqOn)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(
                Brush.verticalGradient(
                    listOf(skin.surface2, skin.surface)
                )
            )
            .border(
                width = 1.dp,
                color = if (!isPurePassThrough && !vm.soundFxBypassed) {
                    skin.accent.copy(alpha = 0.45f)
                } else {
                    skin.border2
                },
                shape = RoundedCornerShape(22.dp)
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = activeTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = skin.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    StudioTagPill(
                        label = statusBadge,
                        highlighted = !isPurePassThrough || vm.soundFxBypassed
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (isPurePassThrough) {
                        "保持原始音频帧逐样本无损直出，选择下方音色卡片即可实时调音"
                    } else if (fxOn && preset.id != SoundPresets.OFF.id) {
                        preset.description
                    } else {
                        "当前运行实时均衡与声场处理链路"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = skin.textDim,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            StudioSwitch(
                checked = fxOn,
                onChange = { vm.toggleSoundFx(it) },
                label = "音效总开关"
            )
        }

        val ghostCurve = remember(eqOn, fxOn, vm.eqCustom, preset.id) {
            if (eqOn && fxOn && vm.eqCustom && preset.id != SoundPresets.OFF.id) {
                presetNetCurve(preset, 56)
            } else {
                null
            }
        }

        StudioCurveGraph(
            curve = netCurve,
            ghostCurve = ghostCurve,
            heightDp = 96f,
            accentActive = !isPurePassThrough && !vm.soundFxBypassed
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ToneMeterPill("低频", toneBands[0], Modifier.weight(1f))
            ToneMeterPill("中频", toneBands[1], Modifier.weight(1f))
            ToneMeterPill("高频", toneBands[2], Modifier.weight(1f))
        }

        if (moduleTags.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                moduleTags.forEach { tag ->
                    StudioTagPill(label = tag, highlighted = false)
                }
            }
        }
    }
}

@Composable
private fun OutputRouteAutoCard(vm: SonanceViewModel) {
    val skin = LocalSkin.current
    val route = vm.currentOutputRoute
    val routeIcon = routeIconOf(route)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(skin.surface2.copy(alpha = 0.85f))
            .border(1.dp, skin.border, RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(skin.accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = routeIcon,
                contentDescription = null,
                tint = skin.accent,
                modifier = Modifier.size(19.dp)
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = "当前输出：${route.label}",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = skin.text
            )
            Text(
                text = if (vm.autoPresetByOutputEnabled) {
                    "已启用按输出设备记忆 · 切换耳机/蓝牙/外放自动还原调音"
                } else {
                    "开启后将为不同输出设备分别记住专属音效"
                },
                style = MaterialTheme.typography.labelSmall,
                color = skin.textMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        StudioSwitch(
            checked = vm.autoPresetByOutputEnabled,
            onChange = { vm.toggleAutoPresetByOutput(it) },
            label = "按输出设备自动记忆"
        )
    }
}

@Composable
private fun PresetFilterBar(
    selected: PresetFilter,
    onSelect: (PresetFilter) -> Unit
) {
    val skin = LocalSkin.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        PresetFilter.entries.forEach { item ->
            val isSelected = item == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (isSelected) skin.accent else skin.surface2)
                    .border(
                        width = 1.dp,
                        color = if (isSelected) skin.accent else skin.border,
                        shape = RoundedCornerShape(20.dp)
                    )
                    .selectable(
                        selected = isSelected,
                        role = Role.RadioButton,
                        onClick = { onSelect(item) }
                    )
                    .padding(horizontal = 14.dp, vertical = 7.dp)
            ) {
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (isSelected) skin.onAccent else skin.textDim
                )
            }
        }
    }
}

@Composable
private fun PresetStudioCard(
    preset: SoundPreset,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val skin = LocalSkin.current
    val previewCurve = remember(preset.id) { presetNetCurve(preset, 32) }
    val featureLabel = remember(preset.id) { buildPresetSummaryChip(preset) }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(
                if (selected) skin.accent.copy(alpha = if (skin.isLight) 0.12f else 0.16f)
                else skin.surface2.copy(alpha = 0.85f)
            )
            .border(
                width = if (selected) 1.4.dp else 1.dp,
                color = if (selected) skin.accent else skin.border,
                shape = RoundedCornerShape(18.dp)
            )
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = preset.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = if (selected) skin.accent else skin.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(skin.accent),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = SonanceIcons.Check,
                        contentDescription = "已选中",
                        tint = skin.onAccent,
                        modifier = Modifier.size(11.dp)
                    )
                }
            } else {
                Text(
                    text = preset.category.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = skin.textMuted
                )
            }
        }

        PresetMiniSparkline(
            curve = previewCurve,
            highlighted = selected,
            modifier = Modifier
                .fillMaxWidth()
                .height(30.dp)
        )

        Text(
            text = preset.character,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) skin.text else skin.textDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Text(
            text = featureLabel,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) skin.accent else skin.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/* ============================================================
 * 页签 2：10 段均衡器（实时频响图 + 竖向混音台/精细滑条 + 曲风快选）
 * ============================================================ */

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EqualizerTabContent(
    vm: SonanceViewModel,
    horizontalMode: Boolean,
    onToggleMode: () -> Unit
) {
    val skin = LocalSkin.current
    val eqLabel = when {
        vm.eqCustom -> "自定义调音"
        vm.eqFromSoundPreset -> "随预设：${vm.soundPreset.name}"
        else -> vm.eqPreset.label
    }

    val curve = remember(vm.eqBands, vm.eqEnabled, vm.soundFxParams, vm.soundFxEnabled) {
        computeLiveNetCurve(
            eqBands = vm.eqBands,
            eqEnabled = vm.eqEnabled,
            fxParams = vm.soundFxParams,
            fxEnabled = vm.soundFxEnabled,
            points = 64
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(skin.surface2.copy(alpha = 0.9f))
            .border(1.dp, skin.border, RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "10 段图形均衡器",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = skin.text
                )
                Text(
                    text = if (vm.eqEnabled) "$eqLabel · 双击频段标签可单项归零" else "当前已关闭（拖动任一频段自动激活）",
                    style = MaterialTheme.typography.labelSmall,
                    color = skin.textMuted
                )
            }
            StudioSwitch(
                checked = vm.eqEnabled,
                onChange = { vm.toggleEq(it) },
                label = "均衡器总开关"
            )
        }

        StudioCurveGraph(
            curve = curve,
            ghostCurve = null,
            heightDp = 104f,
            accentActive = vm.eqEnabled
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PillButton(
                label = if (horizontalMode) "竖向混音台" else "精细横条",
                onClick = onToggleMode,
                filled = false,
                compact = true
            )
            Spacer(Modifier.weight(1f))
            PillButton(
                label = "全部归零",
                onClick = { vm.resetEqBands() },
                filled = false,
                compact = true,
                enabled = vm.eqEnabled || vm.eqBands.any { abs(it) > 0.05f }
            )
        }

        if (horizontalMode) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                EQ_CENTER_FREQS.forEachIndexed { index, hz ->
                    val db = vm.eqBands.getOrElse(index) { 0f }
                    HorizontalEqBandRow(
                        label = eqBandLabel(hz),
                        db = db,
                        minDb = vm.eqMinDb,
                        maxDb = vm.eqMaxDb,
                        highlighted = vm.eqEnabled && abs(db) > 0.05f,
                        onDrag = { vm.setEqBand(index, it) },
                        onResetBand = { vm.setEqBand(index, 0f) }
                    )
                }
            }
        } else {
            VerticalEqMixerBank(
                bands = vm.eqBands,
                eqEnabled = vm.eqEnabled,
                minDb = vm.eqMinDb,
                maxDb = vm.eqMaxDb,
                onChangeBand = { index, db -> vm.setEqBand(index, db) }
            )
        }
    }

    // 曲风快捷预设卡片
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(skin.surface2.copy(alpha = 0.85f))
            .border(1.dp, skin.border, RoundedCornerShape(18.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "曲风快捷曲线",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = skin.text
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            EqPreset.entries.forEach { preset ->
                val isSelected = !vm.eqCustom && !vm.eqFromSoundPreset &&
                    preset == vm.eqPreset &&
                    (preset != EqPreset.OFF || !vm.eqEnabled)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (isSelected) skin.accent else skin.surface3)
                        .border(
                            width = 1.dp,
                            color = if (isSelected) skin.accent else skin.border2,
                            shape = RoundedCornerShape(20.dp)
                        )
                        .selectable(
                            selected = isSelected,
                            role = Role.RadioButton,
                            onClick = { vm.chooseEqPreset(preset) }
                        )
                        .padding(horizontal = 13.dp, vertical = 7.dp)
                ) {
                    Text(
                        text = preset.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isSelected) skin.onAccent else skin.textDim
                    )
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun VerticalEqMixerBank(
    bands: FloatArray,
    eqEnabled: Boolean,
    minDb: Float,
    maxDb: Float,
    onChangeBand: (Int, Float) -> Unit
) {
    val skin = LocalSkin.current
    val context = LocalContext.current
    val span = (maxDb - minDb).coerceAtLeast(0.001f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(skin.surface.copy(alpha = 0.72f))
            .border(1.dp, skin.border, RoundedCornerShape(16.dp))
            .padding(horizontal = 6.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            EQ_CENTER_FREQS.forEachIndexed { index, hz ->
                val db = bands.getOrElse(index) { 0f }
                val active = eqEnabled && abs(db) > 0.05f
                val normalized = ((db - minDb) / span).coerceIn(0f, 1f)

                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = formatShortDb(db),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 9.5.sp,
                            fontFamily = FontFamily.Monospace,
                            fontFeatureSettings = "tnum"
                        ),
                        color = if (active) skin.accent else skin.textMuted,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .combinedClickable(
                                onClick = {},
                                onDoubleClick = {
                                    hapticTick(context)
                                    onChangeBand(index, 0f)
                                }
                            )
                            .padding(vertical = 2.dp)
                    )

                    Spacer(Modifier.height(6.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(172.dp)
                            .semantics {
                                contentDescription = "${eqBandLabel(hz)} 频段"
                                stateDescription = "%.1f 分贝".format(db)
                                progressBarRangeInfo = ProgressBarRangeInfo(
                                    db.coerceIn(minDb, maxDb),
                                    minDb..maxDb
                                )
                                setProgress { requested ->
                                    onChangeBand(index, requested.coerceIn(minDb, maxDb))
                                    true
                                }
                            }
                            .pointerInput(index, minDb, maxDb) {
                                awaitEachGesture {
                                    val down = awaitFirstDown()
                                    val h = size.height.toFloat().coerceAtLeast(1f)
                                    val slop = viewConfiguration.touchSlop
                                    var dx = 0f
                                    var dy = 0f
                                    var dragging = false
                                    var abandoned = false

                                    fun fractionAt(y: Float): Float =
                                        (1f - (y / h)).coerceIn(0f, 1f)

                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!change.pressed) break
                                        dx += change.position.x - change.previousPosition.x
                                        dy += change.position.y - change.previousPosition.y

                                        if (!dragging && !abandoned) {
                                            if (abs(dy) > slop && abs(dy) >= abs(dx) * 0.85f) {
                                                dragging = true
                                                onChangeBand(index, eqDbFromSlider(fractionAt(change.position.y), minDb, maxDb))
                                                change.consume()
                                            } else if (abs(dx) > slop * 1.4f && abs(dx) > abs(dy) * 1.3f) {
                                                abandoned = true
                                            }
                                        } else if (dragging) {
                                            onChangeBand(index, eqDbFromSlider(fractionAt(change.position.y), minDb, maxDb))
                                            change.consume()
                                        }
                                    }

                                    if (!dragging && !abandoned) {
                                        onChangeBand(index, eqDbFromSlider(fractionAt(down.position.y), minDb, maxDb))
                                    }
                                }
                            }
                    ) {
                        Canvas(Modifier.fillMaxSize()) {
                            val cx = size.width / 2f
                            val padV = 8.dp.toPx()
                            val trackTop = padV
                            val trackBottom = size.height - padV
                            val trackLen = (trackBottom - trackTop).coerceAtLeast(1f)
                            val trackW = 4.dp.toPx()
                            val zeroRatio = ((0f - minDb) / span).coerceIn(0f, 1f)
                            val zeroY = trackBottom - zeroRatio * trackLen
                            val thumbY = trackBottom - normalized * trackLen

                            // 中性 0dB 横标与半程参考线
                            drawLine(
                                color = skin.border2.copy(alpha = 0.7f),
                                start = Offset(2.dp.toPx(), zeroY),
                                end = Offset(size.width - 2.dp.toPx(), zeroY),
                                strokeWidth = 1.dp.toPx()
                            )

                            // 轨道底槽
                            drawRoundRect(
                                color = skin.surface3,
                                topLeft = Offset(cx - trackW / 2f, trackTop),
                                size = Size(trackW, trackLen),
                                cornerRadius = CornerRadius(trackW / 2f)
                            )

                            // 0 dB 起点的填充段
                            val fillTop = minOf(zeroY, thumbY)
                            val fillH = abs(thumbY - zeroY)
                            if (fillH > 0.5f) {
                                drawRoundRect(
                                    color = if (eqEnabled) skin.accent else skin.textMuted,
                                    topLeft = Offset(cx - trackW / 2f, fillTop),
                                    size = Size(trackW, fillH),
                                    cornerRadius = CornerRadius(trackW / 2f)
                                )
                            }

                            // 推子滑块头
                            val thumbR = 6.5.dp.toPx()
                            drawCircle(
                                color = if (eqEnabled) skin.accent.copy(alpha = 0.24f) else Color.Transparent,
                                radius = thumbR + 3.dp.toPx(),
                                center = Offset(cx, thumbY)
                            )
                            drawCircle(
                                color = if (active || eqEnabled) skin.accent else skin.textDim,
                                radius = thumbR,
                                center = Offset(cx, thumbY)
                            )
                            drawCircle(
                                color = skin.surface,
                                radius = thumbR * 0.36f,
                                center = Offset(cx, thumbY)
                            )
                        }
                    }

                    Spacer(Modifier.height(6.dp))

                    Text(
                        text = eqBandLabel(hz),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        color = if (active) skin.text else skin.textMuted,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .combinedClickable(
                                onClick = {},
                                onDoubleClick = {
                                    hapticTick(context)
                                    onChangeBand(index, 0f)
                                }
                            )
                            .padding(vertical = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun HorizontalEqBandRow(
    label: String,
    db: Float,
    minDb: Float,
    maxDb: Float,
    highlighted: Boolean,
    onDrag: (Float) -> Unit,
    onResetBand: () -> Unit
) {
    val skin = LocalSkin.current
    val span = (maxDb - minDb).coerceAtLeast(0.001f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (highlighted) skin.accent else skin.textMuted,
            modifier = Modifier
                .width(36.dp)
                .clickable(onClick = onResetBand)
        )
        SonanceSlider(
            value = ((db - minDb) / span).coerceIn(0f, 1f),
            onValueChange = { onDrag(eqDbFromSlider(it, minDb, maxDb)) },
            modifier = Modifier
                .weight(1f)
                .semantics {
                    contentDescription = "$label 频段"
                    stateDescription = "%.1f 分贝".format(db)
                    progressBarRangeInfo = ProgressBarRangeInfo(db.coerceIn(minDb, maxDb), minDb..maxDb)
                    setProgress { requested ->
                        onDrag(requested.coerceIn(minDb, maxDb))
                        true
                    }
                },
            trackHeight = 3.dp,
            thumbSize = 13.dp,
            alwaysShowThumb = true,
            neutralPoint = ((0f - minDb) / span).coerceIn(0f, 1f)
        )
        Text(
            text = formatShortDb(db),
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontFeatureSettings = "tnum"
            ),
            color = if (highlighted) skin.accent else skin.textDim,
            textAlign = TextAlign.End,
            modifier = Modifier
                .width(44.dp)
                .padding(start = 6.dp)
        )
    }
}

/* ============================================================
 * 页签 3：DSP 效果微调
 * ============================================================ */

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EffectsTabContent(vm: SonanceViewModel) {
    val skin = LocalSkin.current
    val fx = vm.soundFxParams

    // 顶栏状态与一键还原
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(skin.surface2.copy(alpha = 0.9f))
            .border(1.dp, skin.border, RoundedCornerShape(18.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "DSP 声场与保护引擎",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = skin.text
            )
            Text(
                text = when {
                    !vm.soundFxEnabled -> "当前已旁路 · 调节任一参数将自动开启"
                    vm.soundFxCustom -> "已基于「${vm.soundPreset.name}」手动微调"
                    else -> "当前使用「${vm.soundPreset.name}」默认配方"
                },
                style = MaterialTheme.typography.labelSmall,
                color = skin.textMuted
            )
        }
        if (vm.soundFxCustom) {
            PillButton(
                label = "还原预设",
                onClick = {
                    if (vm.soundPreset.id == SoundPresets.OFF.id) {
                        vm.chooseSoundPreset(SoundPresets.OFF)
                    } else {
                        vm.updateSoundFx(vm.soundPreset.fx)
                    }
                    vm.showToast("已还原当前预设效果参数")
                },
                filled = false,
                compact = true,
                modifier = Modifier.padding(end = 8.dp)
            )
        }
        StudioSwitch(
            checked = vm.soundFxEnabled,
            onChange = { vm.toggleSoundFx(it) },
            label = "DSP 效果引擎开关"
        )
    }

    // 模块一：低频与扬声器保护
    FxModuleCard(title = "低频增强与扬声器保护", subtitle = "谐波合成低音 · 物理振膜过载拦截") {
        FxSliderControl(
            label = "虚拟低音下潜",
            hint = "在高通滤波之前提取基频，动态生成二/三次谐波，小单元也能感受到低音弹性",
            value = fx.virtualBass,
            min = 0f,
            max = 1f,
            valueLabel = percent(fx.virtualBass),
            onValue = { vm.updateSoundFx(fx.copy(virtualBass = it)) }
        )

        FxSliderControl(
            label = "外放保护高通截止",
            hint = "切除微型喇叭无法还原的超低频震动防止破音；耳机/Hi-Fi 建议设为关闭",
            value = fx.speakerHpfHz,
            min = 0f,
            max = 160f,
            valueLabel = if (fx.speakerHpfHz < 20f) "关闭" else "${fx.speakerHpfHz.roundToInt()} Hz",
            onValue = { raw ->
                val quantized = if (raw < 25f) 0f else raw.coerceIn(30f, 160f)
                vm.updateSoundFx(fx.copy(speakerHpfHz = quantized))
            }
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                0f to "关闭（耳机）",
                40f to "车载 40Hz",
                48f to "平板 48Hz",
                65f to "手机 65Hz"
            ).forEach { (hz, tag) ->
                val active = (hz == 0f && fx.speakerHpfHz < 20f) ||
                    (hz > 0f && abs(fx.speakerHpfHz - hz) < 2f)
                StudioMiniFilterChip(
                    label = tag,
                    selected = active,
                    onClick = { vm.updateSoundFx(fx.copy(speakerHpfHz = hz)) }
                )
            }
        }
    }

    // 模块二：双耳声场与空间
    FxModuleCard(title = "声场定位与空间反射", subtitle = "BS2B 双耳交叉馈送 · 改良 Freeverb") {
        FxSliderControl(
            label = "立体声宽度",
            hint = "210 Hz 以下低频保持锁定单声道稳固中心，仅展宽或收束中高频横向声像",
            value = fx.stereoWidth,
            min = 0f,
            max = 2f,
            valueLabel = "${percent(fx.stereoWidth)}${if (abs(fx.stereoWidth - 1f) < 0.015f) " · 原宽" else ""}",
            neutralPoint = 0.5f,
            onValue = { raw ->
                val snapped = if (abs(raw - 1f) < 0.025f) 1f else raw
                vm.updateSoundFx(fx.copy(stereoWidth = snapped))
            }
        )

        FxSliderControl(
            label = "双耳交叉馈送（BS2B）",
            hint = "模拟近场音箱双耳自然串扰，缓解入耳与头戴耳机的“头中效应”，久听更松弛",
            value = fx.crossfeed,
            min = 0f,
            max = 1f,
            valueLabel = percent(fx.crossfeed),
            onValue = { vm.updateSoundFx(fx.copy(crossfeed = it)) }
        )

        FxSliderControl(
            label = "空间混响湿度",
            hint = "自研早期反射与长尾混合比，0% 为纯干声，调大营造现场或厅堂空气感",
            value = fx.reverbWet,
            min = 0f,
            max = 0.6f,
            valueLabel = percent(fx.reverbWet),
            onValue = { vm.updateSoundFx(fx.copy(reverbWet = it)) }
        )

        val decayActive = fx.reverbWet > 0.005f
        Box(Modifier.alpha(if (decayActive) 1f else 0.55f)) {
            FxSliderControl(
                label = "混响衰减长度",
                hint = if (decayActive) "控制空间余韵散去快慢：小值偏紧凑现场，大值偏空灵厅堂" else "先将上方混响湿度调至大于 0% 后生效",
                value = fx.reverbDecay,
                min = 0f,
                max = 1f,
                valueLabel = percent(fx.reverbDecay),
                onValue = { vm.updateSoundFx(fx.copy(reverbDecay = it)) }
            )
        }
    }

    // 模块三：余量控制与峰值限幅
    FxModuleCard(title = "增益余量与峰值限幅", subtitle = "数字防削波 · 透明软拐点保护") {
        FxSliderControl(
            label = "前置余量补偿（Preamp）",
            hint = "在 EQ 与效果处理前额外压低输入电平，搭配大增益手调曲线防止数字削顶失真",
            value = fx.preampDb,
            min = -12f,
            max = 0f,
            valueLabel = if (fx.preampDb > -0.05f) "0.0 dB" else "%.1f dB".format(fx.preampDb),
            neutralPoint = 1f,
            onValue = { vm.updateSoundFx(fx.copy(preampDb = it)) }
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "软拐点峰值保护（Limiter）",
                    style = MaterialTheme.typography.labelLarge,
                    color = skin.text
                )
                Text(
                    text = "自动软压瞬态高峰值，消除重低音叠加后的爆音破响",
                    style = MaterialTheme.typography.labelSmall,
                    color = skin.textMuted
                )
            }
            StudioSwitch(
                checked = fx.limiter,
                onChange = { vm.updateSoundFx(fx.copy(limiter = it)) },
                label = "峰值保护"
            )
        }

        if (fx.limiter) {
            Spacer(Modifier.height(10.dp))
            FxSliderControl(
                label = "限幅启动阈值",
                hint = "瞬态高于此电平开始柔和压缩，日常推荐 -1.0 dB",
                value = fx.limiterThresholdDb,
                min = -12f,
                max = 0f,
                valueLabel = "%.1f dB".format(fx.limiterThresholdDb),
                neutralPoint = 1f,
                onValue = { vm.updateSoundFx(fx.copy(limiterThresholdDb = it)) }
            )
        }
    }
}

/* ============================================================
 * 页签 4：人声与伴奏（卡拉OK 中置消除）
 * ============================================================ */

@Composable
private fun KaraokeTabContent(vm: SonanceViewModel) {
    val skin = LocalSkin.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(skin.surface2.copy(alpha = 0.9f))
            .border(
                width = 1.dp,
                color = if (vm.karaokeEnabled) skin.accent.copy(alpha = 0.5f) else skin.border,
                shape = RoundedCornerShape(20.dp)
            )
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (vm.karaokeEnabled) skin.accent.copy(alpha = 0.18f)
                        else skin.surface3
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = SonanceIcons.Mic,
                    contentDescription = null,
                    tint = if (vm.karaokeEnabled) skin.accent else skin.textDim,
                    modifier = Modifier.size(22.dp)
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = "人声消音 / 伴奏模式",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = skin.text
                )
                Text(
                    text = if (vm.karaokeEnabled) "正在消减中置人声频段，保留两侧乐器伴奏" else "当前保持完整原唱立体声输出",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (vm.karaokeEnabled) skin.accent else skin.textMuted
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ModeChoiceTile(
                title = "原唱模式",
                subtitle = "完整人声与伴奏立体声",
                selected = !vm.karaokeEnabled,
                onClick = { vm.setKaraoke(false) },
                modifier = Modifier.weight(1f)
            )
            ModeChoiceTile(
                title = "伴奏模式",
                subtitle = "Mid/Side 中置人声削减",
                selected = vm.karaokeEnabled,
                onClick = { vm.setKaraoke(true) },
                modifier = Modifier.weight(1f)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(skin.surface.copy(alpha = 0.7f))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "处理原理与说明",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = skin.text
            )
            Text(
                text = "• 保留超低频贝斯与底鼓重心不空虚，仅对中置人声核心频段做相位相消。\n" +
                    "• 消音效果取决于录音混音时主唱是否居正中，对带有强混响的双声道现场录音残留会略多。\n" +
                    "• 此模式独立于上方的均衡器与音效预设，切回原唱即刻无损还原。",
                style = MaterialTheme.typography.bodySmall,
                color = skin.textMuted
            )
        }
    }
}

/* ============================================================
 * 可视化频响图表与共享模块控件
 * ============================================================ */

@Composable
private fun StudioCurveGraph(
    curve: FloatArray,
    ghostCurve: FloatArray?,
    heightDp: Float,
    accentActive: Boolean
) {
    val skin = LocalSkin.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(skin.surface.copy(alpha = 0.75f))
            .border(1.dp, skin.border, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(heightDp.dp)
        ) {
            val minDb = -12f
            val maxDb = 12f
            val span = maxDb - minDb
            fun yFor(db: Float): Float =
                size.height * (1f - ((db - minDb) / span).coerceIn(0f, 1f))

            val dash = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))
            // +6 dB / 0 dB / -6 dB 参考横线
            listOf(6f, 0f, -6f).forEach { refDb ->
                val y = yFor(refDb)
                drawLine(
                    color = if (refDb == 0f) skin.border2 else skin.border.copy(alpha = 0.55f),
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = if (refDb == 0f) null else dash
                )
            }

            // 倍频程纵向参考刻度（对应 125Hz / 500Hz / 2kHz / 8kHz 在 31..16k 对数轴的位置）
            listOf(125.0, 500.0, 2000.0, 8000.0).forEach { hz ->
                val ratio = (kotlin.math.ln(hz / 31.0) / kotlin.math.ln(16_000.0 / 31.0)).toFloat()
                val x = size.width * ratio
                drawLine(
                    color = skin.border.copy(alpha = 0.4f),
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 0.8.dp.toPx()
                )
            }

            val middleY = yFor(0f)

            if (ghostCurve != null && ghostCurve.size >= 2) {
                val ghostPath = Path()
                val gStep = size.width / (ghostCurve.size - 1)
                ghostCurve.forEachIndexed { idx, db ->
                    val x = idx * gStep
                    val y = yFor(db)
                    if (idx == 0) ghostPath.moveTo(x, y) else ghostPath.lineTo(x, y)
                }
                drawPath(
                    path = ghostPath,
                    color = skin.textMuted.copy(alpha = 0.45f),
                    style = Stroke(
                        width = 1.3.dp.toPx(),
                        pathEffect = dash,
                        cap = StrokeCap.Round
                    )
                )
            }

            if (curve.size >= 2) {
                val strokeColor = if (accentActive) skin.accent else skin.textMuted.copy(alpha = 0.65f)
                val line = Path()
                val fill = Path()
                val step = size.width / (curve.size - 1)
                fill.moveTo(0f, middleY)
                curve.forEachIndexed { i, db ->
                    val x = i * step
                    val y = yFor(db)
                    if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
                    fill.lineTo(x, y)
                }
                fill.lineTo(size.width, middleY)
                fill.close()
                drawPath(
                    path = fill,
                    brush = Brush.verticalGradient(
                        listOf(
                            strokeColor.copy(alpha = if (accentActive) 0.28f else 0.10f),
                            Color.Transparent
                        )
                    )
                )
                drawPath(
                    path = line,
                    color = strokeColor,
                    style = Stroke(
                        width = 2.2.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            listOf("31", "125", "500", "2k", "8k", "16k Hz").forEach { freq ->
                Text(
                    text = freq,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = skin.textMuted
                )
            }
        }
    }
}

@Composable
private fun PresetMiniSparkline(
    curve: FloatArray,
    highlighted: Boolean,
    modifier: Modifier = Modifier
) {
    val skin = LocalSkin.current
    Canvas(modifier = modifier) {
        val minDb = -8f
        val maxDb = 8f
        val span = maxDb - minDb
        fun yFor(db: Float): Float =
            size.height * (1f - ((db - minDb) / span).coerceIn(0f, 1f))

        val zeroY = yFor(0f)
        drawLine(
            color = skin.border2.copy(alpha = 0.55f),
            start = Offset(0f, zeroY),
            end = Offset(size.width, zeroY),
            strokeWidth = 0.8.dp.toPx()
        )

        if (curve.size >= 2) {
            val path = Path()
            val fill = Path()
            val step = size.width / (curve.size - 1)
            fill.moveTo(0f, zeroY)
            curve.forEachIndexed { idx, db ->
                val x = idx * step
                val y = yFor(db)
                if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                fill.lineTo(x, y)
            }
            fill.lineTo(size.width, zeroY)
            fill.close()

            val strokeColor = if (highlighted) skin.accent else skin.textDim
            drawPath(
                path = fill,
                brush = Brush.verticalGradient(
                    listOf(
                        strokeColor.copy(alpha = if (highlighted) 0.24f else 0.12f),
                        Color.Transparent
                    )
                )
            )
            drawPath(
                path = path,
                color = strokeColor,
                style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }
    }
}

@Composable
private fun ToneMeterPill(label: String, db: Float, modifier: Modifier = Modifier) {
    val skin = LocalSkin.current
    val norm = ((db + 6f) / 12f).coerceIn(0f, 1f)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(skin.surface.copy(alpha = 0.72f))
            .border(1.dp, skin.border, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = skin.textMuted
            )
            Text(
                text = formatDbSigned(db),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontFeatureSettings = "tnum"
                ),
                fontWeight = FontWeight.SemiBold,
                color = if (abs(db) > 0.25f) skin.accent else skin.textDim
            )
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
        ) {
            val r = CornerRadius(size.height / 2f)
            drawRoundRect(color = skin.surface3, cornerRadius = r)
            val center = size.width * 0.5f
            val target = size.width * norm
            val startX = minOf(center, target)
            val width = abs(target - center)
            if (width > 0.5f) {
                drawRoundRect(
                    color = skin.accent,
                    topLeft = Offset(startX, 0f),
                    size = Size(width, size.height),
                    cornerRadius = r
                )
            }
        }
    }
}

@Composable
private fun FxModuleCard(
    title: String,
    subtitle: String,
    content: @Composable () -> Unit
) {
    val skin = LocalSkin.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(skin.surface2.copy(alpha = 0.9f))
            .border(1.dp, skin.border, RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = skin.text
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = skin.textMuted
            )
        }
        content()
    }
}

@Composable
private fun FxSliderControl(
    label: String,
    hint: String,
    value: Float,
    min: Float,
    max: Float,
    valueLabel: String,
    neutralPoint: Float? = null,
    onValue: (Float) -> Unit
) {
    val skin = LocalSkin.current
    val span = (max - min).coerceAtLeast(0.0001f)
    val fraction = ((value - min) / span).coerceIn(0f, 1f)
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = skin.text
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontFeatureSettings = "tnum"
                ),
                fontWeight = FontWeight.SemiBold,
                color = skin.accent
            )
        }
        SonanceSlider(
            value = fraction,
            onValueChange = { onValue(min + it * span) },
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = label
                    stateDescription = valueLabel
                    progressBarRangeInfo = ProgressBarRangeInfo(value.coerceIn(min, max), min..max)
                    setProgress { requested ->
                        onValue(requested.coerceIn(min, max))
                        true
                    }
                },
            trackHeight = 3.dp,
            thumbSize = 14.dp,
            alwaysShowThumb = true,
            neutralPoint = neutralPoint
        )
        Text(
            text = hint,
            style = MaterialTheme.typography.labelSmall,
            color = skin.textMuted
        )
    }
}

@Composable
private fun ModeChoiceTile(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val skin = LocalSkin.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) skin.accent else skin.surface)
            .border(
                width = 1.dp,
                color = if (selected) skin.accent else skin.border2,
                shape = RoundedCornerShape(16.dp)
            )
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = if (selected) skin.onAccent else skin.text
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) skin.onAccent.copy(alpha = 0.85f) else skin.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun StudioMiniFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val skin = LocalSkin.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) skin.accent else skin.surface3)
            .border(
                width = 1.dp,
                color = if (selected) skin.accent else skin.border2,
                shape = RoundedCornerShape(16.dp)
            )
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 5.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) skin.onAccent else skin.textDim
        )
    }
}

@Composable
private fun StudioTagPill(label: String, highlighted: Boolean) {
    val skin = LocalSkin.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (highlighted) skin.accent.copy(alpha = 0.16f) else skin.surface3)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (highlighted) skin.accent else skin.textDim
        )
    }
}

@Composable
private fun StudioSwitch(checked: Boolean, onChange: (Boolean) -> Unit, label: String) {
    val skin = LocalSkin.current
    Box(
        modifier = Modifier
            .size(width = 48.dp, height = 36.dp)
            .semantics { contentDescription = label }
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .width(42.dp)
                .height(24.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (checked) skin.accent else skin.surface3),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
        ) {
            Box(
                modifier = Modifier
                    .padding(horizontal = 3.dp)
                    .size(18.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (checked) skin.onAccent else skin.textDim)
            )
        }
    }
}

/* ============================================================
 * 工具函数与 DSP 曲线预览计算
 * ============================================================ */

private fun computeLiveNetCurve(
    eqBands: FloatArray,
    eqEnabled: Boolean,
    fxParams: SoundFxParams,
    fxEnabled: Boolean,
    points: Int
): FloatArray {
    val safePoints = points.coerceAtLeast(2)
    val bands = if (eqEnabled) eqBands else FloatArray(EQ_BAND_COUNT)
    val response = EqResponse(bands, 48_000)
    val fxBudget = if (fxEnabled) effectPreampBudgetDb(fxParams, channels = 2) else 0f
    val manualPreamp = if (fxEnabled) fxParams.preampDb else 0f
    val autoTrim = if (eqEnabled) eqAutoPreampDb(response.maxBoostDb() + fxBudget) else -fxBudget
    return FloatArray(safePoints) { i ->
        val hz = 31.0 * (16_000.0 / 31.0).pow(i.toDouble() / (safePoints - 1))
        response.dbAt(hz) + autoTrim + manualPreamp
    }
}

private fun computeLiveToneBands(
    eqBands: FloatArray,
    eqEnabled: Boolean,
    fxParams: SoundFxParams,
    fxEnabled: Boolean
): FloatArray {
    val bands = if (eqEnabled) eqBands else FloatArray(EQ_BAND_COUNT)
    val response = EqResponse(bands, 48_000)
    val fxBudget = if (fxEnabled) effectPreampBudgetDb(fxParams, channels = 2) else 0f
    val manualPreamp = if (fxEnabled) fxParams.preampDb else 0f
    val trim = if (eqEnabled) eqAutoPreampDb(response.maxBoostDb() + fxBudget) else -fxBudget
    fun avg(vararg hz: Double): Float {
        var sum = 0.0
        for (frequency in hz) sum += response.dbAt(frequency)
        return (sum / hz.size).toFloat() + trim + manualPreamp
    }
    return floatArrayOf(
        avg(62.0, 125.0),
        avg(250.0, 500.0, 1000.0, 2000.0),
        avg(4000.0, 8000.0, 16000.0)
    )
}

private fun buildActiveDspTags(
    fx: SoundFxParams,
    fxEnabled: Boolean,
    eqEnabled: Boolean
): List<String> = buildList {
    if (eqEnabled) add("10 段均衡器")
    if (fxEnabled) {
        if (fx.virtualBass > 0.01f) add("虚拟低音 ${percent(fx.virtualBass)}")
        if (fx.speakerHpfHz >= 20f) add("外放高通 ${fx.speakerHpfHz.roundToInt()}Hz")
        if (fx.crossfeed > 0.01f) add("BS2B ${percent(fx.crossfeed)}")
        if (abs(fx.stereoWidth - 1f) > 0.015f) add("声场 ${percent(fx.stereoWidth)}")
        if (fx.reverbWet > 0.01f) add("混响 ${percent(fx.reverbWet)}")
        if (fx.limiter) add("峰值保护")
    }
}

private fun buildPresetSummaryChip(preset: SoundPreset): String {
    if (preset.id == SoundPresets.OFF.id) return "逐样本直通"
    val p = preset.fx
    val parts = buildList {
        if (p.speakerHpfHz >= 20f) add("高通 ${p.speakerHpfHz.roundToInt()}Hz")
        if (p.crossfeed > 0.01f) add("BS2B")
        if (p.virtualBass > 0.01f) add("谐波低音")
        if (p.reverbWet > 0.01f) add("空间反射")
        if (abs(p.stereoWidth - 1f) > 0.015f) add("声场${if (p.stereoWidth > 1f) "+" else ""}${((p.stereoWidth - 1f) * 100f).roundToInt()}%")
    }
    return if (parts.isEmpty()) "零染色 · 净空解析" else parts.take(2).joinToString(" · ")
}

private fun routeIconOf(route: AudioOutputRoute): ImageVector = when (route) {
    AudioOutputRoute.PHONE_SPEAKER -> SonanceIcons.Volume
    AudioOutputRoute.TABLET_SPEAKER -> SonanceIcons.Volume
    AudioOutputRoute.WIRED_HEADPHONE -> SonanceIcons.Waveform
    AudioOutputRoute.BLUETOOTH -> SonanceIcons.Waveform
    AudioOutputRoute.USB_DAC -> SonanceIcons.Music
}

private fun percent(value: Float): String = "${(value * 100f).roundToInt()}%"

private fun formatShortDb(db: Float): String = when {
    db > 0.05f -> "+%.1f".format(db)
    db < -0.05f -> "%.1f".format(db)
    else -> "0"
}

private fun formatDbSigned(db: Float): String = when {
    db > 0.05f -> "+%.1f dB".format(db)
    db < -0.05f -> "%.1f dB".format(db)
    else -> "0.0 dB"
}
