package com.sonance.player.ui.sheets

import android.graphics.Bitmap
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.sonance.player.SonanceViewModel
import com.sonance.player.data.netease.NeteaseAuth
import com.sonance.player.ui.components.PillButton
import com.sonance.player.ui.theme.LocalSkin
import com.sonance.player.ui.theme.solidSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 网易云登录弹窗：扫码为主，Cookie 粘贴兜底。
 *
 * ## 为什么轮询放在这里，而不是 ViewModel
 *
 * 它只在弹窗可见时有意义 —— 关掉弹窗就该停，没有任何"后台继续等"的理由。
 * `LaunchedEffect` 天然跟着组合生命周期走，比在 VM 里挂一个需要手动取消的任务更省心。
 *
 * ## 二维码过期要自动换一张
 *
 * 800 的语义是"这张过期了"，不是失败。让用户手动点刷新等于把系统的计时器
 * 转嫁给用户；这里直接重新取一张，只在连续失败时才停下来报错。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NeteaseLoginSheet(vm: SonanceViewModel, onDismiss: () -> Unit) {
    val skin = LocalSkin.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var status by remember { mutableStateOf("正在生成二维码…") }
    var generation by remember { mutableIntStateOf(0) }
    var cookieMode by remember { mutableStateOf(false) }
    var cookieText by remember { mutableStateOf("") }
    var cookieBusy by remember { mutableStateOf(false) }
    var cookieError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(generation) {
        qrBitmap = null
        status = "正在生成二维码…"
        val content = NeteaseAuth.newQrContent()
        if (content == null) {
            status = "二维码获取失败，检查网络后点「刷新二维码」"
            return@LaunchedEffect
        }
        qrBitmap = withContext(Dispatchers.Default) { drawQr(content, 512) }
        status = "打开网易云 App，扫一扫"
        val key = NeteaseAuth.keyOf(content)
        // 总时长上限：二维码本身约 5 分钟过期，这里留足余量但绝不无限转
        val deadline = System.currentTimeMillis() + 3 * 60_000L
        while (isActive && System.currentTimeMillis() < deadline) {
            delay(1_600L)
            when (val result = NeteaseAuth.poll(context, key)) {
                NeteaseAuth.PollResult.Waiting -> status = "等待扫码…"
                NeteaseAuth.PollResult.Scanned -> status = "已扫码，请在手机上点确认"
                is NeteaseAuth.PollResult.Confirmed -> {
                    vm.onNeteaseLoggedIn(result.account)
                    onDismiss()
                    return@LaunchedEffect
                }
                NeteaseAuth.PollResult.Expired -> {
                    // 自动换一张，别让用户替系统数秒
                    status = "二维码已过期，正在刷新…"
                    generation++
                    return@LaunchedEffect
                }
                is NeteaseAuth.PollResult.Failed -> {
                    status = result.message
                    // 业务失败（风控 / 网络抖动）连续出现时给用户一个明确的出口
                    delay(2_400L)
                }
            }
        }
        if (isActive) status = "二维码已超时，点「刷新二维码」再试"
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = skin.surface.solidSurface(),
        dragHandle = { SheetGrabber() }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp)
                .padding(bottom = 28.dp)
        ) {
            Text(
                "登录网易云",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = skin.text
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "用你自己的账号登录，才能播放会员曲库、拿无损音质、下载会员歌曲。\n" +
                    "凭证只加密存在本机，不上传、不进云备份，随时可以退出。",
                style = MaterialTheme.typography.labelSmall,
                color = skin.textMuted
            )

            Spacer(Modifier.height(18.dp))

            if (!cookieMode) {
                // ---- 二维码 ----
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(androidx.compose.ui.graphics.Color.White)
                        .padding(18.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val bmp = qrBitmap
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "网易云登录二维码",
                            modifier = Modifier.size(220.dp)
                        )
                    } else {
                        Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
                            Text(
                                status,
                                style = MaterialTheme.typography.bodySmall,
                                color = androidx.compose.ui.graphics.Color(0xFF666666),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status.startsWith("已扫码")) skin.accent else skin.textMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "手机打开网易云音乐 → 左上角扫一扫 → 确认登录",
                    style = MaterialTheme.typography.labelSmall,
                    color = skin.textDim,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    PillButton(label = "刷新二维码", onClick = { generation++ }, filled = false)
                    PillButton(
                        label = "用 Cookie 登录",
                        onClick = {
                            cookieMode = true
                            cookieError = null
                        },
                        filled = false
                    )
                }
            } else {
                // ---- Cookie 兜底 ----
                Text(
                    "粘贴浏览器里的 Cookie",
                    style = MaterialTheme.typography.labelMedium,
                    color = skin.textMuted
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "整段 Cookie 或只贴 MUSIC_U 的值都行。电脑上登录 music.163.com 后，" +
                        "按 F12 → 应用 → Cookie 里能找到它。",
                    style = MaterialTheme.typography.labelSmall,
                    color = skin.textDim
                )
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(skin.surface2)
                        .border(1.dp, skin.accentDim, RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 12.dp)
                ) {
                    BasicTextField(
                        value = cookieText,
                        onValueChange = {
                            cookieText = it
                            cookieError = null
                        },
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            color = skin.text,
                            fontSize = 13.sp
                        ),
                        cursorBrush = SolidColor(skin.accent),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(72.dp)
                    )
                    if (cookieText.isEmpty()) {
                        Text(
                            "MUSIC_U=...",
                            style = MaterialTheme.typography.bodySmall,
                            color = skin.textDim
                        )
                    }
                }
                cookieError?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.labelSmall, color = skin.accent)
                }
                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    PillButton(
                        label = "登录",
                        enabled = cookieText.isNotBlank() && !cookieBusy,
                        onClick = {
                            cookieBusy = true
                            scope.launch {
                                val account = NeteaseAuth.loginWithCookie(context, cookieText)
                                cookieBusy = false
                                if (account == null) {
                                    cookieError = "Cookie 无效或已过期，检查后重试"
                                } else {
                                    vm.onNeteaseLoggedIn(account)
                                    onDismiss()
                                }
                            }
                        }
                    )
                    PillButton(
                        label = "返回扫码",
                        filled = false,
                        onClick = { cookieMode = false }
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            Text(
                "• 使用的是网易云的私有接口，不是官方开放平台，账号理论上存在被风控的可能\n" +
                    "• 只读你的账号权益与曲库信息，不会修改歌单、不会上传任何东西\n" +
                    "• 退出登录会立即删除本机保存的凭证",
                style = MaterialTheme.typography.labelSmall,
                color = skin.textDim
            )
        }
    }
}

/** 与其它弹窗同款的顶部小横条 */
@Composable
private fun SheetGrabber() {
    val skin = LocalSkin.current
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(width = 38.dp, height = 4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(skin.textMuted.copy(alpha = 0.45f))
        )
    }
}

/**
 * 二维码内容 → 位图。
 *
 * 用 zxing 的 `QRCodeWriter` 生成矩阵再逐像素落进 Bitmap：不引 `zxing-android`
 * 那套 Activity 集成，也不需要第三方二维码图片服务（那种服务等于把登录 key 发给别人）。
 * 纠错级别取 M —— 屏幕显示、没有污损，M 足够，且比 H 少一截模块数，扫起来更快。
 */
private fun drawQr(content: String, sizePx: Int): Bitmap? = runCatching {
    val hints = mapOf(
        EncodeHintType.MARGIN to 1,
        EncodeHintType.CHARACTER_SET to "UTF-8",
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M
    )
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    val pixels = IntArray(sizePx * sizePx)
    for (y in 0 until sizePx) {
        val row = y * sizePx
        for (x in 0 until sizePx) {
            pixels[row + x] = if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
    }
    Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.ARGB_8888)
}.getOrNull()
