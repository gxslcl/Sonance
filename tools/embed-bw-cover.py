"""给一个音频文件嵌入一张「黑白封面」，用来复现真实用户的黑白照片场景。

用法：python tools/embed-bw-cover.py shots/test.wav
生成的封面刻意做成：上半浅灰（人像）、下半整片纯黑（衣服）——
这正是让旧版取色「跳过近黑像素」全部失效、从而回落到皮肤色的那种封面。
"""
import sys
from PIL import Image, ImageDraw
from mutagen.wave import WAVE
from mutagen.id3 import APIC

COVER = 'shots/bw-cover.png'

img = Image.new('RGB', (600, 600), (232, 232, 230))
d = ImageDraw.Draw(img)
d.rectangle([0, 0, 600, 300], fill=(206, 206, 204))
d.ellipse([170, 50, 430, 310], fill=(150, 150, 150))
d.rectangle([0, 300, 600, 600], fill=(6, 6, 6))
img.save(COVER)

path = sys.argv[1]
audio = WAVE(path)
if audio.tags is None:
    audio.add_tags()
with open(COVER, 'rb') as f:
    data = f.read()
audio.tags.add(APIC(encoding=3, mime='image/png', type=3, desc='Cover', data=data))
audio.save()
print('已嵌入黑白封面:', path, len(data), 'bytes')
