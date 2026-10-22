package com.sonance.player.data

import java.io.ByteArrayOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * 测试用的**合成加密容器**构造器。
 *
 * 仓库里没有真实样本（`tools/` 下只有 Python 参考实现，样本文件不进版本库），
 * 所以测试用「加密方向」自己造容器：等价于 `tools/decrypt-lab` 里 Python 端的
 * `build()` / `encrypt()`。这样 `probe -> decrypt` 的往返才有意义 ——
 * 断言的是「解出来的字节与原始音频逐字节相同」，不是「函数没抛异常」。
 */
internal object TestContainers {

    val CORE_KEY: ByteArray = hex("687A4852416D736F356B496E62617857")
    val META_KEY: ByteArray = hex("2331346C6A6B5F215C5D2630553C2728")

    /** KGM / KGMA 的 16 字节魔数 */
    const val KGM_MAGIC_HEX = "7CD532EB86027F4BA8AFA68E0FFF9914"

    /**
     * 构造一个 `.ncm` 文件。
     *
     * @param audio   解密后应当是这些字节
     * @param rc4Key  RC4 密钥（任意长度，非空即可）
     * @param metaJson 元数据 JSON，例如 `{"musicName":"A","format":"flac"}`
     * @param image   封面字节，可为空
     */
    fun buildNcm(audio: ByteArray, rc4Key: ByteArray, metaJson: String, image: ByteArray): ByteArray {
        val keyPlain = pad("neteasecloudmusic".toByteArray(Charsets.ISO_8859_1) + rc4Key)
        val keyBlob = xorAll(aesEncrypt(CORE_KEY, keyPlain), 0x64)

        val metaPlain = pad(("music:" + metaJson).toByteArray(Charsets.UTF_8))
        val metaCipher = aesEncrypt(META_KEY, metaPlain)
        val metaBlob = xorAll(
            "163 key(Don't modify):".toByteArray(Charsets.ISO_8859_1) + base64Encode(metaCipher),
            0x63
        )

        val ks = NcmDecoder.keystreamOf(rc4Key)
        val encAudio = ByteArray(audio.size) { i ->
            (audio[i].toInt() xor ks[i and 0xFF].toInt()).toByte()
        }

        val out = ByteArrayOutputStream()
        out.write("CTENFDAM".toByteArray(Charsets.ISO_8859_1))
        out.write(byteArrayOf(0x01, 0x61))
        out.write(u32(keyBlob.size)); out.write(keyBlob)
        out.write(u32(metaBlob.size)); out.write(metaBlob)
        out.write(u32(0))               // CRC32（不校验）
        out.write(0)                    // 保留
        out.write(u32(image.size))      // coverFrameLen
        out.write(u32(image.size))      // imageSize
        out.write(image)
        out.write(encAudio)
        return out.toByteArray()
    }

    /** 构造一个 `.kwm` 文件：1024 字节头 + 异或载荷 */
    fun buildKwm(audio: ByteArray, recipe: Long, suffix: String): ByteArray {
        val header = ByteArray(0x400)
        val magic = "yeelion-kuwo-tme".toByteArray(Charsets.ISO_8859_1)
        System.arraycopy(magic, 0, header, 0, 16)
        for (i in 0 until 8) {
            header[0x18 + i] = ((recipe ushr (8 * i)) and 0xFF).toByte()
        }
        val s = suffix.toByteArray(Charsets.ISO_8859_1)
        System.arraycopy(s, 0, header, 0x30, minOf(8, s.size))

        val mask = KwmDecoder.deriveMask(recipe)
        val body = ByteArray(audio.size) { i ->
            (audio[i].toInt() xor mask[i and 31].toInt()).toByte()
        }
        return header + body
    }

    /** 构造 KGM 头部（44 字节有效 + 补齐到 64） */
    fun kgmHeader(key: ByteArray, version: Int, payloadOffset: Int = 44): ByteArray {
        val h = ByteArray(64)
        System.arraycopy(hex(KGM_MAGIC_HEX), 0, h, 0, 16)
        putU32(h, 0x10, payloadOffset)
        putU32(h, 0x14, version)
        System.arraycopy(key, 0, h, 0x1C, minOf(16, key.size))
        return h
    }

    /**
     * 构造一段「看起来像 FLAC」的音频：`fLaC` + STREAMINFO + 若干帧字节。
     *
     * STREAMINFO 的 last 标记按参数给，用来验证插入封面块时是否被正确改写。
     */
    fun flacAudio(frameBytes: Int, streamInfoLast: Boolean = true): ByteArray {
        val out = ByteArray(4 + 4 + 34 + frameBytes)
        out[0] = 'f'.code.toByte()
        out[1] = 'L'.code.toByte()
        out[2] = 'a'.code.toByte()
        out[3] = 'C'.code.toByte()
        out[4] = ((if (streamInfoLast) 0x80 else 0x00) or 0).toByte()
        out[5] = 0; out[6] = 0; out[7] = 34
        for (i in 0 until 34) out[8 + i] = (i + 1).toByte()
        for (i in 0 until frameBytes) out[42 + i] = ((i * 7) and 0xFF).toByte()
        return out
    }

    /**
     * 构造一段「自带 ID3v2.3 标签」的 mp3 音频。
     *
     * 用来验证还原时会不会把旧标签整段跳过 —— 一个文件开头只能有一个 ID3v2 标签。
     */
    fun mp3WithExistingTag(tagPayload: Int, frameBytes: Int): ByteArray {
        val tag = ByteArray(10 + tagPayload)
        tag[0] = 'I'.code.toByte()
        tag[1] = 'D'.code.toByte()
        tag[2] = '3'.code.toByte()
        tag[3] = 3    // v2.3
        tag[4] = 0    // revision
        tag[5] = 0    // flags
        tag[6] = ((tagPayload shr 21) and 0x7F).toByte()
        tag[7] = ((tagPayload shr 14) and 0x7F).toByte()
        tag[8] = ((tagPayload shr 7) and 0x7F).toByte()
        tag[9] = (tagPayload and 0x7F).toByte()

        val frames = ByteArray(frameBytes)
        for (i in 0 until frameBytes) {
            frames[i] = ((i * 3) and 0xFF).toByte()
        }
        // 首两字节是 MPEG 帧同步，让嗅探稳定判成 mp3
        if (frames.size >= 2) {
            frames[0] = 0xFF.toByte()
            frames[1] = 0xFB.toByte()
        }
        return tag + frames
    }

    // ===== 基础工具 =====

    fun pad(data: ByteArray, block: Int = 16): ByteArray {
        val p = block - data.size % block
        return data + ByteArray(p) { p.toByte() }
    }

    fun aesEncrypt(key: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(data)
    }

    fun base64Encode(data: ByteArray): ByteArray {
        val table = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val sb = StringBuilder()
        var i = 0
        while (i < data.size) {
            val b0 = data[i].toInt() and 0xFF
            val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xFF else 0
            val n = (b0 shl 16) or (b1 shl 8) or b2
            sb.append(table[(n ushr 18) and 63])
            sb.append(table[(n ushr 12) and 63])
            sb.append(if (i + 1 < data.size) table[(n ushr 6) and 63] else '=')
            sb.append(if (i + 2 < data.size) table[n and 63] else '=')
            i += 3
        }
        return sb.toString().toByteArray(Charsets.ISO_8859_1)
    }

    fun u32(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 24) and 0xFF).toByte()
    )

    fun putU32(buf: ByteArray, off: Int, v: Int) {
        buf[off] = (v and 0xFF).toByte()
        buf[off + 1] = ((v ushr 8) and 0xFF).toByte()
        buf[off + 2] = ((v ushr 16) and 0xFF).toByte()
        buf[off + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    fun xorAll(data: ByteArray, k: Int): ByteArray =
        ByteArray(data.size) { (data[it].toInt() xor k).toByte() }

    fun hex(text: String): ByteArray {
        val out = ByteArray(text.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(text[i * 2], 16)
            val lo = Character.digit(text[i * 2 + 1], 16)
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}
