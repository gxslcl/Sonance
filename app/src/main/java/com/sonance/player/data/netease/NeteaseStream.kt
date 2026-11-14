package com.sonance.player.data.netease

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 音质档位。
 *
 * [apiValue] 直接就是接口的 `level` 参数，别改 —— 服务端只认这几个字符串。
 * [shortLabel] 是给 UI 用的**实际**档位标签：显示的是接口返回的 `level`，
 * 不是用户请求的档位（请求无损却只给 320k 时，标"无损"就是自欺）。
 */
enum class NeteaseLevel(
    val apiValue: String,
    val label: String,
    val shortLabel: String,
    val needsVip: Boolean
) {
    STANDARD("standard", "标准", "128k", false),
    HIGHER("higher", "较高", "192k", false),
    EXHIGH("exhigh", "极高", "320k", false),
    LOSSLESS("lossless", "无损", "无损", true),
    HIRES("hires", "Hi-Res", "Hi-Res", true);

    /** 请求时带哪个容器。免费用 mp3，无损两档用 flac */
    val encodeType: String get() = if (this == LOSSLESS || this == HIRES) "flac" else "mp3"

    companion object {
        /** 解析接口返回的 `level`；认不出来就当标准 */
        fun of(value: String?): NeteaseLevel =
            entries.firstOrNull { it.apiValue.equals(value, ignoreCase = true) } ?: STANDARD

        fun fromId(id: String?): NeteaseLevel =
            entries.firstOrNull { it.apiValue == id } ?: EXHIGH

        /** 未登录 / 非会员能选的档位 */
        val freeTier: List<NeteaseLevel> = listOf(STANDARD, HIGHER, EXHIGH)
    }
}

/** 一次取流的实际结果 */
data class NeteaseStreamInfo(
    val url: String,
    /** 接口**返回**的档位（可能低于请求档） */
    val level: NeteaseLevel,
    val br: Int,
    /** 容器：mp3 / flac */
    val type: String,
    /** 只给了试听片段（会员权益没生效） */
    val trial: Boolean
) {
    val isFlac: Boolean get() = type.equals("flac", ignoreCase = true) || type.equals("flac24bit", true)
}

/**
 * 取流：缓存 + 降级。
 *
 * ## 为什么不能再用 `song/media/outer/url`
 *
 * 那条外链的 302 目标 `cdntag` 解码出来是 `mark=os_web,quality_standard` ——
 * **恒定 128k，且会员歌直接 302 到下载页**。换成 `/api/song/enhance/player/url/v1`
 * 之后：免费歌匿名就是 320k，登录会员还能拿无损。
 *
 * ## 缓存规则
 *
 * 地址带签名会过期，接口自己报了 `expi`（实测 1200 秒）。缓存到期时间取
 * `expi - 60 秒`，留一分钟余量给"刚好在过期边缘开始播"的情况。
 * **只缓存在内存**：写磁盘等于存了一堆必然失效的 URL，还会把账号签名留在本地。
 *
 * ## 降级链
 *
 * 请求无损但账号不够档时，服务端不一定报错 —— 常见行为是**默默给低一档**。
 * 所以判断依据永远是响应里的 `level`，不是请求参数；真的给不出地址时才往下试，
 * 最多 3 次（请求档 → 320k → 标准），全失败才给提示。
 */
object NeteaseStream {

    private const val TAG = "SonanceNetease"

    /** 缓存里再留一分钟余量，避免"刚好在过期边缘"取到死链 */
    private const val EXPIRE_SAFETY_MS = 60_000L

    /** 接口没报 expi 时的兜底寿命 */
    private const val DEFAULT_TTL_MS = 15 * 60 * 1000L

    private class Entry(val info: NeteaseStreamInfo, val expireAtMs: Long)

    private val cache = ConcurrentHashMap<String, Entry>()

    /** 每首歌最后一次失败的原因，给 UI 直接拿去提示 */
    private val errors = ConcurrentHashMap<String, String>()

    /**
     * 取流串行化。
     *
     * 点播会先解析当前这首，紧接着后台预取又会遍历到同一批 id；
     * 没有这把锁的话同一首歌可能同时发两次请求。锁内先查缓存，后到的直接命中。
     */
    private val resolveLock = Mutex()

    /** 上次取到的实际档位（给播放页显示） */
    fun qualityOf(remoteId: String): NeteaseLevel? = cache[remoteId]?.info?.level

    /** 上次取到的容器（mp3 / flac） */
    fun formatOf(remoteId: String): String? = cache[remoteId]?.info?.type

    /** 是否只拿到了试听片段（会员权益没生效） */
    fun isTrial(remoteId: String): Boolean = cache[remoteId]?.info?.trial == true

    /** 缓存里当前可用的地址（供同步的 [com.sonance.player.data.OnlineSource.streamUrl] 读取） */
    fun cachedUrl(remoteId: String): String? = cached(remoteId)?.url

    /**
     * 清空地址缓存。
     *
     * 用户改音质档位、或重新登录后调用：不清的话要等 20 分钟自然过期才生效，
     * 用户会觉得"设置没起作用"。清缓存只是一次额外的取流请求，代价很小。
     */
    fun clearCache() {
        cache.clear()
        errors.clear()
    }

    fun failureOf(remoteId: String): String? = errors[remoteId]

    /** 成功取过流就清掉失败记录，否则提示会一直挂着 */
    private fun clearError(id: String) {
        errors.remove(id)
    }

    /**
     * 取一首歌的播放地址。
     *
     * @param level 用户设置的目标档位；登录态不够档会自动降到能给的最高档
     * @param expectedDurationMs 曲目已知总时长（用于识别未填 `freeTrialInfo` 却截断为 30s 的试听流）
     * @return 成功返回实际地址与档位；失败返回 null（原因用 [failureOf] 取）
     */
    suspend fun resolve(
        remoteId: String,
        level: NeteaseLevel,
        expectedDurationMs: Long = 0L
    ): NeteaseStreamInfo? =
        withContext(Dispatchers.IO) {
            val id = remoteId.trim()
            if (id.isEmpty()) return@withContext null

            val account = NeteaseAuth.account
            cached(id, account)?.let { return@withContext it }

            resolveLock.withLock {
                cached(id, account)?.let { return@withLock it }

                // 非会员把无损/HR 直接跳过 —— 少一次必然失败的请求
                val start = if (level.needsVip && (account == null || !account.isVip)) {
                    NeteaseLevel.EXHIGH
                } else {
                    level
                }

                val selection = selectBestAttempt(chain(start), account?.isVip == true) { lv ->
                    requestWithVipFallback(id, lv, account, expectedDurationMs)
                }
                when (selection) {
                    is Selection.Found -> {
                        if (!selection.attempt.info.trial || account?.isVip != true) {
                            cacheAttempt(id, selection.attempt)
                        } else {
                            // 仅极短缓存试听标志让 UI 正确标示"试听"而非误标"Hi-Res"，
                            // 下次 resolve 时 VIP 账号会自动跳过该试听缓存并重试。
                            cache[id] = Entry(selection.attempt.info, System.currentTimeMillis() + 10_000L)
                            Log.w(TAG, "会员账号仍只返回试听片段 id=$id level=${selection.attempt.info.level.apiValue}")
                        }
                        clearError(id)
                        return@withLock selection.attempt.info
                    }
                    is Selection.Denied -> {
                        errors[id] = selection.message
                        Log.i(TAG, "取流被拒 id=$id ${selection.message}")
                        null
                    }
                    is Selection.Failed -> {
                        errors[id] = messageForCode(selection.lastCode, account)
                        Log.i(TAG, "取流失败 id=$id code=${selection.lastCode}")
                        null
                    }
                }
            }
        }

    private fun cacheAttempt(id: String, attempt: Attempt.Ok) {
        val ttl = (attempt.expi * 1000L - EXPIRE_SAFETY_MS)
            .coerceAtLeast(EXPIRE_SAFETY_MS)
        cache[id] = Entry(attempt.info, System.currentTimeMillis() + ttl)
    }

    private fun cached(id: String, account: NeteaseAccount? = NeteaseAuth.account): NeteaseStreamInfo? {
        val entry = cache[id] ?: return null
        if (entry.expireAtMs - System.currentTimeMillis() <= 30_000L) return null
        if (entry.info.trial && account?.isVip == true) {
            cache.remove(id)
            return null
        }
        return entry.info
    }

    private fun chain(start: NeteaseLevel): List<NeteaseLevel> = when (start) {
        NeteaseLevel.HIRES -> listOf(NeteaseLevel.HIRES, NeteaseLevel.LOSSLESS, NeteaseLevel.EXHIGH, NeteaseLevel.STANDARD)
        NeteaseLevel.LOSSLESS -> listOf(NeteaseLevel.LOSSLESS, NeteaseLevel.EXHIGH, NeteaseLevel.STANDARD)
        NeteaseLevel.EXHIGH -> listOf(NeteaseLevel.EXHIGH, NeteaseLevel.STANDARD)
        NeteaseLevel.HIGHER -> listOf(NeteaseLevel.HIGHER, NeteaseLevel.STANDARD)
        NeteaseLevel.STANDARD -> listOf(NeteaseLevel.STANDARD)
    }

    internal sealed interface Attempt {
        class Ok(val info: NeteaseStreamInfo, val expi: Long) : Attempt

        /** 这首歌对这个账号就是没有可播地址（下架 / 付费专辑 / 未登录） */
        class Denied(val message: String) : Attempt

        /** 这一档没给出来，但降一档可能行 */
        class Retry(val code: Int) : Attempt
    }

    private fun requestWithVipFallback(
        remoteId: String,
        level: NeteaseLevel,
        account: NeteaseAccount?,
        expectedDurationMs: Long
    ): Attempt {
        // 1. 优先官方 EAPI（PC 客户端签名）—— 实测高音质与 VIP 曲目需走 EAPI 才稳定下发完整流
        val eapiPc = requestV1Eapi(
            remoteId = remoteId,
            level = level,
            account = account,
            os = "pc",
            appver = "3.0.18.203097",
            osver = "10",
            userAgent = NeteaseHttp.PC_UA,
            expectedDurationMs = expectedDurationMs
        )
        if (eapiPc is Attempt.Ok && (!eapiPc.info.trial || account?.isVip != true)) {
            return eapiPc
        }
        if (eapiPc is Attempt.Denied) return eapiPc

        // 2. 若 PC EAPI 未拿到完整流（例如受限时试听或 -110），切换 Android EAPI 再试
        val eapiAndroid = requestV1Eapi(
            remoteId = remoteId,
            level = level,
            account = account,
            os = "android",
            appver = "8.10.05",
            osver = "13",
            userAgent = NeteaseHttp.ANDROID_UA,
            expectedDurationMs = expectedDurationMs
        )
        if (eapiAndroid is Attempt.Ok && (!eapiAndroid.info.trial || account?.isVip != true)) {
            return eapiAndroid
        }
        if (eapiAndroid is Attempt.Denied) return eapiAndroid

        // 3. EAPI 均只返回试听或异常时，回落到兼容 /api 入口
        val getAttempt = requestV1Get(remoteId, level, account, expectedDurationMs)
        if (getAttempt is Attempt.Ok && (!getAttempt.info.trial || account?.isVip != true)) {
            return getAttempt
        }
        val postAttempt = requestV1Post(remoteId, level, account, expectedDurationMs)
        if (postAttempt is Attempt.Ok && !postAttempt.info.trial) {
            return postAttempt
        }
        val legacyAttempt = requestLegacyUrl(remoteId, level, account, expectedDurationMs)
        if (legacyAttempt is Attempt.Ok && !legacyAttempt.info.trial) {
            return legacyAttempt
        }
        return (eapiAndroid as? Attempt.Ok)
            ?: (eapiPc as? Attempt.Ok)
            ?: (getAttempt as? Attempt.Ok)
            ?: eapiAndroid
    }

    private fun requestV1Eapi(
        remoteId: String,
        level: NeteaseLevel,
        account: NeteaseAccount?,
        os: String,
        appver: String,
        osver: String,
        userAgent: String,
        expectedDurationMs: Long
    ): Attempt {
        val headerObj = JSONObject().apply {
            put("os", os)
            put("appver", appver)
            put("osver", osver)
            put("channel", "netease")
            put("requestId", "${System.currentTimeMillis()}_0001")
            if (!account?.musicU.isNullOrBlank()) put("MUSIC_U", account!!.musicU)
            if (!account?.csrf.isNullOrBlank()) put("__csrf", account!!.csrf)
        }
        val payload = JSONObject().apply {
            put("ids", "[$remoteId]")
            put("level", level.apiValue)
            put("encodeType", level.encodeType)
            put("header", headerObj.toString())
        }
        val cookie = buildString {
            append("os=").append(os).append("; appver=").append(appver)
            if (!account?.musicU.isNullOrBlank()) append("; MUSIC_U=").append(account!!.musicU)
            if (!account?.csrf.isNullOrBlank()) append("; __csrf=").append(account!!.csrf)
        }
        val body = NeteaseHttp.postEapi(
            apiPath = "/api/song/enhance/player/url/v1",
            payload = payload,
            cookie = cookie,
            userAgent = userAgent
        ).body
        val attempt = parsePlayerUrlResponse(body, expectedDurationMs)
        if (attempt is Attempt.Ok) {
            val pathTag = attempt.info.url.substringAfter(".net/", "").take(24)
            Log.i(
                TAG,
                "取流[$os] id=$remoteId req=${level.apiValue} got=${attempt.info.level.apiValue} trial=${attempt.info.trial} br=${attempt.info.br} exp=${expectedDurationMs}ms path=$pathTag"
            )
        }
        return attempt
    }

    /**
     * 请求一档（GET v1）。
     */
    private fun requestV1Get(
        remoteId: String,
        level: NeteaseLevel,
        account: NeteaseAccount?,
        expectedDurationMs: Long
    ): Attempt {
        val path = "/api/song/enhance/player/url/v1" +
            "?ids=%5B$remoteId%5D&level=${level.apiValue}&encodeType=${level.encodeType}"
        return parsePlayerUrlResponse(NeteaseHttp.get(path, account?.cookie).body, expectedDurationMs)
    }

    /** 请求一档（POST v1 form） */
    private fun requestV1Post(
        remoteId: String,
        level: NeteaseLevel,
        account: NeteaseAccount?,
        expectedDurationMs: Long
    ): Attempt {
        val res = NeteaseHttp.postForm(
            "/api/song/enhance/player/url/v1",
            mapOf(
                "ids" to "[$remoteId]",
                "level" to level.apiValue,
                "encodeType" to level.encodeType
            ),
            account?.cookie
        )
        return parsePlayerUrlResponse(res.body, expectedDurationMs)
    }

    /** 兼容旧版取流接口（按 br 取 320k/无损） */
    private fun requestLegacyUrl(
        remoteId: String,
        level: NeteaseLevel,
        account: NeteaseAccount?,
        expectedDurationMs: Long
    ): Attempt {
        val br = when (level) {
            NeteaseLevel.HIRES, NeteaseLevel.LOSSLESS -> 999000
            NeteaseLevel.EXHIGH -> 320000
            NeteaseLevel.HIGHER -> 192000
            NeteaseLevel.STANDARD -> 128000
        }
        val path = "/api/song/enhance/player/url?ids=%5B$remoteId%5D&br=$br"
        return parsePlayerUrlResponse(NeteaseHttp.get(path, account?.cookie).body, expectedDurationMs)
    }

    internal fun parsePlayerUrlResponse(body: String?, expectedDurationMs: Long = 0L): Attempt {
        if (body == null) return Attempt.Retry(-1)
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return Attempt.Retry(-1)
        val topCode = json.optInt("code", -1)
        val item = json.optJSONArray("data")?.optJSONObject(0)
            ?: return Attempt.Retry(topCode)

        val url = item.optString("url").orEmpty()
        val fee = item.optInt("fee", 0)
        val code = item.optInt("code", topCode)

        if (url.isBlank()) {
            // 需单独购买的数字专辑：会员也不包含，降档重试没有意义
            if (fee == 4) return Attempt.Denied("这首歌需要单独购买数字专辑，会员也不包含")
            return when (code) {
                // 404 分两种情况：这一档给不出来（降档可能行），或这首歌本身没有版权。
                // 先按"降档"处理，链走完还不行时 [messageForCode] 会给出准确的说明
                404 -> Attempt.Retry(code)
                301, 401, 403 -> Attempt.Denied("登录已失效，重新扫码登录即可")
                460, 429 -> Attempt.Denied("请求过于频繁，稍后再试")
                else -> Attempt.Retry(code)
            }
        }

        val hasTrialInfo = item.opt("freeTrialInfo") != null && item.opt("freeTrialInfo") != JSONObject.NULL
        val isTrialCdnPath = url.contains("jd-musicrep-ts", ignoreCase = true)
        val returnedTimeMs = item.optLong("time", 0L)
        val br = item.optInt("br", 0)
        val sizeBytes = item.optLong("size", 0L)
        val estimatedMs = if (br > 0 && sizeBytes > 0L) (sizeBytes * 8_000L) / br else 0L
        val knownFullMs = maxOf(expectedDurationMs, returnedTimeMs)
        // 部分接口场景（尤其 Hi-Res/日推曲目）会返回 null 的 freeTrialInfo 且 time 为 0 或整曲时长，
        // 但实际下发的 URL 或文件体积（size * 8 / br）只有约 30 秒试听片段；多信号联合判别。
        val timeIsTrial = returnedTimeMs in 1L..45_000L && (
            expectedDurationMs >= 65_000L ||
                (expectedDurationMs <= 0L && returnedTimeMs in 28_000L..32_500L)
            )
        val sizeIsTrial = estimatedMs in 1L..45_000L && (
            knownFullMs >= 65_000L ||
                (knownFullMs <= 0L && estimatedMs in 25_000L..35_000L)
            )
        val ftp = item.optJSONObject("freeTrialPrivilege")
        val privilegeDenied = ftp != null &&
            ftp.optBoolean("resConsumable", false) &&
            ftp.has("userConsumable") &&
            !ftp.optBoolean("userConsumable", true)
        val sizeLooksFull = knownFullMs > 0L && estimatedMs >= (knownFullMs * 7L) / 10L
        val privilegeTrial = privilegeDenied && !sizeLooksFull
        val info = NeteaseStreamInfo(
            url = url,
            level = NeteaseLevel.of(item.optString("level")),
            br = br,
            type = item.optString("type").ifBlank { "mp3" },
            trial = hasTrialInfo || isTrialCdnPath || timeIsTrial || sizeIsTrial || privilegeTrial
        )
        val expi = item.optLong("expi", DEFAULT_TTL_MS / 1000)
        return Attempt.Ok(info, if (expi > 0) expi else DEFAULT_TTL_MS / 1000)
    }

    internal sealed interface Selection {
        class Found(val attempt: Attempt.Ok) : Selection
        class Denied(val message: String) : Selection
        class Failed(val lastCode: Int) : Selection
    }

    /** 从多档尝试里选出最优流：对 VIP 优先完整流，非完整流仅作最后兜底 */
    internal fun selectBestAttempt(
        levels: List<NeteaseLevel>,
        isVip: Boolean,
        fetcher: (NeteaseLevel) -> Attempt
    ): Selection {
        var lastCode = -1
        var bestTrial: Attempt.Ok? = null
        for (lv in levels) {
            when (val attempt = fetcher(lv)) {
                is Attempt.Ok -> {
                    if (!attempt.info.trial || !isVip) return Selection.Found(attempt)
                    if (bestTrial == null) bestTrial = attempt
                }
                is Attempt.Denied -> return Selection.Denied(attempt.message)
                is Attempt.Retry -> lastCode = attempt.code
            }
        }
        return if (bestTrial != null) Selection.Found(bestTrial) else Selection.Failed(lastCode)
    }

    private fun messageForCode(code: Int, account: NeteaseAccount?): String = when (code) {
        460, 429 -> "请求过于频繁，稍后再试"
        301, 401, 403 -> "登录已失效，重新扫码登录即可"
        -1 -> "网络不通，取不到播放地址"
        // 404 走到这里说明降档链已走完，这首歌对这个账号确实没有可播地址。
        // 具体是"没登录""没会员"还是"下架"，按账号状态下结论，别一律说成下架
        404 -> when {
            account == null -> "这首歌属于会员曲库，登录网易云后才能播放"
            !account.isVip -> "这首歌需要网易云会员，当前账号没有该权益"
            else -> "网易云没有提供这首歌的播放地址（可能已下架，或需要单独购买）"
        }
        else -> "暂时取不到播放地址（code=$code）"
    }
}

/**
 * 用户选的播放音质。
 *
 * 为什么用全局变量而不是参数：`OnlineSource.resolveStream(remoteId)` 的签名里
 * 没有 Context，也不该为了一个档位把 Context 塞进数据层。这个值本身是"进程级偏好"，
 * 由 ViewModel 在启动时和用户改设置时写进来（见 `SonanceViewModel.changeOnlineQuality`）。
 *
 * 之前定过的交互：**设置里选默认档，播放页只显示实际拿到的档位**，
 * 不做"每首临时选一次"。所以这里只需要一个当前值。
 */
object NeteaseQuality {
    @Volatile
    var current: NeteaseLevel = NeteaseLevel.EXHIGH
}
