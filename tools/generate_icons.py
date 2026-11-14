import os, sys, math
from PIL import Image, ImageDraw, ImageFilter

RES = sys.argv[1]
PREVIEW = sys.argv[2]
DENS = {'mdpi': 1, 'hdpi': 1.5, 'xhdpi': 2, 'xxhdpi': 3, 'xxxhdpi': 4}
S = 1024                      # master canvas = 108dp
U = S / 108.0                 # 1dp

def lerp(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(len(a)))

def background():
    im = Image.new('RGBA', (S, S))
    # Native Samsung One UI vibrant imaging gradient: rich cobalt to deep electric blue
    top = (75, 145, 255, 255)     # #4B91FF
    bot = (22, 78, 235, 255)      # #164EEB
    px = im.load()
    for y in range(S):
        c = lerp(top, bot, y / (S - 1))
        for x in range(S):
            px[x, y] = c
    
    # One UI signature ambient illumination from upper-left
    glow = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(glow)
    d.ellipse([-S * 0.25, -S * 0.35, S * 0.75, S * 0.55], fill=(255, 255, 255, 75))
    im.alpha_composite(glow.filter(ImageFilter.GaussianBlur(S * 0.09)))
    return im

def draw_hdr_symbol(draw, cx, cy, radius, mono=False):
    """Draws Samsung-style HDR radiant burst aperture with curved blades"""
    num_blades = 8
    blade_len = radius
    inner_r = radius * 0.34
    
    for i in range(num_blades):
        angle = i * (2 * math.pi / num_blades)
        next_angle = (i + 1) * (2 * math.pi / num_blades)
        mid_angle = angle + (math.pi / num_blades)
        
        # Center points
        x0 = cx + inner_r * math.cos(angle)
        y0 = cy + inner_r * math.sin(angle)
        
        # Tip of blade
        x1 = cx + blade_len * math.cos(mid_angle)
        y1 = cy + blade_len * math.sin(mid_angle)
        
        # Outer curve control
        x2 = cx + (blade_len * 0.72) * math.cos(next_angle)
        y2 = cy + (blade_len * 0.72) * math.sin(next_angle)
        
        x3 = cx + inner_r * math.cos(next_angle)
        y3 = cy + inner_r * math.sin(next_angle)
        
        # Color: alternating pure white and subtle ice blue tint for depth
        color = (255, 255, 255, 255) if (mono or i % 2 == 0) else (232, 242, 255, 255)
        draw.polygon([(x0, y0), (x1, y1), (x2, y2), (x3, y3)], fill=color)

def foreground(mono=False):
    im = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    cx, cy = S / 2, S / 2
    r = 27.5 * U
    
    # 1. Soft ambient shadow for depth (non-mono)
    if not mono:
        sh = Image.new('RGBA', (S, S), (0, 0, 0, 0))
        d_sh = ImageDraw.Draw(sh)
        draw_hdr_symbol(d_sh, cx, cy + 3.2 * U, r, mono=True)
        # Inner core shadow
        d_sh.ellipse([cx - 10 * U, cy - 10 * U + 3.2 * U, cx + 10 * U, cy + 10 * U + 3.2 * U], fill=(12, 35, 120, 120))
        im.alpha_composite(sh.filter(ImageFilter.GaussianBlur(3.4 * U)))
        
    # 2. Main HDR blades
    d = ImageDraw.Draw(im)
    draw_hdr_symbol(d, cx, cy, r, mono=mono)
    
    # 3. Central Luminous Iris Core
    core_r = 9.8 * U
    if mono:
        d.ellipse([cx - core_r, cy - core_r, cx + core_r, cy + core_r], fill=(255, 255, 255, 255))
        # Inner cutout
        inner_cut = 4.2 * U
        d.ellipse([cx - inner_cut, cy - inner_cut, cx + inner_cut, cy + inner_cut], fill=(0, 0, 0, 0))
    else:
        # Luminous white ring
        d.ellipse([cx - core_r, cy - core_r, cx + core_r, cy + core_r], fill=(255, 255, 255, 255))
        # Iris gradient center: soft cyan-blue HDR glow
        iris_r = 6.4 * U
        iris = Image.new('RGBA', (S, S), (0, 0, 0, 0))
        d_iris = ImageDraw.Draw(iris)
        d_iris.ellipse([cx - iris_r, cy - iris_r, cx + iris_r, cy + iris_r], fill=(35, 115, 250, 230))
        # Specular glint
        glint_r = 2.4 * U
        d_iris.ellipse([cx - glint_r - 1.2*U, cy - glint_r - 1.2*U, cx + glint_r - 1.2*U, cy + glint_r - 1.2*U], fill=(255, 255, 255, 255))
        im.alpha_composite(iris)
        
    return im

def squircle(size):
    m = Image.new('L', (size, size), 0)
    p = m.load()
    r = size / 2
    for y in range(size):
        for x in range(size):
            if abs((x - r + 0.5) / r) ** 5 + abs((y - r + 0.5) / r) ** 5 <= 1:
                p[x, y] = 255
    return m

def save_adaptive_layers(bg, fg, mono):
    for dn, k in DENS.items():
        px = round(108 * k)
        out = os.path.join(RES, 'mipmap-' + dn)
        os.makedirs(out, exist_ok=True)
        bg.resize((px, px), Image.LANCZOS).save(os.path.join(out, 'ic_bg.png'))
        fg.resize((px, px), Image.LANCZOS).save(os.path.join(out, 'ic_fg.png'))
        mono.resize((px, px), Image.LANCZOS).save(os.path.join(out, 'ic_mono.png'))

def save_legacy_icons(bg, fg):
    full = bg.copy()
    full.alpha_composite(fg)
    # The inner 72dp is the visible content within the 108dp adaptive canvas
    crop = full.crop((round(S * 18 / 108), round(S * 18 / 108), round(S * 90 / 108), round(S * 90 / 108)))
    
    for dn, k in DENS.items():
        px = round(48 * k)
        out = os.path.join(RES, 'mipmap-' + dn)
        os.makedirs(out, exist_ok=True)
        
        sq = squircle(px)
        icon = Image.new('RGBA', (px, px), (0, 0, 0, 0))
        icon.paste(crop.resize((px, px), Image.LANCZOS), (0, 0), sq)
        icon.save(os.path.join(out, 'ic_launcher.png'))
        
        # Round icon (circular)
        mask = Image.new('L', (px, px), 0)
        ImageDraw.Draw(mask).ellipse([0, 0, px, px], fill=255)
        round_icon = Image.new('RGBA', (px, px), (0, 0, 0, 0))
        round_icon.paste(crop.resize((px, px), Image.LANCZOS), (0, 0), mask)
        round_icon.save(os.path.join(out, 'ic_launcher_round.png'))

bg = background()
fg = foreground()
mono = foreground(mono=True)

save_adaptive_layers(bg, fg, mono)
save_legacy_icons(bg, fg)

# Render comprehensive visual preview sheet
full = bg.copy()
full.alpha_composite(fg)
crop = full.crop((round(S * 18 / 108), round(S * 18 / 108), round(S * 90 / 108), round(S * 90 / 108))).resize((480, 480), Image.LANCZOS)

sheet = Image.new('RGBA', (1200, 560), (241, 241, 243, 255))
sq480 = squircle(480)
icon = Image.new('RGBA', (480, 480), (0, 0, 0, 0))
icon.paste(crop, (0, 0), sq480)
sheet.alpha_composite(icon, (40, 40))

# Small icon preview
small = icon.resize((144, 144), Image.LANCZOS)
sheet.alpha_composite(small, (580, 60))

# Dark AMOLED preview container
dark = Image.new('RGBA', (320, 240), (1, 1, 2, 255))
dark.alpha_composite(small, (88, 48))
sheet.alpha_composite(dark, (840, 40))

# Themed Material You / One UI Monochrome icon preview
mono_icon = Image.new('RGBA', (480, 480), (0, 0, 0, 0))
mc = Image.new('RGBA', (S, S), (45, 52, 65, 255))
mc.alpha_composite(mono)
mono_crop = mc.crop((round(S * 18 / 108), round(S * 18 / 108), round(S * 90 / 108), round(S * 90 / 108))).resize((144, 144), Image.LANCZOS)
mono_small = Image.new('RGBA', (144, 144), (0, 0, 0, 0))
mono_small.paste(mono_crop, (0, 0), squircle(144))
sheet.alpha_composite(mono_small, (580, 280))

sheet.save(PREVIEW)
print(f"Generated Samsung One UI icon set and preview at {PREVIEW}")
