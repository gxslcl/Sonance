"""
网易云音乐 .ncm 解密 —— 实现原理与参考实现

头部布局:
    0x00  8 字节  魔数 "CTENFDAM"
    0x08  2 字节  保留
    0x0A  4 字节  密钥块长度（uint32 LE）
          n 字节  密钥块
          4 字节  元数据块长度（uint32 LE）
          n 字节  元数据块
          4 字节  CRC32（忽略）
          1 字节  保留
          4 字节  封面帧长度 coverFrameLen（uint32 LE）
          4 字节  封面实际长度 imageSize（uint32 LE）
          n 字节  封面图（JPEG/PNG）
    补白 = coverFrameLen - imageSize
          ->      音频数据
    音频偏移 = 元数据结束 + 13 + coverFrameLen

密钥派生:
    密钥块 --每字节 XOR 0x64--> AES-128-ECB 解密(CORE_KEY) --去填充-->
    砍掉前 17 字节 "neteasecloudmusic" -> RC4 密钥

元数据:
    元数据块 --每字节 XOR 0x63--> 砍掉前 22 字节 "163 key(Don't modify):"
    --> Base64 解码 --> AES-128-ECB 解密(META_KEY) --去填充-->
    砍掉 "music:" 前缀 -> JSON（其中 format 字段是后缀的权威来源）

音频解密（注意：不是 AES，是改造版 RC4 密钥流异或）:
    KSA:  box = [0..255]; j = 0
          for i in 0..255: j = (box[i] + j + key[i % keyLen]) & 0xFF; swap(box[i], box[j])
    PRGA: 对音频第 p 字节
          j  = (p + 1) & 0xFF
          ks = box[(box[j] + box[(box[j] + j) & 0xFF]) & 0xFF]
          audio[p] ^= ks
"""

import base64
import json
import struct

from Crypto.Cipher import AES
from Crypto.Util.Padding import pad, unpad

MAGIC = b"CTENFDAM"
CORE_KEY = bytes.fromhex("687A4852416D736F356B496E62617857")  # "hzHRAmso5kInbaxW"
META_KEY = bytes.fromhex("2331346C6A6B5F215C5D2630553C2728")  # "#14ljk_!\\][&0U<('"
KEY_PREFIX = b"neteasecloudmusic"          # 17 字节
META_PREFIX = b"163 key(Don't modify):"    # 22 字节


def rc4_keystream(key: bytes, n: int) -> bytes:
    if not key:
        raise ValueError("RC4 密钥为空")
    box = list(range(256))
    j = 0
    klen = len(key)
    for i in range(256):
        j = (box[i] + j + key[i % klen]) & 0xFF
        box[i], box[j] = box[j], box[i]

    out = bytearray(n)
    for p in range(n):
        jj = (p + 1) & 0xFF
        out[p] = box[(box[jj] + box[(box[jj] + jj) & 0xFF]) & 0xFF]
    return bytes(out)


def xor_all(data: bytes, k: int) -> bytes:
    return bytes(b ^ k for b in data)


def sniff(buf: bytes) -> str:
    if buf[:4] == b"fLaC":
        return "flac"
    if buf[:3] == b"ID3":
        return "mp3"
    if len(buf) >= 2 and buf[0] == 0xFF and (buf[1] & 0xE0) == 0xE0:
        return "mp3"
    if buf[:4] == b"OggS":
        return "ogg"
    return "mp3"


def detect(data: bytes) -> bool:
    return data[:8] == MAGIC


def decrypt(data: bytes):
    """返回 (音频字节, 后缀, 元数据 dict)"""
    if not detect(data):
        raise ValueError("不是 NCM 文件（魔数不匹配）")

    off = 8 + 2  # 魔数 + 2 字节保留

    # ---- 密钥块 ----
    key_len = struct.unpack_from("<I", data, off)[0]
    off += 4
    key_blob = data[off:off + key_len]
    off += key_len

    plain = unpad(AES.new(CORE_KEY, AES.MODE_ECB).decrypt(xor_all(key_blob, 0x64)), 16)
    if not plain.startswith(KEY_PREFIX):
        raise ValueError("密钥块前缀不符，可能不是标准 NCM")
    rc4_key = plain[len(KEY_PREFIX):]

    # ---- 元数据块 ----
    meta_len = struct.unpack_from("<I", data, off)[0]
    off += 4
    meta = {}
    if meta_len > 0:
        blob = data[off:off + meta_len]
        off += meta_len
        x = xor_all(blob, 0x63)
        if not x.startswith(META_PREFIX):
            raise ValueError("元数据前缀不符")
        dec = unpad(AES.new(META_KEY, AES.MODE_ECB).decrypt(
            base64.b64decode(x[len(META_PREFIX):])
        ), 16)
        text = dec.decode("utf-8")
        meta = json.loads(text.split(":", 1)[1])

    # ---- 封面区 ----
    cover_frame_len = struct.unpack_from("<I", data, off + 5)[0]
    audio_off = off + 13 + cover_frame_len

    # ---- 音频 ----
    body = data[audio_off:]
    ks = rc4_keystream(rc4_key, len(body))
    audio = bytes(b ^ k for b, k in zip(body, ks))

    ext = meta.get("format") or sniff(audio)
    return audio, ext, meta


# --------------------------------------------------------------------------
# 加密方向：只用于 PC 端往返自测
# --------------------------------------------------------------------------

def build(audio: bytes, rc4_key: bytes, meta: dict, image: bytes = b"") -> bytes:
    key_plain = pad(KEY_PREFIX + rc4_key, 16)
    key_enc = AES.new(CORE_KEY, AES.MODE_ECB).encrypt(key_plain)
    key_blob = xor_all(key_enc, 0x64)

    meta_text = "music:" + json.dumps(meta, separators=(",", ":"), ensure_ascii=False)
    meta_enc = AES.new(META_KEY, AES.MODE_ECB).encrypt(pad(meta_text.encode("utf-8"), 16))
    meta_blob = xor_all(META_PREFIX + base64.b64encode(meta_enc), 0x63)

    ks = rc4_keystream(rc4_key, len(audio))
    enc_audio = bytes(b ^ k for b, k in zip(audio, ks))

    out = bytearray()
    out += MAGIC
    out += b"\x01\x61"                                    # 2 字节保留
    out += struct.pack("<I", len(key_blob)) + key_blob
    out += struct.pack("<I", len(meta_blob)) + meta_blob
    out += struct.pack("<I", 0)                           # CRC32（不校验）
    out += b"\x00"                                        # 1 字节保留
    out += struct.pack("<I", len(image))                  # coverFrameLen = 封面 + 补白
    out += struct.pack("<I", len(image))                  # imageSize
    out += image
    out += enc_audio
    return bytes(out)
