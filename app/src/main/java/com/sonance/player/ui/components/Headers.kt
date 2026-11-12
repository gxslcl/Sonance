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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sonance.player.data.SortField
import com.sonance.player.data.ViewMode
import com.sonance.player.ui.icon.SonanceIcons
import com.sonance.player.ui.theme.LocalSkin
import com.sonance.player.ui.theme.Skin
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.input.pointer.pointerInput

/**
 * 列表头部的圆角：16dp 对 40dp 高是「圆润矩形」而不是两端半圆。
 * 之前用 999dp 全胶囊，比下方 30dp 高的分类标签还抢戏；这里收一档，
 * 但仍和 TagChip 的 20dp 属于同一族圆润语言。
 */
private val HeaderFieldShape = RoundedCornerShape(16.dp)

/**
 * 搜索框 / 排序按钮共用的「有厚度的玻璃」。
 *
 * 只靠一层 8% 暖白提亮不够：列表背景是封面模糊 + 遮罩的动态渐变，
 * 亮部会把这点提亮直接吞掉，看起来和外面融成一片。所以先用 20% 黑
 * 压住底下忽明忽暗的封面斑纹，得到一块稳定底色，再叠一层上亮下暗的
 * 暖白渐变（13% → 6%）做出玻璃厚度 —— 既和背景拉开层次，又不会退回
 * 80% 不透明 surface 那种死黑板。两个控件共用一个函数，保证完全一致。
 */
private fun Modifier.headerGlass(skin: Skin, shape: Shape): Modifier {
    // 亮色皮肤不能沿用「文字色提亮」：那套渐变本质是把深色文字色叠在 20% 黑上，
    // 深色主题里是提亮，亮色主题里就成了一块脏灰 —— 跟米白页面完全不搭。
    // 浅色改用白色系玻璃（58% 底 + 82%→46% 竖向渐变），压住背后封面斑纹的同时
    // 保持通透，边界交给外面那条 border。
    val baseAlpha = if (skin.isLight) Color.White.copy(alpha = 0.58f)
    else Color.Black.copy(alpha = 0.20f)
    val top = if (skin.isLight) Color.White.copy(alpha = 0.82f)
    else skin.text.copy(alpha = 0.13f)
    val bottom = if (skin.isLight) Color.White.copy(alpha = 0.46f)
    else skin.text.copy(alpha = 0.06f)
    return this
        .clip(shape)
        .background(baseAlpha)
        .background(Brush.verticalGradient(listOf(top, bottom)))
}

@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "搜索歌曲、专辑或艺术家",
    height: Dp = 40.dp,
    /**
     * 键盘右下角键的动作。传了它才会把软键盘的回车换成「搜索」。
     *
     * 本地曲库搜索是**随输入即时过滤**的，不需要这个动作（所以默认 null，
     * 保持回车换行的原样行为）；在线搜索要走网络，用户按一下回车才是明确意图。
     */
    onSearch: (() -> Unit)? = null
) {
    val skin = LocalSkin.current
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .height(height)
            .headerGlass(skin, HeaderFieldShape)
            // 平时克制、聚焦时明确：给一条 accent 细边，也进一步拉开层次
            .border(
                1.dp,
                if (focused) skin.accent.copy(alpha = 0.55f) else skin.border,
                HeaderFieldShape
            )
            .padding(horizontal = 14.dp),
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
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focused = it.isFocused }
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
    modifier: Modifier = Modifier,
    /** 与搜索框等高，圆角轮廓才接得上 */
    height: Dp = 40.dp
) {
    val skin = LocalSkin.current
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            modifier = Modifier
                .height(height)
                .headerGlass(skin, HeaderFieldShape)
                .border(1.dp, skin.border, HeaderFieldShape)
                .clickable { open = true }
                // 瘦身成 图标+小箭头（≈52dp）：原来带着「添加时间」全称
                // 要占掉搜索框近 1/3 宽度。当前是哪种排序收进下拉菜单里看 ——
                // 排序是低频确认项，不值得常驻一个文字标签。
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(
                imageVector = SonanceIcons.Sort,
                contentDescription = "排序方式",
                tint = skin.textDim,
                modifier = Modifier.size(15.dp)
            )
            Text(
                text = if (ascending) "↑" else "↓",
                style = MaterialTheme.typography.labelLarge,
                color = skin.textDim
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
    overflow: (@Composable ColumnScope.() -> Unit)? = null,
    /**
     * 双击第二行左侧的**空白**（「全部 / 最近 / 收藏」指示器右边、右侧图标左边的
     * 那一大片空地）→ 列表回到顶部。
     *
     * 双击而不是单击：这一行同时是分类切换的操作区，单击很容易误触
     * （比如想点「最近」却把列表拽回顶部）。
     */
    onDoubleTapToTop: (() -> Unit)? = null
) {
    val dense = LocalDenseLayout.current

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(if (dense) 8.dp else 12.dp)
    ) {
        // ---- 行 1：搜索 + 排序 ----
        val fieldHeight = if (dense) 36.dp else 40.dp
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (dense) 8.dp else 10.dp)
        ) {
            SearchField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                height = fieldHeight
            )
            SortButton(
                field = sortField,
                ascending = sortAscending,
                onSelect = onSort,
                height = fieldHeight
            )
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
                        .horizontalScroll(rememberScrollState())
                        // 挂在这一整条上（而不只是缝隙里）：它本来就是 weight(1f)，
                        // 指示器靠左排完之后剩下的一大片空地都归它，双击命中区域足够大。
                        .then(
                            if (onDoubleTapToTop == null) Modifier
                            else Modifier.pointerInput(onDoubleTapToTop) {
                                detectTapGestures(onDoubleTap = { onDoubleTapToTop.invoke() })
                            }
                        ),
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
