"""把封面晕染的 6 个档位拼成一张对比图（只裁封面下方那一段，看得最清楚）。

用法：python tools/make-glow-levels.py
产出：shots/glow-levels.png
"""
from PIL import Image, ImageDraw, ImageFont

LEVELS = [
    (1, '0.10', 'lv1'), (2, '0.22', 'lv2'), (3, '0.36', 'lv3'),
    (4, '0.50', 'lv4'), (5, '0.65', 'lv5'), (6, '0.80', 'lv6'),
]
CROP = (0, 1180, 1080, 2060)   # 封面下边缘到进度条上方
COL = 330
GAP = 10
LABEL = 34
COLS = 3

font = ImageFont.truetype('C:/Windows/Fonts/msyh.ttc', 22)

imgs = []
for n, val, name in LEVELS:
    im = Image.open(f'shots/{name}.png').convert('RGB').crop(CROP)
    im = im.resize((COL, round(im.height * COL / im.width)), Image.LANCZOS)
    imgs.append((n, val, im))

_, ch = imgs[0][2].size
rows = (len(imgs) + COLS - 1) // COLS
W = COLS * COL + (COLS - 1) * GAP
H = 6 + rows * (LABEL + ch) + (rows - 1) * GAP
out = Image.new('RGB', (W, H), (22, 22, 24))
d = ImageDraw.Draw(out)

for idx, (n, val, im) in enumerate(imgs):
    r, c = divmod(idx, COLS)
    x = c * (COL + GAP)
    y = 6 + r * (LABEL + ch + GAP)
    d.text((x + 4, y + 4), f'{n} 档 · {val}', font=font, fill=(235, 235, 240))
    out.paste(im, (x, y + LABEL))

out.save('shots/glow-levels.png')
print('shots/glow-levels.png', out.size)
