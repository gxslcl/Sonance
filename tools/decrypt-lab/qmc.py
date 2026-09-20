"""
QQ音乐 QMC 家族解密 —— 实现原理与参考实现

参考：unlock-music 的 qmc_cipher.ts / qmc_key.ts / utils/tea.ts

======================================================================
一、QMCv1（.qmc0 / .qmc2 / .qmc3 / .qmcflac / .qmcogg）
======================================================================
没有统一魔数，靠后缀 + 尾部结构识别。三种流密码：

  1) 静态（无密钥）  .qmc0 / .qmc3
     getMask(i) = staticCipherBox[(i*i + 27) & 0xFF]
     其中 i 先对 0x7FFF 取模（超过才取）

  2) 映射（尾部 128 字节密钥）  .qmcflac / .qmcogg
     idx    = (i*i + 71214) % keyLen
     getMask(i) = rotate(key[idx], idx & 7)
     rotate(v, n): n = (n + 4) % 8; return ((v << n) | (v >> n)) & 0xFF
     ★ 注意旋转量是 (n+4)%8，不是 n —— 这点很容易写错

  3) RC4（QMCv2，见下）

======================================================================
二、QMCv2（.mflac / .mgg / .tkm / .tm* / .x2m / .x3m / ...）
======================================================================
尾部识别（末 4 字节）:
    "QTag" -> 有密钥，密钥长度在 -8..-4（大端）
    "STag" -> 无密钥（退回静态表）

密钥派生 QmcDeriveKey:
    raw --Base64 解码--> rawDec（长度须 >= 16）
    rawDec = decryptV2Key(rawDec):
        若以 "QQMusic EncV2,Key:" (18 字节) 开头:
            out = TEA_decrypt(rawDec[18:], mixKey1)
            out = TEA_decrypt(out,          mixKey2)
            rawDec = Base64 解码(out)        # 长度须 >= 16
        否则原样返回
    simpleKey = simpleMakeKey(salt=106, len=8)
    teaKey[2i] = simpleKey[i], teaKey[2i+1] = rawDec[i]   (i = 0..7)
    sub = TEA_CBC_decrypt(rawDec[8:], teaKey)
    返回 rawDec[0:8] + sub

    simpleMakeKey(salt, len): key[i] = (uint8)( abs(tan(salt + i*0.1)) * 100.0 )

流密码 QmcRC4Cipher（密钥 256 字节）:
    FIRST_SEGMENT_SIZE = 0x80
    SEGMENT_SIZE       = 5120
    KSA:  S = [0..N-1]; j = 0
          for i in 0..N-1: j = (S[i] + j + key[i % N]) % N; swap(S[i], S[j])
          N = key.length
    hash: 连乘 key 的非零字节，溢出或不再增大就停
    getSegmentKey(id): seed = key[id % N]
                       idx  = floor(hash / ((id+1) * seed) * 100.0)
                       return idx % N
    首段: buf[i] ^= key[getSegmentKey(offset + i)]
    其它段: 复制 S，跳过 skipLen = (offset % SEGMENT_SIZE) + getSegmentKey(offset / SEGMENT_SIZE)
            个字节后按 RC4 PRGA 出流异或
"""

import base64
import math
import struct

# ==========================================================================
# TEA（Tiny Encryption Algorithm）—— 32 轮，大端
# ==========================================================================

TEA_DELTA = 0x9E3779B9
TEA_MASK = 0xFFFFFFFF


def _tea_key_words(key: bytes):
    if len(key) != 16:
        raise ValueError("TEA 密钥必须是 16 字节")
    return struct.unpack(">4I", key)


def tea_encrypt_block(key: bytes, block: bytes, rounds: int = 32) -> bytes:
    k0, k1, k2, k3 = _tea_key_words(key)
    v0, v1 = struct.unpack(">2I", block)
    s = 0
    for _ in range(rounds // 2):
        s = (s + TEA_DELTA) & TEA_MASK
        v0 = (v0 + ((((v1 << 4) + k0) & TEA_MASK) ^ ((v1 + s) & TEA_MASK) ^ ((v1 >> 5) + k1))) & TEA_MASK
        v1 = (v1 + ((((v0 << 4) + k2) & TEA_MASK) ^ ((v0 + s) & TEA_MASK) ^ ((v0 >> 5) + k3))) & TEA_MASK
    return struct.pack(">2I", v0, v1)


def tea_decrypt_block(key: bytes, block: bytes, rounds: int = 32) -> bytes:
    k0, k1, k2, k3 = _tea_key_words(key)
    v0, v1 = struct.unpack(">2I", block)
    s = (TEA_DELTA * (rounds // 2)) & TEA_MASK
    for _ in range(rounds // 2):
        v1 = (v1 - ((((v0 << 4) + k2) & TEA_MASK) ^ ((v0 + s) & TEA_MASK) ^ ((v0 >> 5) + k3))) & TEA_MASK
        v0 = (v0 - ((((v1 << 4) + k0) & TEA_MASK) ^ ((v1 + s) & TEA_MASK) ^ ((v1 >> 5) + k1))) & TEA_MASK
        s = (s - TEA_DELTA) & TEA_MASK
    return struct.pack(">2I", v0, v1)


# ==========================================================================
# 腾讯那套「TEA + CBC + PadLen/Salt/Zero」的密钥包装格式
# 密文格式: IV(8) + C1 + C2 ...
# 明文格式: PadLen(1) + Padding(n,0-7) + Salt(2) + Body + Zero(7)
# ==========================================================================

SALT_LEN = 2
ZERO_LEN = 7


def tencent_tea_decrypt(in_buf: bytes, key: bytes) -> bytes:
    if len(in_buf) % 8 != 0:
        raise ValueError("输入长度必须是 8 的倍数")
    if len(in_buf) < 16:
        raise ValueError("输入过短")

    tmp = tea_decrypt_block(key, in_buf[0:8], rounds=32)
    n_pad = tmp[0] & 0x07
    out_len = len(in_buf) - 1 - n_pad - SALT_LEN - ZERO_LEN
    if out_len < 0:
        raise ValueError("长度字段非法")

    out = bytearray(out_len)
    iv_prev = b"\x00" * 8
    iv_cur = in_buf[0:8]
    pos = 8
    tmp_idx = 1 + n_pad

    def crypt_block():
        nonlocal iv_prev, iv_cur, pos, tmp, tmp_idx
        iv_prev = iv_cur
        iv_cur = in_buf[pos:pos + 8]
        xored = bytes(a ^ b for a, b in zip(tmp, iv_cur))
        tmp = tea_decrypt_block(key, xored, rounds=32)
        pos += 8
        tmp_idx = 0

    # 跳过 Salt
    skipped = 1
    while skipped <= SALT_LEN:
        if tmp_idx < 8:
            tmp_idx += 1
            skipped += 1
        else:
            crypt_block()

    # 取 Body
    out_pos = 0
    while out_pos < out_len:
        if tmp_idx < 8:
            out[out_pos] = tmp[tmp_idx] ^ iv_prev[tmp_idx]
            out_pos += 1
            tmp_idx += 1
        else:
            crypt_block()

    # 校验 Zero：解出的字节必须等于上一段密文
    for i in range(1, ZERO_LEN + 1):
        if tmp[tmp_idx] != iv_prev[tmp_idx]:
            raise ValueError("zero check failed")
    return bytes(out)


def tencent_tea_encrypt(body: bytes, key: bytes) -> bytes:
    """加密方向，只用于 PC 端往返自测。

    注意：这不是标准 CBC。参考实现解密侧是
        T0 = D(C0)
        Ti = D(T(i-1) ^ Ci)     (i >= 1)
        Pi = Ti ^ C(i-1)
    反推出来的加密侧就是：
        T0 = P0;  C0 = E(T0)
        Ti = Pi ^ C(i-1);  Ci = E(Ti) ^ T(i-1)
    """
    n_pad = (8 - (len(body) + 10) % 8) % 8
    plain = bytes([n_pad]) + b"\x00" * n_pad + b"\x00" * SALT_LEN + body + b"\x00" * ZERO_LEN
    assert len(plain) % 8 == 0

    t_prev = plain[0:8]
    c_prev = tea_encrypt_block(key, t_prev, rounds=32)
    out = bytearray(c_prev)

    for i in range(8, len(plain), 8):
        p_i = plain[i:i + 8]
        t_i = bytes(a ^ b for a, b in zip(p_i, c_prev))
        e_i = tea_encrypt_block(key, t_i, rounds=32)
        c_i = bytes(a ^ b for a, b in zip(e_i, t_prev))
        out += c_i
        t_prev = t_i
        c_prev = c_i

    return bytes(out)


# ==========================================================================
# QMCv2 密钥派生
# ==========================================================================

MIX_KEY1 = bytes([0x33, 0x38, 0x36, 0x5A, 0x4A, 0x59, 0x21, 0x40,
                  0x23, 0x2A, 0x24, 0x25, 0x5E, 0x26, 0x29, 0x28])
MIX_KEY2 = bytes([0x2A, 0x2A, 0x23, 0x21, 0x28, 0x23, 0x24, 0x25,
                  0x26, 0x5E, 0x61, 0x31, 0x63, 0x5A, 0x2C, 0x54])
V2_PREFIX = b"QQMusic EncV2,Key:"  # 18 字节


def simple_make_key(salt: float, length: int) -> bytes:
    return bytes(0xFF & int(abs(math.tan(salt + i * 0.1)) * 100.0) for i in range(length))


def decrypt_v2_key(key: bytes) -> bytes:
    if len(key) < 18 or key[:18] != V2_PREFIX:
        return key
    out = tencent_tea_decrypt(key[18:], MIX_KEY1)
    out = tencent_tea_decrypt(out, MIX_KEY2)
    dec = base64.b64decode(out)
    if len(dec) < 16:
        raise ValueError("EncV2 密钥解码失败")
    return dec


def qmc_derive_key(raw: bytes) -> bytes:
    raw_dec = base64.b64decode(raw)
    if len(raw_dec) < 16:
        raise ValueError("密钥过短")
    raw_dec = decrypt_v2_key(raw_dec)

    simple_key = simple_make_key(106, 8)
    tea_key = bytearray(16)
    for i in range(8):
        tea_key[i << 1] = simple_key[i]
        tea_key[(i << 1) + 1] = raw_dec[i]

    sub = tencent_tea_decrypt(raw_dec[8:], bytes(tea_key))
    return raw_dec[:8] + sub


# ==========================================================================
# 三种流密码
# ==========================================================================

STATIC_CIPHER_BOX = bytes([
    0x77, 0x48, 0x32, 0x73, 0xDE, 0xF2, 0xC0, 0xC8, 0x95, 0xEC, 0x30, 0xB2, 0x51, 0xC3, 0xE1, 0xA0,
    0x9E, 0xE6, 0x9D, 0xCF, 0xFA, 0x7F, 0x14, 0xD1, 0xCE, 0xB8, 0xDC, 0xC3, 0x4A, 0x67, 0x93, 0xD6,
    0x28, 0xC2, 0x91, 0x70, 0xCA, 0x8D, 0xA2, 0xA4, 0xF0, 0x08, 0x61, 0x90, 0x7E, 0x6F, 0xA2, 0xE0,
    0xEB, 0xAE, 0x3E, 0xB6, 0x67, 0xC7, 0x92, 0xF4, 0x91, 0xB5, 0xF6, 0x6C, 0x5E, 0x84, 0x40, 0xF7,
    0xF3, 0x1B, 0x02, 0x7F, 0xD5, 0xAB, 0x41, 0x89, 0x28, 0xF4, 0x25, 0xCC, 0x52, 0x11, 0xAD, 0x43,
    0x68, 0xA6, 0x41, 0x8B, 0x84, 0xB5, 0xFF, 0x2C, 0x92, 0x4A, 0x26, 0xD8, 0x47, 0x6A, 0x7C, 0x95,
    0x61, 0xCC, 0xE6, 0xCB, 0xBB, 0x3F, 0x47, 0x58, 0x89, 0x75, 0xC3, 0x75, 0xA1, 0xD9, 0xAF, 0xCC,
    0x08, 0x73, 0x17, 0xDC, 0xAA, 0x9A, 0xA2, 0x16, 0x41, 0xD8, 0xA2, 0x06, 0xC6, 0x8B, 0xFC, 0x66,
    0x34, 0x9F, 0xCF, 0x18, 0x23, 0xA0, 0x0A, 0x74, 0xE7, 0x2B, 0x27, 0x70, 0x92, 0xE9, 0xAF, 0x37,
    0xE6, 0x8C, 0xA7, 0xBC, 0x62, 0x65, 0x9C, 0xC2, 0x08, 0xC9, 0x88, 0xB3, 0xF3, 0x43, 0xAC, 0x74,
    0x2C, 0x0F, 0xD4, 0xAF, 0xA1, 0xC3, 0x01, 0x64, 0x95, 0x4E, 0x48, 0x9F, 0xF4, 0x35, 0x78, 0x95,
    0x7A, 0x39, 0xD6, 0x6A, 0xA0, 0x6D, 0x40, 0xE8, 0x4F, 0xA8, 0xEF, 0x11, 0x1D, 0xF3, 0x1B, 0x3F,
    0x3F, 0x07, 0xDD, 0x6F, 0x5B, 0x19, 0x30, 0x19, 0xFB, 0xEF, 0x0E, 0x37, 0xF0, 0x0E, 0xCD, 0x16,
    0x49, 0xFE, 0x53, 0x47, 0x13, 0x1A, 0xBD, 0xA4, 0xF1, 0x40, 0x19, 0x60, 0x0E, 0xED, 0x68, 0x09,
    0x06, 0x5F, 0x4D, 0xCF, 0x3D, 0x1A, 0xFE, 0x20, 0x77, 0xE4, 0xD9, 0xDA, 0xF9, 0xA4, 0x2B, 0x76,
    0x1C, 0x71, 0xDB, 0x00, 0xBC, 0xFD, 0x0C, 0x6C, 0xA5, 0x47, 0xF7, 0xF6, 0x00, 0x79, 0x4A, 0x11,
])


class QmcStaticCipher:
    """QMCv1 无密钥：固定的 256 字节表"""

    def get_mask(self, offset: int) -> int:
        if offset > 0x7FFF:
            offset %= 0x7FFF
        return STATIC_CIPHER_BOX[(offset * offset + 27) & 0xFF]

    def decrypt(self, buf: bytearray, offset: int) -> None:
        for i in range(len(buf)):
            buf[i] ^= self.get_mask(offset + i)


class QmcMapCipher:
    """QMCv1 有 128 字节尾部密钥"""

    def __init__(self, key: bytes):
        if not key:
            raise ValueError("密钥为空")
        self.key = key
        self.n = len(key)

    @staticmethod
    def rotate(value: int, bits: int) -> int:
        r = (bits + 4) % 8  # ★ 注意 +4
        return ((value << r) | (value >> r)) & 0xFF

    def get_mask(self, offset: int) -> int:
        if offset > 0x7FFF:
            offset %= 0x7FFF
        idx = (offset * offset + 71214) % self.n
        return self.rotate(self.key[idx], idx & 7)

    def decrypt(self, buf: bytearray, offset: int) -> None:
        for i in range(len(buf)):
            buf[i] ^= self.get_mask(offset + i)


class QmcRC4Cipher:
    """QMCv2：256 字节密钥，分段 RC4"""

    FIRST_SEGMENT_SIZE = 0x80
    SEGMENT_SIZE = 5120

    def __init__(self, key: bytes):
        if not key:
            raise ValueError("密钥为空")
        self.key = key
        self.n = len(key)

        s = bytearray(self.n)
        for i in range(self.n):
            s[i] = i & 0xFF
        j = 0
        for i in range(self.n):
            j = (s[i] + j + self.key[i % self.n]) % self.n
            s[i], s[j] = s[j], s[i]
        self.s = bytes(s)

        # hash：连乘非零密钥字节，溢出或不再增大即停
        h = 1
        for i in range(self.n):
            v = self.key[i]
            if v == 0:
                continue
            nxt = (h * v) & 0xFFFFFFFF
            if nxt == 0 or nxt <= h:
                break
            h = nxt
        self.hash = h

    def get_segment_key(self, seg_id: int) -> int:
        seed = self.key[seg_id % self.n]
        # ★ 与参考实现的差异：参考实现没处理 seed == 0，会算出 Infinity -> NaN，
        #   进而让该分段的循环一次都不执行（等于不解密）。这里显式返回 0（不做跳过），
        #   行为更合理。真实 QMCv2 密钥里是否会出现 0 字节，需要用真实样本确认。
        if seed == 0:
            return 0
        idx = int((self.hash / ((seg_id + 1) * seed)) * 100.0)
        return idx % self.n

    def decrypt(self, buf: bytearray, offset: int) -> None:
        to_process = len(buf)
        processed = 0

        if offset < self.FIRST_SEGMENT_SIZE:
            ln = min(to_process, self.FIRST_SEGMENT_SIZE - offset)
            for i in range(ln):
                buf[processed + i] ^= self.key[self.get_segment_key(offset + i)]
            to_process -= ln
            processed += ln
            offset += ln
            if to_process == 0:
                return

        if offset % self.SEGMENT_SIZE != 0:
            ln = min(self.SEGMENT_SIZE - (offset % self.SEGMENT_SIZE), to_process)
            self._enc_segment(buf, processed, ln, offset)
            to_process -= ln
            processed += ln
            offset += ln
            if to_process == 0:
                return

        while to_process > self.SEGMENT_SIZE:
            self._enc_segment(buf, processed, self.SEGMENT_SIZE, offset)
            to_process -= self.SEGMENT_SIZE
            processed += self.SEGMENT_SIZE
            offset += self.SEGMENT_SIZE

        if to_process > 0:
            self._enc_segment(buf, processed, to_process, offset)

    def _enc_segment(self, buf: bytearray, base: int, length: int, offset: int) -> None:
        s = bytearray(self.s)
        skip = (offset % self.SEGMENT_SIZE) + self.get_segment_key(offset // self.SEGMENT_SIZE)
        j = 0
        k = 0
        for i in range(-skip, length):
            j = (j + 1) % self.n
            k = (s[j] + k) % self.n
            s[k], s[j] = s[j], s[k]
            if i >= 0:
                buf[base + i] ^= s[(s[j] + s[k]) % self.n]
