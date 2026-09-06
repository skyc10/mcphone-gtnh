# -*- coding: utf-8 -*-
"""Generate 10 flat-style app icons (128x128, gradient rounded bg + white glyph)."""
from PIL import Image, ImageDraw
import os

S = 512  # supersample canvas
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                   "src/main/resources/assets/mcphone/textures/ui")
os.makedirs(OUT, exist_ok=True)


def vertical_gradient(size, top, bottom):
    w, h = size
    img = Image.new("RGBA", size)
    d = ImageDraw.Draw(img)
    for y in range(h):
        t = y / max(1, h - 1)
        c = tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3)) + (255,)
        d.line([(0, y), (w, y)], fill=c)
    return img


def rounded_mask(size, radius):
    m = Image.new("L", size, 0)
    d = ImageDraw.Draw(m)
    d.rounded_rectangle([0, 0, size[0] - 1, size[1] - 1], radius=radius, fill=255)
    return m


def base_icon(top, bottom, radius_ratio=0.22):
    img = vertical_gradient((S, S), top, bottom)
    img.putalpha(rounded_mask((S, S), int(S * radius_ratio)))
    return img, ImageDraw.Draw(img)


def save(img, name):
    img = img.resize((128, 128), Image.LANCZOS)
    img.save(os.path.join(OUT, name))
    print("wrote", name)


W = (255, 255, 255, 255)
W2 = (255, 255, 255, 200)
W3 = (255, 255, 255, 140)

# ---------- clock ----------
img, d = base_icon((74, 144, 226), (44, 90, 160))
cx, cy, r = S // 2, S // 2, 150
d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=W, width=28)
d.line([cx, cy, cx, cy - 95], fill=W, width=24)          # minute
d.line([cx, cy, cx + 70, cy + 45], fill=W, width=24)     # hour
d.ellipse([cx - 14, cy - 14, cx + 14, cy + 14], fill=W)
save(img, "app_clock.png")

# ---------- weather ----------
img, d = base_icon((88, 178, 194), (46, 118, 150))
d.ellipse([300, 90, 430, 220], fill=(255, 214, 90, 255))   # sun
d.ellipse([110, 210, 260, 330], fill=W)                     # cloud puffs
d.ellipse([200, 170, 380, 320], fill=W)
d.rounded_rectangle([120, 265, 370, 330], radius=32, fill=W)
save(img, "app_weather.png")

# ---------- notes ----------
img, d = base_icon((222, 178, 74), (176, 134, 46))
d.rounded_rectangle([130, 100, 382, 412], radius=28, fill=W)
for i, y in enumerate((170, 235, 300, 355)):
    wline = 200 if i == 3 else 212
    d.line([170, y, 170 + wline, y], fill=(120, 108, 70, 255), width=16)
save(img, "app_notes.png")

# ---------- ender chest ----------
img, d = base_icon((136, 96, 208), (86, 56, 150))
d.rounded_rectangle([100, 210, 412, 420], radius=24, fill=W)
d.pieslice([100, 120, 412, 330], 180, 360, fill=W2)          # lid
d.rectangle([228, 268, 284, 336], fill=(70, 40, 120, 255))   # latch
d.ellipse([242, 288, 270, 316], fill=(190, 160, 255, 255))
save(img, "app_enderchest.png")

# ---------- teleport ----------
img, d = base_icon((214, 96, 130), (150, 52, 96))
ax = 120
d.polygon([(ax, 256 - 70), (ax + 110, 256 - 70), (ax + 110, 256 - 115), (ax + 200, 256), (ax + 110, 256 + 115), (ax + 110, 256 + 70), (ax, 256 + 70)], fill=W)
d.polygon([(392, 186), (292, 256), (392, 326)], fill=W3)
save(img, "app_teleport.png")

# ---------- ae2 / ME network ----------
img, d = base_icon((80, 178, 190), (40, 116, 140))
c = (S // 2, S // 2)
nodes = [(c[0], c[1] - 150), (c[0] - 150, c[1] + 110), (c[0] + 150, c[1] + 110), (c[0], c[1] + 40)]
for n in nodes:
    d.line([c[0], c[1], n[0], n[1]], fill=W2, width=18)
d.ellipse([c[0] - 52, c[1] - 52, c[0] + 52, c[1] + 52], fill=W)
for n in nodes:
    d.ellipse([n[0] - 34, n[1] - 34, n[0] + 34, n[1] + 34], fill=W)
d.ellipse([c[0] - 26, c[1] - 26, c[0] + 26, c[1] + 26], fill=(40, 116, 140, 255))
save(img, "app_ae2.png")

# ---------- camera ----------
img, d = base_icon((110, 118, 138), (62, 66, 84))
d.rounded_rectangle([90, 170, 422, 400], radius=40, fill=W)
d.rounded_rectangle([180, 130, 330, 186], radius=20, fill=W)
d.ellipse([190, 220, 322, 352], fill=(62, 66, 84, 255))
d.ellipse([220, 250, 292, 322], fill=W2)
d.rectangle([350, 200, 396, 232], fill=W2)
save(img, "app_camera.png")

# ---------- gallery ----------
img, d = base_icon((222, 140, 78), (168, 96, 44))
d.rounded_rectangle([120, 130, 392, 382], radius=28, fill=W3)
d.rounded_rectangle([96, 160, 368, 412], radius=28, fill=W)
d.polygon([(130, 380), (230, 260), (300, 340), (340, 300), (368, 380)], fill=(150, 116, 60, 255))
d.ellipse([140, 190, 196, 246], fill=(255, 214, 90, 255))
save(img, "app_gallery.png")

# ---------- settings gear ----------
img, d = base_icon((150, 152, 162), (96, 98, 110))
import math
cx, cy = S // 2, S // 2
for i in range(8):
    a = i * math.pi / 4
    x1 = cx + math.cos(a) * 120
    y1 = cy + math.sin(a) * 120
    x2 = cx + math.cos(a) * 190
    y2 = cy + math.sin(a) * 190
    d.line([x1, y1, x2, y2], fill=W, width=56)
d.ellipse([cx - 140, cy - 140, cx + 140, cy + 140], fill=W)
d.ellipse([cx - 62, cy - 62, cx + 62, cy + 62], fill=(110, 112, 124, 255))
save(img, "app_settings.png")

# ---------- app manager grid ----------
img, d = base_icon((168, 168, 96), (112, 116, 56))
pos = [(120, 120), (284, 120), (120, 284), (284, 284)]
for i, (x, y) in enumerate(pos):
    d.rounded_rectangle([x, y, x + 108, y + 108], radius=24, fill=W if i != 3 else W2)
save(img, "app_appmgr.png")

print("all icons done ->", OUT)
