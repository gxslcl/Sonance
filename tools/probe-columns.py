"""
检查截图里有没有「竖条」（横向的周期性亮度跳变）。

晕染曾经是「横向切 32 段、每段一条纵向渐变」画出来的，段与段的交界处
像素会被两个矩形各画一次，屏幕上就出现一排等距竖线。
现在改成一张位图拉伸，理论上相邻列应该是连续的。

用法：
  python tools/probe-columns.py <png> <y0> <y1> [标签] [放大图输出名]
"""
import sys
from PIL import Image


def analyze(path, y0, y1, label='', zoom=None):
    im = Image.open(path).convert('RGB')
    W, H = im.size
    px = im.load()
    cols = []
    for x in range(W):
        s = 0
        for y in range(y0, y1):
            r, g, b = px[x, y]
            s += r + g + b
        cols.append(s / (y1 - y0) / 3.0)

    mean = sum(cols) / len(cols)
    print('== %s  (%s)  y=[%d,%d]  尺寸 %dx%d' % (label, path, y0, y1, W, H))
    print('   列亮度: mean=%.3f  min=%.3f  max=%.3f' % (mean, min(cols), max(cols)))

    diffs = [abs(cols[i + 1] - cols[i]) for i in range(len(cols) - 1)]
    md = sum(diffs) / len(diffs)
    print('   相邻列差: mean=%.3f  max=%.3f' % (md, max(diffs)))

    thresh = max(0.6, md * 4)
    jumps = [(i, cols[i], cols[i + 1]) for i, d in enumerate(diffs) if d > thresh]
    print('   超过 %.2f 的跳变: %d 处' % (thresh, len(jumps)))
    for i, a, b in jumps[:24]:
        print('     x=%4d  %.2f -> %.2f' % (i, a, b))

    if zoom:
        crop = im.crop((0, y0, W, y1))
        crop = crop.resize((W, (y1 - y0) * 3), Image.NEAREST)
        crop.save(zoom)
        print('   放大图: %s' % zoom)


if __name__ == '__main__':
    a = sys.argv
    analyze(a[1], int(a[2]), int(a[3]), a[4] if len(a) > 4 else '', a[5] if len(a) > 5 else None)
