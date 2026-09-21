"""
「歌词态与封面态的背景是否一致」对照图。

两张图是同一首歌、同一个页面，只是一个停在歌词态、一个停在封面态。
中间那张是差值放大图：如果背景真的同一套渐变，差值图上除了内容区域
（歌词文字 / 封面图）以外应该全是黑的。
"""
from PIL import Image, ImageChops, ImageDraw, ImageFont

LYRICS = 'shots/p0.png'
COVER = 'shots/p1.png'
# 都是背景上的点（封面矩形是 x 68~1012 / y 314~1394，所以取左右外侧与上下方）
PTS = [(30, 400), (30, 1000), (1050, 700), (540, 200), (540, 1500), (540, 2300)]
SCALE = 0.30
FONT = 'C:/Windows/Fonts/msyh.ttc'
font = ImageFont.truetype(FONT, 19)
mono = ImageFont.truetype(FONT, 16)      # 用中文字体，避免中文标签变方块

a = Image.open(LYRICS).convert('RGB')
b = Image.open(COVER).convert('RGB')

# 差值放大 6 倍，让「背景是否一致」用肉眼也能判断
diff = ImageChops.difference(a, b).point(lambda v: min(255, v * 6))

panels = [('歌词态', a), ('封面态', b), ('差值 ×6（背景应全黑）', diff)]
cells = []
for title, im in panels:
    im = im.resize((int(im.width * SCALE), int(im.height * SCALE)), Image.LANCZOS)
    cells.append((title, im))

w, h = cells[0][1].size
GAP, LABEL_H = 12, 28
W = w * 3 + GAP * 4
H = GAP + LABEL_H + h + 8 + 18 * len(PTS) + GAP
out = Image.new('RGB', (W, H), (24, 24, 26))
d = ImageDraw.Draw(out)

for i, (title, im) in enumerate(cells):
    x = GAP + i * (w + GAP)
    d.text((x + 2, GAP), title, font=font, fill=(238, 238, 240))
    out.paste(im, (x, GAP + LABEL_H))

lines = []
for p in PTS:
    ca, cb = a.getpixel(p), b.getpixel(p)
    delta = max(abs(ca[k] - cb[k]) for k in range(3))
    lines.append('%s  歌词 %s / 封面 %s  Δmax=%d' % (
        str(p).ljust(12),
        '#%02X%02X%02X' % ca, '#%02X%02X%02X' % cb, delta))
for i, ln in enumerate(lines):
    d.text((GAP, GAP + LABEL_H + h + 8 + i * 18), ln, font=mono, fill=(198, 198, 204))

out.save('shots/lyrics-glow-compare.png')
print('shots/lyrics-glow-compare.png', out.size)
for ln in lines:
    print(' ', ln)
