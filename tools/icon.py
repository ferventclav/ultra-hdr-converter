"""Ultra HDR app icon, cut from the chosen art (tools/icon_art.jpg: two glowing orange plates and a
sparkle on near-black, 1024 px, drawn as a full icon face). Writes the adaptive-icon layers at every
launcher density, plus a preview on Samsung's squircle so it can be checked by eye.

  background  the art's flat near-black, full bleed
  foreground  the art with that black taken out (colour-to-alpha), so the glow stays a soft halo;
              scaled so every solid part sits inside the 66dp safe zone
  monochrome  for themed icons: sparkles solid, top plate stronger than the bottom one, no glow

Usage: python tools/icon.py app/src/main/res <preview.png>
"""
import os
import sys

import numpy as np
from PIL import Image

RES, PREVIEW = sys.argv[1], sys.argv[2]
ART = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'icon_art.jpg')
DENS = {'mdpi': 1, 'hdpi': 1.5, 'xhdpi': 2, 'xxhdpi': 3, 'xxxhdpi': 4}
SAFE_RADIUS = 33.0            # dp, the safe zone of a 108dp layer

src = np.asarray(Image.open(ART).convert('RGB'), dtype=np.float64)
N = src.shape[0]
BG = np.median(np.concatenate([src[:8, :8].reshape(-1, 3), src[-8:, -8:].reshape(-1, 3)]), axis=0)

# Colour to alpha against the flat background: each pixel becomes the least-opaque colour that,
# laid over the background, gives back the original pixel.
up = np.where(src > BG, (src - BG) / (255 - BG), 0)
down = np.where(src < BG, (BG - src) / np.maximum(BG, 1), 0)
alpha = np.max(np.maximum(up, down), axis=2)
alpha[alpha < 0.03] = 0                                   # JPEG noise in the flat black
safe_a = np.maximum(alpha, 1e-6)[..., None]
colour = np.clip((src - BG) / safe_a + BG, 0, 255)
colour[alpha == 0] = 0

# Solid parts (plates and sparkles, not the glow) and how far they reach from the centre.
solid = np.clip((alpha - 0.35) / 0.55, 0, 1)
ys, xs = np.nonzero(solid > 0.5)
reach = np.sqrt((xs + 0.5 - N / 2) ** 2 + (ys + 0.5 - N / 2) ** 2).max()
FACE = min(72.0, SAFE_RADIUS * N / reach)                 # dp the art's 1024 px face spans
print(f'background {BG.round().astype(int).tolist()}, solid reach {reach:.0f}px, face {FACE:.1f}dp')

# Monochrome levels: whiteness marks the sparkles; green over red tells the light top plate
# (about 0.75) from the deep bottom plate and its shadow (about 0.45).
whiteness = np.clip((colour.min(axis=2) - 160) / 50, 0, 1)
ratio = colour[..., 1] / np.maximum(colour[..., 0], 1)
plate = 0.40 + 0.25 * np.clip((ratio - 0.47) / 0.28, 0, 1)
mono_a = solid * np.maximum(plate, whiteness)


def rgba(rgb, a):
    return Image.fromarray(np.dstack([rgb, a * 255]).round().astype(np.uint8), 'RGBA')


fg_art = rgba(colour, alpha)
mono_art = rgba(np.full_like(src, 255), mono_a)


def layer(art, k):
    """The art scaled to FACE dp, centred on a 108dp canvas, at k px per dp."""
    size, face = round(108 * k), round(FACE * k)
    small = art.convert('RGBa').resize((face, face), Image.LANCZOS).convert('RGBA')
    out = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    out.alpha_composite(small, ((size - face) // 2, (size - face) // 2))
    return out


def save(make, name):
    for dn, k in DENS.items():
        out = os.path.join(RES, 'mipmap-' + dn)
        os.makedirs(out, exist_ok=True)
        make(k).save(os.path.join(out, name + '.png'))


bg_colour = tuple(int(round(v)) for v in BG) + (255,)
save(lambda k: Image.new('RGBA', (round(108 * k),) * 2, bg_colour), 'ic_bg')
save(lambda k: layer(fg_art, k), 'ic_fg')
save(lambda k: layer(mono_art, k), 'ic_mono')


# Preview at 10 px per dp: Samsung's squircle and a plain circle (the strictest mask), large and at
# home-screen size, on light and dark wallpapers, plus the themed version.
K = 10
CANVAS, VIS = 108 * K, 72 * K
crop = ((CANVAS - VIS) // 2,) * 2 + ((CANVAS + VIS) // 2,) * 2


def mask(kind):
    yy, xx = np.mgrid[0:VIS, 0:VIS]
    r = VIS / 2
    u, v = np.abs((xx + 0.5 - r) / r), np.abs((yy + 0.5 - r) / r)
    inside = (u ** 5 + v ** 5 <= 1) if kind == 'squircle' else (u ** 2 + v ** 2 <= 1)
    return Image.fromarray((inside * 255).astype(np.uint8), 'L')


def icon(fg, bg, kind):
    full = Image.new('RGBA', (CANVAS, CANVAS), bg)
    full.alpha_composite(fg)
    out = Image.new('RGBA', (VIS, VIS), (0, 0, 0, 0))
    out.paste(full.crop(crop), (0, 0), mask(kind))
    return out


fg10, mono10 = layer(fg_art, K), layer(mono_art, K)
big = icon(fg10, bg_colour, 'squircle').resize((480, 480), Image.LANCZOS)
circle = icon(fg10, bg_colour, 'circle').resize((240, 240), Image.LANCZOS)
tinted = Image.new('RGBA', mono10.size, (255, 196, 160, 255))
tinted.putalpha(mono10.getchannel('A'))
themed = icon(tinted, (58, 44, 38, 255), 'squircle')
small = big.resize((132, 132), Image.LANCZOS)

sheet = Image.new('RGBA', (1240, 560), (241, 241, 243, 255))
sheet.alpha_composite(big, (40, 40))
sheet.alpha_composite(small, (580, 60))
sheet.alpha_composite(circle, (560, 260))
dark = Image.new('RGBA', (380, 480), (1, 1, 2, 255))
dark.alpha_composite(small, (124, 50))
dark.alpha_composite(themed.resize((132, 132), Image.LANCZOS), (124, 290))
sheet.alpha_composite(dark, (830, 40))
sheet.save(PREVIEW)
print('ok')
