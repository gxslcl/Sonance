package com.sonance.player.data

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * QQ 音乐 QMC 系列的加密原语，移植自 unlock-music CLI（Go, MIT License）
 * 的 `algo/qmc` 包，并用真实文件逐字节核对过。
 *
 * 三个部分：
 *  1. [TencentTea] —— 腾讯魔改 TEA（32 轮 + 私有填充方案），用于解 ekey；
 *  2. [QmcKeyDerive] —— ekey（base64）→ 真实密钥，兼容 EncV2 双层包装；
 *  3. [QmcCipher] —— 三种流式掩码：静态表（QMCv1）/ Map（短密钥）/ RC4（长密钥）。
 *
 * 全部纯 Kotlin + JVM（NCM 的 AES 走 javax.crypto，这里 TEA 手写），可在 JVM 单测。
 *
 * ## 为什么掩码函数都是「按绝对偏移」可重入的
 *
 * 静态表和 Map 的掩码是 offset 的纯函数；RC4 虽然有内部状态，但官方实现按
 * 5120 字节分段、每段从 key box 重放 —— 所以三种都能用
 * `decryptChunk(buf, count, startPos)` 的绝对下标语义无缝接入流式管线。
 */
object QmcCrypto {

    // ===== 1. 腾讯 TEA =====

    private const val DELTA = 0x9E3779B9.toInt()
    private const val ROUNDS = 32
    private const val SUM = DELTA * ROUNDS

    /** 单块 TEA 解密（8 字节），标准 TEA、32 轮、小端序字节序 */
    private fun teaDecryptBlock(block: ByteArray, off: Int, key: IntArray) {
        var v0 = ((block[off].toInt() and 0xFF)) or
            ((block[off + 1].toInt() and 0xFF) shl 8) or
            ((block[off + 2].toInt() and 0xFF) shl 16) or
            ((block[off + 3].toInt() and 0xFF) shl 24)
        var v1 = ((block[off + 4].toInt() and 0xFF)) or
            ((block[off + 5].toInt() and 0xFF) shl 8) or
            ((block[off + 6].toInt() and 0xFF) shl 16) or
            ((block[off + 7].toInt() and 0xFF) shl 24)

        var sum = SUM
        for (i in 0 until ROUNDS) {
            v1 -= ((v0 shl 4) + key[2]) xor (v0 + sum) xor ((v0 ushr 5) + key[3])
            v0 -= ((v1 shl 4) + key[0]) xor (v1 + sum) xor ((v1 ushr 5) + key[1])
            sum -= DELTA
        }

        block[off] = (v0 and 0xFF).toByte()
        block[off + 1] = ((v0 ushr 8) and 0xFF).toByte()
        block[off + 2] = ((v0 ushr 16) and 0xFF).toByte()
        block[off + 3] = ((v0 ushr 24) and 0xFF).toByte()
        block[off + 4] = (v1 and 0xFF).toByte()
        block[off + 5] = ((v1 ushr 8) and 0xFF).toByte()
        block[off + 6] = ((v1 ushr 16) and 0xFF).toByte()
        block[off + 7] = ((v1 ushr 24) and 0xFF).toByte()
    }

    /** 单块 TEA 加密；与 [teaDecryptBlock] 互逆，供测试构造样本 */
    private fun teaEncryptBlock(block: ByteArray, off: Int, key: IntArray) {
        var v0 = ((block[off].toInt() and 0xFF)) or
            ((block[off + 1].toInt() and 0xFF) shl 8) or
            ((block[off + 2].toInt() and 0xFF) shl 16) or
            ((block[off + 3].toInt() and 0xFF) shl 24)
        var v1 = ((block[off + 4].toInt() and 0xFF)) or
            ((block[off + 5].toInt() and 0xFF) shl 8) or
            ((block[off + 6].toInt() and 0xFF) shl 16) or
            ((block[off + 7].toInt() and 0xFF) shl 24)

        var sum = 0
        for (i in 0 until ROUNDS) {
            sum += DELTA
            v0 += ((v1 shl 4) + key[0]) xor (v1 + sum) xor ((v1 ushr 5) + key[1])
            v1 += ((v0 shl 4) + key[2]) xor (v0 + sum) xor ((v0 ushr 5) + key[3])
        }

        block[off] = (v0 and 0xFF).toByte()
        block[off + 1] = ((v0 ushr 8) and 0xFF).toByte()
        block[off + 2] = ((v0 ushr 16) and 0xFF).toByte()
        block[off + 3] = ((v0 ushr 24) and 0xFF).toByte()
        block[off + 4] = (v1 and 0xFF).toByte()
        block[off + 5] = ((v1 ushr 8) and 0xFF).toByte()
        block[off + 6] = ((v1 ushr 16) and 0xFF).toByte()
        block[off + 7] = ((v1 ushr 24) and 0xFF).toByte()
    }

    private fun keyToIntArray(key: ByteArray): IntArray {
        require(key.size == 16) { "tea key must be 16 bytes" }
        return IntArray(4) { i ->
            ((key[i * 4].toInt() and 0xFF)) or
                ((key[i * 4 + 1].toInt() and 0xFF) shl 8) or
                ((key[i * 4 + 2].toInt() and 0xFF) shl 16) or
                ((key[i * 4 + 3].toInt() and 0xFF) shl 24)
        }
    }

    /**
     * 腾讯 TEA 解密（`decryptTencentTea` 的移植）。
     *
     * 与标准 TEA-CBC 不同：填充长度藏在首字节低 3 位，salt 2 字节、尾部校验 7 字节，
     * 分组链是「c_i = E(m_i XOR c_{i-1}) XOR D(c_{i-1})」的变体。
     * 输入必须是 8 的倍数且 ≥ 16 字节；尾部 7 字节零校验失败返回 null
     * —— 这正是「密钥不对」的可靠信号，比解出噪声再嗅探早得多。
     */
    fun teaDecrypt(inBuf: ByteArray, key: ByteArray): ByteArray? {
        if (inBuf.size % 8 != 0 || inBuf.size < 16) return null
        val k = keyToIntArray(key)

        val dest = ByteArray(8)
        System.arraycopy(inBuf, 0, dest, 0, 8)
        teaDecryptBlock(dest, 0, k)

        val padLen = dest[0].toInt() and 0x7
        val outLen = inBuf.size - 1 - padLen - 2 - 7
        if (outLen < 0) return null
        val out = ByteArray(outLen)

        var ivPrev = ByteArray(8)
        val ivCur = ByteArray(8)
        System.arraycopy(inBuf, 0, ivCur, 0, 8)
        var inPos = 8
        var destIdx = 1 + padLen

        fun cryptBlock() {
            System.arraycopy(ivCur, 0, ivPrev, 0, 8)
            System.arraycopy(inBuf, inPos, ivCur, 0, 8)
            for (i in 0 until 8) dest[i] = (dest[i].toInt() xor ivCur[i].toInt()).toByte()
            teaDecryptBlock(dest, 0, k)
            inPos += 8
            destIdx = 0
        }

        var i = 1
        while (i <= 2) {
            if (destIdx < 8) {
                destIdx++; i++
            } else {
                cryptBlock()
            }
        }

        var outPos = 0
        while (outPos < outLen) {
            if (destIdx < 8) {
                out[outPos] = (dest[destIdx].toInt() xor ivPrev[destIdx].toInt()).toByte()
                destIdx++; outPos++
            } else {
                cryptBlock()
            }
        }

        // 尾部 7 字节零校验（ivPrev 与密文比对，密钥错时必然不等）
        for (j in 1..7) {
            if (dest[destIdx] != ivPrev[destIdx]) return null
        }
        return out
    }

    /**
     * 腾讯 TEA 加密；[teaDecrypt] 的逆运算，主要供测试构造样本。
     *
     * 填充方案：`[随机头(低3位=padLen)][padLen 随机][2 salt][数据][7 零]`，
     * 分组链：`dest_i = m_i XOR c_{i-1}`，`c_i = E(dest_i) XOR dest_{i-1}`。
     */
    fun teaEncrypt(plain: ByteArray, key: ByteArray): ByteArray {
        require(plain.size >= 8) { "tea payload too small" }
        val k = keyToIntArray(key)
        val padLen = (8 - (plain.size + 10) % 8 + 8) % 8
        val total = plain.size + 10 + padLen
        val m = ByteArray(total)
        // 头字节：低 3 位必须等于 padLen，高位随意（解密端只看低 3 位）
        m[0] = ((Math.random() * 0x20).toInt() and 0xF8 or padLen).toByte()
        // pad 与 salt 区填伪随机；尾部 7 字节必须是 0（解密端做零校验）
        for (i in 1 until 3 + padLen) {
            m[i] = ((Math.random() * 256).toInt()).toByte()
        }
        System.arraycopy(plain, 0, m, 3 + padLen, plain.size)

        val out = ByteArray(total)
        var destPrev = ByteArray(8)
        var cPrev = ByteArray(8)
        var off = 0
        while (off < total) {
            // dest_i = m_i XOR c_{i-1}
            val destI = ByteArray(8) { b ->
                (m[off + b].toInt() xor cPrev[b].toInt()).toByte()
            }
            val e = destI.copyOf()
            teaEncryptBlock(e, 0, k)
            // c_i = E(dest_i) XOR dest_{i-1}
            val cI = ByteArray(8) { b ->
                (e[b].toInt() xor destPrev[b].toInt()).toByte()
            }
            System.arraycopy(cI, 0, out, off, 8)
            destPrev = destI
            cPrev = cI
            off += 8
        }
        return out
    }

    // ===== 2. ekey 派生 =====

    /** tan 表：`byte(abs(tan(salt + i*0.1)) * 100)`，与官方实现一致 */
    private fun simpleMakeKey(salt: Byte, length: Int): ByteArray =
        ByteArray(length) { i ->
            kotlin.math.abs(kotlin.math.tan(salt + i * 0.1) * 100.0).toInt().toByte()
        }

    private const val RAW_KEY_PREFIX_V2 = "QQMusic EncV2,Key:"

    private val DERIVE_V2_KEY1 = byteArrayOf(
        0x33, 0x38, 0x36, 0x5A, 0x4A, 0x59, 0x21, 0x40,
        0x23, 0x2A, 0x24, 0x25, 0x5E, 0x26, 0x29, 0x28
    )
    private val DERIVE_V2_KEY2 = byteArrayOf(
        0x2A, 0x2A, 0x23, 0x21, 0x28, 0x23, 0x24, 0x25,
        0x26, 0x5E, 0x61, 0x31, 0x63, 0x5A, 0x2C, 0x54
    )

    /** ekey（原始 base64 字节）→ 真实密钥；格式非法返回 null */
    fun deriveKey(rawKey: ByteArray): ByteArray? {
        val decoded = runCatching {
            java.util.Base64.getDecoder().decode(rawKey)
        }.getOrNull() ?: return null
        if (decoded.size < 16) return null

        val v1Input = if (String(decoded.copyOfRange(0, minOf(decoded.size, RAW_KEY_PREFIX_V2.length)), Charsets.UTF_8) == RAW_KEY_PREFIX_V2) {
            val v2Raw = decoded.copyOfRange(RAW_KEY_PREFIX_V2.length, decoded.size)
            // EncV2：双层 TEA → 再 base64
            val buf1 = teaDecrypt(v2Raw, DERIVE_V2_KEY1) ?: return null
            val buf2 = teaDecrypt(buf1, DERIVE_V2_KEY2) ?: return null
            runCatching { java.util.Base64.getDecoder().decode(buf2) }.getOrNull() ?: return null
        } else {
            decoded
        }
        return deriveKeyV1(v1Input)
    }

    private fun deriveKeyV1(rawKeyDec: ByteArray): ByteArray? {
        if (rawKeyDec.size < 16) return null
        val simpleKey = simpleMakeKey(106, 8)
        val teaKey = ByteArray(16)
        for (i in 0 until 8) {
            teaKey[i * 2] = simpleKey[i]
            teaKey[i * 2 + 1] = rawKeyDec[i]
        }
        val rs = teaDecrypt(rawKeyDec.copyOfRange(8, rawKeyDec.size), teaKey) ?: return null
        return rawKeyDec.copyOfRange(0, 8) + rs
    }

    // ===== 3. 流式掩码 =====

    /**
     * 掩码流：按绝对偏移解密，天然支持 `decryptChunk(buf, count, startPos)` 的
     * 「任意起点、跨块连续」语义。
     */
    sealed class Cipher {
        abstract fun decrypt(buf: ByteArray, count: Int, offset: Int)
    }

    /** QMCv1：128 字节静态表，`idx = (offset² + 27) & 0xff`。掩码是自反 XOR */
    class StaticCipher : Cipher() {
        companion object {
            private val BOX = intArrayOf(
                0x77, 0x48, 0x32, 0x73, 0xDE, 0xF2, 0xC0, 0xC8,
                0x95, 0xEC, 0x30, 0xB2, 0x51, 0xC3, 0xE1, 0xA0,
                0x9E, 0xE6, 0x9D, 0xCF, 0xFA, 0x7F, 0x14, 0xD1,
                0xCE, 0xB8, 0xDC, 0xC3, 0x4A, 0x67, 0x93, 0xD6,
                0x28, 0xC2, 0x91, 0x70, 0xCA, 0x8D, 0xA2, 0xA4,
                0xF0, 0x08, 0x61, 0x90, 0x7E, 0x6F, 0xA2, 0xE0,
                0xEB, 0xAE, 0x3E, 0xB6, 0x67, 0xC7, 0x92, 0xF4,
                0x91, 0xB5, 0xF6, 0x6C, 0x5E, 0x84, 0x40, 0xF7,
                0xF3, 0x1B, 0x02, 0x7F, 0xD5, 0xAB, 0x41, 0x89,
                0x28, 0xF4, 0x25, 0xCC, 0x52, 0x11, 0xAD, 0x43,
                0x68, 0xA6, 0x41, 0x8B, 0x84, 0xB5, 0xFF, 0x2C,
                0x92, 0x4A, 0x26, 0xD8, 0x47, 0x6A, 0x7C, 0x95,
                0x61, 0xCC, 0xE6, 0xCB, 0xBB, 0x3F, 0x47, 0x58,
                0x89, 0x75, 0xC3, 0x75, 0xA1, 0xD9, 0xAF, 0xCC,
                0x08, 0x73, 0x17, 0xDC, 0xAA, 0x9A, 0xA2, 0x16,
                0x41, 0xD8, 0xA2, 0x06, 0xC6, 0x8B, 0xFC, 0x66,
                0x34, 0x9F, 0xCF, 0x18, 0x23, 0xA0, 0x0A, 0x74,
                0xE7, 0x2B, 0x27, 0x70, 0x92, 0xE9, 0xAF, 0x37,
                0xE6, 0x8C, 0xA7, 0xBC, 0x62, 0x65, 0x9C, 0xC2,
                0x08, 0xC9, 0x88, 0xB3, 0xF3, 0x43, 0xAC, 0x74,
                0x2C, 0x0F, 0xD4, 0xAF, 0xA1, 0xC3, 0x01, 0x64,
                0x95, 0x4E, 0x48, 0x9F, 0xF4, 0x35, 0x78, 0x95,
                0x7A, 0x39, 0xD6, 0x6A, 0xA0, 0x6D, 0x40, 0xE8,
                0x4F, 0xA8, 0xEF, 0x11, 0x1D, 0xF3, 0x1B, 0x3F,
                0x3F, 0x07, 0xDD, 0x6F, 0x5B, 0x19, 0x30, 0x19,
                0xFB, 0xEF, 0x0E, 0x37, 0xF0, 0x0E, 0xCD, 0x16,
                0x49, 0xFE, 0x53, 0x47, 0x13, 0x1A, 0xBD, 0xA4,
                0xF1, 0x40, 0x19, 0x60, 0x0E, 0xED, 0x68, 0x09,
                0x06, 0x5F, 0x4D, 0xCF, 0x3D, 0x1A, 0xFE, 0x20,
                0x77, 0xE4, 0xD9, 0xDA, 0xF9, 0xA4, 0x2B, 0x76,
                0x1C, 0x71, 0xDB, 0x00, 0xBC, 0xFD, 0x0C, 0x6C,
                0xA5, 0x47, 0xF7, 0xF6, 0x00, 0x79, 0x4A, 0x11
            )
        }

        override fun decrypt(buf: ByteArray, count: Int, offset: Int) {
            for (i in 0 until count) {
                var o = offset + i
                if (o > 0x7FFF) o %= 0x7FFF
                buf[i] = (buf[i].toInt() xor BOX[(o * o + 27) and 0xff]).toByte()
            }
        }
    }

    /** QMCv2 短密钥：`idx = (offset² + 71214) % size`，再按 `idx & 7` 旋转 */
    class MapCipher(key: ByteArray) : Cipher() {
        private val key = key.copyOf()
        private val size = key.size

        override fun decrypt(buf: ByteArray, count: Int, offset: Int) {
            for (i in 0 until count) {
                var o = offset + i
                if (o > 0x7FFF) o %= 0x7FFF
                val idx = (o * o + 71214) % size
                buf[i] = (buf[i].toInt() xor rotate(key[idx], (idx and 0x7).toByte()).toInt()).toByte()
            }
        }

        private fun rotate(value: Byte, bits: Byte): Byte {
            val r = ((bits + 4) % 8).toInt()
            val left = (value.toInt() and 0xFF) shl r
            val right = (value.toInt() and 0xFF) ushr r
            return (left or right).toByte()
        }
    }

    /**
     * QMCv2 长密钥（RC4 变体）：
     * - 首 128 字节用 `key[segmentSkip(id)]` 直接异或；
     * - 之后每 5120 字节一段，段内从 key box 重放 RC4（按 offset 跳过对应步数）。
     */
    class RC4Cipher(key: ByteArray) : Cipher() {
        private val key = key.copyOf()
        private val box = key.size.let { n -> ByteArray(n) { i -> i.toByte() } }
        private val n = key.size
        private var hash: Long = 1

        init {
            var j = 0
            for (i in 0 until n) {
                j = (j + (box[i].toInt() and 0xFF) + (key[i % n].toInt() and 0xFF)) % n
                val t = box[i]; box[i] = box[j]; box[j] = t
            }
            hash = 1
            for (i in 0 until n) {
                val v = (key[i].toInt() and 0xFF).toLong()
                if (v == 0L) continue
                val next = hash * v
                if (next == 0L || next <= hash) break
                hash = next
            }
        }

        override fun decrypt(buf: ByteArray, count: Int, offset: Int) {
            var processed = 0
            var pos = offset
            var toProcess = count

            if (pos < FIRST_SEGMENT) {
                val blockSize = minOf(toProcess, FIRST_SEGMENT - pos)
                for (i in 0 until blockSize) {
                    buf[i] = (buf[i].toInt() xor key[segmentSkip(pos + i)].toInt()).toByte()
                }
                processed += blockSize; pos += blockSize; toProcess -= blockSize
                if (toProcess == 0) return
            }

            if (pos % SEGMENT != 0) {
                val blockSize = minOf(toProcess, SEGMENT - pos % SEGMENT)
                encASegment(buf, processed, blockSize, pos)
                processed += blockSize; pos += blockSize; toProcess -= blockSize
            }

            while (toProcess > SEGMENT) {
                encASegment(buf, processed, SEGMENT, pos)
                processed += SEGMENT; pos += SEGMENT; toProcess -= SEGMENT
            }
            if (toProcess > 0) {
                encASegment(buf, processed, toProcess, pos)
            }
        }

        private fun encASegment(buf: ByteArray, outOff: Int, len: Int, offset: Int) {
            val b = box.copyOf()
            var j = 0
            var k = 0
            val skipLen = (offset % SEGMENT) + segmentSkip(offset / SEGMENT)
            for (i in -skipLen until len) {
                j = (j + 1) % n
                k = ((b[j].toInt() and 0xFF) + k) % n
                val t = b[j]; b[j] = b[k]; b[k] = t
                if (i >= 0) {
                    buf[outOff + i] = (buf[outOff + i].toInt() xor
                        b[((b[j].toInt() and 0xFF) + (b[k].toInt() and 0xFF)) % n].toInt()).toByte()
                }
            }
        }

        private fun segmentSkip(id: Int): Int {
            val seed = (key[id % n].toInt() and 0xFF)
            // Go 版在双精度浮点里算这一步（除零得 Inf 也不炸）；Kotlin 的 Long 除零会抛，必须走 Double
            val idx = (hash.toDouble() / ((id + 1).toLong() * seed).toDouble() * 100.0)
            if (idx.isInfinite() || idx.isNaN()) return 0
            return (idx.toLong() % n).toInt()
        }

        private companion object {
            const val SEGMENT = 5120
            const val FIRST_SEGMENT = 128
        }
    }

    /** 按派生出的密钥长度选择掩码流：>300 → RC4；非空 → Map；空 → 静态表 */
    fun cipherFor(key: ByteArray?): Cipher = when {
        key == null -> StaticCipher()
        key.size > 300 -> RC4Cipher(key)
        key.isNotEmpty() -> MapCipher(key)
        else -> StaticCipher()
    }
}
