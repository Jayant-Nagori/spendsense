"""Renders the iOS app icon (1024x1024, opaque) from the Android adaptive-icon geometry (ic_launcher_*.xml)."""
import sys
from PIL import Image, ImageDraw

S = 1024
OFF, SPAN = 18.0, 72.0          # Android 108dp canvas; iOS shows the central 72dp
k = S / SPAN
def p(x, y): return ((x - OFF) * k, (y - OFF) * k)

img = Image.new("RGB", (S, S), (0, 0, 0))
# radial background: #1A1A1A at (86,18) -> #000 at radius 90
px = img.load()
cx, cy = p(86, 18); r = 90 * k
for y in range(S):
    for x in range(S):
        d = ((x - cx) ** 2 + (y - cy) ** 2) ** 0.5 / r
        if d < 0.55: v = 0x1A + (0x0A - 0x1A) * (d / 0.55)
        else: v = 0x0A * max(0.0, 1 - (d - 0.55) / 0.45)
        v = int(v); px[x, y] = (v, v, v)
d = ImageDraw.Draw(img)
gold = (0xD6, 0xB9, 0x7C); w = int(5.5 * k)
def line(a, b):
    d.line([p(*a), p(*b)], fill=gold, width=w)
    for q in (a, b):
        X, Y = p(*q); d.ellipse([X - w / 2, Y - w / 2, X + w / 2, Y + w / 2], fill=gold)
line((39, 34), (69, 34)); line((39, 46), (69, 46))
# cubic M44,34 C68,34 68,58 44,58 then L66,78
pts = []
for i in range(41):
    t = i / 40
    x = (1-t)**3*44 + 3*(1-t)**2*t*68 + 3*(1-t)*t*t*68 + t**3*44
    y = (1-t)**3*34 + 3*(1-t)**2*t*34 + 3*(1-t)*t*t*58 + t**3*58
    pts.append((x, y))
for a, b in zip(pts, pts[1:]): line(a, b)
line((44, 58), (66, 78))
d.polygon([p(72,26), p(74,31), p(79,33), p(74,35), p(72,40), p(70,35), p(65,33), p(70,31)], fill=(0xF2, 0xE6, 0xCC))
img.save(sys.argv[1])
