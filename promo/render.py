"""dsh-watch 像素风宣传片渲染器。

思路：所有画面先画在 360x640 的“像素质点层”上（真像素块），
再放大 3 倍到 1080x1920；中文说明文字单独用矢量字体叠在最上层保证可读。
"""

import math
import os
import random
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

# ---------------------------------------------------------------- 基本参数
W, H = 1080, 1920
SCALE = 3                 # 像素质点层放大倍数
PW, PH = W // SCALE, H // SCALE   # 360 x 640
FPS = 30

BG = (11, 13, 18)
PANEL = (19, 24, 38)
PANEL2 = (26, 33, 52)
ACCENT = (43, 108, 246)
ACCENT_D = (26, 70, 170)
TEXT = (230, 234, 242)
MUTED = (139, 147, 167)
GREEN = (53, 199, 89)
ORANGE = (255, 176, 32)
RED = (240, 84, 84)

FONT_DIR = r"C:\Windows\Fonts"
F_CN_BOLD = os.path.join(FONT_DIR, "msyhbd.ttc")
F_CN = os.path.join(FONT_DIR, "msyh.ttc")
F_MONO = os.path.join(FONT_DIR, "consolab.ttf")
F_MONO_R = os.path.join(FONT_DIR, "consola.ttf")


_font_cache = {}
_text_cache = {}


def font(path, size):
    key = (path, size)
    if key not in _font_cache:
        _font_cache[key] = ImageFont.truetype(path, size)
    return _font_cache[key]


def ease_out(x):
    return 1 - (1 - max(0.0, min(1.0, x))) ** 3


def ease_in_out(x):
    x = max(0.0, min(1.0, x))
    return x * x * (3 - 2 * x)


def clamp01(x):
    return max(0.0, min(1.0, x))


def seg(t, start, end):
    """把全局时间映射成 0..1 的段内进度。"""
    if t <= start:
        return 0.0
    if t >= end:
        return 1.0
    return (t - start) / (end - start)


# ---------------------------------------------------------------- 素材
def pixelize(path, size, colors=28):
    """把真机截图压成像素画：缩小 → 调色板量化 → 需要时再放大。"""
    img = Image.open(path).convert("RGB")
    img = img.resize(size, Image.LANCZOS)
    img = img.quantize(colors=colors, method=Image.MEDIANCUT, dither=Image.NONE)
    return img.convert("RGB")


class Assets:
    def __init__(self):
        os.makedirs(WORK, exist_ok=True)
        self.shots = {}      # 像素化版本：给缩略图用
        self.sharp = {}      # 原始清晰版：放大展示时用
        for fname in sorted(os.listdir(SHOTS)):
            if not (fname.startswith("dsh-watch-") and fname.endswith(".png")):
                continue
            key = fname[len("dsh-watch-"):-len(".png")]
            key = key.split("-", 1)[-1]
            path = os.path.join(SHOTS, fname)
            self.shots[key] = pixelize(path, (190, 250))
            self.sharp[key] = Image.open(path).convert("RGB")
        self.egg = Image.open(EGG).convert("RGBA")

    def shot(self, key, sharp=False):
        return (self.sharp if sharp else self.shots).get(key)


# ---------------------------------------------------------------- 绘制工具
def px_text(draw, xy, text, size, color, font_path=F_MONO, anchor="la"):
    draw.text(xy, text, font=font(font_path, size), fill=color, anchor=anchor)


def has_cjk(text):
    return any("\u4e00" <= ch <= "\u9fff" for ch in text)


def px_auto(draw, xy, text, size, color, anchor="la"):
    """有中文就用中文字体，没有就用等宽像素字体。"""
    path = F_CN if has_cjk(text) else F_MONO_R
    draw.text(xy, text, font=font(path, size), fill=color, anchor=anchor)


def crisp(draw, xy, text, size, color, anchor="la", bold=True, spacing=0):
    f = font(F_CN_BOLD if bold else F_CN, size)
    draw.text(xy, text, font=f, fill=color, anchor=anchor)


def panel(draw, box, fill, outline=None, radius=4, width=1):
    draw.rounded_rectangle(box, radius=radius, fill=fill, outline=outline, width=width)


def typed(draw, xy, text, size, color, progress, path=F_CN_BOLD, anchor="la", spacing=0):
    """打字机效果：按字符数截断（用 x 偏移精确定位，中文也不抖）。"""
    n = int(round(len(text) * clamp01(progress)))
    if n <= 0:
        return
    f = font(path, size)
    draw.text(xy, text[:n], font=f, fill=color, anchor=anchor)


def watch_frame(layer, cx, cy, w, h, shot=None, glow=False, radius=None):
    """画一块手表：外框 + 屏幕 + 可选像素截图。坐标在像素质点层。"""
    d = ImageDraw.Draw(layer, "RGBA")
    r = radius if radius is not None else int(w * 0.22)
    if glow:
        g = Image.new("RGBA", layer.size, (0, 0, 0, 0))
        gd = ImageDraw.Draw(g)
        gd.rounded_rectangle((cx - w // 2 - 4, cy - h // 2 - 4, cx + w // 2 + 4, cy + h // 2 + 4),
                             radius=r + 3, fill=ACCENT + (70,))
        g = g.filter(ImageFilter.GaussianBlur(6))
        layer.alpha_composite(g)
        d = ImageDraw.Draw(layer, "RGBA")
    d.rounded_rectangle((cx - w // 2 - 3, cy - h // 2 - 3, cx + w // 2 + 3, cy + h // 2 + 3),
                        radius=r + 3, fill=(38, 45, 64), outline=(60, 70, 96), width=1)
    d.rounded_rectangle((cx - w // 2, cy - h // 2, cx + w // 2, cy + h // 2),
                        radius=r, fill=(7, 8, 12))
    if shot is not None:
        sharp = shot.size[0] > 260          # 原图（378x496）就用高质量缩放，保持文字可读
        resample = Image.LANCZOS if sharp else Image.NEAREST
        s = shot.resize((w - 2, h - 2), resample)
        mask = Image.new("L", s.size, 0)
        ImageDraw.Draw(mask).rounded_rectangle((0, 0, s.size[0] - 1, s.size[1] - 1),
                                               radius=max(2, r - 1), fill=255)
        layer.paste(s, (cx - w // 2 + 1, cy - h // 2 + 1), mask)
    return layer


def mini_ui(layer, cx, cy, w, h):
    """没有截图时在屏幕里画一版迷你 UI（状态点 + 事件行 + 底部按钮）。"""
    d = ImageDraw.Draw(layer, "RGBA")
    x0, y0 = cx - w // 2 + 6, cy - h // 2 + 6
    x1, y1 = cx + w // 2 - 6, cy + h // 2 - 6
    d.ellipse((x0, y0, x0 + 5, y0 + 5), fill=ORANGE)
    d.rectangle((x0 + 9, y0, x0 + 42, y0 + 5), fill=TEXT)
    y = y0 + 14
    rows = [(0.9, ACCENT), (0.6, (120, 130, 150)), (0.8, TEXT), (0.5, (110, 200, 130)),
            (0.7, ACCENT), (0.4, (120, 130, 150)), (0.65, TEXT), (0.5, (110, 200, 130))]
    usable = y1 - 26 - y
    step = max(7, usable // len(rows))
    for i, (ratio, col) in enumerate(rows):
        ry = y + i * step
        d.rectangle((x0, ry, x0 + int((x1 - x0) * ratio), ry + 3), fill=col + (230,))
    bw = (x1 - x0 - 8) // 3
    for i in range(3):
        bx = x0 + i * (bw + 4)
        d.rounded_rectangle((bx, y1 - 20, bx + bw, y1 - 4), radius=3,
                            fill=ACCENT if i == 0 else PANEL2)
    return layer


def block_dissolve(a, b, p, seed=7, cell=6):
    """像素方块擦除转场。"""
    if p <= 0:
        return a
    if p >= 1:
        return b
    out = a.copy()
    rnd = random.Random(seed)
    cells = [(x, y) for y in range(0, PH, cell) for x in range(0, PW, cell)]
    rnd.shuffle(cells)
    n = int(len(cells) * ease_in_out(p))
    for (x, y) in cells[:n]:
        box = (x, y, min(PW, x + cell), min(PH, y + cell))
        out.paste(b.crop(box), box)
    return out


# ---------------------------------------------------------------- 场景
def scene_title(t, dur, A):
    layer = Image.new("RGBA", (PW, PH), BG + (255,))
    d = ImageDraw.Draw(layer, "RGBA")
    # 背景网格
    for x in range(0, PW, 24):
        d.line((x, 0, x, PH), fill=(18, 22, 33, 255))
    for y in range(0, PH, 24):
        d.line((0, y, PW, y), fill=(18, 22, 33, 255))

    p = ease_out(seg(t, 0.15, 1.0))
    # 标题逐字母浮现
    title = "DSH WATCH"
    fs = 22
    f = font(F_MONO, fs)
    total = d.textlength(title, font=f)
    x0 = (PW - total) / 2
    for i, ch in enumerate(title):
        appear = clamp01((t - 0.2 - i * 0.07) / 0.25)
        if appear <= 0:
            continue
        dx = x0 + d.textlength(title[:i], font=f)
        yy = 168 - int((1 - ease_out(appear)) * 16)
        col = ACCENT if ch not in " " else BG
        d.text((dx, yy), ch, font=f, fill=col + (int(255 * appear),))

    # 下划线扫描
    line_w = int(total * ease_out(seg(t, 0.9, 1.8)))
    if line_w > 0:
        d.rectangle((x0, 200, x0 + line_w, 202), fill=ACCENT + (255,))

    # 小手表图标（像素）
    bob = int(math.sin(t * 3.2) * 3)
    watch_frame(layer, PW // 2, 400 + bob, 92, 122, shot=None, glow=True)
    mini_ui(layer, PW // 2, 400 + bob, 92, 122)

    # 底部提示
    if t > 1.2:
        px_text(d, (PW // 2, 585), "press any key", 7, MUTED, F_MONO_R, "ma")
    return layer


def scene_problem(t, dur, A):
    layer = Image.new("RGBA", (PW, PH), BG + (255,))
    d = ImageDraw.Draw(layer, "RGBA")
    # 桌面 + 亮着的电脑
    d.rectangle((0, 470, PW, PH), fill=(14, 17, 25, 255))
    cx, cy = 210, 380
    d.rectangle((cx - 62, cy - 44, cx + 62, cy + 34), fill=(10, 12, 18), outline=(58, 66, 88))
    pulse = 0.55 + 0.45 * math.sin(t * 4.0)
    scr = (int(30 + 40 * pulse), int(70 + 60 * pulse), int(150 + 80 * pulse))
    d.rectangle((cx - 56, cy - 38, cx + 56, cy + 28), fill=scr + (255,))
    # 屏幕上滚动的代码行
    for i in range(6):
        w = 18 + ((i * 37 + int(t * 40)) % 70)
        d.rectangle((cx - 48, cy - 30 + i * 9, cx - 48 + w, cy - 27 + i * 9),
                    fill=(200, 225, 255, 180))
    d.rectangle((cx - 10, cy + 34, cx + 10, cy + 42), fill=(58, 66, 88))
    d.rectangle((cx - 26, cy + 42, cx + 26, cy + 46), fill=(58, 66, 88))

    # 手表在旁边，但联系断开（虚线 + 断叉）
    hx, hy = 96, 300
    watch_frame(layer, hx, hy, 58, 76, shot=None)
    px_text(ImageDraw.Draw(layer, "RGBA"), (hx, hy), "?", 26, (56, 62, 82), F_MONO, "mm")
    d2 = ImageDraw.Draw(layer, "RGBA")
    for i in range(0, 62, 8):
        d2.line((hx + 34 + i, hy - 6 - i * 0.1, hx + 38 + i, hy - 6 - i * 0.1),
                fill=(120, 60, 60, 220), width=1)
    d2.line((hx + 72, hy - 22, hx + 86, hy - 4), fill=RED + (255,), width=2)
    d2.line((hx + 86, hy - 22, hx + 72, hy - 4), fill=RED + (255,), width=2)
    return layer


def scene_live(t, dur, A):
    layer = Image.new("RGBA", (PW, PH), BG + (255,))
    d = ImageDraw.Draw(layer, "RGBA")
    slide = int((1 - ease_out(seg(t, 0, 0.8))) * 40)
    watch_frame(layer, PW // 2 + 60 + slide, 300, 186, 244,
                shot=A.shot("live-events-and-command", sharp=True), glow=True)
    # 高亮框在动作行上扫动
    if t > 0.6:
        k = (t * 0.6) % 1.0
        y = 210 + int(k * 150)
        d.rectangle((PW // 2 + 60 - 74, y, PW // 2 + 60 + 74, y + 12),
                    outline=ACCENT + (200,), width=1)
    return layer


def scene_approval(t, dur, A):
    layer = Image.new("RGBA", (PW, PH), BG + (255,))
    d = ImageDraw.Draw(layer, "RGBA")
    watch_frame(layer, PW // 2 - 70, 300, 186, 244, shot=A.shot("approval", sharp=True), glow=True)
    # 手指点击：从右下飞入
    p = ease_out(seg(t, 0.6, 2.2))
    fx = int((PW - 40) + (PW // 2 - 60 - (PW - 40)) * p)
    fy = int(PH - 60 + (300 + 70 - (PH - 60)) * p)
    if p > 0.02:
        d.ellipse((fx - 7, fy - 7, fx + 7, fy + 7), fill=(240, 245, 255), outline=(90, 100, 130))
    if t > 2.4:
        rp = clamp01((t - 2.4) / 0.9)
        r = int(10 + rp * 40)
        d.ellipse((fx - r, fy - r, fx + r, fy + r), outline=GREEN + (int(220 * (1 - rp)),), width=2)
    return layer


def scene_pairing(t, dur, A):
    layer = Image.new("RGBA", (PW, PH), BG + (255,))
    d = ImageDraw.Draw(layer, "RGBA")
    watch_frame(layer, PW // 2, 290, 186, 244, shot=A.shot("connect-screen", sharp=True), glow=True)
    # 6 位码逐个跳出
    code = "482913"
    fs = 20
    f = font(F_MONO, fs)
    total = d.textlength(code, font=f) + 5 * 8
    x0 = (PW - total) / 2
    for i, ch in enumerate(code):
        app = clamp01((t - 0.8 - i * 0.12) / 0.25)
        if app <= 0:
            continue
        bx = x0 + i * (fs + 4)
        by = 500 - int((1 - ease_out(app)) * 14)
        d.rounded_rectangle((bx - 3, by - 6, bx + fs + 1, by + fs + 2), radius=2,
                            fill=PANEL2, outline=ACCENT_D)
        d.text((bx, by), ch, font=f, fill=TEXT if app > 0.5 else MUTED)
    return layer


def scene_remote(t, dur, A):
    """竖向三段：手表 → 公网 → 电脑，数据点沿着线跑。"""
    layer = Image.new("RGBA", (PW, PH), BG + (255,))
    d = ImageDraw.Draw(layer, "RGBA")
    cx = PW // 2
    wy, cy, py = 118, 285, 432

    for y in range(wy + 72, cy - 32, 7):
        d.rectangle((cx - 1, y, cx + 1, y + 3), fill=(52, 62, 88))
    for y in range(cy + 32, py - 46, 7):
        d.rectangle((cx - 1, y, cx + 1, y + 3), fill=(52, 62, 88))

    watch_frame(layer, cx, wy, 92, 122, shot=A.shot("live-events-and-command", sharp=True), glow=True)


    for (ox, oy, r) in [(-24, 5, 14), (0, -6, 19), (24, 5, 14), (0, 7, 16)]:
        d.ellipse((cx + ox - r, cy + oy - r, cx + ox + r, cy + oy + r), fill=(38, 50, 76))
    px_text(d, (cx, cy), "NET", 9, (150, 190, 255), F_MONO, "mm")
    px_text(d, (cx, cy + 48), "public tunnel", 7, MUTED, F_MONO_R, "ma")

    d.rounded_rectangle((cx - 92, py - 44, cx + 92, py + 34), radius=3,
                        fill=(12, 15, 22), outline=(58, 66, 88))
    d.rectangle((cx - 84, py - 36, cx + 84, py + 18), fill=(18, 46, 30))
    for i in range(5):
        w = 24 + (i * 29 + int(t * 30)) % 90
        d.rectangle((cx - 78, py - 30 + i * 9, cx - 78 + w, py - 26 + i * 9),
                    fill=(150, 235, 175, 210))
    px_text(d, (cx, py + 48), "DSH on your PC", 7, MUTED, F_MONO_R, "ma")

    for k in range(8):
        prog = ((t * 0.5) + k / 8) % 1.0
        if prog < 0.5:
            te = prog / 0.5
            y = (wy + 72) + (cy - 32 - (wy + 72)) * te
            col = ACCENT
        else:
            te = (prog - 0.5) / 0.5
            y = (cy + 32) + (py - 46 - (cy + 32)) * te
            col = GREEN
        d.rectangle((cx - 2, y - 2, cx + 2, y + 2), fill=col)
    return layer


def scene_install(t, dur, A):
    layer = Image.new("RGBA", (PW, PH), BG + (255,))
    d = ImageDraw.Draw(layer, "RGBA")
    steps = [
        ("1", "pwsh -File tools\\install-plugin.ps1", "装电脑端插件"),
        ("2", "pwsh -File tools\\build.ps1", "打出 54KB 的 APK"),
        ("3", "手表配对 6 位码", "连上，开跑"),
    ]
    for i, (num, cmd, note) in enumerate(steps):
        app = clamp01((t - 0.3 - i * 1.1) / 0.6)
        if app <= 0:
            continue
        y = 210 + i * 96
        dx = int((1 - ease_out(app)) * 24)
        d.rounded_rectangle((28 + dx, y, PW - 28 + dx, y + 76), radius=4,
                            fill=PANEL, outline=(40, 50, 74))
        d.rounded_rectangle((36 + dx, y + 28, 58 + dx, y + 50), radius=3, fill=ACCENT)
        px_text(d, (47 + dx, y + 39), num, 12, (255, 255, 255), F_MONO, "mm")
        px_auto(d, (72 + dx, y + 18), cmd, 9, TEXT, "la")
        px_auto(d, (72 + dx, y + 46), note, 10, MUTED, "la")
        if app > 0.85:
            d.line((PW - 62, y + 38, PW - 52, y + 50), fill=GREEN, width=2)
            d.line((PW - 52, y + 50, PW - 36, y + 24), fill=GREEN, width=2)
    return layer


def scene_outro(t, dur, A):
    layer = Image.new("RGBA", (PW, PH), BG + (255,))
    d = ImageDraw.Draw(layer, "RGBA")
    bob = int(math.sin(t * 3.6) * 4)
    egg = A.egg.resize((150, 150), Image.NEAREST)
    layer.alpha_composite(egg, (PW // 2 - 75, 150 + bob))
    px_text(d, (PW // 2, 330), "dsh-watch", 20, ACCENT, F_MONO, "ma")
    px_text(d, (PW // 2, 362), "54 KB  ·  pixel friendly  ·  MIT", 7, MUTED, F_MONO_R, "ma")
    return layer


SCENES = [
    ("title", scene_title, 4.2),
    ("problem", scene_problem, 6.3),
    ("live", scene_live, 8.0),
    ("approval", scene_approval, 8.0),
    ("pairing", scene_pairing, 7.0),
    ("remote", scene_remote, 8.0),
    ("install", scene_install, 7.0),
    ("outro", scene_outro, 8.0),
]

# 每个场景顶部的中文说明（矢量层，保证可读）
CAPTIONS = {
    "problem": ("出门在外，Agent 还在跑", "你却看不到它在做什么"),
    "live": ("实时动作流", "工具 · 结果 · 模型回复，一行不落"),
    "approval": ("手表上直接审批", "提权请求来了，点一下允许或拒绝"),
    "pairing": ("6 位码配对", "局域网自动发现，不用记 IP"),
    "remote": ("不在同一网络也能连", "公网隧道 + 轮询同步，延迟约 2 秒"),
    "install": ("三步装上", ""),
    "outro": ("", ""),
}


def draw_captions(canvas, scene, t, dur):
    d = ImageDraw.Draw(canvas, "RGBA")
    if scene == "title":
        app = ease_out(seg(t, 0.9, 1.8))
        if app > 0:
            d.text((W // 2, 720), "把手表接上你的 DeepSeek Harness", font=font(F_CN_BOLD, 54),
                   fill=TEXT + (int(255 * app),), anchor="ma")
            d.text((W // 2, 800), "实时动作 · 提权审批 · 局域网 / 公网", font=font(F_CN, 34),
                   fill=MUTED + (int(255 * app),), anchor="ma")
    elif scene == "outro":
        app = ease_out(seg(t, 0.5, 1.4))
        if app > 0:
            d.text((W // 2, 1260), "三步装上，出门也能盯活", font=font(F_CN_BOLD, 52),
                   fill=TEXT + (int(255 * app),), anchor="ma")
            d.text((W // 2, 1350), "install-plugin.ps1  →  build.ps1  →  手表配对",
                   font=font(F_CN, 30), fill=MUTED + (int(255 * app),), anchor="ma")
    elif scene in CAPTIONS:
        big, small = CAPTIONS[scene]
        if big:
            app = ease_out(seg(t, 0.15, 0.85))
            y = 1500 - int((1 - app) * 24)
            d.text((W // 2, y), big, font=font(F_CN_BOLD, 62), fill=TEXT + (int(255 * app),), anchor="ma")
        if small:
            app = ease_out(seg(t, 0.5, 1.2))
            d.text((W // 2, 1600 - int((1 - app) * 16)), small, font=font(F_CN, 36),
                   fill=MUTED + (int(255 * app),), anchor="ma")

    # 顶部品牌条
    if scene not in ("title", "outro"):
        d.rectangle((0, 0, W, 4), fill=ACCENT + (255,))
        d.text((44, 62), "DSH WATCH", font=font(F_MONO, 30), fill=ACCENT + (255,))
        d.text((W - 44, 66), "手表远程 · 像素版", font=font(F_CN, 28), fill=MUTED + (255,), anchor="ra")


def scanline_overlay():
    """预生成一层极淡的扫描线（alpha 合成，不会打出透明洞）。"""
    ov = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(ov)
    for y in range(0, H, 4):
        d.line((0, y, W, y), fill=(0, 0, 0, 8))
    return ov


def scanlines(frame, overlay):
    out = Image.alpha_composite(frame, overlay)
    return out


def vignette():
    """返回一张预乘好的暗角层（中心透明、四周压暗）。"""
    v = Image.new("L", (W, H), 0)
    d = ImageDraw.Draw(v)
    d.ellipse((-W * 0.25, -H * 0.15, W * 1.25, H * 1.15), fill=255)
    v = v.filter(ImageFilter.GaussianBlur(160))
    # 反相：中心 0（不压暗），四周 255（压暗）
    v = Image.eval(v, lambda x: 255 - x)
    layer = Image.new("RGBA", (W, H), (0, 0, 0, 255))
    layer.putalpha(v.point(lambda x: int(x * 0.55)))
    return layer


# ---------------------------------------------------------------- 音频
def make_audio(path, duration):
    import struct

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
        out = []
        cnt = int(sr * dur)
        for i in range(cnt):
            env = min(1.0, i / 200) * (1 - i / cnt) ** 1.6
            v = 1.0 if math.sin(2 * math.pi * freq * i / sr) > 0 else -1.0
            out.append(v * env * vol)
        return out

    def noise(dur, vol=0.35):
        out = []
        cnt = int(sr * dur)
        rnd = random.Random(3)
        for i in range(cnt):
            env = (1 - i / cnt) ** 2
            out.append((rnd.random() * 2 - 1) * env * vol)
        return out

    # 简单和弦进行 (A minor-ish)，120 BPM，一小节 2 秒
    chords = [
        (220.0, [220.0, 261.6, 329.6]),
        (196.0, [196.0, 246.9, 293.7]),
        (174.6, [174.6, 220.0, 261.6]),
        (164.8, [164.8, 207.7, 246.9]),
    ]
    bar = 2.0
    t = 0.0
    idx = 0
    while t < duration:
        root, notes = chords[idx % len(chords)]
        # bass
        add(square(root / 2, bar * 0.9, 0.35), t, 0.20)
        # arpeggio 8 分音符
        for step in range(8):
            f = notes[step % 3] * (2 if step >= 4 else 1)
            add(square(f, bar / 8 * 0.9, 0.28), t + step * bar / 8, 0.10)
        # hat
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
            s = int(max(-1.0, min(1.0, v)) * 22000)
            frames += struct.pack("<h", s)
        w.writeframes(bytes(frames))


# ---------------------------------------------------------------- 主流程
def main():
    only_still = "--stills" in sys.argv
    ffmpeg = FFMPEG
    out_mp4 = os.path.join(BASE, "dsh-watch-promo.mp4")
    audio = os.path.join(WORK, "chiptune.wav")

    A = Assets()
    total = sum(d for _, _, d in SCENES)
    print(f"总时长 {total:.1f}s / {int(total * FPS)} 帧", flush=True)

    # 时间轴
    timeline = []
    t0 = 0.0
    for name, fn, dur in SCENES:
        timeline.append((name, fn, t0, dur))
        t0 += dur

    vmask = vignette()
    scan_overlay = scanline_overlay()
    stills_done = set()

    if not only_still:
        make_audio(audio, total)
        cmd = [ffmpeg, "-y", "-hide_banner", "-loglevel", "error",
               "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}", "-r", str(FPS), "-i", "-",
               "-i", audio,
               "-c:v", "libx264", "-preset", "medium", "-crf", "20", "-pix_fmt", "yuv420p",
               "-c:a", "aac", "-b:a", "128k", "-shortest", out_mp4]
        proc = subprocess.Popen(cmd, stdin=subprocess.PIPE)

    total_frames = int(total * FPS)
    for fi in range(total_frames):
        t = fi / FPS
        # 找当前场景
        cur = timeline[-1]
        for item in timeline:
            if item[2] <= t < item[2] + item[3]:
                cur = item
                break
        name, fn, start, dur = cur
        local = t - start
        layer = fn(local, dur, A)

        # 转场：场景结束前 0.35s 用像素方块擦到下一场景
        nxt = None
        for i, item in enumerate(timeline):
            if item[0] == name and i + 1 < len(timeline):
                nxt = timeline[i + 1]
        TRANS = 0.35
        if nxt and local > dur - TRANS:
            p = (local - (dur - TRANS)) / TRANS
            nxt_layer = nxt[1](0.0, nxt[3], A)
            layer = block_dissolve(layer, nxt_layer, p, seed=hash(name) % 1000)

        # 像素质点层 → 全尺寸
        frame = layer.resize((W, H), Image.NEAREST).convert("RGBA")
        frame = Image.alpha_composite(frame, vmask)
        draw_captions(frame, name, local, dur)

        rgb = Image.alpha_composite(Image.new("RGBA", (W, H), BG + (255,)), frame)
        rgb = scanlines(rgb, scan_overlay).convert("RGB")

        if only_still and name not in stills_done and local >= dur * 0.45:
            stills_done.add(name)
            rgb.save(os.path.join(WORK, f"still-{name}.png"))
            print("still", name, flush=True)

        if not only_still:
            proc.stdin.write(rgb.tobytes())

        if fi % 150 == 0:
            print(f"  {fi}/{total_frames} ({t:.1f}s)", flush=True)

    if only_still:
        print("静态帧已输出到", WORK)
        return

    proc.stdin.close()
    proc.wait()
    print("完成:", out_mp4, os.path.getsize(out_mp4) // 1024, "KB", flush=True)


if __name__ == "__main__":
    main()
