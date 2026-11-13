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
                val draft = NeteaseAccount(
                    musicU = musicU,
                    csrf = csrf,
                    userId = 0L,
                    nickname = "",
                    avatarUrl = "",
                    vipType = 0,
                    savedAtMs = System.currentTimeMillis()
                )
                // 803 的体里字段各版本不一致（有的带 profile，有的只带 userId），
                // 所以再用 Cookie 问一次账号信息 —— 那是唯一权威来源，顺带能拿到会员态
                val full = fetchAccount(draft) ?: draft
                account = full
                NeteaseAccountStore.save(context, full)
                PollResult.Confirmed(full)
            }
            8821 -> PollResult.Failed("账号存在安全风险，需要在手机上验证后再扫码")
            else -> {
                Log.w(TAG, "扫码轮询未知 code=$code：${body.take(200)}")
                PollResult.Failed("登录失败（code=$code）")
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

            val draft = NeteaseAccount(
                musicU = musicU,
                csrf = extractCookieValue(raw, "__csrf").orEmpty(),
                userId = 0L,
                nickname = "",
                avatarUrl = "",
                vipType = 0,
                savedAtMs = System.currentTimeMillis()
            )
            // 必须问一次账号信息：既验证 Cookie 有效，也拿到昵称和会员态
            val full = fetchAccount(draft) ?: return@withContext null
            account = full
            NeteaseAccountStore.save(context, full)
            full
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
