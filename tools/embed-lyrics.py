# -*- coding: utf-8 -*-
"""
给一个音频文件前挂一个 ID3v2.3 标签（含 USLT 歌词），用来验证
「律动柱页面的 3 行歌词」这条链路。

为什么用自己写的 ID3 组装而不是 mutagen：项目里的 `AudioTags` 是**自己解析**
ID3v2 的（不依赖 Android），所以这里按 `Id3Writer.kt` 的字节布局手工拼一遍，
等于顺带验证了"写进去的能被自己读出来"。

用法：
    python tools/embed-lyrics.py <输入音频> <输出音频>
"""
import sys
import struct

LRC = "\n".join([
    "[00:00.00]夜色渐深 城市开始呼吸",
    "[00:04.00]路灯把影子拉得很长",
    "[00:08.00]我数着窗外的最后一班车",
    "[00:12.00]鼓点在胸口轻轻地敲",
    "[00:16.00]所有声音都退到背景里",
    "[00:20.00]只剩这一句还在循环",
    "[00:24.00]天亮之前 谁也不说话",
])


def utf16(text):
    """编码字节(0x01) + BOM + UTF-16LE"""
    return b"\x01\xff\xfe" + text.encode("utf-16-le")


def frame(fid, payload):
    """帧头：ID(4) + 长度(4，普通大端) + flags(2)"""
    return fid.encode("ascii") + struct.pack(">I", len(payload)) + b"\x00\x00" + payload


def text_frame(fid, text):
    return frame(fid, utf16(text))


def uslt_frame(text):
    """USLT = 编码(1) + 语言(3) + 描述(BOM + 终止符) + 正文(BOM + UTF-16LE)"""
    payload = (b"\x01" + b"XXX" + b"\xff\xfe\x00\x00" + b"\xff\xfe")
    return frame("USLT", payload + text.encode("utf-16-le"))


def syncsafe(n):
    return bytes([(n >> 21) & 0x7F, (n >> 14) & 0x7F, (n >> 7) & 0x7F, n & 0x7F])


def build_tag(title, artist, album, lyrics):
    body = (text_frame("TIT2", title) + text_frame("TPE1", artist)
            + text_frame("TALB", album) + uslt_frame(lyrics))
    return b"ID3\x03\x00\x00" + syncsafe(len(body)) + body


def strip_existing_tag(data):
    """把文件开头原有的 ID3v2 标签剥掉（一个文件只能有一个）"""
    if data[:3] != b"ID3":
        return data
    size = ((data[6] & 0x7F) << 21) | ((data[7] & 0x7F) << 14) \
        | ((data[8] & 0x7F) << 7) | (data[9] & 0x7F)
    footer = 10 if data[5] & 0x10 else 0
    return data[10 + size + footer:]


def main():
    src, dst = sys.argv[1], sys.argv[2]
    title = sys.argv[3] if len(sys.argv) > 3 else "Shadow Dance"
    artist = sys.argv[4] if len(sys.argv) > 4 else "Sonance Test"
    album = sys.argv[5] if len(sys.argv) > 5 else "Verification"
    with open(src, "rb") as f:
        audio = strip_existing_tag(f.read())
    tag = build_tag(title, artist, album, LRC)
    with open(dst, "wb") as f:
        f.write(tag + audio)
    print("写入 %d 字节标签 + %d 字节音频 -> %s" % (len(tag), len(audio), dst))


if __name__ == "__main__":
    main()
