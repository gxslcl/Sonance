package com.sonance.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.sonance.player.SonanceViewModel
import com.sonance.player.ui.components.PillButton
import com.sonance.player.ui.icon.SonanceIcons
import com.sonance.player.ui.screens.AppIconGrid
import com.sonance.player.ui.theme.LocalSkin
import com.sonance.player.ui.theme.solidSurface

/**
 * 「应用名称 + 顶栏图标」编辑面板。
 *
 * 入口是**双击顶栏标题 / logo**（见 `AppTopBar.onTitleDoubleTap`）——
 * 这两样是应用的门面，藏进设置页没人会想到去改；标题本身就在顶栏上，
 * 双击它是最近的路。
 *
 * ⚠️ 这里换的是**顶栏那个小方块**，不是桌面启动器图标。
 * 桌面图标走 `activity-alias` 启停（`AppIconSwitcher`），入口仍在设置页 ——
 * 换桌面图标会杀掉桌面缓存、几秒后才刷新，属于"重量级"操作，
 * 不适合和"改个名字"并排放在一个随手双击就叫出来的面板里。
 */
@Composable
fun BrandEditorDialog(
    vm: SonanceViewModel,
    onDismiss: () -> Unit
) {
    val skin = LocalSkin.current
    var name by remember { mutableStateOf(vm.appDisplayName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = skin.surface2.solidSurface(),
        title = {
            Text(
                "名称与图标",
                style = MaterialTheme.typography.titleMedium,
                color = skin.text
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    "名称和图标都显示在顶栏上。桌面启动器图标在「设置 → 应用图标」里换。",
                    style = MaterialTheme.typography.bodySmall,
                    color = skin.textMuted
                )

                // ---- 应用名 ----
                Column {
                    Text(
                        "应用名称",
                        style = MaterialTheme.typography.labelSmall,
                        color = skin.textMuted
                    )
                    Spacer(Modifier.height(4.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(skin.surface)
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        BasicTextField(
                            value = name,
                            onValueChange = { name = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = skin.text),
                            cursorBrush = SolidColor(skin.accent),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // ---- 顶栏图标 ----
                Column {
                    Text(
                        "顶栏图标",
                        style = MaterialTheme.typography.labelSmall,
                        color = skin.textMuted
                    )
                    Spacer(Modifier.height(8.dp))
                    // 「无」：只针对顶栏 —— 图标融入背景，但左上角保留可点区域进设置。
                    // 桌面启动器图标不受影响（那个在「设置 → 桌面应用图标」里换）。
                    val noneSelected = vm.topBarLogoId == "none"
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (noneSelected) skin.accentGlow else skin.surface)
                            .border(
                                width = if (noneSelected) 1.dp else 0.dp,
                                color = if (noneSelected) skin.accent else Color.Transparent,
                                shape = RoundedCornerShape(10.dp)
                            )
                            .clickable { vm.changeTopBarLogo("none") }
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .border(1.dp, skin.border2, RoundedCornerShape(7.dp))
                        )
                        Text(
                            "无图标（融入背景，仍可点进设置）",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (noneSelected) skin.accent else skin.text,
                            modifier = Modifier.weight(1f)
                        )
                        if (noneSelected) {
                            Icon(
                                imageVector = SonanceIcons.Check,
                                contentDescription = null,
                                tint = skin.accent,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 250.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        // 同一套图案，但**只改顶栏**：不碰 activity-alias，
                        // 桌面图标不动、也不用等桌面刷新
                        AppIconGrid(
                            currentId = vm.topBarLogoId,
                            onSelect = { vm.changeTopBarLogo(it) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            PillButton(
                label = "保存",
                onClick = {
                    vm.changeDisplayName(name)
                    onDismiss()
                }
            )
        },
        dismissButton = {
            PillButton(label = "取消", onClick = onDismiss, filled = false)
        }
    )
}
