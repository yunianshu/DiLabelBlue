#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
DiLabel 控制台 App 图标生成器（PIL 绘制，可复现）。

设计语言与 App 内 UI 一致：深蓝渐变圆角底 + 青色雷达同心环/扫掠拖尾 + 打印机图形。
产物：
  demo/res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png   传统启动图标
  demo/res/mipmap-xxxhdpi/ic_launcher_fg.png                          自适应图标前景（API 26+）
  demo/icon_preview.png                                               512px 预览图
用法：python3 scripts/make_icon.py
"""
import math
import os
from PIL import Image, ImageDraw

ROOT = os.path.normpath(os.path.join(os.path.dirname(__file__), '..', 'demo'))

C_BG_TOP = (11, 18, 32)        # 0B1220
C_BG_MID = (14, 26, 51)        # 0E1A33
C_BG_BOT = (27, 46, 86)        # 1B2E56
C_CYAN = (34, 211, 238)        # 22D3EE
C_GREEN = (52, 211, 153)       # 34D399
C_AMBER = (251, 191, 36)       # FBBF24
C_WHITE = (230, 237, 247)      # E6EDF7
C_LINE = (148, 163, 184)       # 94A3B8

SS = 4          # 超采样倍数（抗锯齿）
BASE = 512      # 设计基准画布


def lerp(c1, c2, t):
    return tuple(int(a + (b - a) * t) for a, b in zip(c1, c2))


def draw_compose(img, cx, cy, s):
    """在 512 基准坐标系画雷达+打印机（s 为缩放）。img 为 RGBA，cx/cy 已是像素。"""
    def P(v):                    # 基准坐标 -> 超采样像素
        return v * s * SS

    # ---- 半透明图层：扫掠拖尾（三段渐弱扇面） + 十字线 ----
    overlay = Image.new('RGBA', img.size, (0, 0, 0, 0))
    od = ImageDraw.Draw(overlay)
    fan = [(-108, -90, 110), (-126, -108, 64), (-144, -126, 30)]
    for a1, a2, alpha in fan:
        od.pieslice([cx - P(150), cy - P(150), cx + P(150), cy + P(150)],
                    start=a1, end=a2, fill=(34, 211, 238, alpha))
    dim = (34, 211, 238, 46)
    lw = max(1, int(P(2.0)))
    od.line([cx - P(150), cy, cx + P(150), cy], fill=dim, width=lw)
    od.line([cx, cy - P(150), cx, cy + P(150)], fill=dim, width=lw)
    img.alpha_composite(overlay)

    d = ImageDraw.Draw(img, 'RGBA')

    # ---- 同心环 ----
    for r, alpha in ((150, 95), (105, 75), (60, 130)):
        rr = P(r)
        d.ellipse([cx - rr, cy - rr, cx + rr, cy + rr],
                  outline=(34, 211, 238, alpha), width=max(1, int(P(2.6))))

    # ---- 外环信号点 ----
    for ang, col in ((205, C_AMBER), (335, C_GREEN)):
        a = math.radians(ang)
        px, py = cx + P(150) * math.cos(a), cy + P(150) * math.sin(a)
        pr = P(7)
        d.ellipse([px - pr, py - pr, px + pr, py + pr], fill=col + (255,))

    # ---- 打印机（居中构图） ----
    bw, bh, br = P(96), P(52), P(14)
    bx, by = cx, cy + P(10)
    # 出纸槽先画在机身后面（下沿露出一截）
    d.rounded_rectangle([bx - P(58), by - P(6), bx + P(58), by + P(34)],
                        radius=P(6), fill=(10, 16, 30, 230))
    # 机身（青色渐变近似：上亮下暗两层）
    d.rounded_rectangle([bx - bw, by - bh, bx + bw, by + bh],
                        radius=br, fill=C_CYAN + (255,))
    d.rounded_rectangle([bx - bw, by, bx + bw, by + bh],
                        radius=br, fill=(17, 176, 202, 255))
    d.rounded_rectangle([bx - bw, by - bh, bx + bw, by],
                        radius=br, fill=C_CYAN + (255,))
    # 顶部出纸（居中白纸）
    pw, ph = P(54), P(46)
    ptop, pbot = by - bh - P(20), by - bh + P(14)
    d.rounded_rectangle([bx - pw, ptop, bx + pw, pbot],
                        radius=P(5), fill=C_WHITE + (255,))
    lw2 = max(1, int(P(3.2)))
    for i in range(3):
        ly = ptop + P(11) + i * P(11)
        d.line([bx - P(38), ly, bx + P(38) - i * P(14), ly],
               fill=C_LINE + (255,), width=lw2)
    # 出纸口（机身下沿深色横条）
    d.rounded_rectangle([bx - P(52), by + P(8), bx + P(52), by + P(26)],
                        radius=P(4), fill=(10, 16, 30, 255))
    # 状态灯
    lr = P(6)
    lx, ly = bx + bw - P(18), by - bh + P(18)
    d.ellipse([lx - lr, ly - lr, lx + lr, ly + lr], fill=C_GREEN + (255,))


def render_icon(size, out_path, rounded=True):
    """传统启动图标：深蓝渐变圆角底 + 雷达打印机组合。"""
    S = size * SS
    img = Image.new('RGBA', (S, S), (0, 0, 0, 0))

    # 渐变底
    col = Image.new('RGB', (1, S))
    for y in range(S):
        t = y / (S - 1)
        c = lerp(C_BG_TOP, C_BG_MID, t / 0.55) if t < 0.55 else lerp(C_BG_MID, C_BG_BOT, (t - 0.55) / 0.45)
        col.putpixel((0, y), c)
    grad = col.resize((S, S))

    mask = Image.new('L', (S, S), 0)
    md = ImageDraw.Draw(mask)
    md.rounded_rectangle([0, 0, S - 1, S - 1], radius=int(S * 0.22), fill=255)
    if not rounded:
        md.rectangle([0, 0, S - 1, S - 1], fill=255)
    img.paste(grad, (0, 0), mask)

    draw_compose(img, S / 2, S * 0.47, size / BASE)

    img = img.resize((size, size), Image.LANCZOS)
    img.save(out_path)


def render_foreground(size, out_path):
    """自适应图标前景：透明底，图形收进安全区（约 61% 内）。"""
    S = size * SS
    img = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    draw_compose(img, S / 2, S / 2, size / BASE * 0.58)
    img = img.resize((size, size), Image.LANCZOS)
    img.save(out_path)


def main():
    dens = {'mdpi': 48, 'hdpi': 72, 'xhdpi': 96, 'xxhdpi': 144, 'xxxhdpi': 192}
    for dpi, px in dens.items():
        d = os.path.join(ROOT, 'res', 'mipmap-' + dpi)
        os.makedirs(d, exist_ok=True)
        render_icon(px, os.path.join(d, 'ic_launcher.png'))
        print('ic_launcher.png', dpi, px)
    d = os.path.join(ROOT, 'res', 'mipmap-xxxhdpi')
    render_foreground(432, os.path.join(d, 'ic_launcher_fg.png'))
    print('ic_launcher_fg.png 432')
    render_icon(512, os.path.join(ROOT, 'icon_preview.png'))
    print('icon_preview.png 512')


if __name__ == '__main__':
    main()
