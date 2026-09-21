"""把四种包裹方式的截图拼成 2×2 对比图。

用法：python tools/make-mode-compare.py
产出：shots/glow-modes.png
"""
from PIL import Image, ImageDraw, ImageFont

SHOTS = [
    ('下方', 'shots/bw-bottom.png'),
    ('左右', 'shots/bw-sides.png'),
    ('四周', 'shots/bw-around.png'),
    ('全屏', 'shots/bw-full2.png'),
]
CROP = (0, 150, 1080, 1500)
COL = 320
GAP = 10
LABEL = 34
COLS = 2

font = ImageFont.truetype('C:/Windows/Fonts/msyh.ttc', 24)

def fit(img, w):
    return img.resize((w, round(img.height * w / img.width)), Image.LANCZOS)

cells = []
for name, path in SHOTS:
    im = fit(Image.open(path).convert('RGB').crop(CROP), COL)
    cells.append((name, im))

cw, ch = cells[0][1].size
rows = (len(cells) + COLS - 1) // COLS
W = COLS * COL + (COLS - 1) * GAP
H = 6 + rows * (LABEL + ch) + (rows - 1) * GAP
out = Image.new('RGB', (W, H), (22, 22, 24))
d = ImageDraw.Draw(out)

for idx, (name, im) in enumerate(cells):
    r, c = divmod(idx, COLS)
    x = c * (COL + GAP)
    y = 6 + r * (LABEL + ch + GAP)
    d.text((x + 4, y + 4), name, font=font, fill=(238, 238, 240))
    out.paste(im, (x, y + LABEL))

out.save('shots/glow-modes.png')
print('shots/glow-modes.png', out.size)
