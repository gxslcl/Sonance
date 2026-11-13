package com.sonance.player.data.netease

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

/**
 * 已登录的网易云账号。
 *
 * 只保存"能代表这个账号说话"的最小凭据（[musicU]）以及展示用的资料。
 * **不保存密码** —— 登录走扫码，账号密码从来不经手本应用。
 *
 * [csrf] 是 `__csrf`，写操作（歌单、收藏）才需要；当前只读接口可以不带，
 * 但既然登录响应里就有，一并存下来，后面做歌单收藏时不用改存储格式。
 */
data class NeteaseAccount(
    val musicU: String,
    val csrf: String,
    val userId: Long,
    val nickname: String,
    val avatarUrl: String,
    /** 11 = 黑胶 VIP，0 = 普通账号。以 `nuser/account/get` 的返回为准 */
    val vipType: Int,
    val savedAtMs: Long
) {
    /** 是不是会员。只用来决定"要不要显示无损档"，能不能真播仍以接口返回为据 */
    val isVip: Boolean get() = vipType > 0

    /**
     * 拼进每个请求的 Cookie。
     *
     * `os=pc; appver=2.10.13` 这两个是**硬要求**（实测 2026-11-13）：
     * 不带的话服务端按普通网页访客处理，会员账号也只给 128k 或试听片段，
     * 表现为"明明登录了却没有音质"。社区里绝大多数"登录后仍低音质"都是漏了它。
     */
    val cookie: String
        get() = buildString {
            append("os=pc; appver=2.10.13")
            if (musicU.isNotBlank()) append("; MUSIC_U=").append(musicU)
            if (csrf.isNotBlank()) append("; __csrf=").append(csrf)
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("musicU", musicU)
        put("csrf", csrf)
        put("userId", userId)
        put("nickname", nickname)
        put("avatarUrl", avatarUrl)
        put("vipType", vipType)
        put("savedAtMs", savedAtMs)
    }

    companion object {
        fun fromJson(o: JSONObject): NeteaseAccount? {
            val musicU = o.optString("musicU")
            // MUSIC_U 是唯一凭据，缺了这条记录就等于没有登录
            if (musicU.isBlank()) return null
            return NeteaseAccount(
                musicU = musicU,
                csrf = o.optString("csrf"),
                userId = o.optLong("userId"),
                nickname = o.optString("nickname"),
                avatarUrl = o.optString("avatarUrl"),
                vipType = o.optInt("vipType"),
                savedAtMs = o.optLong("savedAtMs")
            )
        }
    }
}

/**
 * 账号凭证的读写：**Keystore（AES/GCM）加密后**放进独立的 SharedPreferences。
 *
 * ## 为什么不用明文 / 不用 androidx.security
 *
 * `MUSIC_U` 等价于账号密码 —— 拿到它就能以这个账号的身份取流、下载、看私人歌单。
 * 明文躺在 `shared_prefs` 里，任何拿到 root 或备份的人都直接读走，所以必须加密。
 *
 * 没用 `androidx.security:security-crypto`：1.1.0 已停止维护，且它自己也只是
 * 一层 Keystore 封装。这里三十行就能做到同样的事，还不引入一个死依赖。
 *
 * ## 加解密失败怎么办
 *
 * Keystore 里的密钥可能因为系统升级、恢复出厂、指纹/锁屏密码变更而失效
 * （典型现象是 `KeyPermanentlyInvalidatedException`）。这时候**不能崩**：
 * 清掉密文、当作未登录，用户重新扫一次码就好。用 [load] 的返回值表达这个状态。
 */
object NeteaseAccountStore {

    private const val TAG = "SonanceNetease"
    private const val PREFS = "sonance_netease_account"
    private const val KEY_PAYLOAD = "payload"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "sonance_netease_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128

    fun load(context: Context): NeteaseAccount? {
        val sp = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = sp.getString(KEY_PAYLOAD, null)?.takeIf { it.isNotBlank() } ?: return null
        val plain = runCatching { decrypt(stored) }.getOrElse { t ->
            Log.w(TAG, "账号凭证解密失败，按未登录处理：${t.javaClass.simpleName}")
            // 密钥失效后这串密文再也解不开了，留着只会每次都失败一次
            sp.edit().remove(KEY_PAYLOAD).apply()
            return null
        }
        return runCatching { NeteaseAccount.fromJson(JSONObject(plain)) }.getOrNull()
    }

    fun save(context: Context, account: NeteaseAccount) {
        val sp = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cipherText = runCatching { encrypt(account.toJson().toString()) }.getOrElse { t ->
            Log.w(TAG, "账号凭证加密失败：${t.javaClass.simpleName}: ${t.message}")
            null
        }
        if (cipherText == null) {
            // 存不下加密串时**宁可不存**，也不要退回明文
            Log.w(TAG, "未能加密保存登录状态，本次登录仅进程内有效")
            return
        }
        sp.edit().putString(KEY_PAYLOAD, cipherText).apply()
    }

    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_PAYLOAD).apply()
    }

    // ===== Keystore =====

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    /** 输出 `base64(iv) + "." + base64(cipher)`。iv 必须一起存，GCM 每次加密都随机 */
    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val bytes = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) +
            "." + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun decrypt(payload: String): String {
        val dot = payload.indexOf('.')
        require(dot > 0) { "凭证格式不对" }
        val iv = Base64.decode(payload.substring(0, dot), Base64.NO_WRAP)
        val body = Base64.decode(payload.substring(dot + 1), Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(body), Charsets.UTF_8)
    }
}
