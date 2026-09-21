"""
生成「滑动时上一页越过顶栏」的修复前后对比图。

两张图是同一个手势（从屏幕中部向下拖 500px，即往回翻上一曲）的中间帧，
分别取自修复前 / 修复后。

红线 = 分页器视口上边界（= 顶栏底部，实测 y=200）。红线以上是顶栏 / 状态栏，
不应该出现任何页面内容。
"""
from PIL import Image, ImageDraw, ImageFont

BEFORE = 'shots/g1.png'
AFTER = 'shots/h1.png'
CROP = (0, 0, 1080, 760)
SCALE = 0.44
LABEL_H = 32
VIEWPORT_TOP = 200          # 顶栏底部，实测

FONT = 'C:/Windows/Fonts/msyh.ttc'
font = ImageFont.truetype(FONT, 21)
small = ImageFont.truetype(FONT, 16)

imgs = []
for path in (BEFORE, AFTER):
    im = Image.open(path).convert('RGB').crop(CROP)
    im = im.resize((int(im.width * SCALE), int(im.height * SCALE)), Image.LANCZOS)
    imgs.append(im)

w, h = imgs[0].size
GAP = 14
W = w * 2 + GAP * 3
H = GAP + LABEL_H + h + 30 + GAP
out = Image.new('RGB', (W, H), (24, 24, 26))
d = ImageDraw.Draw(out)

titles = ['修复前：上一页的一截画在了顶栏上', '修复后：顶栏区域干净']
for i, (im, title) in enumerate(zip(imgs, titles)):
    x = GAP + i * (w + GAP)
    d.text((x + 2, GAP), title, font=font, fill=(238, 238, 240))
    top = GAP + LABEL_H
    out.paste(im, (x, top))
    yb = top + int(VIEWPORT_TOP * SCALE)
    d.line([(x, yb), (x + w, yb)], fill=(255, 84, 84), width=2)

d.text(
    (GAP, GAP + LABEL_H + h + 8),
    '红线 = 分页器视口上边界（顶栏底部，实测 y=200）。红线以上属于顶栏 / 状态栏，不该出现页面内容。',
    font=small, fill=(198, 198, 204)
)

out.save('shots/swipe-compare.png')
print('shots/swipe-compare.png', out.size)
