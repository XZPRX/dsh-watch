"""dsh-watch 像素风宣传片（横屏 1920x1080）。

版式原则（解决上一版错位问题）：
  1. 装饰性像素元素画在 640x360 的质点层，×3 放大；文字/截图/圆角框全部画在全分辨率层。
  2. 所有坐标只来自 LAYOUT 常量，不再出现“手调两套坐标系”。
  3. 每个镜头 = 标题区(左上) + 视觉区(左) + 文案区(右)，安全边距 96px。
"""

import math
import os
import random
import struct
import subprocess
import sys
import wave

import os

base = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ROOT = os.path.dirname(BASE)
# 截图目录：默认取系统“图片\Screenshots”，可用环境变量 DSH_WATCH_SHOTS 覆盖
SHOTS = os.environ.get("DSH_WATCH_SHOTS") or os.path.join(
    os.path.expanduser("~"), "Pictures", "Screenshots")
EGG = os.path.join(ROOT, "app", "res", "drawable", "easter_egg.png")
WORK = os.path.join(BASE, "work")
# ffmpeg：优先环境变量，其次 PATH
FFMPEG = os.environ.get("FFMPEG") or "ffmpeg"

from PIL import Image, ImageDraw, ImageFilter, ImageFont

W, H = 1920, 1080
S = 3                      # 质点层放大倍数
PW, PH = W // S, H // S    # 640 x 360
FPS = 30
MARGIN = 96

BG = (11, 13, 18)
PANEL = (19, 24, 38)
PANEL2 = (26, 33, 52)
LINE = (46, 56, 80)
ACCENT = (43, 108, 246)
ACCENT_D = (26, 70, 170)
TEXT = (232, 236, 244)
MUTED = (140, 148, 168)
GREEN = (53, 199, 89)
ORANGE = (255, 176, 32)
RED = (240, 84, 84)

FD = r"C:\Windows\Fonts"
F_CB = os.path.join(FD, "msyhbd.ttc")
F_C = os.path.join(FD, "msyh.ttc")
F_M = os.path.join(FD, "consolab.ttf")
F_MR = os.path.join(FD, "consola.ttf")


_fc = {}


def font(path, size):
    key = (path, size)
    if key not in _fc:
        _fc[key] = ImageFont.truetype(path, size)
    return _fc[key]


def clamp01(x):
    return max(0.0, min(1.0, x))


def ease_out(x):
    return 1 - (1 - clamp01(x)) ** 3


def ease_io(x):
    x = clamp01(x)
    return x * x * (3 - 2 * x)


def seg(t, a, b):
    if t <= a:
        return 0.0
    if t >= b:
        return 1.0
    return (t - a) / (b - a)


def fade(t, a, b):
    """出现用的透明度和位移。"""
    p = ease_out(seg(t, a, b))
    return p, int((1 - p) * 26)


# ------------------------------------------------------------------ 素材
class Assets:
    def __init__(self):
        self.sharp = {}
        for fname in sorted(os.listdir(SHOTS)):
            if fname.startswith("dsh-watch-") and fname.endswith(".png"):
                key = fname[len("dsh-watch-"):-len(".png")].split("-", 1)[-1]
                self.sharp[key] = Image.open(os.path.join(SHOTS, fname)).convert("RGB")
        self.egg = Image.open(EGG).convert("RGBA")
        self._pix = {}

    def pixel(self, key, size):
        k = (key, size)
        if k not in self._pix:
            img = self.sharp[key].resize(size, Image.LANCZOS)
            img = img.quantize(colors=40, method=Image.MEDIANCUT, dither=Image.NONE).convert("RGB")
            self._pix[k] = img
        return self._pix[k]


# ------------------------------------------------------------------ 画表
def watch(img, cx, cy, w, h, shot=None, glow=True, radius=None):
    """在整帧上画一块手表（全分辨率坐标，左右边缘严格对齐）。"""
    d = ImageDraw.Draw(img, "RGBA")
    r = radius if radius is not None else int(w * 0.20)
    x0, y0, x1, y1 = cx - w // 2, cy - h // 2, cx + w // 2, cy + h // 2
    if glow:
        g = Image.new("RGBA", img.size, (0, 0, 0, 0))
        ImageDraw.Draw(g).rounded_rectangle((x0 - 14, y0 - 14, x1 + 14, y1 + 14),
                                            radius=r + 12, fill=ACCENT + (46,))
        img.alpha_composite(g.filter(ImageFilter.GaussianBlur(18)))
        d = ImageDraw.Draw(img, "RGBA")
    d.rounded_rectangle((x0 - 9, y0 - 9, x1 + 9, y1 + 9), radius=r + 8,
                        fill=(36, 43, 62), outline=LINE, width=2)
    d.rounded_rectangle((x0, y0, x1, y1), radius=r, fill=(6, 7, 11))
    if shot is not None:
        s = shot.resize((w, h), Image.LANCZOS)
        mask = Image.new("L", (w, h), 0)
        ImageDraw.Draw(mask).rounded_rectangle((0, 0, w - 1, h - 1), radius=r, fill=255)
        img.paste(s, (x0, y0), mask)
    return (x0, y0, x1, y1)


def mini_ui(img, box, t):
    """没有截图时，在表盘里画一版简化 UI。"""
    x0, y0, x1, y1 = box
    d = ImageDraw.Draw(img, "RGBA")
    pad = 26
    d.ellipse((x0 + pad, y0 + pad, x0 + pad + 16, y0 + pad + 16), fill=ORANGE)
    d.rectangle((x0 + pad + 26, y0 + pad + 2, x1 - pad - 60, y0 + pad + 16), fill=TEXT)
    rows = [0.92, 0.55, 0.78, 0.42, 0.66, 0.5, 0.72, 0.46]
    top = y0 + pad + 40
    step = (y1 - y0 - 2 * pad - 120) // len(rows)
    for i, ratio in enumerate(rows):
        col = ACCENT if i in (0, 4) else (GREEN if i in (3, 7) else (110, 120, 140))
        d.rectangle((x0 + pad, top + i * step, x0 + pad + int((x1 - x0 - 2 * pad) * ratio),
                     top + i * step + 11), fill=col)
    bw = (x1 - x0 - 2 * pad - 24) // 3
    for i in range(3):
        bx = x0 + pad + i * (bw + 12)
        d.rounded_rectangle((bx, y1 - pad - 66, bx + bw, y1 - pad - 8), radius=10,
                            fill=ACCENT if i == 0 else PANEL2)


# ------------------------------------------------------------------ 文案工具
def title(d, xy, text, size, color, alpha=255, anchor="la"):
    d.text(xy, text, font=font(F_CB, size), fill=color + (alpha,), anchor=anchor)


def body(d, xy, text, size, color, alpha=255, anchor="la"):
    d.text(xy, text, font=font(F_C, size), fill=color + (alpha,), anchor=anchor)


def mono(d, xy, text, size, color, alpha=255, anchor="la"):
    d.text(xy, text, font=font(F_M, size), fill=color + (alpha,), anchor=anchor)


def mono_r(d, xy, text, size, color, alpha=255, anchor="la"):
    d.text(xy, text, font=font(F_MR, size), fill=color + (alpha,), anchor=anchor)


def bullet(d, x, y, text, t, start, color=TEXT, accent=ACCENT):
    p, dy = fade(t, start, start + 0.6)
    if p <= 0:
        return
    d.rectangle((x, y + 12, x + 18, y + 30), fill=accent + (int(255 * p),))
    body(d, (x + 40, y), text, 34, color, int(255 * p))


def header(img, d, t):
    d.rectangle((0, 0, W, 6), fill=ACCENT + (255,))
    mono(d, (MARGIN, 52), "DSH WATCH", 30, ACCENT)
    body(d, (W - MARGIN, 56), "手表远程 · 像素版", 28, MUTED, anchor="ra")


def backdrop(img, t):
    """质点层的背景网格 + 缓慢漂移的光斑。"""
    art = Image.new("RGBA", (PW, PH), BG + (255,))
    d = ImageDraw.Draw(art)
    for x in range(0, PW, 20):
        d.line((x, 0, x, PH), fill=(17, 21, 31, 255))
    for y in range(0, PH, 20):
        d.line((0, y, PW, y), fill=(17, 21, 31, 255))
    # 两个很淡的角落光斑，别在画面中间留大色块
    gx = int(PW * 0.2 + math.sin(t * 0.4) * 24)
    gy = int(PH * 0.25 + math.cos(t * 0.3) * 18)
    d.ellipse((gx - 120, gy - 70, gx + 120, gy + 70), fill=(24, 33, 56, 46))
    gx2 = int(PW * 0.82 + math.cos(t * 0.35) * 24)
    gy2 = int(PH * 0.78 + math.sin(t * 0.45) * 18)
    d.ellipse((gx2 - 130, gy2 - 76, gx2 + 130, gy2 + 76), fill=(22, 30, 52, 40))
    return art.resize((W, H), Image.NEAREST)


# ------------------------------------------------------------------ 镜头
def sc_title(t, dur, A):
    img = Image.new("RGBA", (W, H), BG + (255,))
    img.alpha_composite(backdrop(img, t))
    d = ImageDraw.Draw(img, "RGBA")

    letters = "DSH WATCH"
    f = font(F_M, 92)
    total = d.textlength(letters, font=f)
    x = (W - total) / 2
    for i, ch in enumerate(letters):
        p = clamp01((t - 0.25 - i * 0.06) / 0.3)
        if p <= 0 or ch == " ":
            continue
        dx = x + d.textlength(letters[:i], font=f)
        d.text((dx, 330 - int((1 - ease_out(p)) * 24)), ch, font=f,
               fill=ACCENT + (int(255 * p),))
    # 下划线
    lw = int(total * ease_out(seg(t, 0.9, 1.7)))
    if lw:
        d.rectangle((x, 448, x + lw, 452), fill=ACCENT + (255,))
    p, dy = fade(t, 1.2, 1.9)
    if p > 0:
        title(d, (W // 2, 520 + dy), "把手表接上你的 DeepSeek Harness", 58, TEXT, int(255 * p), anchor="ma")
        body(d, (W // 2, 610 + dy), "实时动作 · 提权审批 · 局域网 / 公网", 34, MUTED, int(255 * p), anchor="ma")
    # 右下角小手表
    box = watch(img, 1620, 560, 200, 268, shot=A.pixel("wifi-direct", (200, 268)), glow=True)
    p2, _ = fade(t, 1.6, 2.4)
    if p2 > 0:
        mono_r(d, (1620, 760), "watch app", 24, MUTED, int(220 * p2), anchor="ma")
    p3, _ = fade(t, 2.2, 2.8)
    if p3 > 0:
        mono_r(d, (W // 2, 900), "press any key", 26, MUTED, int(160 * p3), anchor="ma")
    return img


def sc_problem(t, dur, A):
    img = Image.new("RGBA", (W, H), BG + (255,))
    img.alpha_composite(backdrop(img, t))
    d = ImageDraw.Draw(img, "RGBA")
    header(img, d, t)

    # 左：空手表
    box = watch(img, 380, 520, 260, 348, shot=None, glow=False)
    mini_ui(img, (box[0], box[1], box[2], box[3]), t) if False else None
    d = ImageDraw.Draw(img, "RGBA")
    mono(d, ((box[0] + box[2]) // 2, (box[1] + box[3]) // 2), "?", 120, (48, 54, 74), anchor="mm")

    # 中：断开的连线
    x_start, x_end, y = box[2] + 40, 1180, (box[1] + box[3]) // 2
    for x in range(x_start, x_end - 110, 26):
        d.rectangle((x, y - 2, x + 14, y + 2), fill=(58, 68, 94))
    mx = (x_start + x_end) // 2
    d.line((mx - 22, y - 26, mx + 22, y + 22), fill=RED + (255,), width=6)
    d.line((mx + 22, y - 26, mx - 22, y + 22), fill=RED + (255,), width=6)

    # 右：在跑的电脑
    px, py = 1480, 520
    d.rounded_rectangle((px - 210, py - 150, px + 210, py + 130), radius=12,
                        fill=(12, 15, 22), outline=LINE, width=2)
    d.rectangle((px - 192, py - 132, px + 192, py + 92), fill=(16, 42, 28))
    for i in range(6):
        w = 60 + (i * 53 + int(t * 60)) % 200
        d.rectangle((px - 176, py - 116 + i * 26, px - 176 + w, py - 102 + i * 26),
                    fill=(150, 235, 175, 210))
    d.rectangle((px - 26, py + 130, px + 26, py + 150), fill=(58, 66, 88))
    d.rectangle((px - 78, py + 150, px + 78, py + 160), fill=(58, 66, 88))
    mono_r(d, (px, py + 196), "agent is still running…", 26, MUTED, anchor="ma")

    p, dy = fade(t, 0.6, 1.4)
    title(d, (W // 2, 790 + dy), "出门在外，Agent 还在跑", 64, TEXT, int(255 * p), anchor="ma")
    p2, dy2 = fade(t, 1.2, 2.0)
    body(d, (W // 2, 876 + dy2), "你却看不到它在做什么", 36, MUTED, int(255 * p2), anchor="ma")
    return img


def sc_live(t, dur, A):
    img = Image.new("RGBA", (W, H), BG + (255,))
    img.alpha_composite(backdrop(img, t))
    d = ImageDraw.Draw(img, "RGBA")
    header(img, d, t)

    box = watch(img, 470, 540, 380, 508, shot=A.sharp["live-events-and-command"], glow=True)
    d = ImageDraw.Draw(img, "RGBA")
    # 高亮框在动作行上扫
    k = (t * 0.35) % 1.0
    hy = box[1] + 60 + int(k * (box[3] - box[1] - 200))
    d.rounded_rectangle((box[0] + 16, hy, box[2] - 16, hy + 40), radius=8,
                        outline=ACCENT + (200,), width=3)

    x = 960
    title(d, (x, 300), "实时动作流", 72, TEXT)
    bullet(d, x, 430, "工具调用与命令，一行不落", t, 0.5)
    bullet(d, x, 500, "执行结果、成功/失败一眼可见", t, 0.9)
    bullet(d, x, 570, "模型的每段回复实时滚上来", t, 1.3)
    body(d, (x, 690), "不用掏电脑：抬手就能盯住这一轮跑到哪了", 30, MUTED, int(255 * ease_out(seg(t, 1.7, 2.4))))
    return img


def sc_approval(t, dur, A):
    img = Image.new("RGBA", (W, H), BG + (255,))
    img.alpha_composite(backdrop(img, t))
    d = ImageDraw.Draw(img, "RGBA")
    header(img, d, t)

    box = watch(img, 470, 540, 380, 508, shot=A.sharp["approval"], glow=True)
    d = ImageDraw.Draw(img, "RGBA")

    # 手指点「允许」：位置按表框比例算，保证不跑偏
    bw, bh = box[2] - box[0], box[3] - box[1]
    fx = box[0] + int(bw * 0.30)
    fy = box[1] + int(bh * 0.79)
    p = ease_out(seg(t, 0.9, 2.0))
    if p > 0.02:
        cx = int(W * 0.62 + (fx - W * 0.62) * p)
        cy = int(H * 0.95 + (fy - H * 0.95) * p)
        d.ellipse((cx - 22, cy - 22, cx + 22, cy + 22), fill=(242, 246, 255), outline=(90, 100, 130), width=3)
        d.ellipse((cx - 10, cy - 10, cx + 10, cy + 10), fill=(200, 210, 235))
    if t > 2.2:
        rp = clamp01((t - 2.2) / 1.0)
        r = int(30 + rp * 120)
        ra = max(0, int(230 * (1 - rp)))
        if ra > 0:
            d.ellipse((fx - r, fy - r, fx + r, fy + r), outline=GREEN + (ra,), width=5)

    x = 960
    title(d, (x, 300), "手表上直接审批", 72, TEXT)
    bullet(d, x, 430, "提权请求会推到表上，带命令与原因", t, 0.5)
    bullet(d, x, 500, "点「允许」或「拒绝」，电脑那边立刻生效", t, 0.9, accent=GREEN)
    bullet(d, x, 570, "不理会就倒计时，自动转交电脑弹窗", t, 1.3)
    body(d, (x, 690), "危险操作不用跑回电脑点确认", 30, MUTED, int(255 * ease_out(seg(t, 1.7, 2.4))))
    return img


def sc_pairing(t, dur, A):
    img = Image.new("RGBA", (W, H), BG + (255,))
    img.alpha_composite(backdrop(img, t))
    d = ImageDraw.Draw(img, "RGBA")
    header(img, d, t)

    watch(img, 470, 540, 380, 508, shot=A.sharp["wifi-direct"], glow=True)
    d = ImageDraw.Draw(img, "RGBA")

    x = 960
    title(d, (x, 300), "扫一眼就能连上", 72, TEXT)
    bullet(d, x, 420, "局域网里自动发现电脑，不用记 IP", t, 0.4)
    bullet(d, x, 490, "6 位配对码，一次性、自动轮换", t, 0.8)
    bullet(d, x, 560, "配对后免配置，换网自动切地址", t, 1.2)

    # 配对码：6 个独立方块，等距、垂直居中
    code = "482913"
    bw2, gap = 92, 18
    total = 6 * bw2 + 5 * gap
    col_center = 1310
    bx0 = col_center - total // 2
    for i, ch in enumerate(code):
        p = clamp01((t - 1.5 - i * 0.1) / 0.3)
        if p <= 0:
            continue
        bx = bx0 + i * (bw2 + gap)
        by = 700 - int((1 - ease_out(p)) * 18)
        d.rounded_rectangle((bx, by, bx + bw2, by + 118), radius=12, fill=PANEL2,
                            outline=ACCENT_D, width=2)
        mono(d, (bx + bw2 // 2, by + 59), ch, 62, TEXT, int(255 * p), anchor="mm")
    body(d, (col_center, 848), "电脑上打开 /pair 页面就能看到配对码", 28, MUTED,
         int(255 * ease_out(seg(t, 2.2, 2.9))), anchor="ma")
    return img


def sc_remote(t, dur, A):
    img = Image.new("RGBA", (W, H), BG + (255,))
    img.alpha_composite(backdrop(img, t))
    d = ImageDraw.Draw(img, "RGBA")
    header(img, d, t)

    # 左：竖向链路 watch -> cloud -> pc
    cx = 470
    wy, cy, py = 250, 520, 790
    for y in range(wy + 150, cy - 60, 18):
        d.rectangle((cx - 2, y, cx + 2, y + 8), fill=(52, 62, 88))
    for y in range(cy + 60, py - 110, 18):
        d.rectangle((cx - 2, y, cx + 2, y + 8), fill=(52, 62, 88))

    box = watch(img, cx, wy, 190, 254, shot=A.pixel("public-tunnel", (190, 254)), glow=True)
    d = ImageDraw.Draw(img, "RGBA")

    for (ox, oy, r) in [(-56, 10, 34), (0, -14, 46), (56, 10, 34), (0, 16, 38)]:
        d.ellipse((cx + ox - r, cy + oy - r, cx + ox + r, cy + oy + r), fill=(38, 50, 76))
    mono_r(d, (cx, cy), "NET", 30, (150, 190, 255), anchor="mm")

    d.rounded_rectangle((cx - 200, py - 110, cx + 200, py + 80), radius=12,
                        fill=(12, 15, 22), outline=LINE, width=2)
    d.rectangle((cx - 184, py - 94, cx + 184, py + 52), fill=(18, 46, 30))
    for i in range(5):
        w = 50 + (i * 61 + int(t * 40)) % 220
        d.rectangle((cx - 168, py - 80 + i * 26, cx - 168 + w, py - 66 + i * 26),
                    fill=(150, 235, 175, 210))

    for k in range(9):
        prog = ((t * 0.5) + k / 9) % 1.0
        if prog < 0.5:
            te = prog / 0.5
            y = (wy + 150) + (cy - 60 - (wy + 150)) * te
            col = ACCENT
        else:
            te = (prog - 0.5) / 0.5
            y = (cy + 60) + (py - 110 - (cy + 60)) * te
            col = GREEN
        d.rectangle((cx - 5, y - 5, cx + 5, y + 5), fill=col)

    x = 960
    title(d, (x, 300), "不在同一网络也能连", 72, TEXT)
    bullet(d, x, 420, "公网隧道：cloudflared / ssh 一键起", t, 0.4)
    bullet(d, x, 490, "隧道把 SSE 缓冲住？自动切 2 秒轮询", t, 0.8)
    bullet(d, x, 560, "地址变了、网断了，自动切回可用路径", t, 1.2)
    body(d, (x, 690), "手表只要有网，人在外面也能用", 30, MUTED,
         int(255 * ease_out(seg(t, 1.7, 2.4))))
    return img


def sc_install(t, dur, A):
    img = Image.new("RGBA", (W, H), BG + (255,))
    img.alpha_composite(backdrop(img, t))
    d = ImageDraw.Draw(img, "RGBA")
    header(img, d, t)

    title(d, (MARGIN, 150), "三步装上", 64, TEXT)
    body(d, (MARGIN, 236), "Windows + 一块 Android 手表，全程不用 Gradle", 30, MUTED)

    steps = [
        ("1", "pwsh -File tools\\install-plugin.ps1", "把电脑端插件装进 DSH profile"),
        ("2", "pwsh -File tools\\build.ps1", "打出 54 KB 的 APK（aapt2 → javac → d8）"),
        ("3", "手表配对 6 位码", "连上后抬手就能用"),
    ]
    for i, (num, cmd, note) in enumerate(steps):
        p, dy = fade(t, 0.4 + i * 0.9, 1.0 + i * 0.9)
        if p <= 0:
            continue
        y = 320 + i * 190 + dy
        d.rounded_rectangle((MARGIN, y, W - MARGIN, y + 150), radius=12,
                            fill=PANEL, outline=LINE, width=2)
        d.rounded_rectangle((MARGIN + 28, y + 44, MARGIN + 92, y + 108), radius=8, fill=ACCENT)
        mono(d, (MARGIN + 60, y + 76), num, 34, (255, 255, 255), int(255 * p), anchor="mm")
        any("\u4e00" <= ch <= "\u9fff" for ch in cmd) and body(d, (MARGIN + 124, y + 34), cmd, 32, TEXT, int(255 * p))
        if not any("\u4e00" <= ch <= "\u9fff" for ch in cmd):
            mono_r(d, (MARGIN + 124, y + 34), cmd, 34, TEXT, int(255 * p))
        body(d, (MARGIN + 124, y + 88), note, 28, MUTED, int(255 * p))
        if p > 0.85:
            ax = W - MARGIN - 70
            d.line((ax, y + 66, ax + 22, y + 96), fill=GREEN + (255,), width=7)
            d.line((ax + 22, y + 96, ax + 62, y + 34), fill=GREEN + (255,), width=7)
    return img


def sc_outro(t, dur, A):
    img = Image.new("RGBA", (W, H), BG + (255,))
    img.alpha_composite(backdrop(img, t))
    d = ImageDraw.Draw(img, "RGBA")

    bob = int(math.sin(t * 3.4) * 10)
    egg = A.egg.resize((320, 320), Image.NEAREST)
    img.alpha_composite(egg, (300 - 160, 540 - 160 + bob))
    d = ImageDraw.Draw(img, "RGBA")

    p, dy = fade(t, 0.4, 1.2)
    mono(d, (720, 330 + dy), "dsh-watch", 86, ACCENT, int(255 * p))
    body(d, (724, 440 + dy), "把手表接上你的 DeepSeek Harness", 40, TEXT, int(255 * p))
    mono_r(d, (724, 520), "54 KB  ·  MIT  ·  zero dependency  ·  rollback friendly", 28,
           MUTED, int(255 * p))

    p2, dy2 = fade(t, 1.4, 2.4)
    if p2 > 0:
        body(d, (724, 640 + dy2), "install-plugin.ps1  →  build.ps1  →  手表配对", 32, TEXT, int(255 * p2))
        body(d, (724, 700 + dy2), "出门也能盯活，抬手就能审批", 32, MUTED, int(255 * p2))
    return img


SCENES = [
    ("title", sc_title, 4.6),
    ("problem", sc_problem, 5.8),
    ("live", sc_live, 7.2),
    ("approval", sc_approval, 7.6),
    ("pairing", sc_pairing, 6.6),
    ("remote", sc_remote, 7.6),
    ("install", sc_install, 7.0),
    ("outro", sc_outro, 7.0),
]
TOTAL = sum(s[2] for s in SCENES)


# ------------------------------------------------------------------ 转场/后期
def block_wipe(a, b, p, seed=3, cell=60):
    if p <= 0:
        return a
    if p >= 1:
        return b
    out = a.copy()
    rnd = random.Random(seed)
    cells = [(x, y) for y in range(0, H, cell) for x in range(0, W, cell)]
    rnd.shuffle(cells)
    for (x, y) in cells[: int(len(cells) * ease_io(p))]:
        box = (x, y, min(W, x + cell), min(H, y + cell))
        out.paste(b.crop(box), box)
    return out


def make_vignette():
    v = Image.new("L", (W, H), 0)
    ImageDraw.Draw(v).ellipse((-W * 0.2, -H * 0.2, W * 1.2, H * 1.2), fill=255)
    v = v.filter(ImageFilter.GaussianBlur(180))
    v = Image.eval(v, lambda x: 255 - x)
    out = Image.new("RGBA", (W, H), (0, 0, 0, 255))
    out.putalpha(v.point(lambda x: int(x * 0.5)))
    return out


def make_scanlines():
    ov = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(ov)
    for y in range(0, H, 4):
        d.line((0, y, W, y), fill=(0, 0, 0, 9))
    return ov


# ------------------------------------------------------------------ 音频
def make_audio(path, duration):
    sr = 44100
    n = int(sr * duration)
    buf = [0.0] * n

    def add(samples, start, gain=0.25):
        i0 = int(start * sr)
        for k, s in enumerate(samples):
            idx = i0 + k
            if 0 <= idx < n:
                buf[idx] += s * gain

    def square(freq, dur, vol=0.5):
        cnt = int(sr * dur)
        out = []
        for i in range(cnt):
            env = min(1.0, i / 200) * (1 - i / cnt) ** 1.6
            out.append((1.0 if math.sin(2 * math.pi * freq * i / sr) > 0 else -1.0) * env * vol)
        return out

    def noise(dur, vol=0.35):
        cnt = int(sr * dur)
        rnd = random.Random(5)
        return [((rnd.random() * 2 - 1) * (1 - i / cnt) ** 2 * vol) for i in range(cnt)]

    chords = [(220.0, [220.0, 261.6, 329.6]), (196.0, [196.0, 246.9, 293.7]),
              (174.6, [174.6, 220.0, 261.6]), (164.8, [164.8, 207.7, 246.9])]
    bar, t, idx = 2.0, 0.0, 0
    while t < duration:
        root, notes = chords[idx % 4]
        add(square(root / 2, bar * 0.9, 0.35), t, 0.20)
        for step in range(8):
            add(square(notes[step % 3] * (2 if step >= 4 else 1), bar / 8 * 0.9, 0.28),
                t + step * bar / 8, 0.10)
        for step in range(4):
            add(noise(0.03, 0.5), t + step * bar / 4 + bar / 8, 0.12)
        t += bar
        idx += 1

    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sr)
        frames = bytearray()
        for v in buf:
            frames += struct.pack("<h", int(max(-1.0, min(1.0, v)) * 22000))
        w.writeframes(bytes(frames))


# ------------------------------------------------------------------ 主流程
def main():
    stills = "--stills" in sys.argv
    ffmpeg = FFMPEG
    out = os.path.join(BASE, "dsh-watch-promo-wide.mp4")
    audio = os.path.join(WORK, "chiptune.wav")
    os.makedirs(WORK, exist_ok=True)

    A = Assets()
    timeline, t0 = [], 0.0
    for name, fn, dur in SCENES:
        timeline.append((name, fn, t0, dur))
        t0 += dur
    print(f"横屏 {W}x{H} / {TOTAL:.1f}s / {int(TOTAL * FPS)} 帧", flush=True)

    vig, scan = make_vignette(), make_scanlines()
    done = set()

    if not stills:
        make_audio(audio, TOTAL)
        proc = subprocess.Popen(
            [ffmpeg, "-y", "-hide_banner", "-loglevel", "error",
             "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}", "-r", str(FPS), "-i", "-",
             "-i", audio, "-c:v", "libx264", "-preset", "medium", "-crf", "20",
             "-pix_fmt", "yuv420p", "-af", "volume=11dB", "-c:a", "aac", "-b:a", "160k", "-shortest", out],
            stdin=subprocess.PIPE)

    total_frames = int(TOTAL * FPS)
    for fi in range(total_frames):
        t = fi / FPS
        cur = timeline[-1]
        for item in timeline:
            if item[2] <= t < item[2] + item[3]:
                cur = item
                break
        name, fn, start, dur = cur
        local = t - start
        frame = fn(local, dur, A)

        idx = [i for i, it in enumerate(timeline) if it[0] == name][0]
        if idx + 1 < len(timeline):
            nxt = timeline[idx + 1]
            if local > dur - 0.35:
                frame = block_wipe(frame, nxt[1](0.0, nxt[3], A), (local - (dur - 0.35)) / 0.35,
                                   seed=idx * 17 + 5)

        frame = Image.alpha_composite(frame, vig)
        rgb = frame.convert("RGB")
        rgb = Image.alpha_composite(rgb.convert("RGBA"), scan).convert("RGB")

        if stills and name not in done and local >= dur * 0.5:
            done.add(name)
            rgb.save(os.path.join(WORK, f"wide-{name}.png"))
            print("still", name, flush=True)

        if not stills:
            proc.stdin.write(rgb.tobytes())
        if fi % 200 == 0:
            print(f"  {fi}/{total_frames}", flush=True)

    if stills:
        print("静态帧 →", WORK)
        return
    proc.stdin.close()
    proc.wait()
    print("完成:", out, os.path.getsize(out) // 1024, "KB", flush=True)


if __name__ == "__main__":
    main()
