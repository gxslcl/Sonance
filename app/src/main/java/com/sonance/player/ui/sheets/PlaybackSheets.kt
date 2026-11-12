package com.sonance.player.ui.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sonance.player.SonanceViewModel
import com.sonance.player.playback.EqPreset
import com.sonance.player.playback.EqResponse
import com.sonance.player.playback.SoundPreset
import com.sonance.player.playback.SoundPresetCategory
import com.sonance.player.playback.SoundPresets
import com.sonance.player.playback.eqBandLabel
import com.sonance.player.playback.eqDbFromSlider
import com.sonance.player.playback.presetNetCurve
import com.sonance.player.playback.presetToneBands
import com.sonance.player.ui.components.PillButton
import com.sonance.player.ui.components.SonanceSlider
import com.sonance.player.ui.icon.SonanceIcons
import com.sonance.player.ui.theme.LocalSkin
import com.sonance.player.ui.theme.solidSurface
import kotlin.math.pow
import kotlin.math.roundToInt

/* ============================================================
 * 均衡器面板
 * ============================================================ */

/** 先选听感，再按需展开细节；EQ 曲线和预设效果的关系在面板里明确说明。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EqualizerSheet(vm: SonanceViewModel, onDismiss: () -> Unit) {
    val skin = LocalSkin.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var category by remember { mutableStateOf<SoundPresetCategory?>(null) }
    var effectsExpanded by remember { mutableStateOf(false) }
    var eqExpanded by remember { mutableStateOf(false) }
    var karaokeExpanded by remember { mutableStateOf(false) }

    // 比较只在这个面板里有效，返回或关闭时绝不能把播放器遗留在旁通状态。
    DisposableEffect(vm) {
        onDispose { vm.setSoundFxBypass(false) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = skin.surface.solidSurface(),
        dragHandle = { SheetHandle() }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 720.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 26.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "音效工作室",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = skin.text
                    )
                    Text(
                        "找到适合你和设备的声音",
                        style = MaterialTheme.typography.labelSmall,
                        color = skin.textMuted
                    )
                }
                Text("SONANCE", style = MaterialTheme.typography.labelSmall, color = skin.accent)
            }

            Spacer(Modifier.height(16.dp))
            CurrentSoundCard(vm)

            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("挑选音色", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = skin.text)
                Spacer(Modifier.weight(1f))
                Text("左右滑动查看更多", style = MaterialTheme.typography.labelSmall, color = skin.textMuted)
            }
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PresetChip("全部", category == null) { category = null }
                SoundPresetCategory.entries.forEach { item ->
                    PresetChip(item.label, category == item) { category = item }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SoundPresets.ALL.filter { category == null || it.category == category }.forEach { preset ->
                    val selected = preset.id == vm.soundPreset.id &&
                        (preset.id != SoundPresets.OFF.id || (!vm.eqEnabled && !vm.soundFxEnabled))
                    SoundPresetCard(preset, selected) { vm.chooseSoundPreset(preset) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "选择预设会载入它的 EQ 曲线，替换当前曲风曲线。不同耳机和手机听感会有差异。",
                style = MaterialTheme.typography.labelSmall,
                color = skin.textMuted
            )

            Spacer(Modifier.height(20.dp))
            SectionToggle(
                title = "音效微调",
                subtitle = "低频、空间、宽度与峰值保护",
                expanded = effectsExpanded,
                onClick = { effectsExpanded = !effectsExpanded }
            )
            if (effectsExpanded) {
                Column(Modifier.padding(horizontal = 4.dp, vertical = 12.dp)) {
                    FxSliderRow("虚拟低音", "轻补低频谐波，适合外放；过强可能增加失真", vm.soundFxParams.virtualBass, 0f, 1f, percent(vm.soundFxParams.virtualBass)) {
                        vm.updateSoundFx(vm.soundFxParams.copy(virtualBass = it))
                    }
                    FxSliderRow("空间混响", "少量即可增加空间感，太多会模糊人声", vm.soundFxParams.reverbWet, 0f, 0.6f, percent(vm.soundFxParams.reverbWet)) {
                        vm.updateSoundFx(vm.soundFxParams.copy(reverbWet = it))
                    }
                    FxSliderRow("混响余韵", "调整衰减长短；混响为零时无影响", vm.soundFxParams.reverbDecay, 0f, 1f, percent(vm.soundFxParams.reverbDecay)) {
                        vm.updateSoundFx(vm.soundFxParams.copy(reverbDecay = it))
                    }
                    FxSliderRow("立体声宽度", "100% 为原始宽度；仅对双声道生效", vm.soundFxParams.stereoWidth, 0f, 2f, percent(vm.soundFxParams.stereoWidth)) {
                        vm.updateSoundFx(vm.soundFxParams.copy(stereoWidth = it))
                    }
                    FxSliderRow("手动预留", "只在极端曲线或手调 EQ 时让路，日常预设基本不会整体变小", vm.soundFxParams.preampDb, -12f, 0f, "%.1f dB".format(vm.soundFxParams.preampDb)) {
                        vm.updateSoundFx(vm.soundFxParams.copy(preampDb = it))
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("峰值保护", style = MaterialTheme.typography.labelLarge, color = skin.text)
                            Text("提前控制过高的采样峰值；末端防削顶始终生效", style = MaterialTheme.typography.labelSmall, color = skin.textMuted)
                        }
                        ToggleSwitch(vm.soundFxParams.limiter, { vm.updateSoundFx(vm.soundFxParams.copy(limiter = it)) }, "峰值保护")
                    }
                    Text("虚拟低音与混响仅处理单、双声道；多声道仍支持 EQ。", style = MaterialTheme.typography.labelSmall, color = skin.textMuted)
                }
            }

            Spacer(Modifier.height(10.dp))
            SectionToggle(
                title = "10 段均衡器",
                subtitle = when {
                    !vm.eqEnabled -> "已关闭 · 曲线会保留"
                    vm.eqFromSoundPreset -> "当前音效的配套曲线${if (vm.eqCustom) " · 已微调" else ""}"
                    vm.eqCustom -> "自定义曲线"
                    vm.eqPreset == EqPreset.OFF -> "平直"
                    else -> "曲风 · ${vm.eqPreset.label}"
                },
                expanded = eqExpanded,
                onClick = { eqExpanded = !eqExpanded }
            )
            if (eqExpanded) {
                Column(Modifier.padding(horizontal = 4.dp, vertical = 12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("均衡器开关", style = MaterialTheme.typography.labelLarge, color = skin.text)
                            Text("独立控制 EQ，不关闭额外音效", style = MaterialTheme.typography.labelSmall, color = skin.textMuted)
                        }
                        ToggleSwitch(vm.eqEnabled, { vm.toggleEq(it) }, "均衡器")
                    }
                    EqCurveGraph(vm.eqBands)
                    Spacer(Modifier.height(12.dp))
                    Text("曲风 EQ", style = MaterialTheme.typography.labelLarge, color = skin.text)
                    Text("选择曲风会替换预设曲线，不会叠加两条 EQ。", style = MaterialTheme.typography.labelSmall, color = skin.textMuted)
                    Spacer(Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        EqPreset.entries.forEach { preset ->
                            val selected = !vm.eqFromSoundPreset && !vm.eqCustom && preset == vm.eqPreset
                            PresetChip(preset.label, selected) { vm.chooseEqPreset(preset) }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("频段 · dB", style = MaterialTheme.typography.labelLarge, color = skin.text)
                        Spacer(Modifier.weight(1f))
                        PillButton(label = "恢复平直", onClick = { vm.resetEqBands() }, filled = false)
                    }
                    var draggingIndex by remember { mutableStateOf(-1) }
                    vm.eqCenterFreqs.forEachIndexed { i, hz ->
                        BandRow(
                            label = eqBandLabel(hz),
                            db = vm.eqBands.getOrElse(i) { 0f },
                            minDb = vm.eqMinDb,
                            maxDb = vm.eqMaxDb,
                            highlighted = draggingIndex == i,
                            onDrag = { draggingIndex = i; vm.setEqBand(i, it) },
                            onDragEnd = { draggingIndex = -1 }
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("拖动会自动开启 EQ；增益叠加时自动留余量。设置会在下次启动时保留。", style = MaterialTheme.typography.labelSmall, color = skin.textMuted)
                }
            }

            Spacer(Modifier.height(10.dp))
            SectionToggle(
                title = "人声与伴奏",
                subtitle = if (vm.karaokeEnabled) "伴奏模式 · 中置声道消除" else "原唱",
                expanded = karaokeExpanded,
                onClick = { karaokeExpanded = !karaokeExpanded }
            )
            if (karaokeExpanded) {
                Column(Modifier.padding(horizontal = 4.dp, vertical = 12.dp)) {
                    Text("伴奏通过中置声道消除实现，效果因歌曲而异；单声道无变化。", style = MaterialTheme.typography.labelSmall, color = skin.textMuted)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PresetChip("原唱", !vm.karaokeEnabled) { vm.setKaraoke(false) }
                        PresetChip("伴奏", vm.karaokeEnabled) { vm.setKaraoke(true) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CurrentSoundCard(vm: SonanceViewModel) {
    val skin = LocalSkin.current
    val modified = vm.soundFxCustom || (vm.eqFromSoundPreset && vm.eqCustom)
    val name = if (vm.soundFxEnabled && vm.soundPreset.id == SoundPresets.OFF.id) {
        "自定义音效"
    } else vm.soundPreset.name
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.linearGradient(listOf(skin.accent.copy(alpha = 0.16f), skin.surface2)))
            .border(1.dp, skin.accent.copy(alpha = 0.25f), RoundedCornerShape(18.dp))
            .padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 14.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        vm.soundFxBypassed -> "正在对比 · 无音效"
                        vm.soundFxEnabled -> "正在使用"
                        else -> "音效预设已关闭"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = skin.accent
                )
                Text(
                    name + if (modified) " · 已微调" else "",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = skin.text
                )
            }
            ToggleSwitch(vm.soundFxEnabled, { vm.toggleSoundFx(it) }, "音效预设")
        }
        Text(
            when {
                vm.soundFxEnabled && vm.soundPreset.id == SoundPresets.OFF.id -> "手动调整的低频、空间与动态。"
                vm.soundFxEnabled -> vm.soundPreset.description
                vm.soundPreset.id == SoundPresets.OFF.id && vm.soundFxParams.isNeutral ->
                    "开启开关会载入「清晨 · 通透」，也可直接选择下方音色。"
                else -> "关闭预设后可随时恢复上次选择。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = skin.textDim
        )
        val eqStatus = when {
            !vm.soundFxEnabled && vm.eqEnabled -> "均衡器仍在生效"
            vm.soundFxEnabled && vm.eqFromSoundPreset && !vm.eqEnabled -> "配套 EQ 已单独关闭"
            vm.soundFxEnabled && !vm.eqFromSoundPreset && vm.eqEnabled -> "已使用独立 EQ 曲线"
            else -> null
        }
        if (eqStatus != null) {
            Spacer(Modifier.height(4.dp))
            Text(eqStatus, style = MaterialTheme.typography.labelSmall, color = skin.accent)
        }
        // 把"这套预设到底改了什么"摊开说：低频/中频/高频各自多少 dB，比形容词可信。
        // 外放档例外：它的低频数字是负的（切掉喇叭放不出的部分），靠谐波补回来，
        // 只报 dB 会让人以为"外放反而减了低音"。
        val tone = remember(vm.soundPreset.id) { presetToneBands(vm.soundPreset) }
        val toneLine = when {
            !vm.soundFxEnabled || vm.eqCustom || vm.soundPreset.id == SoundPresets.OFF.id -> null
            vm.soundPreset.id == SoundPresets.DUAL_SPEAKER.id ->
                "手机喇叭放不出的超低频已切除，低音靠谐波补回；中频补厚度、高频去尖锐"
            else -> "相对原声 · 低 ${signedDb(tone[0])}  中 ${signedDb(tone[1])}  高 ${signedDb(tone[2])}"
        }
        if (toneLine != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                toneLine,
                style = MaterialTheme.typography.labelSmall,
                color = skin.textMuted
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            CompareHoldButton(
                active = vm.soundFxBypassed,
                enabled = vm.soundFxEnabled || vm.eqEnabled || vm.soundFxBypassed,
                onHold = { vm.setSoundFxBypass(it) }
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "按住听原声，松手立刻恢复；伴奏模式不变",
                style = MaterialTheme.typography.labelSmall,
                color = skin.textMuted,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * 按住试听原声。
 *
 * 调音这件事必须能反复 A/B，每次都点两下太慢；按住 = 原声、松手 = 音效，
 * 是调音台上"旁通键"最自然的映射，也不会把手感留在旁通状态。
 * 读屏用户走语义点击：点一下切换，再点一下恢复。
 */
@Composable
private fun CompareHoldButton(active: Boolean, enabled: Boolean, onHold: (Boolean) -> Unit) {
    val skin = LocalSkin.current
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) skin.accent else skin.surface2)
            .then(
                if (enabled) {
                    Modifier.pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                onHold(true)
                                tryAwaitRelease()
                                onHold(false)
                            }
                        )
                    }
                } else {
                    Modifier
                }
            )
            .semantics {
                contentDescription = if (active) "正在听原声" else "按住试听原声"
                role = Role.Button
                onClick(label = "切换对比") {
                    onHold(!active)
                    true
                }
            }
            .padding(horizontal = 18.dp, vertical = 11.dp)
    ) {
        Text(
            text = if (active) "正在听原声" else "按住听原声",
            style = MaterialTheme.typography.labelLarge,
            color = if (active) skin.onAccent else skin.text,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/** 带正负号的 dB 文案：负数用真正的减号，读起来不像连字符。 */
private fun signedDb(value: Float): String {
    val rounded = (value * 10f).roundToInt() / 10f
    return when {
        rounded > 0.05f -> "+%.1f".format(rounded)
        rounded < -0.05f -> "−%.1f".format(-rounded)
        else -> "0.0"
    }
}

@Composable
private fun SoundPresetCard(preset: SoundPreset, selected: Boolean, onClick: () -> Unit) {
    val skin = LocalSkin.current
    Column(
        Modifier
            .width(156.dp)
            .height(150.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) skin.accent.copy(alpha = 0.14f) else skin.surface2)
            .border(1.dp, if (selected) skin.accent else skin.border2, RoundedCornerShape(16.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = "${preset.name}，${preset.character}，${preset.description}"
            }
            .padding(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(preset.category.label, style = MaterialTheme.typography.labelSmall, color = if (selected) skin.accent else skin.textMuted)
            Spacer(Modifier.weight(1f))
            if (selected) {
                Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(skin.accent))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(preset.name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = skin.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(2.dp))
        Text(preset.character, style = MaterialTheme.typography.labelSmall, color = skin.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.weight(1f))
        PresetCurveGraph(preset, selected)
    }
}

/**
 * 卡片底部的频响缩略图。
 *
 * 用和处理器同一套 biquad 系数算出来的**净**响应（含自动余量），所以三件事同时成立：
 * 图形和听到的一致、不同预设一眼能分出来、也不需要看懂 dB 才能选。
 * 纵轴固定 ±6 dB —— 让"曲线上下的幅度"就是真实强度，不随预设自动放大。
 */
@Composable
private fun PresetCurveGraph(preset: SoundPreset, highlighted: Boolean) {
    val skin = LocalSkin.current
    val curve = remember(preset.id) { presetNetCurve(preset, 40) }
    Canvas(Modifier.fillMaxWidth().height(30.dp)) {
        val middle = size.height / 2f
        drawLine(
            skin.border2.copy(alpha = 0.7f),
            Offset(0f, middle), Offset(size.width, middle),
            1.dp.toPx()
        )
        val path = Path()
        curve.forEachIndexed { i, db ->
            val x = size.width * i / (curve.size - 1)
            val y = middle - (db.coerceIn(-6f, 6f) / 12f) * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path,
            color = if (highlighted) skin.accent else skin.textDim.copy(alpha = 0.7f),
            style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}

@Composable
private fun SectionToggle(title: String, subtitle: String, expanded: Boolean, onClick: () -> Unit) {
    val skin = LocalSkin.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(skin.surface2)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = skin.text)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = skin.textMuted)
        }
        androidx.compose.material3.Icon(
            imageVector = SonanceIcons.ChevronDown,
            contentDescription = if (expanded) "收起" else "展开",
            tint = skin.textMuted,
            modifier = Modifier.size(20.dp).rotate(if (expanded) 180f else 0f)
        )
    }
}

/** 用实际 biquad 系数绘制近似频响，而非把十个滑杆点当成真实曲线。 */
@Composable
private fun EqCurveGraph(bands: FloatArray) {
    val skin = LocalSkin.current
    val curve = remember(bands) {
        val response = EqResponse(bands, 48_000)
        FloatArray(96) { i ->
            val hz = 31.0 * (16_000.0 / 31.0).pow(i / 95.0)
            response.dbAt(hz).coerceIn(-12f, 12f)
        }
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(skin.surface2).padding(12.dp)
    ) {
        Row(Modifier.fillMaxWidth()) {
            Text("频响示意", style = MaterialTheme.typography.labelSmall, color = skin.textDim)
            Spacer(Modifier.weight(1f))
            Text("±12 dB · 48 kHz", style = MaterialTheme.typography.labelSmall, color = skin.textMuted)
        }
        Spacer(Modifier.height(8.dp))
        Canvas(Modifier.fillMaxWidth().height(72.dp)) {
            val middle = size.height / 2f
            for (fraction in listOf(0f, 0.5f, 1f)) {
                val y = size.height * fraction
                drawLine(skin.border2.copy(alpha = if (fraction == 0.5f) 0.9f else 0.4f), Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
            }
            val line = Path()
            val fill = Path().apply { moveTo(0f, middle) }
            curve.forEachIndexed { i, db ->
                val x = size.width * i / (curve.size - 1)
                val y = middle - db / 24f * size.height
                if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
                fill.lineTo(x, y)
            }
            fill.lineTo(size.width, middle)
            fill.close()
            drawPath(fill, brush = Brush.verticalGradient(listOf(skin.accent.copy(alpha = 0.22f), Color.Transparent)))
            drawPath(line, skin.accent, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        }
        Spacer(Modifier.height(5.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("31 Hz", "1 kHz", "16 kHz").forEach {
                Text(it, style = MaterialTheme.typography.labelSmall, color = skin.textMuted)
            }
        }
    }
}

private fun percent(value: Float): String = "${(value * 100f).roundToInt()}%"

/** 单条效果强度滑杆：可读的单位、作用说明和完整触摸宽度。 */
@Composable
private fun FxSliderRow(
    label: String,
    hint: String,
    value: Float,
    min: Float,
    max: Float,
    valueLabel: String,
    onValue: (Float) -> Unit
) {
    val skin = LocalSkin.current
    val fraction = ((value - min) / (max - min)).coerceIn(0f, 1f)
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = skin.text)
            Spacer(Modifier.weight(1f))
            Text(valueLabel, style = MaterialTheme.typography.labelMedium, color = skin.accent)
        }
        SonanceSlider(
            value = fraction,
            onValueChange = { onValue(min + it * (max - min)) },
            modifier = Modifier.fillMaxWidth().semantics {
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
            alwaysShowThumb = true
        )
        Text(hint, style = MaterialTheme.typography.labelSmall, color = skin.textMuted)
    }
}

/** 单根频段滑杆：左侧频点、中间滑杆、右侧当前增益 */
@Composable
private fun BandRow(
    label: String,
    db: Float,
    minDb: Float,
    maxDb: Float,
    highlighted: Boolean,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit
) {
    val skin = LocalSkin.current
    val span = (maxDb - minDb).coerceAtLeast(0.001f)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (highlighted) skin.accent else skin.textMuted,
            modifier = Modifier.width(34.dp)
        )
        SonanceSlider(
            value = ((db - minDb) / span).coerceIn(0f, 1f),
            onValueChange = { onDrag(eqDbFromSlider(it, minDb, maxDb)) },
            onValueChangeFinished = onDragEnd,
            modifier = Modifier.weight(1f).semantics {
                contentDescription = "$label 频段"
                stateDescription = "%.1f 分贝".format(db)
                progressBarRangeInfo = ProgressBarRangeInfo(db.coerceIn(minDb, maxDb), minDb..maxDb)
                setProgress { requested ->
                    onDrag(requested.coerceIn(minDb, maxDb))
                    onDragEnd()
                    true
                }
            },
            trackHeight = 3.dp,
            thumbSize = 12.dp,
            alwaysShowThumb = true,
            neutralPoint = ((0f - minDb) / span).coerceIn(0f, 1f)
        )
        Text(
            text = if (db > 0.05f) "+%.1f".format(db) else if (db < -0.05f) "%.1f".format(db) else "0",
            style = MaterialTheme.typography.labelSmall,
            color = if (highlighted) skin.accent else skin.textDim,
            modifier = Modifier
                .width(42.dp)
                .padding(start = 8.dp)
        )
    }
}

/* ============================================================
 * 睡眠定时器面板
 * ============================================================ */

/** 倒计时可选时长（分钟） */
private val SLEEP_MINUTES = listOf(5, 10, 15, 30, 45, 60, 90)

/**
 * 睡眠定时器面板。
 *
 * 两种模式：
 * - **倒计时**：N 分钟后暂停（按 `elapsedRealtime` 计时，不受系统时间被改的影响）
 * - **播完当前曲**：在离结尾还有 700ms 时暂停，不靠 `onSongStarted`（那会多放一小段）
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SleepTimerSheet(vm: SonanceViewModel, onDismiss: () -> Unit) {
    val skin = LocalSkin.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val label = vm.sleepTimerLabel

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = skin.surface.solidSurface(),
        dragHandle = { SheetHandle() }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "睡眠定时器",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = skin.text
                    )
                    Text(
                        label?.let { "剩余 $it，到点自动暂停" } ?: "未开启 · 到点自动暂停播放",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (label != null) skin.accent else skin.textMuted
                    )
                }
                if (label != null) {
                    PillButton(label = "取消", onClick = {
                        vm.clearSleepTimer()
                        vm.showToast("已取消睡眠定时器")
                    }, filled = false)
                }
            }

            Spacer(Modifier.height(18.dp))
            Text("倒计时", style = MaterialTheme.typography.labelMedium, color = skin.textMuted)
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SLEEP_MINUTES.forEach { m ->
                    val active = vm.sleepTimerMode == SonanceViewModel.SleepTimerMode.COUNTDOWN &&
                        vm.sleepTimerTotalMinutes == m
                    PresetChip(
                        label = if (m >= 60) "${m / 60} 小时" else "$m 分钟",
                        selected = active
                    ) { vm.setSleepTimer(m) }
                }
            }

            Spacer(Modifier.height(18.dp))
            Text("其它", style = MaterialTheme.typography.labelMedium, color = skin.textMuted)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PresetChip(
                    label = "播完当前这首",
                    selected = vm.sleepTimerMode == SonanceViewModel.SleepTimerMode.AFTER_CURRENT
                ) { vm.setSleepAfterCurrent() }
                PresetChip(
                    label = "关闭定时",
                    selected = vm.sleepTimerMode == SonanceViewModel.SleepTimerMode.OFF
                ) { vm.clearSleepTimer() }
            }
        }
    }
}

/* ============================================================
 * 共用小件
 * ============================================================ */

@Composable
private fun SheetHandle() {
    val skin = LocalSkin.current
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .width(38.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(skin.textMuted.copy(alpha = 0.45f))
        )
    }
}

/** 胶囊选项：选中用强调色填充，未选中用 surface3 */
@Composable
private fun PresetChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val skin = LocalSkin.current
    Box(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) skin.accent else skin.surface3)
            .border(
                width = 1.dp,
                color = if (selected) skin.accent else skin.border2,
                shape = RoundedCornerShape(20.dp)
            )
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 7.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) skin.onAccent else skin.textDim
        )
    }
}

/** 开关（和设置页里那颗同款，独立出来给面板用） */
@Composable
private fun ToggleSwitch(checked: Boolean, onChange: (Boolean) -> Unit, label: String) {
    val skin = LocalSkin.current
    Box(
        Modifier
            .size(48.dp)
            .semantics { contentDescription = label }
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .width(42.dp)
                .height(24.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (checked) skin.accent else skin.surface3),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
        ) {
            Box(
                Modifier
                    .padding(horizontal = 3.dp)
                    .size(18.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (checked) skin.onAccent else skin.textDim)
            )
        }
    }
}
