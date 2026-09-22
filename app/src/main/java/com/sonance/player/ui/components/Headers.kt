package com.sonance.player.ui.components

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sonance.player.data.SortField
import com.sonance.player.data.ViewMode
import com.sonance.player.ui.icon.SonanceIcons
import com.sonance.player.ui.theme.LocalSkin
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.CircleShape

@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "搜索歌曲、专辑或艺术家",
    height: Dp = 44.dp,
    /**
     * 键盘右下角键的动作。传了它才会把软键盘的回车换成「搜索」。
     *
     * 本地曲库搜索是**随输入即时过滤**的，不需要这个动作（所以默认 null，
     * 保持回车换行的原样行为）；在线搜索要走网络，用户按一下回车才是明确意图。
     */
    onSearch: (() -> Unit)? = null
) {
    val skin = LocalSkin.current
    Row(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(9.dp))
            .background(skin.surface)
            .border(1.dp, skin.border, RoundedCornerShape(9.dp))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Icon(
            imageVector = SonanceIcons.Search,
            contentDescription = null,
            tint = skin.textMuted,
            modifier = Modifier.size(16.dp)
        )
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodyMedium,
                    color = skin.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = skin.text),
                cursorBrush = SolidColor(skin.accent),
                keyboardOptions = if (onSearch != null) {
                    KeyboardOptions(imeAction = ImeAction.Search)
                } else {
                    KeyboardOptions.Default
                },
                keyboardActions = if (onSearch != null) {
                    KeyboardActions(onSearch = { onSearch() })
                } else {
                    KeyboardActions.Default
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (value.isNotEmpty()) {
            Icon(
                imageVector = SonanceIcons.Close,
                contentDescription = "清空",
                tint = skin.textMuted,
                modifier = Modifier
                    .size(16.dp)
                    .clickable { onValueChange("") }
            )
        }
    }
}

@Composable
fun SortButton(
    field: SortField,
    ascending: Boolean,
    onSelect: (SortField) -> Unit,
    modifier: Modifier = Modifier
) {
    val skin = LocalSkin.current
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(9.dp))
                .background(skin.surface)
                .border(1.dp, skin.border, RoundedCornerShape(9.dp))
                .clickable { open = true }
                .padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Icon(
                imageVector = SonanceIcons.Sort,
                contentDescription = "排序",
                tint = skin.textDim,
                modifier = Modifier.size(15.dp)
            )
            Text(
                text = field.label + if (ascending) " ↑" else " ↓",
                style = MaterialTheme.typography.labelLarge,
                color = skin.textDim,
                maxLines = 1
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SortField.entries.forEach { f ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = if (f == field) "${f.label}  ${if (ascending) "↑" else "↓"}" else f.label,
                            color = if (f == field) skin.accent else skin.text
                        )
                    },
                    onClick = { open = false; onSelect(f) }
                )
            }
        }
    }
}

@Composable
fun ChipsRow(
    chips: List<Pair<String, Boolean>>,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        chips.forEachIndexed { index, (label, selected) ->
            TagChip(label = label, selected = selected, onClick = { onSelect(index) })
        }
    }
}

/**
 * ⁝ 溢出菜单按钮。
 *
 * 收纳那些"不常用但必须有入口"的东西（视图切换、文件夹），
 * 让主行只留高频控件。以后要加功能直接往 [content] 里塞，头部不会再变高。
 */
@Composable
fun DotsMenuButton(content: @Composable ColumnScope.() -> Unit) {
    val skin = LocalSkin.current
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(if (open) skin.accentGlow else Color.Transparent)
                .border(1.dp, if (open) Color.Transparent else skin.border2, CircleShape)
                .clickable { open = true },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = SonanceIcons.MoreVertical,
                contentDescription = "更多",
                tint = if (open) skin.accent else skin.textDim,
                modifier = Modifier.size(17.dp)
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, content = content)
    }
}

/** 菜单里的一行：图标 + 文字 +（选中时）右侧对勾 */
@Composable
fun MenuRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val skin = LocalSkin.current
    DropdownMenuItem(
        text = {
            Text(
                text = label,
                color = if (selected) skin.accent else skin.text
            )
        },
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) skin.accent else skin.textDim,
                modifier = Modifier.size(17.dp)
            )
        },
        trailingIcon = if (selected) {
            {
                Icon(
                    imageVector = SonanceIcons.Check,
                    contentDescription = null,
                    tint = skin.accent,
                    modifier = Modifier.size(16.dp)
                )
            }
        } else null,
        onClick = onClick
    )
}

/**
 * 列表头部：**两行**。
 *
 * ```
 * [🔍 搜索框 (weight 1)] [⇅ 排序]
 * [分类 / 结果统计] [⁝]          [播放全部] [🔀]
 * ```
 *
 * 设计上的三个约定：
 *
 * 1. **统计信息不在这里** —— 它并进了顶栏标题行（[AppTopBar] 的 `subtitleInline`）。
 *    之前顶栏副标题和这里的 `leadingText` 是同一份内容，同一屏出现两次，
 *    搜索时两个数字还会打架（一个是曲库总数、一个是筛选后数量）。
 * 2. **视图切换收进 [overflow] 菜单** —— 它一天改不了一次，却占着搜索框旁边
 *    近 18% 的宽度；让给搜索框更值。
 * 3. **第二行左侧是"上下文槽位"** —— 调用方决定放什么：平时放分类切换，
 *    搜索时换成"找到 N 首"。搜索时本来也不会去切分类，让位不损失任何操作。
 *
 * 第二行左侧带 `weight(1f)` + 横向滚动：Compose 的 Row 会**先量非加权子项**，
 * 所以右侧的播放按钮永远不会被挤掉；左侧在窄屏上可以滑动，而不是被裁掉。
 */
@Composable
fun ListHeader(
    query: String,
    onQueryChange: (String) -> Unit,
    sortField: SortField,
    sortAscending: Boolean,
    onSort: (SortField) -> Unit,
    modifier: Modifier = Modifier,
    /** 第二行左侧：分类切换 / 搜索时的结果统计 */
    leading: @Composable ((Modifier) -> Unit)? = null,
    /** 第二行右侧：播放全部 / 随机 / 重新扫描 等 */
    actions: @Composable ((Modifier) -> Unit)? = null,
    /** ⁝ 溢出菜单的内容。传 null 就不显示这个按钮 */
    overflow: (@Composable ColumnScope.() -> Unit)? = null
) {
    val dense = LocalDenseLayout.current

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(if (dense) 8.dp else 12.dp)
    ) {
        // ---- 行 1：搜索 + 排序 ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (dense) 8.dp else 10.dp)
        ) {
            SearchField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                height = if (dense) 38.dp else 44.dp
            )
            SortButton(field = sortField, ascending = sortAscending, onSelect = onSort)
        }

        // ---- 行 2：上下文槽位 + ⁝ + 播放操作 ----
        if (leading != null || actions != null || overflow != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    leading?.invoke(Modifier)
                    overflow?.let { DotsMenuButton(content = it) }
                }
                if (actions != null) {
                    Spacer(Modifier.width(8.dp))
                    actions(Modifier)
                }
            }
        }
    }
}
