package com.sonance.player.ui.sheets

import android.graphics.Bitmap
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.sonance.player.SonanceViewModel
import com.sonance.player.data.netease.NeteaseAuth
import com.sonance.player.data.netease.NeteaseLevel
import com.sonance.player.ui.components.PillButton
import com.sonance.player.ui.components.TagChip
import com.sonance.player.ui.components.rememberRemoteCover
import com.sonance.player.ui.theme.LocalSkin
import com.sonance.player.ui.theme.solidSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class NeteaseLoginTab(val label: String) {
    QR("扫码"),
    SMS("验证码"),
    PASSWORD("密码"),
    COOKIE("Cookie")
}

/**
 * 网易云多方式登录弹窗：扫码 / 手机验证码 / 账号密码 / Cookie 凭证。
 *
 * 轮询只在选中「扫码」且弹窗打开时运行，切到其它方式或关闭弹窗自动停止。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NeteaseLoginSheet(vm: SonanceViewModel, onDismiss: () -> Unit) {
    val skin = LocalSkin.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var tab by remember { mutableStateOf(NeteaseLoginTab.QR) }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var qrStatus by remember { mutableStateOf("正在生成二维码…") }
    var qrGeneration by remember { mutableIntStateOf(0) }

    // 短信验证码状态
    var smsPhone by remember { mutableStateOf("") }
    var smsCode by remember { mutableStateOf("") }
    var smsCountdown by remember { mutableIntStateOf(0) }
    var smsSending by remember { mutableStateOf(false) }

    // 账号密码状态
    var pwdPhone by remember { mutableStateOf("") }
    var pwdText by remember { mutableStateOf("") }
    var pwdVisible by remember { mutableStateOf(false) }

    // Cookie 状态
    var cookieText by remember { mutableStateOf("") }

    // 表单公共提交态与错误文案
    var busy by remember { mutableStateOf(false) }
    var formError by remember { mutableStateOf<String?>(null) }
    var formInfo by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(smsCountdown) {
        if (smsCountdown > 0) {
            delay(1_000L)
            smsCountdown--
        }
    }

    LaunchedEffect(tab, qrGeneration) {
        if (tab != NeteaseLoginTab.QR) return@LaunchedEffect
        qrBitmap = null
        qrStatus = "正在生成二维码…"
        val content = NeteaseAuth.newQrContent()
        if (content == null) {
            qrStatus = "二维码获取失败，检查网络后点「刷新二维码」"
            return@LaunchedEffect
        }
        qrBitmap = withContext(Dispatchers.Default) { drawQr(content, 512) }
        qrStatus = "打开网易云 App，扫一扫"
        val key = NeteaseAuth.keyOf(content)
        val deadline = System.currentTimeMillis() + 3 * 60_000L
        while (isActive && System.currentTimeMillis() < deadline) {
            delay(1_600L)
            when (val result = NeteaseAuth.poll(context, key)) {
                NeteaseAuth.PollResult.Waiting -> qrStatus = "等待扫码…"
                NeteaseAuth.PollResult.Scanned -> qrStatus = "已扫码，请在手机上点确认"
                is NeteaseAuth.PollResult.Confirmed -> {
                    vm.onNeteaseLoggedIn(result.account)
                    onDismiss()
                    return@LaunchedEffect
                }
                NeteaseAuth.PollResult.Expired -> {
                    qrStatus = "二维码已过期，正在刷新…"
                    qrGeneration++
                    return@LaunchedEffect
                }
                is NeteaseAuth.PollResult.Failed -> {
                    qrStatus = result.message
                    delay(2_400L)
                }
            }
        }
        if (isActive) qrStatus = "二维码已超时，点「刷新二维码」再试"
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
                .verticalScroll(rememberScrollState())
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
                "登录后将解锁会员曲库、个人每日推荐、云歌单与无损音质。凭证只加密保存在本机。",
                style = MaterialTheme.typography.labelSmall,
                color = skin.textMuted
            )

            Spacer(Modifier.height(14.dp))

            // 方式切换胶囊栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(skin.surface2)
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                NeteaseLoginTab.entries.forEach { item ->
                    val selected = tab == item
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (selected) skin.accent else Color.Transparent)
                            .clickable(enabled = !busy) {
                                tab = item
                                formError = null
                                formInfo = null
                            }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = item.label,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (selected) Color.White else skin.textMuted,
                            maxLines = 1
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            when (tab) {
                NeteaseLoginTab.QR -> {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.White)
                            .padding(18.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        val bmp = qrBitmap
                        if (bmp != null) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "网易云登录二维码",
                                modifier = Modifier.size(210.dp)
                            )
                        } else {
                            Box(Modifier.size(210.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    qrStatus,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF666666),
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Text(
                        qrStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (qrStatus.startsWith("已扫码")) skin.accent else skin.textMuted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "手机打开网易云音乐 → 左上角扫一扫 → 确认登录（免输入密码）",
                        style = MaterialTheme.typography.labelSmall,
                        color = skin.textDim,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(14.dp))
                    PillButton(
                        label = "刷新二维码",
                        filled = false,
                        onClick = { qrGeneration++ }
                    )
                }

                NeteaseLoginTab.SMS -> {
                    Text("手机号（+86）", style = MaterialTheme.typography.labelMedium, color = skin.textMuted)
                    Spacer(Modifier.height(6.dp))
                    SheetInputBox(
                        value = smsPhone,
                        onValueChange = {
                            smsPhone = it.filter(Char::isDigit).take(15)
                            formError = null
                        },
                        placeholder = "输入绑定网易云的手机号",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("短信验证码", style = MaterialTheme.typography.labelMedium, color = skin.textMuted)
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        SheetInputBox(
                            value = smsCode,
                            onValueChange = {
                                smsCode = it.filter(Char::isDigit).take(8)
                                formError = null
                            },
                            placeholder = "6 位验证码",
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                        PillButton(
                            label = when {
                                smsSending -> "发送中…"
                                smsCountdown > 0 -> "${smsCountdown}s"
                                else -> "获取验证码"
                            },
                            filled = false,
                            enabled = smsPhone.length >= 6 && smsCountdown == 0 && !smsSending && !busy,
                            onClick = {
                                smsSending = true
                                formError = null
                                formInfo = null
                                scope.launch {
                                    val res = NeteaseAuth.sendSmsCaptcha(smsPhone)
                                    smsSending = false
                                    when (res) {
                                        NeteaseAuth.SmsSendResult.Sent -> {
                                            smsCountdown = 60
                                            formInfo = "验证码已发送，请注意查收短信"
                                        }
                                        is NeteaseAuth.SmsSendResult.Failed -> {
                                            formError = res.message
                                        }
                                    }
                                }
                            }
                        )
                    }
                    FormFeedback(error = formError, info = formInfo)
                    Spacer(Modifier.height(16.dp))
                    PillButton(
                        label = if (busy) "正在登录…" else "验证码登录",
                        enabled = smsPhone.length >= 6 && smsCode.length >= 4 && !busy,
                        onClick = {
                            busy = true
                            formError = null
                            formInfo = null
                            scope.launch {
                                val res = NeteaseAuth.loginWithPhoneCaptcha(context, smsPhone, smsCode)
                                busy = false
                                when (res) {
                                    is NeteaseAuth.LoginResult.Success -> {
                                        vm.onNeteaseLoggedIn(res.account)
                                        onDismiss()
                                    }
                                    is NeteaseAuth.LoginResult.Failed -> formError = res.message
                                }
                            }
                        }
                    )
                }

                NeteaseLoginTab.PASSWORD -> {
                    Text("手机号", style = MaterialTheme.typography.labelMedium, color = skin.textMuted)
                    Spacer(Modifier.height(6.dp))
                    SheetInputBox(
                        value = pwdPhone,
                        onValueChange = {
                            pwdPhone = it.filter(Char::isDigit).take(15)
                            formError = null
                        },
                        placeholder = "输入网易云账号手机号",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("登录密码", style = MaterialTheme.typography.labelMedium, color = skin.textMuted)
                        Text(
                            text = if (pwdVisible) "隐藏密码" else "显示密码",
                            style = MaterialTheme.typography.labelSmall,
                            color = skin.accent,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { pwdVisible = !pwdVisible }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    SheetInputBox(
                        value = pwdText,
                        onValueChange = {
                            pwdText = it
                            formError = null
                        },
                        placeholder = "仅用于单次摘要验证，不保存明文",
                        visualTransformation = if (pwdVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                    )
                    FormFeedback(error = formError, info = formInfo)
                    Spacer(Modifier.height(16.dp))
                    PillButton(
                        label = if (busy) "正在登录…" else "密码登录",
                        enabled = pwdPhone.length >= 6 && pwdText.isNotEmpty() && !busy,
                        onClick = {
                            busy = true
                            formError = null
                            scope.launch {
                                val res = NeteaseAuth.loginWithPassword(context, pwdPhone, pwdText)
                                busy = false
                                when (res) {
                                    is NeteaseAuth.LoginResult.Success -> {
                                        vm.onNeteaseLoggedIn(res.account)
                                        onDismiss()
                                    }
                                    is NeteaseAuth.LoginResult.Failed -> formError = res.message
                                }
                            }
                        }
                    )
                }

                NeteaseLoginTab.COOKIE -> {
                    Text("粘贴浏览器里的 Cookie", style = MaterialTheme.typography.labelMedium, color = skin.textMuted)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "整段 Cookie 或只贴 MUSIC_U 的值都行。在电脑浏览器登录 music.163.com 后，按 F12 → 应用 → Cookie 即可复制。",
                        style = MaterialTheme.typography.labelSmall,
                        color = skin.textDim
                    )
                    Spacer(Modifier.height(10.dp))
                    SheetInputBox(
                        value = cookieText,
                        onValueChange = {
                            cookieText = it
                            formError = null
                        },
                        placeholder = "MUSIC_U=...",
                        singleLine = false,
                        height = 84.dp
                    )
                    FormFeedback(error = formError, info = formInfo)
                    Spacer(Modifier.height(16.dp))
                    PillButton(
                        label = if (busy) "正在校验…" else "Cookie 登录",
                        enabled = cookieText.isNotBlank() && !busy,
                        onClick = {
                            busy = true
                            formError = null
                            scope.launch {
                                val account = NeteaseAuth.loginWithCookie(context, cookieText)
                                busy = false
                                if (account == null) {
                                    formError = "Cookie 无效或已过期，检查后重试"
                                } else {
                                    vm.onNeteaseLoggedIn(account)
                                    onDismiss()
                                }
                            }
                        }
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            Text(
                "• 只读取你的账号权益、每日推荐与个人云歌单，不会修改歌单或上传任何内容\n" +
                    "• 退出登录会立即删除本机加密凭证",
                style = MaterialTheme.typography.labelSmall,
                color = skin.textDim
            )
        }
    }
}

/**
 * 已登录账号管理弹窗（由在线页顶部账号胶囊点开）：查看会员身份、快捷切音质、切换账号、退出。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NeteaseAccountSheet(
    vm: SonanceViewModel,
    onSwitchAccount: () -> Unit,
    onDismiss: () -> Unit
) {
    val account = vm.neteaseAccount ?: return
    val skin = LocalSkin.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val avatar = rememberRemoteCover(account.avatarUrl.takeIf { it.isNotBlank() }, 140)

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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(skin.surface2),
                    contentAlignment = Alignment.Center
                ) {
                    if (avatar != null) {
                        Image(
                            bitmap = avatar,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Text(
                            account.nickname.take(1).ifBlank { "云" },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = skin.textMuted
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        account.nickname.ifBlank { "网易云用户" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = skin.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        if (account.isVip) "黑胶 VIP · 已解锁无损与会员曲库" else "普通账号 · 最高支持极高 320k",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (account.isVip) skin.accent else skin.textMuted
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            Text("在线播放与下载音质", style = MaterialTheme.typography.labelMedium, color = skin.textMuted)
            Spacer(Modifier.height(8.dp))
            val levels = if (account.isVip) NeteaseLevel.entries else NeteaseLevel.freeTier
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                levels.forEach { level ->
                    TagChip(
                        label = level.label,
                        selected = vm.onlineQuality == level,
                        onClick = { vm.changeOnlineQuality(level) }
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                PillButton(
                    label = "切换账号",
                    filled = false,
                    onClick = {
                        onDismiss()
                        onSwitchAccount()
                    }
                )
                PillButton(
                    label = "退出登录",
                    filled = false,
                    onClick = {
                        vm.logoutNetease()
                        onDismiss()
                    }
                )
            }
        }
    }
}

@Composable
private fun SheetInputBox(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    height: Dp? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default
) {
    val skin = LocalSkin.current
    val boxMod = modifier
        .let { if (height != null) it.height(height) else it }
        .fillMaxWidth()
        .clip(RoundedCornerShape(10.dp))
        .background(skin.surface2)
        .border(1.dp, skin.accentDim, RoundedCornerShape(10.dp))
        .padding(horizontal = 12.dp, vertical = 12.dp)
    Box(boxMod, contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = skin.text,
                fontSize = 14.sp
            ),
            cursorBrush = SolidColor(skin.accent),
            modifier = Modifier.fillMaxWidth()
        )
        if (value.isEmpty()) {
            Text(
                text = placeholder,
                style = MaterialTheme.typography.bodySmall,
                color = skin.textDim
            )
        }
    }
}

@Composable
private fun FormFeedback(error: String?, info: String?) {
    val skin = LocalSkin.current
    if (!error.isNullOrBlank()) {
        Spacer(Modifier.height(8.dp))
        Text(error, style = MaterialTheme.typography.labelSmall, color = skin.accent)
    } else if (!info.isNullOrBlank()) {
        Spacer(Modifier.height(8.dp))
        Text(info, style = MaterialTheme.typography.labelSmall, color = skin.textMuted)
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
