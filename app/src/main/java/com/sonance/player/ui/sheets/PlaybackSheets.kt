package com.sonance.player.ui.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sonance.player.SonanceViewModel
import com.sonance.player.playback.EqPreset
import com.sonance.player.playback.eqBandLabel
import com.sonance.player.ui.components.PillButton
import com.sonance.player.ui.components.SonanceSlider
import com.sonance.player.ui.icon.SonanceIcons
import com.sonance.player.ui.theme.LocalSkin
import com.sonance.player.ui.theme.solidSurface

/* ============================================================
 * 均衡器面板
 * ============================================================ */

/**
 * 均衡器面板。
 *
 * 走软件均衡器（[com.sonance.player.playback.EqProcessor]），所以这里**没有**
 * "本机不支持"这种分支 —— 只要有音频输出，10 段滑杆就一定有效。
 *
 * 布局用**横向滑杆**而不是常见的那种竖排推子：手机竖屏宽度本来就窄，
 * 10 根竖推子挤在一行里每根不到 30dp，手指点不准；横排每行一根、
 * 左频点右增益，读起来也更快。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EqualizerSheet(vm: SonanceViewModel, onDismiss: () -> Unit) {
    val skin = LocalSkin.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = skin.surface.solidSurface(),
        dragHandle = { SheetHandle() }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 26.dp)
        ) {
            // ---- 标题 + 总开关 ----
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "均衡器",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = skin.text
                    )
                    Text(
                        if (vm.eqEnabled) {
                            "10 段 · 当前 ${vm.eqPreset.label}${if (vm.eqCustom) "（已微调）" else ""}"
                        } else {
                            "10 段 · 已关闭，播放保持原始音色"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (vm.eqEnabled) skin.accent else skin.textMuted
                    )
                }
                ToggleSwitch(checked = vm.eqEnabled, onChange = { vm.toggleEq(it) })
            }

            Spacer(Modifier.height(16.dp))

            // ---- 预设 ----
            Text("预设", style = MaterialTheme.typography.labelMedium, color = skin.textMuted)
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                EqPreset.entries.forEach { p ->
                    // 手动拖过之后不再高亮任何预设，避免"显示流行、实际是自定义"的错位
                    val selected = !vm.eqCustom && p == vm.eqPreset
                    PresetChip(p.label, selected) { vm.chooseEqPreset(p) }
                }
            }

            Spacer(Modifier.height(18.dp))

            // ---- 频段滑杆 ----
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("频段", style = MaterialTheme.typography.labelMedium, color = skin.textMuted)
                Spacer(Modifier.weight(1f))
                PillButton(label = "复位", onClick = { vm.resetEqBands() }, filled = false)
            }
            Spacer(Modifier.height(6.dp))

            var draggingIndex by remember { mutableStateOf(-1) }
            vm.eqCenterFreqs.forEachIndexed { i, hz ->
                val db = vm.eqBands.getOrElse(i) { 0f }
                BandRow(
                    label = eqBandLabel(hz),
                    db = db,
                    minDb = vm.eqMinDb,
                    maxDb = vm.eqMaxDb,
                    highlighted = draggingIndex == i,
                    onDrag = {
                        draggingIndex = i
                        vm.setEqBand(i, it)
                    },
                    onDragEnd = { draggingIndex = -1 }
                )
            }

            Spacer(Modifier.height(14.dp))
            Text(
                "提示：拖动任意频段会自动打开均衡器；调节结果会记住，下次启动直接生效。",
                style = MaterialTheme.typography.labelSmall,
                color = skin.textDim
            )
        }
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
            onValueChange = onDrag,
            onValueChangeFinished = onDragEnd,
            modifier = Modifier.weight(1f),
            trackHeight = 3.dp,
            thumbSize = 12.dp,
            alwaysShowThumb = true
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
            .clickable(onClick = onClick)
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
private fun ToggleSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val skin = LocalSkin.current
    Box(
        Modifier
            .width(42.dp)
            .height(24.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (checked) skin.accent else skin.surface3)
            .clickable { onChange(!checked) },
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Box(
            Modifier
                .padding(horizontal = 3.dp)
                .width(18.dp)
                .height(18.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(if (checked) skin.onAccent else skin.textDim)
        )
    }
}
