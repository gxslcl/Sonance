"""
截图竖条缺陷的量化探针（Pillow 版）。

对图片按列求平均亮度 → 游程分段 → 打印每个「亮度平台」的位置与宽度。
竖条的判据：出现一段与左右两侧都不同的平台，且两侧是硬边。

用法：
    python tools/seam-analyze.py <图片...> [--y0 200] [--y1 1900] [--label 名称]
"""
import sys
import numpy as np
from PIL import Image

args = sys.argv[1:]
paths = []
y0, y1 = 200, 1900
i = 0
while i < len(args):
    if args[i] == '--y0':
        y0 = int(args[i + 1]); i += 2
    elif args[i] == '--y1':
        y1 = int(args[i + 1]); i += 2
    else:
        paths.append(args[i]); i += 1


def profile(path):
    im = Image.open(path).convert('L')
    a = np.asarray(im, dtype=np.float32)
    h, w = a.shape
    y0c, y1c = min(y0, h - 1), min(y1, h)
    col = a[y0c:y1c].mean(axis=0)
    return w, h, col, y0c, y1c


def segments(col, tol=1.0, min_run=10):
    out = []
    start = 0
    acc = col[0]
    for x in range(1, len(col)):
        if abs(col[x] - acc / (x - start)) <= tol:
            acc += col[x]
            continue
        out.append((start, x - 1, acc / (x - start)))
        start, acc = x, col[x]
    out.append((start, len(col) - 1, acc / (len(col) - start)))
    merged = []
    for s, e, m in out:
        if (e - s + 1) < min_run and merged:
            ps, pe, pm = merged[-1]
            wprev = pe - ps + 1
            wcur = e - s + 1
            merged[-1] = (ps, e, (pm * wprev + m * wcur) / (wprev + wcur))
        else:
            merged.append((s, e, m))
    return merged


for p in paths:
    w, h, col, y0c, y1c = profile(p)
    print(f'\n=== {p.split("/")[-1]}  {w}x{h}  y∈[{y0c},{y1c}) ===')
    print('  亮度平台（宽度 ≥ 20px）:')
    for s, e, m in segments(col):
        wd = e - s + 1
        if wd < 20:
            continue
        print(f'    {s:5d}..{e:5d}  宽 {wd:4d} ({100*wd/w:5.1f}%)  亮度 {m:6.1f}')

    # 逐列一阶差分的显著峰 —— 定位硬边
    d = np.diff(col)
    idx = np.argsort(-np.abs(d))[:10]
    print('  最强的列间跳变:')
    for k in sorted(idx):
        print(f'    x={k+1:5d}  Δ={d[k]:+7.2f}')
