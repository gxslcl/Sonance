"""往应用的「在线封面缓存」里塞几张**纯色封面**，用来在模拟器上造出真实封面场景。

为什么要它：模拟器曲库里的 WAV 没有内嵌封面，晕染只能回落到皮肤的占位配色
（非常暗），于是「切歌时背景闪一下」这种缺陷在截图里**量不出来** —— 实测背景
亮度只有 18~26，几乎就是皮肤底色。给几首歌注入高饱和封面之后，晕染才会像
真机上那样明显，过渡缺陷才有可测量的幅度。

原理：`CoverCache` 就是 `files/cover_cache/<sha1("cover|v2|标题|歌手")>.jpg`，
非空文件即视为"有封面"，`ArtworkLoader.decode()` 会读它并按它取色
（存成 `t:c<key>` 色卡，优先级高于本地专辑封面）。

用法：
  python tools/inject-covers.py                 # 注入默认的 6 首
  python tools/inject-covers.py "03 Velvet Dreams"
"""
import hashlib
import io
import os
import subprocess
import sys
import tempfile
import time

from PIL import Image, ImageDraw

ADB = "C:/Android/Sdk/platform-tools/adb.exe"
PKG = "com.sonance.player"

# 歌名 → (歌手, 封面渐变色[上, 下])。每首一个明显不同的色相，
# 且下半部分要够亮 —— 色卡取的是封面**下边缘**三段。
#
# ⚠️ 歌手必须**逐首写对**：缓存 key 是 `sha1("cover|v2|歌名|歌手")`，
# 歌手写错就会注入到另一个 key 上，而应用照旧读它自己那个 key ——
# 表现是"文件明明注入进去了，界面/写回却还是旧内容"，非常难查
# （本轮就这么误解了一轮：把「Write Test」的歌手写成「未知艺术家」，
#   于是写回时用的是上一轮留下的另一个缓存文件）。
#
# 「12 Raw Form」也要有：冒烟测试默认落在恢复出来的那首歌上，
# 少一首就会拿到"几乎没有颜色"的兜底配色，那一档等于白测。
COLORS = {
    "01 Midnight Horizon": ("未知艺术家", (232, 132, 32), (255, 186, 92)),    # 暖琥珀
    "02 Golden Hour": ("未知艺术家", (28, 168, 158), (96, 226, 206)),         # 青绿
    "03 Velvet Dreams": ("未知艺术家", (198, 62, 176), (244, 140, 226)),      # 品红
    "04 Concentric": ("未知艺术家", (64, 108, 236), (132, 176, 255)),         # 蓝
    "06 Concrete Lines": ("未知艺术家", (96, 186, 48), (168, 236, 120)),      # 绿
    "07 Dawn Chorus": ("未知艺术家", (236, 88, 64), (255, 158, 128)),         # 砖红
    "08 Afterglow": ("未知艺术家", (240, 196, 32), (255, 232, 128)),          # 金黄
    "09 Shadow Dance": ("未知艺术家", (128, 72, 232), (186, 140, 255)),       # 紫
    "10 Analog Heart": ("未知艺术家", (232, 96, 152), (255, 158, 196)),       # 粉
    "11 Deep Current": ("未知艺术家", (24, 128, 216), (96, 186, 248)),        # 天蓝
    "12 Raw Form": ("未知艺术家", (176, 152, 40), (226, 206, 104)),           # 土黄
    "Write Test": ("测试歌手", (40, 168, 96), (120, 226, 152)),               # 草绿
}


def key_of(title: str, artist: str) -> str:
    raw = f"{title.strip().lower()}|{artist.strip().lower()}"
    return hashlib.sha1(f"cover|v2|{raw}".encode("utf-8")).hexdigest()


def make_jpeg(c_top, c_bottom) -> bytes:
    """上半深、下半亮 —— 和真实封面一样是纵向渐变，取色只取下面 1/3。"""
    im = Image.new("RGB", (800, 800))
    d = ImageDraw.Draw(im)
    for y in range(800):
        t = y / 799
        d.line(
            [(0, y), (800, y)],
            fill=tuple(int(c_top[i] + (c_bottom[i] - c_top[i]) * t) for i in range(3)),
        )
    # 加一点几何图形，免得看起来像纯色块（也顺便验证取色确实取了下方）
    d.ellipse([240, 120, 560, 440], fill=tuple(int(v * 1.15) for v in c_bottom[:3]) if max(c_bottom) < 230 else c_bottom)
    buf = io.BytesIO()
    im.save(buf, "JPEG", quality=92)
    return buf.getvalue()


def adb(args, binary=False):
    for _ in range(5):
        p = subprocess.run([ADB] + args, capture_output=True)
        out = p.stdout if binary else p.stdout.decode("utf-8", "replace")
        err = p.stderr.decode("utf-8", "replace")
        if "offline" in err or "no devices" in err:
            subprocess.run([ADB, "start-server"], capture_output=True)
            time.sleep(1.5)
            continue
        return out + ("" if binary else err)
    raise RuntimeError("adb 重试耗尽")


def main():
    titles = sys.argv[1:] or list(COLORS.keys())
    for title in titles:
        artist, c_top, c_bottom = COLORS[title]
        key = key_of(title, artist)
        data = make_jpeg(c_top, c_bottom)
        # 生成物放临时目录、用完就删 —— 项目根目录不该留一堆测试图片
        name = os.path.join(tempfile.gettempdir(), f"sonance-cover-{key}.jpg")
        with open(name, "wb") as f:
            f.write(data)
        adb(["push", name, f"/data/local/tmp/{key}.jpg"])
        os.remove(name)
        adb(["shell", "chmod", "666", f"/data/local/tmp/{key}.jpg"])
        adb(["shell", "run-as", PKG, "mkdir", "-p", "files/cover_cache"])
        adb(["shell", "run-as", PKG, "cp",
             f"/data/local/tmp/{key}.jpg", f"files/cover_cache/{key}.jpg"])
        print(f"{title}  ->  {key}.jpg  {len(data)} bytes  {c_bottom}")
    adb(["shell", "run-as", PKG, "ls", "-l", "files/cover_cache"])
    print("\n注入完成。重启应用后生效：")
    print("  adb shell am force-stop " + PKG)


if __name__ == "__main__":
    main()
