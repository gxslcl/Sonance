"""
PC 端模拟测试：QMC 家族（TEA / 密钥派生 / 三种流密码）
"""

import base64
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import qmc

PASS = 0
FAIL = 0


def check(name, cond, detail=""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  [PASS] {name}")
    else:
        FAIL += 1
        print(f"  [FAIL] {name}  {detail}")


print("=" * 66)
print("QMC / TEA 测试")
print("=" * 66)

# ---- 1. TEA 块加解密往返 ----
key16 = bytes.fromhex("00112233445566778899AABBCCDDEEFF")
block = bytes.fromhex("0123456789ABCDEF")
enc = qmc.tea_encrypt_block(key16, block, rounds=32)
dec = qmc.tea_decrypt_block(key16, enc, rounds=32)
check("TEA 32 轮往返一致", dec == block, f"{enc.hex()} -> {dec.hex()}")
check("TEA 密文 != 明文", enc != block)

# ---- 2. simpleMakeKey ----
import math
sk = qmc.simple_make_key(106, 8)
manual = bytes(0xFF & int(abs(math.tan(106 + i * 0.1)) * 100.0) for i in range(8))
check("simpleMakeKey(106,8) 与公式一致", sk == manual, sk.hex())
check("simpleMakeKey 长度 = 8", len(sk) == 8)

# ---- 3. 腾讯 TEA-CBC 包装往返 ----
for body in [b"", b"x" * 7, b"y" * 24, bytes(range(64))]:
    c = qmc.tencent_tea_encrypt(body, key16)
    p = qmc.tencent_tea_decrypt(c, key16)
    check(f"腾讯 TEA-CBC 往返 (body={len(body)}B)", p == body, f"got {len(p)}B")
check("腾讯 TEA-CBC 密文长度是 8 的倍数",
      len(qmc.tencent_tea_encrypt(b"abc", key16)) % 8 == 0)

# ---- 4. QMCv2 密钥派生（走 EncV2 分支）----
prefix8 = bytes.fromhex("AABBCCDDEEFF0011")
target_body = bytes((i * 13 + 5) & 0xFF for i in range(24))
target = prefix8 + target_body

simple = qmc.simple_make_key(106, 8)
tea_key = bytearray(16)
for i in range(8):
    tea_key[i << 1] = simple[i]
    tea_key[(i << 1) + 1] = prefix8[i]

x = prefix8 + qmc.tencent_tea_encrypt(target_body, bytes(tea_key))
blob = (qmc.V2_PREFIX
        + qmc.tencent_tea_encrypt(
            qmc.tencent_tea_encrypt(base64.b64encode(x), qmc.MIX_KEY2),
            qmc.MIX_KEY1))
raw = base64.b64encode(blob)

derived = qmc.qmc_derive_key(raw)
check("EncV2 密钥派生端到端一致", derived == target,
      f"got {derived.hex()} want {target.hex()}")

# 非 EncV2：直接是 base64(prefix8 + TEA(body))
x_plain = prefix8 + qmc.tencent_tea_encrypt(target_body, bytes(tea_key))
raw_plain = base64.b64encode(x_plain)
derived2 = qmc.qmc_derive_key(raw_plain)
check("非 EncV2 密钥派生一致", derived2 == target, derived2.hex())

# ---- 5. 静态流密码（QMCv1 无密钥）----
data = bytearray(bytes((i * 7 + 3) & 0xFF for i in range(5000)))
orig = bytes(data)
c = qmc.QmcStaticCipher()
c.decrypt(data, 0)
check("静态密码改了数据", bytes(data) != orig)
c.decrypt(data, 0)
check("静态密码可逆（再异或一次还原）", bytes(data) == orig)
check("静态掩码公式抽查", c.get_mask(0) == qmc.STATIC_CIPHER_BOX[27]
      and c.get_mask(10) == qmc.STATIC_CIPHER_BOX[(100 + 27) & 0xFF])

# ---- 6. 映射流密码（QMCv1 尾部 128 字节密钥）----
key128 = bytes((i * 11 + 7) & 0xFF for i in range(128))
data = bytearray(bytes((i * 5 + 1) & 0xFF for i in range(9000)))
orig = bytes(data)
m = qmc.QmcMapCipher(key128)
m.decrypt(data, 0)
check("映射密码改了数据", bytes(data) != orig)
m.decrypt(data, 0)
check("映射密码可逆", bytes(data) == orig)
# rotate 的旋转量是 (bits+4)%8
check("rotate(v,0) == ror4", qmc.QmcMapCipher.rotate(0x12, 0) == (((0x12 << 4) | (0x12 >> 4)) & 0xFF))

# ---- 7. RC4 分段密码（QMCv2）----
key256 = bytes((i * 17 + 3) & 0xFF for i in range(256))
data = bytearray(bytes((i * 29 + 9) & 0xFF for i in range(20000)))
orig = bytes(data)
r = qmc.QmcRC4Cipher(key256)
r.decrypt(data, 0)
check("RC4 密码改了数据", bytes(data) != orig)
r.decrypt(data, 0)
check("RC4 密码可逆（跨 0x80 与 5120 分段）", bytes(data) == orig)

# 非零偏移 + 分块调用：应与整块解密结果一致
src = bytes((i * 31 + 2) & 0xFF for i in range(13000))

whole2 = bytearray(src)
qmc.QmcRC4Cipher(key256).decrypt(whole2, 0)

chunked = bytearray(src)
r2 = qmc.QmcRC4Cipher(key256)
pos = 0
for step in (100, 300, 5000, 7000, 600):
    r2.decrypt(memoryview(chunked)[pos:pos + step], pos)
    pos += step
if pos < len(chunked):
    r2.decrypt(memoryview(chunked)[pos:], pos)
check("RC4 分块 + 非零偏移与整块结果一致", bytes(chunked) == bytes(whole2))

print()
print("=" * 66)
print(f"结果：PASS {PASS}   FAIL {FAIL}")
print("=" * 66)
sys.exit(1 if FAIL else 0)
