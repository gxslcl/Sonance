"""
酷我音乐 .kwm 解密 —— 实现原理与参考实现

格式（1024 字节头 + 异或载荷）:
    0x000  12 字节  魔数 "yeelion-kuwo"（旧版为 "yeelion-kuwo-tme"，16 字节）
    0x018   8 字节  「配方」recipe，按小端 uint64 解释
    0x030   8 字节  码率 + 后缀，例如 "320mp3" / "flac"（可作后缀判定的兜底）
    0x400   ...     异或后的音频

掩码派生（32 字节）:
    n   = int.from_bytes(recipe, "little")     # 8 字节小端无符号
    s   = str(n).encode("ascii")               # 十进制字符串
    mask[i] = ROOT[i] ^ s[i % len(s)]          # ROOT 为 32 字节固定串

解密:
    out[i] = data[0x400 + i] ^ mask[i % 32]
"""

MAGIC = b"yeelion-kuwo"
MAGIC_OLD = b"yeelion-kuwo-tme"
HEADER_SIZE = 0x400
ROOT = b"MoOtOiTvINGwd2E6n0E1i7L5t2IoOoNk"  # 32 字节固定串

RECIPE_OFF = 0x18
SUFFIX_OFF = 0x30


def derive_mask(recipe: bytes) -> bytes:
    """8 字节配方 -> 32 字节掩码"""
    if len(recipe) != 8:
        raise ValueError("recipe 必须是 8 字节")
    n = int.from_bytes(recipe, "little")
    s = str(n).encode("ascii")
    return bytes(ROOT[i] ^ s[i % len(s)] for i in range(32))


def detect(data: bytes) -> bool:
    return data[:16] == MAGIC_OLD or data[:12] == MAGIC


def suffix_hint(data: bytes) -> str:
    """头部 0x30 处写着的「码率+后缀」，例如 "320mp3" -> "mp3"、"flac" -> "flac"

    形式是「前导数字(码率) + 后缀」，所以要剥掉前导数字。
    """
    raw = data[SUFFIX_OFF:SUFFIX_OFF + 8].split(b"\x00", 1)[0]
    i = 0
    while i < len(raw) and 48 <= raw[i] <= 57:
        i += 1
    return raw[i:].decode("ascii", "ignore")


def decrypt(data: bytes) -> bytes:
    if not detect(data):
        raise ValueError("不是 KWM 文件（魔数不匹配）")
    if len(data) <= HEADER_SIZE:
        raise ValueError("文件过小")
    mask = derive_mask(data[RECIPE_OFF:RECIPE_OFF + 8])
    body = data[HEADER_SIZE:]
    return bytes(b ^ mask[i & 31] for i, b in enumerate(body))


# --------------------------------------------------------------------------
# 加密方向：只用于 PC 端往返自测
# --------------------------------------------------------------------------

def encrypt(plain: bytes, recipe: bytes, suffix: bytes = b"320mp3") -> bytes:
    header = bytearray(HEADER_SIZE)
    header[0:16] = MAGIC_OLD
    header[RECIPE_OFF:RECIPE_OFF + 8] = recipe
    header[SUFFIX_OFF:SUFFIX_OFF + 8] = suffix.ljust(8, b"\x00")[:8]
    mask = derive_mask(recipe)
    body = bytes(b ^ mask[i & 31] for i, b in enumerate(plain))
    return bytes(header) + body
