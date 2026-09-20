# -*- coding: utf-8 -*-
"""
把 all-variants.html 里的 40 个 SVG 图标转成 Android 资源。

产出：
  res/drawable/logo_<id>.xml                 前景矢量（108 画布，内容压到 66% 安全区）
  res/mipmap-anydpi-v26/ic_launcher_<id>.xml 自适应图标
  res/mipmap/ic_launcher_<id>.xml            API<26 回退（背景方块 + 前景）
  res/values/ic_launcher_colors.xml          各变体背景色
  res/values/ic_launcher_variants.xml        变体元数据（别名 / 标签）
"""
import os, re, math
import xml.etree.ElementTree as ET

HTML = r"C:\Users\1\Downloads\all-variants.html"
RES = r"C:\Users\1\WorkBuddy\2026-09-19-18-01-53\Sonance\app\src\main\res"

BG_CLASS = {
    'bg-black':   '#FF0A0A0A',
    'bg-dark':    '#FF121212',
    'bg-cream':   '#FFF0EBE0',
    'bg-navy':    '#FF162032',
    'bg-ivory':   '#FFEDE8DC',
    'bg-wine':    '#FF2A1018',
    'bg-forest':  '#FF0F1F18',
    'bg-slate':   '#FF1E2428',
    'bg-purple':  '#FF2D1B4E',
    'bg-charcoal':'#FF2A2A2A',
    'bg-coral':   '#FFF5E6DE',
    'bg-teal-bg': '#FF0D2826',
    'bg-sky':     '#FF1A2A3A',
    'bg-magenta': '#FF2A1028',
    'bg-olive':   '#FF1A1F10',
}

NS = '{http://www.w3.org/2000/svg}'


def a2c(v):
    """SVG 颜色 -> Android 颜色（补 alpha）"""
    if not v or v == 'none':
        return None
    v = v.strip()
    if v.startswith('#'):
        if len(v) == 7:
            return '#FF' + v[1:]
        return v
    return v


def fnum(v, default=0.0):
    try:
        return float(v)
    except (TypeError, ValueError):
        return default


def circle_path(cx, cy, r):
    return "M%.4f,%.4f a%.4f,%.4f 0 1,0 %.4f,0 a%.4f,%.4f 0 1,0 %.4f,0" % (
        cx - r, cy, r, r, 2 * r, r, r, -2 * r)


def dashed_circle_path(cx, cy, r, dash, gap):
    """VectorDrawable 不支持 stroke-dasharray，手动把圆拆成一段段弧"""
    total = 2 * math.pi * r
    period = dash + gap
    n = max(1, int(round(total / period)))
    step = total / n
    dash_len = step * (dash / period)
    segs = []
    for i in range(n):
        a0 = (i * step) / r
        a1 = (i * step + dash_len) / r
        x0, y0 = cx + r * math.cos(a0), cy + r * math.sin(a0)
        x1, y1 = cx + r * math.cos(a1), cy + r * math.sin(a1)
        large = 1 if (a1 - a0) > math.pi else 0
        segs.append("M%.4f,%.4f A%.4f,%.4f 0 %d,1 %.4f,%.4f" % (x0, y0, r, r, large, x1, y1))
    return " ".join(segs)


def rect_path(x, y, w, h, rx=0.0, ry=None):
    if ry is None:
        ry = rx
    rx = max(0.0, min(rx, w / 2.0))
    ry = max(0.0, min(ry, h / 2.0))
    if rx <= 0 or ry <= 0:
        return "M%.4f,%.4f h%.4f v%.4f h%.4f z" % (x, y, w, h, -w)
    return (
        "M%.4f,%.4f h%.4f a%.4f,%.4f 0 0,1 %.4f,%.4f v%.4f "
        "a%.4f,%.4f 0 0,1 %.4f,%.4f h%.4f a%.4f,%.4f 0 0,1 %.4f,%.4f "
        "v%.4f a%.4f,%.4f 0 0,1 %.4f,%.4f z"
    ) % (
        x + rx, y, w - 2 * rx, rx, ry, rx, ry,
        h - 2 * ry, rx, ry, -rx, ry,
        -(w - 2 * rx), rx, ry, -rx, -ry,
        -(h - 2 * ry), rx, ry, rx, -ry,
    )


def convert_group(node, out, indent):
    """把 SVG 节点转成 VectorDrawable 的 <group>/<path>"""
    pad = "    " * indent
    tag = node.tag.replace(NS, '')

    if tag == 'g':
        tr = node.get('transform', '')
        m = re.search(r'rotate\(\s*(-?[\d.]+)(?:\s+(-?[\d.]+)\s+(-?[\d.]+))?\s*\)', tr)
        if m:
            rot = m.group(1)
            px = m.group(2) or '50'
            py = m.group(3) or '50'
            out.append('%s<group android:rotation="%s" android:pivotX="%s" android:pivotY="%s">' % (pad, rot, px, py))
        else:
            out.append('%s<group>' % pad)
        for ch in node:
            convert_group(ch, out, indent + 1)
        out.append('%s</group>' % pad)
        return

    d = None
    fill = None
    stroke = None
    sw = None
    cap = None
    join = None
    dash = None
    opacity = node.get('opacity')

    if tag == 'circle':
        cx, cy, r = fnum(node.get('cx')), fnum(node.get('cy')), fnum(node.get('r'))
        dash_raw = node.get('stroke-dasharray')
        if dash_raw:
            nums = [float(x) for x in re.split(r'[,\s]+', dash_raw.strip()) if x]
            dash = (nums[0], nums[1] if len(nums) > 1 else nums[0])
            d = dashed_circle_path(cx, cy, r, dash[0], dash[1])
        else:
            d = circle_path(cx, cy, r)
        fill = node.get('fill')
        stroke = node.get('stroke')
        sw = node.get('stroke-width')
    elif tag == 'rect':
        x, y = fnum(node.get('x')), fnum(node.get('y'))
        w, h = fnum(node.get('width')), fnum(node.get('height'))
        rx, ry = fnum(node.get('rx')), fnum(node.get('ry'))
        d = rect_path(x, y, w, h, rx, ry)
        fill = node.get('fill')
        stroke = node.get('stroke')
        sw = node.get('stroke-width')
    elif tag == 'line':
        x1, y1 = fnum(node.get('x1')), fnum(node.get('y1'))
        x2, y2 = fnum(node.get('x2')), fnum(node.get('y2'))
        d = "M%.4f,%.4f L%.4f,%.4f" % (x1, y1, x2, y2)
        stroke = node.get('stroke')
        sw = node.get('stroke-width')
        fill = 'none'
        cap = node.get('stroke-linecap')
    elif tag == 'path':
        d = node.get('d')
        fill = node.get('fill')
        stroke = node.get('stroke')
        sw = node.get('stroke-width')
        cap = node.get('stroke-linecap')
        join = node.get('stroke-linejoin')

    if not d:
        return

    fill = a2c(fill)
    stroke = a2c(stroke)
    # 没有显式 fill 且不是纯描边时，SVG 默认黑色填充
    if fill is None and stroke is None:
        fill = '#FF000000'

    out.append('%s<path' % pad)
    out.append('%s    android:pathData="%s"' % (pad, d))
    if fill:
        out.append('%s    android:fillColor="%s"' % (pad, fill))
    if stroke:
        out.append('%s    android:strokeColor="%s"' % (pad, stroke))
        out.append('%s    android:strokeWidth="%s"' % (pad, sw or '1'))
        if cap:
            out.append('%s    android:strokeLineCap="%s"' % (pad, cap))
        if join:
            out.append('%s    android:strokeLineJoin="%s"' % (pad, join))
    if opacity:
        o = fnum(opacity, 1.0)
        if fill and fill != '#00000000':
            out.append('%s    android:fillAlpha="%.3f"' % (pad, o))
        if stroke:
            out.append('%s    android:strokeAlpha="%.3f"' % (pad, o))
    out.append('%s/>' % pad)


def build_vector(svg_el, bg_override=None):
    """返回 (vector_xml_body_lines, extracted_bg_color_or_None)"""
    children = list(svg_el)
    bg = bg_override

    # 全幅 <rect width=100 height=100 fill=...> 当成自适应图标背景，不画进前景
    if children and children[0].tag == NS + 'rect':
        r0 = children[0]
        if fnum(r0.get('width')) >= 99 and fnum(r0.get('height')) >= 99:
            c = a2c(r0.get('fill'))
            if c:
                bg = c
            children = children[1:]

    body = []
    for ch in children:
        convert_group(ch, body, 2)
    return body, bg


def main():
    html = open(HTML, encoding='utf-8').read()

    # 切分 series
    series_blocks = re.split(r'<div class="series">', html)[1:]

    variants = []
    for si, block in enumerate(series_blocks, start=1):
        st = re.search(r'<div class="series-title">.*?</span>(.*?)</div>', block, re.S)
        series_name = st.group(1).strip() if st else ("系列%d" % si)
        cards = re.findall(
            r'<div class="card">\s*<div class="icon-box ([^"]+)">(.*?)</div>\s*'
            r'<span class="label">(.*?)</span>',
            block, re.S)
        for vi, (bgclass, svg_html, label) in enumerate(cards, start=1):
            svg_html = svg_html.strip()
            svg_el = ET.fromstring(svg_html)
            vid = "s%d_%d" % (si, vi)
            body, bg = build_vector(svg_el, BG_CLASS.get(bgclass, '#FF121212'))
            variants.append({
                'id': vid,
                'series': series_name,
                'label': label.strip(),
                'bg': bg or '#FF121212',
                'body': body,
            })

    print("解析到 %d 个变体" % len(variants))

    # ---- drawable/logo_*.xml ----
    ddir = os.path.join(RES, 'drawable')
    os.makedirs(ddir, exist_ok=True)
    for v in variants:
        lines = ['<?xml version="1.0" encoding="utf-8"?>',
                 '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
                 '    android:width="108dp"',
                 '    android:height="108dp"',
                 '    android:viewportWidth="108"',
                 '    android:viewportHeight="108">',
                 '    <group android:scaleX="0.66" android:scaleY="0.66"',
                 '        android:translateX="21" android:translateY="21">']
        lines += v['body']
        lines += ['    </group>', '</vector>', '']
        with open(os.path.join(ddir, 'logo_%s.xml' % v['id']), 'w', encoding='utf-8') as f:
            f.write("\n".join(lines))

    # ---- 背景色资源 ----
    colors = ['<?xml version="1.0" encoding="utf-8"?>', '<resources>']
    for v in variants:
        colors.append('    <color name="ic_bg_%s">%s</color>' % (v['id'], v['bg']))
    colors += ['</resources>', '']
    with open(os.path.join(RES, 'values', 'ic_launcher_colors.xml'), 'w', encoding='utf-8') as f:
        f.write("\n".join(colors))

    # ---- mipmap-anydpi-v26 自适应图标 ----
    adir = os.path.join(RES, 'mipmap-anydpi-v26')
    os.makedirs(adir, exist_ok=True)
    for v in variants:
        xml = ('<?xml version="1.0" encoding="utf-8"?>\n'
               '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
               '    <background android:drawable="@color/ic_bg_%s" />\n'
               '    <foreground android:drawable="@drawable/logo_%s" />\n'
               '</adaptive-icon>\n') % (v['id'], v['id'])
        with open(os.path.join(adir, 'ic_launcher_%s.xml' % v['id']), 'w', encoding='utf-8') as f:
            f.write(xml)

    # ---- mipmap 回退（API < 26）----
    mdir = os.path.join(RES, 'mipmap')
    os.makedirs(mdir, exist_ok=True)
    for v in variants:
        lines = ['<?xml version="1.0" encoding="utf-8"?>',
                 '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
                 '    android:width="108dp"',
                 '    android:height="108dp"',
                 '    android:viewportWidth="108"',
                 '    android:viewportHeight="108">',
                 '    <path android:pathData="M0,0 h108 v108 h-108 z" android:fillColor="%s" />' % v['bg'],
                 '    <group android:scaleX="0.66" android:scaleY="0.66"',
                 '        android:translateX="21" android:translateY="21">']
        lines += v['body']
        lines += ['    </group>', '</vector>', '']
        with open(os.path.join(mdir, 'ic_launcher_%s.xml' % v['id']), 'w', encoding='utf-8') as f:
            f.write("\n".join(lines))

    # ---- 变体元数据（Kotlin 侧直接用）----
    meta = ['<?xml version="1.0" encoding="utf-8"?>', '<resources>']
    for v in variants:
        meta.append('    <string name="icon_label_%s">%s · %s</string>'
                    % (v['id'], v['series'], v['label']))
    meta.append('    <string-array name="app_icon_ids">')
    for v in variants:
        meta.append('        <item>%s</item>' % v['id'])
    meta.append('    </string-array>')
    meta += ['</resources>', '']
    with open(os.path.join(RES, 'values', 'ic_launcher_variants.xml'), 'w', encoding='utf-8') as f:
        f.write("\n".join(meta))

    # ---- 顺便打印 Kotlin 常量表，方便核对 ----
    print("\n".join("%-8s %-6s %-8s %s" % (v['id'], v['bg'], v['series'], v['label']) for v in variants))


if __name__ == '__main__':
    main()
