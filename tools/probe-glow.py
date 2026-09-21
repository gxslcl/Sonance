"""采样封面下方那几个点的颜色，确认晕染到底有没有画上去。

用法：python tools/probe-glow.py shots/xxx.png [shots/yyy.png ...]
"""
import sys
from PIL import Image

ROWS = [1350, 1420, 1500, 1580, 1660, 1740, 1850, 2000, 2250]
COLS = [60, 540, 1010]

for path in sys.argv[1:]:
    im = Image.open(path).convert('RGB')
    print(f'--- {path}  ({im.size[0]}x{im.size[1]}) ---')
    for y in ROWS:
        if y >= im.height:
            continue
        cells = ' | '.join(
            '#%02X%02X%02X' % im.getpixel((x, y)) for x in COLS if x < im.width
        )
        print(f'  y={y:<5} {cells}')
