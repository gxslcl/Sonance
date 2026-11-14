package com.sonance.player.data.netease

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 网易云账号：扫码登录、会员状态、退出。
 *
 * ## 为什么是扫码，不是账号密码
 *
 * 手机通常就在手里，扫一下是最短路径；更关键的是**不碰密码** ——
 * 密码一旦进来就要面对"怎么存"的问题，而扫码只需要存一个 Cookie。
 *
 * ## 只走 GET
 *
 * 实测（2026-11-13）二维码两步、`nuser/account/get` 都能用 `/api/...` 的 GET 入口，
 * 不校验 weapi/eapi 签名。所以这一层里没有 AES、没有 RSA，也没有第三方代理实例。
 *
 * ## 状态存在哪
 *
 * 内存里一份 [account]（UI 直接读），磁盘上一份加密的（[NeteaseAccountStore]）。
 * 冷启动不主动请求网络 —— 离线时也能显示"已登录"，只是会员态是上次的值。
 */
object NeteaseAuth {

    private const val TAG = "SonanceNetease"

    /** 二维码内容前缀。扫码后手机端会跳到确认页 */
    private const val QR_PREFIX = "/login?codekey="

    /** 当前账号。null = 未登录 */
    @Volatile
    var account: NeteaseAccount? = null
        private set

    private var loadedFromDisk = false

    /** 进程内第一次读盘；之后直接用内存值。返回当前账号 */
    fun ensureLoaded(context: Context): NeteaseAccount? {
        if (!loadedFromDisk) {
            loadedFromDisk = true
            account = NeteaseAccountStore.load(context)
        }
        return account
    }

    fun logout(context: Context) {
        account = null
        loadedFromDisk = true
        NeteaseAccountStore.clear(context)
    }

    // ===== 扫码 =====

    /**
     * 取一个新的二维码 key。
     *
     * @return 用于渲染二维码的完整链接（`https://music.163.com/login?codekey=...`），
     *         失败返回 null
     */
    suspend fun newQrContent(): String? = withContext(Dispatchers.IO) {
        val json = NeteaseHttp.getJson("/api/login/qrcode/unikey?type=1") ?: return@withContext null
        val key = json.optString("unikey")
        if (json.optInt("code", -1) != 200 || key.isBlank()) return@withContext null
        NeteaseHttp.BASE + QR_PREFIX + key
    }

    /** 从二维码内容里把 key 抽回来 —— 轮询要用它，而不是整条 URL */
    fun keyOf(qrContent: String): String = qrContent.substringAfter("codekey=", "")

    sealed interface PollResult {
        /** 还没人扫 */
        data object Waiting : PollResult

        /** 手机上扫了，等确认 */
        data object Scanned : PollResult

        /** 确认成功，[account] 已就绪并落盘 */
        data class Confirmed(val account: NeteaseAccount) : PollResult

        /** 二维码过期，需要重新取一张 */
        data object Expired : PollResult

        /** 网络或业务失败；[message] 可直接给用户看 */
        data class Failed(val message: String) : PollResult
    }

    /**
     * 轮询一次扫码结果。
     *
     * 802 是"已扫码待确认"，803 才算成功 —— 这两步分开是有意义的：
     * 把 802 当成功会让用户在手机上还没点确认时就被"登录"了。
     */
    suspend fun poll(context: Context, key: String): PollResult = withContext(Dispatchers.IO) {
        if (key.isBlank()) return@withContext PollResult.Expired
        val response = NeteaseHttp.get("/api/login/qrcode/client/login?key=$key&type=1")
        val body = response.body ?: return@withContext PollResult.Failed("网络不通，稍后再试")

        val code = runCatching { org.json.JSONObject(body).optInt("code", -1) }.getOrDefault(-1)
        when (code) {
            800 -> PollResult.Expired
            801 -> PollResult.Waiting
            802 -> PollResult.Scanned
            803 -> {
                val musicU = NeteaseHttp.cookieValue(response.setCookies, "MUSIC_U")
                if (musicU.isNullOrBlank()) {
                    // 803 但没拿到 MUSIC_U：多半是接口形态变了。
                    // 重试没有意义（用户已经确认过），如实报出来
                    return@withContext PollResult.Failed("登录响应缺少凭证，请稍后再试")
                }
                val csrf = NeteaseHttp.cookieValue(response.setCookies, "__csrf").orEmpty()
                val full = finalizeCookieLogin(context, musicU, csrf, fallbackDraft = true)!!
                PollResult.Confirmed(full)
            }
            8821 -> PollResult.Failed("账号存在安全风险，需要在手机上验证后再扫码")
            else -> {
                Log.w(TAG, "扫码轮询未知 code=$code：${body.take(200)}")
                PollResult.Failed("登录失败（code=$code）")
            }
        }
    }

    // ===== 手机验证码 / 密码登录 =====

    sealed interface SmsSendResult {
        data object Sent : SmsSendResult
        data class Failed(val message: String) : SmsSendResult
    }

    sealed interface LoginResult {
        data class Success(val account: NeteaseAccount) : LoginResult
        data class Failed(val message: String) : LoginResult
    }

    /** 发送手机登录验证码。国内手机号自动走 +86 */
    suspend fun sendSmsCaptcha(phone: String, ctcode: String = "86"): SmsSendResult =
        withContext(Dispatchers.IO) {
            val cleaned = phone.trim()
            if (cleaned.length < 6 || cleaned.any { !it.isDigit() }) {
                return@withContext SmsSendResult.Failed("请输入正确的手机号")
            }
            val code = ctcode.trim().ifBlank { "86" }
            val path = "/api/sms/captcha/sent?cellphone=${java.net.URLEncoder.encode(cleaned, "UTF-8")}" +
                "&ctcode=${java.net.URLEncoder.encode(code, "UTF-8")}"
            val res = NeteaseHttp.get(path)
            val raw = res.body ?: res.errorBody
            parseSmsSendResponse(raw)
        }

    /** 手机号 + 短信验证码登录 */
    suspend fun loginWithPhoneCaptcha(
        context: Context,
        phone: String,
        captcha: String,
        ctcode: String = "86"
    ): LoginResult = withContext(Dispatchers.IO) {
        val cleanedPhone = phone.trim()
        val cleanedCode = captcha.trim()
        if (cleanedPhone.length < 6 || cleanedPhone.any { !it.isDigit() }) {
            return@withContext LoginResult.Failed("请输入正确的手机号")
        }
        if (cleanedCode.length < 4 || cleanedCode.any { !it.isDigit() }) {
            return@withContext LoginResult.Failed("请输入收到的短信验证码")
        }
        val res = NeteaseHttp.postForm(
            "/api/login/cellphone",
            mapOf(
                "phone" to cleanedPhone,
                "countrycode" to ctcode.trim().ifBlank { "86" },
                "captcha" to cleanedCode,
                "rememberLogin" to "true"
            )
        )
        completeCellphoneLogin(context, res)
    }

    /** 手机号 + 密码登录（密码按网易云约定做 MD5 后提交，本地不落盘） */
    suspend fun loginWithPassword(
        context: Context,
        phone: String,
        password: String,
        ctcode: String = "86"
    ): LoginResult = withContext(Dispatchers.IO) {
        val cleanedPhone = phone.trim()
        if (cleanedPhone.length < 6 || cleanedPhone.any { !it.isDigit() }) {
            return@withContext LoginResult.Failed("请输入正确的手机号")
        }
        if (password.isBlank()) {
            return@withContext LoginResult.Failed("请输入登录密码")
        }
        val res = NeteaseHttp.postForm(
            "/api/login/cellphone",
            mapOf(
                "phone" to cleanedPhone,
                "countrycode" to ctcode.trim().ifBlank { "86" },
                "password" to md5Hex(password),
                "rememberLogin" to "true"
            )
        )
        completeCellphoneLogin(context, res)
    }

    private suspend fun completeCellphoneLogin(
        context: Context,
        res: NeteaseHttp.Response
    ): LoginResult {
        val parsed = parseCellphoneLoginPayload(res.body ?: res.errorBody, res.setCookies)
        if (parsed.errorMessage != null) return LoginResult.Failed(parsed.errorMessage)
        val musicU = parsed.musicU ?: return LoginResult.Failed("未拿到登录凭证，请改用扫码或 Cookie 登录")
        val full = finalizeCookieLogin(
            context = context,
            musicU = musicU,
            csrf = parsed.csrf,
            fallbackDraft = false,
            seedUserId = parsed.userId,
            seedNickname = parsed.nickname,
            seedAvatarUrl = parsed.avatarUrl
        ) ?: return LoginResult.Failed("登录态校验失败，请重试或改用扫码登录")
        return LoginResult.Success(full)
    }

    internal data class ParsedLoginPayload(
        val musicU: String? = null,
        val csrf: String = "",
        val userId: Long = 0L,
        val nickname: String = "",
        val avatarUrl: String = "",
        val errorMessage: String? = null
    )

    internal fun parseSmsSendResponse(raw: String?): SmsSendResult {
        if (raw.isNullOrBlank()) return SmsSendResult.Failed("网络不通或请求被拦截，稍后再试")
        val json = runCatching { org.json.JSONObject(raw) }.getOrNull()
            ?: return SmsSendResult.Failed("发送失败，响应异常")
        val code = json.optInt("code", -1)
        if (code == 200) return SmsSendResult.Sent
        val msg = json.optString("message").ifBlank { json.optString("msg") }
        val friendly = when (code) {
            400 -> "手机号格式有误或未支持该号段"
            405 -> "发送太频繁，请稍后再试"
            462, 8821 -> "触发安全验证，建议改用「扫码登录」"
            else -> msg.ifBlank { "发送失败（code=$code）" }
        }
        return SmsSendResult.Failed(friendly)
    }

    internal fun parseCellphoneLoginPayload(
        raw: String?,
        setCookies: List<String>
    ): ParsedLoginPayload {
        if (raw.isNullOrBlank()) {
            return ParsedLoginPayload(errorMessage = "网络不通，稍后再试")
        }
        val json = runCatching { org.json.JSONObject(raw) }.getOrNull()
            ?: return ParsedLoginPayload(errorMessage = "登录响应无法解析")
        val code = json.optInt("code", -1)
        if (code != 200) {
            val serverMsg = json.optString("message").ifBlank { json.optString("msg") }
            val friendly = when (code) {
                400 -> "手机号或登录方式不被接口接受，可切换扫码登录"
                501 -> "账号不存在"
                502 -> "手机号或密码错误"
                503 -> "验证码错误或已失效"
                509 -> "尝试次数过多，请稍后再试或使用扫码登录"
                462, 8821 -> "账号需要安全校验，请使用网易云 App 扫码或 Cookie 登录"
                -460 -> "当前网络环境触发网易云校验，建议直接「扫码登录」"
                else -> serverMsg.ifBlank { "登录失败（code=$code）" }
            }
            return ParsedLoginPayload(errorMessage = friendly)
        }
        val rawCookie = json.optString("cookie")
        val musicU = NeteaseHttp.cookieValue(setCookies, "MUSIC_U")
            ?: extractCookieValue(rawCookie, "MUSIC_U")
        if (musicU.isNullOrBlank()) {
            return ParsedLoginPayload(errorMessage = "响应未下发会话凭证，请改用扫码登录")
        }
        val csrf = NeteaseHttp.cookieValue(setCookies, "__csrf")
            ?: extractCookieValue(rawCookie, "__csrf").orEmpty()
        val accObj = json.optJSONObject("account")
        val profileObj = json.optJSONObject("profile")
        val userId = accObj?.optLong("id")?.takeIf { it > 0L }
            ?: profileObj?.optLong("userId") ?: 0L
        return ParsedLoginPayload(
            musicU = musicU,
            csrf = csrf,
            userId = userId,
            nickname = profileObj?.optString("nickname").orEmpty(),
            avatarUrl = profileObj?.optString("avatarUrl").orEmpty()
        )
    }

    internal fun md5Hex(text: String): String {
        val bytes = java.security.MessageDigest.getInstance("MD5")
            .digest(text.toByteArray(Charsets.UTF_8))
        return buildString(bytes.size * 2) {
            for (b in bytes) {
                append(((b.toInt() ushr 4) and 0xF).toString(16))
                append((b.toInt() and 0xF).toString(16))
            }
        }
    }

    /**
     * Cookie 兜底登录。
     *
     * 扫码偶尔会被风控拦（8821），或者用户已经在浏览器登录过、只想把凭证搬过来。
     * 这里接受三种输入形态：整段 Cookie、`MUSIC_U=xxx` 这样的片段、或只贴值本身。
     *
     * @return 校验通过的账号；Cookie 无效返回 null
     */
    suspend fun loginWithCookie(context: Context, raw: String): NeteaseAccount? =
        withContext(Dispatchers.IO) {
            val musicU = extractCookieValue(raw, "MUSIC_U") ?: raw.trim()
                .takeIf { it.isNotEmpty() && !it.contains('=') && !it.contains(';') }
                ?: return@withContext null
            if (musicU.length < 10) return@withContext null

            finalizeCookieLogin(
                context = context,
                musicU = musicU,
                csrf = extractCookieValue(raw, "__csrf").orEmpty(),
                fallbackDraft = false
            )
        }

    private suspend fun finalizeCookieLogin(
        context: Context,
        musicU: String,
        csrf: String,
        fallbackDraft: Boolean,
        seedUserId: Long = 0L,
        seedNickname: String = "",
        seedAvatarUrl: String = ""
    ): NeteaseAccount? {
        val draft = NeteaseAccount(
            musicU = musicU,
            csrf = csrf,
            userId = seedUserId,
            nickname = seedNickname,
            avatarUrl = seedAvatarUrl,
            vipType = 0,
            savedAtMs = System.currentTimeMillis()
        )
        // 再用 Cookie 问一次账号详情 —— 唯一权威的 vipType / profile 来源
        val full = fetchAccount(draft) ?: if (fallbackDraft) draft else return null
        account = full
        NeteaseAccountStore.save(context, full)
        return full
    }

    private fun extractCookieValue(raw: String, name: String): String? {
        val idx = raw.indexOf("$name=", ignoreCase = true)
        if (idx < 0) return null
        val rest = raw.substring(idx + name.length + 1)
        val value = rest.substringBefore(';').substringBefore(' ').trim().trim('"')
        return value.takeIf { it.isNotEmpty() }
    }

    // ===== 账号信息 =====

    /**
     * 用给定凭据问一次账号信息，补全昵称 / 头像 / 会员态。
     *
     * `profile == null` 表示 Cookie 无效（过期或被踢）→ 返回 null，调用方切回未登录。
     */
    suspend fun fetchAccount(base: NeteaseAccount): NeteaseAccount? = withContext(Dispatchers.IO) {
        val json = NeteaseHttp.getJson("/api/nuser/account/get", base.cookie) ?: return@withContext null
        if (json.optInt("code", -1) != 200) return@withContext null
        val acc = json.optJSONObject("account") ?: return@withContext null
        val profile = json.optJSONObject("profile")
        base.copy(
            userId = acc.optLong("id").takeIf { it > 0L }
                ?: profile?.optLong("userId") ?: base.userId,
            nickname = profile?.optString("nickname").orEmpty().ifBlank { base.nickname },
            avatarUrl = profile?.optString("avatarUrl").orEmpty().ifBlank { base.avatarUrl },
            // 11 = 黑胶 VIP。部分账号是 10（音乐包），也视作会员
            vipType = acc.optInt("vipType", base.vipType)
                .let { if (it == 0) profile?.optInt("vipType", 0) ?: 0 else it },
            savedAtMs = base.savedAtMs
        )
    }

    /**
     * 校验并刷新会员态。
     *
     * 只在"第一次在线操作 / 播放会员歌之前"调一次，结果缓存 10 分钟 ——
     * 每次搜索都问一遍既慢又没必要。失败（断网）时**保留原值**，
     * 不能因为一次网络抖动就把用户踢下线。
     */
    private const val REFRESH_INTERVAL_MS = 10 * 60 * 1000L
    @Volatile private var lastRefreshAtMs = 0L

    suspend fun refreshIfStale(context: Context): NeteaseAccount? {
        val current = ensureLoaded(context) ?: return null
        val now = System.currentTimeMillis()
        if (now - lastRefreshAtMs < REFRESH_INTERVAL_MS) return current
        lastRefreshAtMs = now

        val fresh = fetchAccount(current) ?: return current
        if (fresh != current) {
            account = fresh
            NeteaseAccountStore.save(context, fresh)
        }
        return fresh
    }
}
