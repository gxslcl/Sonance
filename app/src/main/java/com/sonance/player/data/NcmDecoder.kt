package com.sonance.player.data

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * 网易云音乐 `.ncm` 解码器。
 *
 * ## 头部布局
 * ```
 * 0x00  8 字节   魔数 "CTENFDAM"
 * 0x08  2 字节   保留
 * 0x0A  4 字节   密钥块长度（uint32 LE）
 *       n 字节   密钥块
 *       4 字节   元数据块长度
 *       n 字节   元数据块
 *       4 字节   CRC32（不校验）
 *       1 字节   保留
 *       4 字节   封面帧长度 coverFrameLen（含补白）
 *       4 字节   封面实际长度 imageSize
 *       n 字节   封面图（JPEG / PNG）
 *       补白 = coverFrameLen - imageSize
 *       ->       音频数据
 * ```
 * 音频起点 = 元数据结束 + 13 + coverFrameLen。
 *
 * ## 密钥派生
 * 密钥块 → 逐字节异或 0x64 → AES-128-ECB 解密 → 去 PKCS#7 填充
 * → 砍掉前缀 `neteasecloudmusic` → 得到 RC4 密钥。
 *
 * ## 音频解密不是 AES
 * 名字里带 RC4，但用的是**改造过的密钥流**：KSA 只做一遍、PRGA 的下标
 * 与循环变量脱钩，导致密钥流**只依赖位置 p mod 256**，是一个 256 字节的周期流。
 * 所以这里在构造时预计算 256 字节，解密退化成查表异或 —— 既快，
 * 又天然支持任意切块（[decryptChunk] 只按 `startPos and 0xFF` 取起点）。
 */
class NcmDecoder private constructor(
    private val keystream: ByteArray,
    private val coverOffset: Int,
    private val coverLength: Int,
    override val info: HeaderInfo
) : AudioDecoder {

    override val formatName: String get() = FORMAT

    override fun decryptChunk(buf: ByteArray, count: Int, startPos: Long): Boolean {
        if (count <= 0) return true
        if (count > buf.size || startPos < 0) return false
        var i = 0
        var p = (startPos and 0xFF).toInt()
        while (i < count) {
            buf[i] = (buf[i].toInt() xor keystream[p].toInt()).toByte()
            i++
            p = (p + 1) and 0xFF
        }
        return true
    }

    /**
     * 从「文件开头到载荷起点」的原始字节里切出封面。
     *
     * 封面在头部里是**独立的一段**（不在音频载荷里），所以必须在跳过它之前
     * 顺手拿走；等流走到音频起点时，封面已经过去了。
     */
    override fun extractCover(prefix: ByteArray): ByteArray? {
        if (coverLength <= 0 || coverOffset < 0) return null
        val end = coverOffset + coverLength
        if (end > prefix.size) return null
        return prefix.copyOfRange(coverOffset, end)
    }

    companion object {
        const val FORMAT = "ncm"

        val extensions: List<String> = listOf("ncm")

        private const val MAGIC = "CTENFDAM"

        /** 密钥块与元数据块的固定前后缀，用来快速否掉「碰巧同魔数」的文件 */
        private val KEY_PREFIX = "neteasecloudmusic".toByteArray(Charsets.ISO_8859_1)
        private val META_PREFIX = "163 key(Don't modify):".toByteArray(Charsets.ISO_8859_1)

        /** 内嵌的两把 AES-128 密钥（公开常量，所有 ncm 文件一致） */
        private val CORE_KEY = hex("687A4852416D736F356B496E62617857")
        private val META_KEY = hex("2331346C6A6B5F215C5D2630553C2728")

        private const val XOR_KEY = 0x64
        private const val XOR_META = 0x63

        /** 探测需要读到的头部长度上限：元数据块正常不超过 1KB，16KB 足够覆盖 */
        const val PROBE_BYTES = 16 * 1024

        /** 廉价魔数判定 */
        fun looksLike(header: ByteArray): Boolean =
            AudioDecoder.startsWith(header, MAGIC)

        fun probe(header: ByteArray, fileSize: Long): AudioDecoder? {
            if (!looksLike(header)) return null

            var off = 10
            val keyLen = readU32(header, off) ?: return null
            off += 4
            if (keyLen <= 0 || keyLen > header.size - off) return null
            val keyBlob = header.copyOfRange(off, off + keyLen)
            off += keyLen

            val metaLen = readU32(header, off) ?: return null
            off += 4
            if (metaLen < 0 || metaLen > header.size - off) return null
            val metaBlob = if (metaLen > 0) header.copyOfRange(off, off + metaLen) else ByteArray(0)
            off += metaLen

            // 密钥块：异或 0x64 -> AES-ECB 解密 -> 去填充 -> 剥前缀
            val keyPlain = aesDecrypt(CORE_KEY, xorAll(keyBlob, XOR_KEY)) ?: return null
            if (!AudioDecoder.startsWith(keyPlain, KEY_PREFIX)) return null
            val rc4Key = keyPlain.copyOfRange(KEY_PREFIX.size, keyPlain.size)
            if (rc4Key.isEmpty()) return null

            // 元数据块：异或 0x63 -> 剥前缀 -> Base64 -> AES-ECB -> 去填充 -> 砍 "music:" -> JSON
            val meta = parseMeta(metaBlob)

            // 封面区：CRC32(4) + 保留(1) + coverFrameLen(4) + imageSize(4) + 图
            val coverFrameLen = readU32(header, off + 5) ?: return null
            val imageSize = readU32(header, off + 9) ?: return null
            val payloadOffset = off.toLong() + 13L + coverFrameLen.toLong()
            if (coverFrameLen < 0 || imageSize < 0) return null
            if (payloadOffset > fileSize) return null
            // 封面长度不能超过帧长度，否则说明头部被写坏了
            val coverLen = if (imageSize in 1..coverFrameLen) imageSize else 0

            val declared = AudioDecoder.normalizeExt(meta["format"] as? String)
            val bitrate = (meta["bitrate"] as? Number)?.toInt() ?: 0
            val duration = (meta["duration"] as? Number)?.toLong() ?: 0L

            val metadata = buildMap<String, Any> {
                (meta["musicName"] as? String)?.takeIf { it.isNotBlank() }?.let { put("title", it) }
                artistOf(meta["artist"])?.let { put("artist", it) }
                (meta["album"] as? String)?.takeIf { it.isNotBlank() }?.let { put("album", it) }
                if (bitrate > 0) put("bitrate", bitrate)
                if (duration > 0) put("duration", duration)
            }

            return NcmDecoder(
                keystream = keystreamOf(rc4Key),
                coverOffset = off + 13,
                coverLength = coverLen,
                info = HeaderInfo(
                    payloadOffset = payloadOffset,
                    suggestedExt = declared,
                    format = FORMAT,
                    metadata = metadata,
                    totalSize = fileSize
                )
            )
        }

        // ===== 内部实现 =====

        /**
         * 改造版 RC4 的密钥流，周期恒为 256。
         *
         * KSA 与标准 RC4 相同；PRGA 里 `j` 被替换成 `(p + 1) & 0xFF`，
         * 于是输出只与 `p mod 256` 有关 —— 这正是能预计算成 256 字节查表的原因。
         */
        internal fun keystreamOf(key: ByteArray): ByteArray {
            val box = IntArray(256) { it }
            var j = 0
            for (i in 0 until 256) {
                j = (box[i] + j + (key[i % key.size].toInt() and 0xFF)) and 0xFF
                val t = box[i]; box[i] = box[j]; box[j] = t
            }
            val out = ByteArray(256)
            for (p in 0 until 256) {
                val jj = (p + 1) and 0xFF
                val idx = (box[jj] + box[(box[jj] + jj) and 0xFF]) and 0xFF
                out[p] = box[idx].toByte()
            }
            return out
        }

        private fun parseMeta(blob: ByteArray): Map<String, Any> {
            if (blob.isEmpty()) return emptyMap()
            val x = xorAll(blob, XOR_META)
            if (!AudioDecoder.startsWith(x, META_PREFIX)) return emptyMap()
            val b64 = String(x, META_PREFIX.size, x.size - META_PREFIX.size, Charsets.ISO_8859_1)
            val cipher = base64Decode(b64) ?: return emptyMap()
            val plain = aesDecrypt(META_KEY, cipher) ?: return emptyMap()
            val text = String(plain, Charsets.UTF_8)
            val json = text.substringAfter(':', text)

            val out = HashMap<String, Any>()
            runCatching {
                val obj = org.json.JSONObject(json)
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = obj.opt(k)
                    if (v != null) out[k] = v
                }
            }
            return out
        }

        private fun artistOf(raw: Any?): String? {
            val arr = raw as? org.json.JSONArray ?: return null
            val names = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val item = arr.optJSONArray(i) ?: continue
                val name = item.optString(0).trim()
                if (name.isNotEmpty()) names += name
            }
            return names.joinToString(" / ").takeIf { it.isNotBlank() }
        }

        private fun readU32(buf: ByteArray, off: Int): Int? {
            if (off < 0 || off + 4 > buf.size) return null
            val v = (buf[off].toLong() and 0xFF) or
                ((buf[off + 1].toLong() and 0xFF) shl 8) or
                ((buf[off + 2].toLong() and 0xFF) shl 16) or
                ((buf[off + 3].toLong() and 0xFF) shl 24)
            return if (v > Int.MAX_VALUE) null else v.toInt()
        }

        private fun xorAll(data: ByteArray, k: Int): ByteArray =
            ByteArray(data.size) { (data[it].toInt() xor k).toByte() }

        /** AES-128-ECB 解密 + 去 PKCS#7 填充；密文长度非法或解密失败返回 null */
        private fun aesDecrypt(key: ByteArray, data: ByteArray): ByteArray? {
            if (data.isEmpty() || data.size % 16 != 0) return null
            val plain = runCatching {
                val cipher = Cipher.getInstance("AES/ECB/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
                cipher.doFinal(data)
            }.getOrNull() ?: return null
            if (plain.isEmpty()) return null
            val pad = plain[plain.size - 1].toInt() and 0xFF
            if (pad !in 1..16 || pad > plain.size) return null
            for (i in plain.size - pad until plain.size) {
                if ((plain[i].toInt() and 0xFF) != pad) return null
            }
            return plain.copyOf(plain.size - pad)
        }

        /**
         * 标准 Base64 解码。
         *
         * 不用 `android.util.Base64` 是为了让这段逻辑能在纯 JVM 单测里跑
         * （项目没引 Robolectric，framework 桩方法只会返回默认值）；
         * `java.util.Base64` 又要 API 26，而 minSdk 是 24。
         */
        internal fun base64Decode(text: String): ByteArray? {
            val out = java.io.ByteArrayOutputStream(text.length * 3 / 4 + 3)
            var buffer = 0
            var bits = 0
            for (c in text) {
                val v = when (c) {
                    in 'A'..'Z' -> c - 'A'
                    in 'a'..'z' -> c - 'a' + 26
                    in '0'..'9' -> c - '0' + 52
                    '+', '-' -> 62
                    '/', '_' -> 63
                    '=' -> return out.toByteArray()
                    else -> continue // 忽略换行、空格等
                }
                buffer = (buffer shl 6) or v
                bits += 6
                if (bits >= 8) {
                    bits -= 8
                    out.write((buffer shr bits) and 0xFF)
                }
            }
            return out.toByteArray()
        }

        private fun hex(text: String): ByteArray {
            val out = ByteArray(text.length / 2)
            for (i in out.indices) {
                val hi = Character.digit(text[i * 2], 16)
                val lo = Character.digit(text[i * 2 + 1], 16)
                out[i] = ((hi shl 4) or lo).toByte()
            }
            return out
        }
    }
}
