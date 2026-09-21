"""用 Python 复现 ArtworkLoader.extractTint 的取样逻辑，喂一张黑白封面进去，
确认「严格取样取不到时会退回宽松取样」，从而不会落到皮肤色。

用法：python tools/verify-tint.py shots/bw-cover.png
"""
import sys
import colorsys
from PIL import Image

N = 24
im = Image.open(sys.argv[1]).convert('RGB').resize((N, N), Image.LANCZOS)
px = im.load()


def avg(x0, x1, strict):
    y0 = N * 2 // 3          # 只看下边缘 1/3
    r = g = b = n = 0
    for y in range(y0, N):
        for x in range(x0, x1):
            rr, gg, bb = px[x, y]
            if strict:
                if max(rr, gg, bb) < 24:
                    continue
                if min(rr, gg, bb) > 232:
                    continue
            r += rr
            g += gg
            b += bb
            n += 1
    if n == 0:
        return None
    return (r // n, g // n, b // n)


def normalize(rgb):
    r, g, b = [c / 255 for c in rgb]
    h, l, s = colorsys.rgb_to_hls(r, g, b)
    s = min(max(s, 0.12), 0.85)
    l = min(max(l, 0.36), 0.60)
    r, g, b = colorsys.hls_to_rgb(h, l, s)
    return tuple(round(c * 255) for c in (r, g, b))


seg = N // 3
for strict in (True, False):
    segs = [avg(0, seg, strict), avg(seg, seg * 2, strict), avg(seg * 2, N, strict)]
    print(f'strict={strict!s:5} 取样: {segs}')

strict_segs = [avg(0, seg, True), avg(seg, seg * 2, True), avg(seg * 2, N, True)]
if any(s is None for s in strict_segs):
    final = [avg(0, seg, False), avg(seg, seg * 2, False), avg(seg * 2, N, False)]
    print('→ 严格取样不全，退回宽松取样:', final)
else:
    final = strict_segs
print('→ 归一化后的色卡:', [None if s is None else normalize(s) for s in final])
