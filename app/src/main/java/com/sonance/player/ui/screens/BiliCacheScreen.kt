package com.sonance.player.ui.screens

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sonance.player.data.bili.BiliCoverLoader
import com.sonance.player.data.bili.BiliEntry
import com.sonance.player.data.bili.BiliRepository
import com.sonance.player.data.bili.BiliScanResult
import com.sonance.player.data.bili.ShizukuShell
import com.sonance.player.data.formatSize
import com.sonance.player.playback.BiliVideoSession
import com.sonance.player.ui.VideoPlayerActivity
import com.sonance.player.ui.components.EmptyState
import com.sonance.player.ui.components.IconAction
import com.sonance.player.ui.components.LoadingState
import com.sonance.player.ui.components.RowActionSheet
import com.sonance.player.ui.components.RowSheetAction
import com.sonance.player.ui.components.SelectionCircle
import com.sonance.player.ui.icon.SonanceIcons
import com.sonance.player.ui.theme.LocalSkin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * B 站离线缓存列表。
 *
 * 只在**平板（宽屏）布局**下出现：侧栏「我的收藏」下面一项。手机布局没有侧栏，
 * 而且这个功能依赖 Shizuku + B 站缓存目录，属于「重度本地媒体管理」，
 * 放手机上只会挤占主流程。
 *
 * 数据来源全部是本机的 `Android/data/tv.danmaku.bilibilihd/download/`，
 * 通过 Shizuku 以 shell 身份读取，不联网、不碰账号。
 */
@Composable
fun BiliCacheScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val skin = LocalSkin.current
    val scope = rememberCoroutineScope()
    val repo = remember { BiliRepository(context) }

    // null 表示正在扫描
    var result by remember { mutableStateOf<BiliScanResult?>(null) }
    var preparingKey by remember { mutableStateOf<String?>(null) }
    var prepareProgress by remember { mutableStateOf(0f) }
    var hint by remember { mutableStateOf<String?>(null) }

    /** 后台正在刷新（列表已经用缓存铺出来了，不动它） */
    var refreshing by remember { mutableStateOf(false) }

    // 本地转存量。要遍历目录，只能在 IO 线程算，别拿去卡重组
    var localBytes by remember { mutableLongStateOf(0L) }

    suspend fun rescan() {
        hint = null
        refreshing = true
        result = repo.scan()
        refreshing = false
    }

    LaunchedEffect(Unit) {
        // 先用上次的结果把列表铺出来：扫 300 多条要几秒，
        // 而本地缓存读出来只要几十毫秒。之后后台扫描完成再悄悄换掉。
        val cached = repo.loadCached()
        if (cached != null) result = BiliScanResult.Ok(cached)
        rescan()
    }

    val entries = (result as? BiliScanResult.Ok)?.entries.orEmpty()

    LaunchedEffect(entries.size) {
        localBytes = withContext(Dispatchers.IO) { repo.cachedBytes() }
    }

    // 删除：待确认的目标、执行中标记、多选态
    var deleteTargets by remember { mutableStateOf<List<BiliEntry>>(emptyList()) }
    var deleting by remember { mutableStateOf(false) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedKeys by remember { mutableStateOf<Set<String>>(emptySet()) }

    /**
     * 真正删盘：先删我们自己的转存目录，再经 Shizuku 删 B 站原缓存。
     * 只有确认删干净的条目才从列表移除 —— 失败的留在列表里，连同原因一起提示，
     * 这样用户可以重试，也不会出现「列表没了但文件还在」。
     */
    fun performDelete(targets: List<BiliEntry>) {
        if (deleting || targets.isEmpty()) return
        val snapshot = entries
        deleting = true
        deleteTargets = emptyList()
        hint = "正在删除 ${targets.size} 个缓存…"
        scope.launch {
            val report = repo.deleteEntries(targets, snapshot)
            if (report.deletedKeys.isNotEmpty()) {
                val removed = report.deletedKeys.toSet()
                result = BiliScanResult.Ok(snapshot.filterNot { it.key in removed })
                localBytes = withContext(Dispatchers.IO) { repo.cachedBytes() }
            }
            deleting = false
            selectionMode = false
            selectedKeys = emptySet()
            hint = buildString {
                if (report.successCount > 0) append("已删除 ${report.successCount} 个缓存")
                if (report.failureCount > 0) {
                    if (report.successCount > 0) append("；")
                    append("${report.failureCount} 个失败：")
                    val first = report.failures.first()
                    append(first.title).append("（").append(first.reason).append("）")
                    if (report.failureCount > 1) append(" 等")
                }
            }
        }
    }

    // 多选态下先退多选，再让外层处理返回
    BackHandler(enabled = selectionMode) {
        selectionMode = false
        selectedKeys = emptySet()
    }

    deleteTargets.takeIf { it.isNotEmpty() }?.let { targets ->
        AlertDialog(
            onDismissRequest = { if (!deleting) deleteTargets = emptyList() },
            title = { Text("删除缓存") },
            text = {
                Text(
                    text = if (targets.size == 1) {
                        "将从设备上永久删除「${targets.first().displayTitle}」的缓存文件，" +
                            "包括视频轨、音频轨、弹幕/字幕与元数据，以及本应用转存的合成文件。此操作不可恢复。"
                    } else {
                        "将删除 ${targets.size} 个缓存，其视频轨、音频轨、弹幕/字幕、元数据以及" +
                            "本应用转存的合成文件都会被永久删除。此操作不可恢复，是否确认？"
                    },
                    color = skin.text
                )
            },
            confirmButton = {
                TextButton(enabled = !deleting, onClick = { performDelete(targets) }) {
                    Text("删除", color = skin.danger)
                }
            },
            dismissButton = {
                TextButton(enabled = !deleting, onClick = { deleteTargets = emptyList() }) {
                    Text("取消", color = skin.textMuted)
                }
            }
        )
    }

    fun play(entry: BiliEntry) {
        if (!entry.playable) {
            hint = "这条缓存没有独立的音视频轨（旧版缓存格式），暂不支持"
            return
        }
        if (preparingKey != null) return

        // 浮窗存在时不进全屏：把浮窗里的内容就地换成刚点的这条。
        // 列表与索引都从这次点击重新铺给会话，不读任何过期的「当前播放项」。
        val session = BiliVideoSession.of(context)
        if (session.floating && session.items.isNotEmpty()) {
            val playable = entries.filter { it.playable }.map { e ->
                BiliVideoSession.Item(
                    key = e.key,
                    title = e.displayTitle,
                    owner = e.owner,
                    entry = e,
                    fallbackPath = null
                )
            }
            session.playFromList(playable, entry.key)
            return
        }

        preparingKey = entry.key
        prepareProgress = 0f
        hint = null
        scope.launch {
            val outcome = repo.prepare(entry) { prepareProgress = it }
            preparingKey = null
            outcome.onSuccess { media ->
                context.startActivity(
                    Intent(context, VideoPlayerActivity::class.java)
                        .putExtra(VideoPlayerActivity.EXTRA_VIDEO, media.file.absolutePath)
                        .putExtra(VideoPlayerActivity.EXTRA_KEY, entry.key)
                        .putStringArrayListExtra(
                            VideoPlayerActivity.EXTRA_KEYS,
                            ArrayList(entries.map { it.key })
                        )
                        .putExtra(VideoPlayerActivity.EXTRA_TITLE, entry.displayTitle)
                        .putExtra(VideoPlayerActivity.EXTRA_OWNER, entry.owner)
                )
            }.onFailure {
                hint = it.message ?: "准备视频失败"
            }
        }
    }

    Column(modifier.fillMaxSize()) {
        when (val r = result) {
            null -> LoadingState(
                title = "正在读取 B 站缓存…",
                subtitle = "通过 Shizuku 以 shell 身份访问缓存目录"
            )

            BiliScanResult.NoShizuku -> EmptyState(
                icon = SonanceIcons.Download,
                title = "还没有安装 Shizuku",
                subtitle = "B 站的缓存放在 Android/data 里，Android 11 起普通应用读不到。" +
                    "装一个 Shizuku，用无线调试启动服务后就能读；不需要 root。",
                actionLabel = "打开 Shizuku",
                onAction = {
                    val intent = context.packageManager
                        .getLaunchIntentForPackage(ShizukuShell.SHIZUKU_PACKAGE)
                    if (intent != null) {
                        runCatching { context.startActivity(intent) }
                    } else {
                        hint = "没有找到 Shizuku，请先从应用商店或官网安装"
                    }
                }
            )

            BiliScanResult.NotRunning -> EmptyState(
                icon = SonanceIcons.Download,
                title = "Shizuku 服务没有运行",
                subtitle = "打开 Shizuku，按提示用无线调试启动服务（每次重启手机后需要重新启动一次），" +
                    "然后回到这里刷新。",
                actionLabel = "去启动",
                onAction = {
                    val intent = context.packageManager
                        .getLaunchIntentForPackage(ShizukuShell.SHIZUKU_PACKAGE)
                    if (intent != null) runCatching { context.startActivity(intent) }
                }
            )

            BiliScanResult.NoPermission -> EmptyState(
                icon = SonanceIcons.Download,
                title = "需要授予 Shizuku 权限",
                subtitle = "Sonance 需要以 shell 身份读取缓存目录。只有你在列表里主动确认删除时，" +
                    "才会动 B 站的缓存文件。",
                actionLabel = "去授权",
                onAction = {
                    // 必须是用户点击触发：系统弹窗拦一道，先解释清楚再问
                    if (context is Activity) {
                        ShizukuShell.requestPermission { granted ->
                            if (granted) scope.launch { rescan() }
                        }
                    } else {
                        hint = "请在应用内操作"
                    }
                }
            )

            is BiliScanResult.Failed -> EmptyState(
                icon = SonanceIcons.Download,
                title = "读取失败",
                subtitle = r.message,
                actionLabel = "重试",
                onAction = { scope.launch { rescan() } }
            )

            is BiliScanResult.Ok -> {
                if (entries.isEmpty()) {
                    EmptyState(
                        icon = SonanceIcons.Download,
                        title = "还没有离线缓存",
                        subtitle = "先在 B 站里缓存视频，回到这里点右上角刷新。",
                        actionLabel = "刷新",
                        onAction = { scope.launch { rescan() } }
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = 16.dp, end = 16.dp, top = 6.dp, bottom = 16.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        item {
                            if (selectionMode) {
                                // 多选工具条：结构与歌曲列表的选择态一致（关闭 / 全选 / 删除）
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconAction(
                                        icon = SonanceIcons.Close,
                                        contentDescription = "退出多选",
                                        onClick = {
                                            selectionMode = false
                                            selectedKeys = emptySet()
                                        },
                                        iconSize = 18.dp,
                                        tint = skin.text
                                    )
                                    Text(
                                        text = "已选 ${selectedKeys.size} 个",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = skin.text,
                                        modifier = Modifier
                                            .padding(start = 2.dp)
                                            .weight(1f)
                                    )
                                    val allSelected =
                                        entries.isNotEmpty() && selectedKeys.size == entries.size
                                    Text(
                                        text = if (allSelected) "取消全选" else "全选",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (deleting) skin.textMuted else skin.accent,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable(enabled = !deleting) {
                                                selectedKeys = if (allSelected) {
                                                    emptySet()
                                                } else {
                                                    entries.map { it.key }.toSet()
                                                }
                                            }
                                            .padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                    IconAction(
                                        icon = SonanceIcons.Trash,
                                        contentDescription = "删除选中缓存",
                                        onClick = {
                                            deleteTargets = entries.filter {
                                                it.key in selectedKeys
                                            }
                                        },
                                        iconSize = 18.dp,
                                        tint = if (selectedKeys.isEmpty()) {
                                            skin.textMuted
                                        } else {
                                            skin.danger
                                        },
                                        enabled = selectedKeys.isNotEmpty() && !deleting
                                    )
                                }
                            } else {
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            text = "共 ${entries.size} 个缓存",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = skin.textMuted
                                        )
                                        // 总大小：这些缓存一共占了多少空间。
                                        // 用户关心的就是这个 —— 手机上几百个缓存动辄几十 GB，
                                        // 得先看见才知道该不该清理。
                                        Text(
                                            text = "占用 ${formatSize(entries.sumOf { it.sizeBytes })}" +
                                                (if (localBytes > 0) {
                                                    " · 已转存 ${formatSize(localBytes)}"
                                                } else {
                                                    ""
                                                }),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = skin.textMuted.copy(alpha = 0.75f)
                                        )
                                    }
                                    // 长按已经取代 ⋮：在统计行常驻一句灰字提示，
                                    // 纯文字、不是按钮，也不新增图标。
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            text = if (refreshing) "刷新中…" else "刷新",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Medium,
                                            color = if (refreshing) skin.textMuted else skin.accent,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .clickable(enabled = !refreshing) {
                                                    scope.launch { rescan() }
                                                }
                                                .padding(horizontal = 10.dp, vertical = 4.dp)
                                        )
                                        Text(
                                            text = "长按条目可更多操作",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = skin.textMuted.copy(alpha = 0.65f)
                                        )
                                    }
                                }
                            }
                        }
                        items(entries, key = { it.key }) { entry ->
                            BiliRow(
                                entry = entry,
                                preparing = preparingKey == entry.key,
                                progress = if (preparingKey == entry.key) prepareProgress else 0f,
                                enabled = preparingKey == null && !deleting,
                                selectionMode = selectionMode,
                                selected = entry.key in selectedKeys,
                                onClick = {
                                    if (selectionMode) {
                                        selectedKeys = if (entry.key in selectedKeys) {
                                            selectedKeys - entry.key
                                        } else {
                                            selectedKeys + entry.key
                                        }
                                    } else {
                                        play(entry)
                                    }
                                },
                                onDelete = { deleteTargets = listOf(entry) },
                                onEnterMultiSelect = {
                                    selectionMode = true
                                    selectedKeys = setOf(entry.key)
                                }
                            )
                        }
                    }
                }
            }
        }

        hint?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelMedium,
                color = skin.accent,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun BiliRow(
    entry: BiliEntry,
    preparing: Boolean,
    progress: Float,
    enabled: Boolean,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onEnterMultiSelect: () -> Unit
) {
    val skin = LocalSkin.current
    var actionsOpen by remember(entry.key) { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            // 多选态下点整行就是勾选；普通态仍然只允许可播放的条目进入播放。
            // 行尾不再有 ⋮：低频操作（删除 / 多选）收进长按弹出的动作面板。
            .combinedClickable(
                enabled = enabled && (selectionMode || entry.playable),
                onClick = onClick,
                onLongClick = if (enabled && !selectionMode) {
                    { actionsOpen = true }
                } else {
                    null
                }
            )
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .width(112.dp)
                .height(63.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(skin.surface3)
        ) {
            BiliCover(entry.cover, Modifier.fillMaxSize())
            if (!entry.playable) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(skin.bg.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "不支持",
                        style = MaterialTheme.typography.labelSmall,
                        color = skin.text
                    )
                }
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = entry.displayTitle,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (entry.playable) skin.text else skin.textMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = buildString {
                    if (entry.owner.isNotBlank()) append(entry.owner).append(" · ")
                    append(entry.qualityLabel)
                    if (entry.frameNote.isNotBlank()) append(' ').append(entry.frameNote)
                    append(" · ").append(entry.durationText)
                },
                style = MaterialTheme.typography.labelSmall,
                color = skin.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.width(10.dp))

        if (selectionMode) {
            Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
                SelectionCircle(selected = selected)
            }
        } else if (preparing) {
            Box(Modifier.width(56.dp), contentAlignment = Alignment.CenterEnd) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = skin.accent
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = skin.textMuted
                    )
                }
            }
        } else {
            // 大小文字固定 56dp 右对齐：去掉 ⋮ 之后行尾宽度不变，
            // 文字基线和删除前完全一致，列表看上去不会"整列往右挪"。
            Text(
                text = formatSize(entry.sizeBytes),
                style = MaterialTheme.typography.labelSmall,
                color = skin.textMuted,
                modifier = Modifier.width(56.dp),
                textAlign = TextAlign.End
            )
        }
    }

    // 长按动作面板：B站行只有"删除本条"和"多选删除"两件事
    if (actionsOpen) {
        RowActionSheet(
            title = entry.displayTitle,
            subtitle = listOf(entry.owner, entry.qualityLabel)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
                .ifBlank { null },
            actions = listOf(
                RowSheetAction("删除文件…", destructive = true, onClick = onDelete),
                RowSheetAction("多选删除", destructive = true, onClick = onEnterMultiSelect)
            ),
            onDismiss = { actionsOpen = false }
        )
    }
}

@Composable
private fun BiliCover(url: String, modifier: Modifier = Modifier) {
    val skin = LocalSkin.current
    var bitmap by remember(url) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(url) { bitmap = BiliCoverLoader.load(url, 240) }

    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    } else {
        Box(modifier, contentAlignment = Alignment.Center) {
            Icon(
                imageVector = SonanceIcons.Play,
                contentDescription = null,
                tint = skin.textMuted,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
