"""
PC 端模拟测试：用「加密 -> 解密 -> 比对」的方式验证各格式的算法实现。

说明：手头没有真实的 .kwm / .ncm 样本文件，所以这里测的是
「实现是否与文档化的算法自洽」。它能抓出编码错误，
但**不能**证明公式本身与真实厂商文件一致（那需要真实样本）。
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import kwm
import ncm

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


def fake_flac(n=4096):
    return b"fLaC" + bytes((i * 37 + 11) & 0xFF for i in range(n - 4))


def fake_mp3(n=4096):
    return b"ID3\x03\x00\x00\x00\x00\x00\x20" + bytes((i * 53 + 7) & 0xFF for i in range(n - 10))


# ==========================================================================
print("=" * 66)
print("KWM (酷我) 往返测试")
print("=" * 66)

plain = fake_flac()
recipe = (0x0123456789ABCDEF).to_bytes(8, "little")
blob = kwm.encrypt(plain, recipe, suffix=b"flac")

check("魔数可识别", kwm.detect(blob))
check("后缀提示 = flac", kwm.suffix_hint(blob) == "flac", kwm.suffix_hint(blob))
check("密文与明文不同", blob[kwm.HEADER_SIZE:] != plain)
out = kwm.decrypt(blob)
check("解密结果 == 明文", out == plain, f"len {len(out)} vs {len(plain)}")
check("解密后是 FLAC", out[:4] == b"fLaC")

# 另一组配方 / mp3
plain2 = fake_mp3()
recipe2 = (987654321).to_bytes(8, "little")
blob2 = kwm.encrypt(plain2, recipe2, suffix=b"128mp3")
out2 = kwm.decrypt(blob2)
check("第二组（mp3）往返一致", out2 == plain2)
check("后缀提示 = mp3", kwm.suffix_hint(blob2) == "mp3", kwm.suffix_hint(blob2))

# 掩码派生：十进制字符串取模（长度 < 32 时循环使用）
m = kwm.derive_mask((123).to_bytes(8, "little"))
check("掩码长度 = 32", len(m) == 32)
check("掩码 = ROOT[i] ^ \"123\"[i%3]", m == bytes(
    kwm.ROOT[i] ^ b"123"[i % 3] for i in range(32)))


# ==========================================================================
print()
print("=" * 66)
print("NCM (网易云) 往返测试")
print("=" * 66)

rc4_key = bytes.fromhex("00112233445566778899AABBCCDDEEFF") * 4  # 64 字节
meta = {"musicName": "Test Song", "artist": [["Someone", 1]], "format": "flac"}
audio = fake_flac(8192)

blob = ncm.build(audio, rc4_key, meta)
check("魔数可识别", ncm.detect(blob))
out, ext, got_meta = ncm.decrypt(blob)
check("解密结果 == 明文", out == audio, f"len {len(out)} vs {len(audio)}")
check("后缀来自元数据 = flac", ext == "flac", ext)
check("元数据还原正确", got_meta.get("musicName") == "Test Song"
      and got_meta.get("format") == "flac", str(got_meta))

# 带封面 + 补白
image = bytes((i * 7) & 0xFF for i in range(300))
blob_img = ncm.build(audio, rc4_key, meta, image=image)
out_img, ext_img, _ = ncm.decrypt(blob_img)
check("带封面时往返一致", out_img == audio)
check("带封面时后缀正确", ext_img == "flac")

# 无元数据 -> 必须靠嗅探
blob_nometa = ncm.build(audio, rc4_key, meta={})
out_nm, ext_nm, meta_nm = ncm.decrypt(blob_nometa)
check("无元数据时仍能解出音频", out_nm == audio)
check("无元数据时靠嗅探得到 flac", ext_nm == "flac", ext_nm)
check("无元数据时 meta 为空", meta_nm == {})

# 无元数据 + mp3 内容
audio_mp3 = fake_mp3(4096)
blob_mp3 = ncm.build(audio_mp3, rc4_key, meta={})
out_mp3, ext_mp3, _ = ncm.decrypt(blob_mp3)
check("无元数据 + mp3 内容 -> 嗅探为 mp3", out_mp3 == audio_mp3 and ext_mp3 == "mp3", ext_mp3)

# RC4 密钥流：确认与公式一致（前 8 字节手算比对）
ks = ncm.rc4_keystream(bytes([1, 2, 3]), 8)
box = list(range(256))
j = 0
for i in range(256):
    j = (box[i] + j + bytes([1, 2, 3])[i % 3]) & 0xFF
    box[i], box[j] = box[j], box[i]
manual = []
for p in range(8):
    jj = (p + 1) & 0xFF
    manual.append(box[(box[jj] + box[(box[jj] + jj) & 0xFF]) & 0xFF])
check("RC4 密钥流与公式一致", list(ks) == manual)


# ==========================================================================
print()
print("=" * 66)
print(f"结果：PASS {PASS}   FAIL {FAIL}")
print("=" * 66)
sys.exit(1 if FAIL else 0)
