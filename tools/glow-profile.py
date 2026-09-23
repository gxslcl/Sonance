"""量一组「切歌过渡」帧里**背景区**的平均色，用来判断过渡中背景有没有闪。

播放页的晕染画在整页背景层，只出现在封面之外的边缘区域。
所以取三条**远离封面和文字**的边带取平均：
  左缘 x=[0,60]、右缘 x=[1020,1080]、顶带 y=[230,300]
（1080x2400 竖屏手机；封面大致在 y=[290,1240]、x=[85,995]。）

用法：
  python tools/glow-profile.py shots/glow-*.png

输出每帧三条带子的 RGB 均值 + 整屏均值，直接横向比就能看出
「过渡中点是不是比两端明显偏亮 / 偏暗」。
"""
import sys
import glob
from PIL import Image

STRIPS = [
    ("左缘", (0, 300, 60, 1900)),
    ("右缘", (1020, 300, 1080, 1900)),
    ("顶带", (100, 230, 980, 300)),
    ("底带", (100, 2000, 980, 2140)),
]


def mean_rgb(im, box):
    return im.crop(box).resize((1, 1), Image.BOX).getpixel((0, 0))[:3]


def main():
    patterns = sys.argv[1:]
    files = []
    for p in patterns:
        files.extend(sorted(glob.glob(p)))
    if not files:
        print("没有匹配的文件")
        return
    head = "文件".ljust(26) + "".join(n.ljust(18) for n, _ in STRIPS) + "整屏"
    print(head)
    print("-" * len(head))
    for f in files:
        im = Image.open(f).convert("RGB")
        row = f.split("\\")[-1].split("/")[-1].ljust(26)
        for _, box in STRIPS:
            r, g, b = mean_rgb(im, box)
            row += f"{r:3d},{g:3d},{b:3d}".ljust(18)
        r, g, b = mean_rgb(im, (0, 0, im.width, im.height))
        row += f"{r:3d},{g:3d},{b:3d}"
        print(row)


if __name__ == "__main__":
    main()
