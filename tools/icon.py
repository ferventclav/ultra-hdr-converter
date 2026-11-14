"""Ultra HDR app icon, One UI style (the Knowledge app's recipe): a solid white glyph with a little
depth on a soft gradient. The glyph is a sun rising behind two hills, with a halo round the sun for
"brighter than white". Writes the adaptive-icon layers (background, foreground, monochrome) at every
launcher density, plus a preview on Samsung's squircle so it can be checked by eye.

Usage: python tools/icon.py app/src/main/res <preview.png>
"""
import os
import sys
from PIL import Image, ImageDraw, ImageFilter

RES, PREVIEW = sys.argv[1], sys.argv[2]
DENS = {'mdpi': 1, 'hdpi': 1.5, 'xhdpi': 2, 'xxhdpi': 3, 'xxxhdpi': 4}
S = 1024                      # master canvas = 108dp
U = S / 108.0                 # 1dp


def lerp(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(len(a)))


def background():
    im = Image.new('RGBA', (S, S))
    top, bot = (255, 189, 72, 255), (255, 96, 66, 255)
    px = im.load()
    for y in range(S):
        c = lerp(top, bot, y / (S - 1))
        for x in range(S):
            px[x, y] = c
    # soft light from the upper left, like One UI's gradient icons
    glow = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    ImageDraw.Draw(glow).ellipse([-S * 0.25, -S * 0.35, S * 0.75, S * 0.55], fill=(255, 255, 255, 56))
    im.alpha_composite(glow.filter(ImageFilter.GaussianBlur(S * 0.08)))
    return im


def smooth(mask, r=1.7):
    """Rounds every corner of a hard-edged shape: blur, cut at half, then a hair of anti-aliasing."""
    m = mask.filter(ImageFilter.GaussianBlur(r * U)).point(lambda v: 255 if v > 127 else 0)
    return m.filter(ImageFilter.GaussianBlur(0.18 * U))


def shape(points):
    m = Image.new('L', (S, S), 0)
    ImageDraw.Draw(m).polygon([(x * U, y * U) for x, y in points], fill=255)
    return smooth(m)


def disc(cx, cy, r):
    m = Image.new('L', (S, S), 0)
    ImageDraw.Draw(m).ellipse([(cx - r) * U, (cy - r) * U, (cx + r) * U, (cy + r) * U], fill=255)
    return m


SUN = (67, 45, 8.5)
BACK_HILL = [(42, 73), (63, 52), (84, 73)]
FRONT_HILL = [(26, 73), (44, 48.5), (59, 64), (67, 73)]


def paint(color, mask, alpha=255):
    layer = Image.new('RGBA', (S, S), color[:3] + (0,))
    layer.putalpha(mask.point(lambda v: v * alpha // 255))
    return layer


def foreground(mono=False):
    im = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    cx, cy, r = SUN
    # The halo: two soft rings of light round the sun.
    for ring, alpha in ((17.5, 46), (12.8, 96)):
        im.alpha_composite(paint((255, 255, 255), disc(cx, cy, ring).filter(ImageFilter.GaussianBlur(0.8 * U)), alpha))
    im.alpha_composite(paint((255, 255, 255), smooth(disc(cx, cy, r), 0.6)))

    back, front = shape(BACK_HILL), shape(FRONT_HILL)
    if not mono:
        # Soft shadow under the hills for depth, in a deep coral.
        shadow = Image.new('L', (S, S), 0)
        shadow.paste(255, (0, 0), back)
        shadow.paste(255, (0, 0), front)
        shadow = shadow.transform((S, S), Image.AFFINE, (1, 0, 0, 0, 1, -2.6 * U)).filter(ImageFilter.GaussianBlur(3.2 * U))
        im.alpha_composite(paint((176, 52, 24), shadow, 120))
    im.alpha_composite(paint((255, 255, 255) if mono else (255, 233, 214), back))
    if not mono:
        # A thin gap of the back hill's shade keeps the front hill crisp where they overlap.
        edge = front.filter(ImageFilter.MaxFilter(9))
        im.alpha_composite(paint((242, 196, 168), edge, 150))
    im.alpha_composite(paint((255, 255, 255), front))
    return im


def save(img, name):
    for dn, k in DENS.items():
        px = round(108 * k)
        out = os.path.join(RES, 'mipmap-' + dn)
        os.makedirs(out, exist_ok=True)
        img.resize((px, px), Image.LANCZOS).save(os.path.join(out, name + '.png'))


bg, fg, mono = background(), foreground(), foreground(mono=True)
save(bg, 'ic_bg')
save(fg, 'ic_fg')
save(mono, 'ic_mono')


def squircle(size):
    m = Image.new('L', (size, size), 0)
    p = m.load()
    r = size / 2
    for y in range(size):
        for x in range(size):
            if abs((x - r + .5) / r) ** 5 + abs((y - r + .5) / r) ** 5 <= 1:
                p[x, y] = 255
    return m


# Preview: the layers on One UI's squircle as the launcher crops them, at two sizes, on light and
# dark home screens, plus the themed (monochrome) version.
full = bg.copy()
full.alpha_composite(fg)
box = (round(S * 18 / 108), round(S * 18 / 108), round(S * 90 / 108), round(S * 90 / 108))
mask = squircle(480)
icon = Image.new('RGBA', (480, 480), (0, 0, 0, 0))
icon.paste(full.crop(box).resize((480, 480), Image.LANCZOS), (0, 0), mask)
sheet = Image.new('RGBA', (1180, 560), (241, 241, 243, 255))
sheet.alpha_composite(icon, (40, 40))
small = icon.resize((144, 144), Image.LANCZOS)
sheet.alpha_composite(small, (580, 60))
dark = Image.new('RGBA', (300, 300), (1, 1, 2, 255))
dark.alpha_composite(small, (78, 40))
sheet.alpha_composite(dark, (840, 40))
themed = Image.new('RGBA', (S, S), (60, 70, 90, 255))
themed.alpha_composite(mono)
mono_icon = Image.new('RGBA', (480, 480), (0, 0, 0, 0))
mono_icon.paste(themed.crop(box).resize((480, 480), Image.LANCZOS), (0, 0), mask)
sheet.alpha_composite(mono_icon.resize((144, 144), Image.LANCZOS), (580, 260))
sheet.save(PREVIEW)
print('ok')
