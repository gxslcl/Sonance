package com.sonance.player.data.netease

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
     * @return 成功返回实际地址与档位；失败返回 null（原因用 [failureOf] 取）
     */
    suspend fun resolve(remoteId: String, level: NeteaseLevel): NeteaseStreamInfo? {
        val id = remoteId.trim()
        if (id.isEmpty()) return null

        cached(id)?.let { return it }

        return resolveLock.withLock {
            cached(id)?.let { return@withLock it }

            val account = NeteaseAuth.account
            // 非会员把无损/HR 直接跳过 —— 少一次必然失败的请求
            val start = if (level.needsVip && (account == null || !account.isVip)) {
                NeteaseLevel.EXHIGH
            } else {
                level
            }

            var lastCode = -1
            for (lv in chain(start)) {
                val attempt = request(id, lv, account)
                when (attempt) {
                    is Attempt.Ok -> {
                        val ttl = (attempt.expi * 1000L - EXPIRE_SAFETY_MS)
                            .coerceAtLeast(EXPIRE_SAFETY_MS)
                        cache[id] = Entry(attempt.info, System.currentTimeMillis() + ttl)
                        clearError(id)
                        return@withLock attempt.info
                    }
                    is Attempt.Denied -> {
                        // 版权下架 / 需单独购买这类，降档再试也没用
                        errors[id] = attempt.message
                        Log.i(TAG, "取流被拒 id=$id ${attempt.message}")
                        return@withLock null
                    }
                    is Attempt.Retry -> {
                        lastCode = attempt.code
                    }
                }
            }
            errors[id] = messageForCode(lastCode, account)
            Log.i(TAG, "取流失败 id=$id code=$lastCode")
            null
        }
    }

    private fun cached(id: String): NeteaseStreamInfo? {
        val entry = cache[id] ?: return null
        if (entry.expireAtMs - System.currentTimeMillis() <= 30_000L) return null
        return entry.info
    }

    private fun chain(start: NeteaseLevel): List<NeteaseLevel> = when (start) {
        NeteaseLevel.HIRES -> listOf(NeteaseLevel.HIRES, NeteaseLevel.LOSSLESS, NeteaseLevel.EXHIGH, NeteaseLevel.STANDARD)
        NeteaseLevel.LOSSLESS -> listOf(NeteaseLevel.LOSSLESS, NeteaseLevel.EXHIGH, NeteaseLevel.STANDARD)
        NeteaseLevel.EXHIGH -> listOf(NeteaseLevel.EXHIGH, NeteaseLevel.STANDARD)
        NeteaseLevel.HIGHER -> listOf(NeteaseLevel.HIGHER, NeteaseLevel.STANDARD)
        NeteaseLevel.STANDARD -> listOf(NeteaseLevel.STANDARD)
    }.take(3)

    private sealed interface Attempt {
        class Ok(val info: NeteaseStreamInfo, val expi: Long) : Attempt

        /** 这首歌对这个账号就是没有可播地址（下架 / 付费专辑 / 未登录） */
        class Denied(val message: String) : Attempt

        /** 这一档没给出来，但降一档可能行 */
        class Retry(val code: Int) : Attempt
    }

    /**
     * 请求一档。
     *
     * 用 `/api/song/enhance/player/url/v1`：实测 GET 免签名可用，
     * 参数 `level` + `encodeType` 即可，不需要 weapi 那套 AES/RSA。
     */
    private fun request(remoteId: String, level: NeteaseLevel, account: NeteaseAccount?): Attempt {
        val path = "/api/song/enhance/player/url/v1" +
            "?ids=%5B$remoteId%5D&level=${level.apiValue}&encodeType=${level.encodeType}"
        val body = NeteaseHttp.get(path, account?.cookie).body ?: return Attempt.Retry(-1)

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

        val info = NeteaseStreamInfo(
            url = url,
            level = NeteaseLevel.of(item.optString("level")),
            br = item.optInt("br", 0),
            type = item.optString("type").ifBlank { "mp3" },
            // 非 null 表示只给了试听片段（会员权益没生效）
            trial = item.opt("freeTrialInfo") != null && item.opt("freeTrialInfo") != JSONObject.NULL
        )
        val expi = item.optLong("expi", DEFAULT_TTL_MS / 1000)
        return Attempt.Ok(info, if (expi > 0) expi else DEFAULT_TTL_MS / 1000)
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
