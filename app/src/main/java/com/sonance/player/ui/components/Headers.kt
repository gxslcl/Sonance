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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sonance.player.data.SortField
import com.sonance.player.data.ViewMode
import com.sonance.player.ui.icon.SonanceIcons
import com.sonance.player.ui.theme.LocalSkin

@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "搜索歌曲、专辑或艺术家",
    height: Dp = 44.dp
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
fun ViewToggle(
    mode: ViewMode,
    onChange: (ViewMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val skin = LocalSkin.current
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(9.dp))
            .background(skin.surface)
            .border(1.dp, skin.border, RoundedCornerShape(9.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        listOf(ViewMode.LIST to SonanceIcons.ListView, ViewMode.GRID to SonanceIcons.GridView)
            .forEach { (m, icon) ->
                val selected = mode == m
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(if (selected) skin.surface3 else Color.Transparent)
                        .clickable { onChange(m) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = if (m == ViewMode.LIST) "列表视图" else "网格视图",
                        tint = if (selected) skin.accent else skin.textMuted,
                        modifier = Modifier.size(16.dp)
                    )
                }
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
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        chips.forEachIndexed { index, (label, selected) ->
            TagChip(label = label, selected = selected, onClick = { onSelect(index) })
        }
    }
}

/** 顶部工具行：搜索 + 视图切换 + 排序 + 快捷筛选/操作 */
@Composable
fun ListHeader(
    query: String,
    onQueryChange: (String) -> Unit,
    sortField: SortField,
    sortAscending: Boolean,
    onSort: (SortField) -> Unit,
    modifier: Modifier = Modifier,
    viewMode: ViewMode? = null,
    onViewMode: ((ViewMode) -> Unit)? = null,
    chips: @Composable ((Modifier) -> Unit)? = null,
    leadingText: String? = null,
    actions: @Composable ((Modifier) -> Unit)? = null
) {
    val skin = LocalSkin.current
    val dense = LocalDenseLayout.current

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(if (dense) 8.dp else 12.dp)
    ) {
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
            if (viewMode != null && onViewMode != null) {
                ViewToggle(mode = viewMode, onChange = onViewMode)
            }
            SortButton(field = sortField, ascending = sortAscending, onSelect = onSort)
        }

        if (dense) {
            // 矮屏：筛选 chips 与统计/操作合并到同一行
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                chips?.invoke(Modifier.weight(1f, fill = false))
                if (leadingText != null) {
                    Text(
                        text = leadingText,
                        style = MaterialTheme.typography.labelSmall,
                        color = skin.textMuted,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                actions?.invoke(Modifier)
            }
        } else {
            chips?.invoke(Modifier.fillMaxWidth())
            if (leadingText != null || actions != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = leadingText.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = skin.textMuted,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                    actions?.invoke(Modifier)
                }
            }
        }
    }
}
