"""把封面晕染的两个强度档拼成一张对比图。

用法：python tools/make-glow-compare.py
产出：shots/glow-compare.png
"""
from PIL import Image, ImageDraw, ImageFont

A = Image.open('shots/glow-a-cover.png').convert('RGB')
B = Image.open('shots/glow-b-cover.png').convert('RGB')

COL = 480          # 每列宽度
GAP = 20
LABEL = 46

def fit(img, w):
    return img.resize((w, round(img.height * w / img.width)), Image.LANCZOS)

a_full, b_full = fit(A, COL), fit(B, COL)
row1_h = max(a_full.height, b_full.height)

# 局部放大：封面下方那一段（封面底边在 y≈1300 附近）
CROP = (0, 1150, A.width, 1750)
a_crop, b_crop = fit(A.crop(CROP), COL), fit(B.crop(CROP), COL)
row2_h = max(a_crop.height, b_crop.height)

W = COL * 2 + GAP
H = LABEL + row1_h + 28 + 30 + row2_h + 16
out = Image.new('RGB', (W, H), (22, 22, 24))
d = ImageDraw.Draw(out)
try:
    font = ImageFont.truetype('C:/Windows/Fonts/msyh.ttc', 28)
    small = ImageFont.truetype('C:/Windows/Fonts/msyh.ttc', 22)
except OSError:
    font = small = ImageFont.load_default()

def title(x, y, text):
    d.text((x, y), text, font=font, fill=(238, 238, 240))

def sub(x, y, text):
    d.text((x, y), text, font=small, fill=(150, 150, 156))

title(6, 8, 'A 档 · 若隐若现（0.10）')
title(COL + GAP + 6, 8, 'B 档 · 清淡可见（0.18）')
out.paste(a_full, (0, LABEL))
out.paste(b_full, (COL + GAP, LABEL))

y2 = LABEL + row1_h + 28
sub(6, y2, '封面下方局部放大')
sub(COL + GAP + 6, y2, '封面下方局部放大')
y3 = y2 + 30
out.paste(a_crop, (0, y3))
out.paste(b_crop, (COL + GAP, y3))

out.save('shots/glow-compare.png')
print('shots/glow-compare.png', out.size)
