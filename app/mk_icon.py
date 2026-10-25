# Generate O3 Root launcher icon (legacy + adaptive + monochrome).
import os
from PIL import Image, ImageDraw, ImageFont, ImageFilter

ROOT = os.environ.get("RES_DIR") or os.path.join(os.path.dirname(os.path.abspath(__file__)), "res")
FONT = os.environ.get("FONT") or (
    r"C:/Windows/Fonts/segoeuib.ttf" if os.name == "nt"
    else "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf")
SS = 4  # supersample factor for the legacy tile

TOP_BG = (18, 26, 36)
BOT_BG = (9, 12, 17)
G_TOP = (110, 231, 183)   # mint-300
G_BOT = (5, 150, 105)     # emerald-600


def lerp(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))


def vgrad(size, top, bottom):
    w, h = size
    strip = Image.new("RGB", (1, h))
    for y in range(h):
        strip.putpixel((0, y), lerp(top, bottom, y / max(1, h - 1)))
    return strip.resize((w, h), Image.BICUBIC)


def monogram(px, ratio=0.60, ss=4):
    """Emerald gradient 'O3' centered on a transparent canvas of px x px."""
    big = px * ss
    mask = Image.new("L", (big, big), 0)
    d = ImageDraw.Draw(mask)
    target = big * ratio
    size = int(target * 1.4)
    f = None
    while size > 8:
        f = ImageFont.truetype(FONT, size)
        box = d.textbbox((0, 0), "O3", font=f)
        w = box[2] - box[0]
        h = box[3] - box[1]
        if w <= target and h <= target * 0.90:
            break
        size -= 6
    box = d.textbbox((0, 0), "O3", font=f)
    w, h = box[2] - box[0], box[3] - box[1]
    x = (big - w) / 2 - box[0]
    y = (big - h) / 2 - box[1]
    glyph_mask = Image.new("L", (big, big), 0)
    ImageDraw.Draw(glyph_mask).text((x, y), "O3", font=f, fill=255)
    shadow = glyph_mask.filter(ImageFilter.GaussianBlur(big * 0.018)).point(lambda v: int(v * 0.55))
    grad = vgrad((big, big), G_TOP, G_BOT).convert("RGBA")
    out = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    shadow_layer = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    shadow_layer.paste((0, 0, 0, 255), (0, 0), shadow)
    out = Image.alpha_composite(out, shadow_layer)
    glyph = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    glyph.paste(grad, (0, 0), glyph_mask)
    out = Image.alpha_composite(out, glyph)
    return out.resize((px, px), Image.LANCZOS)


def legacy(px):
    """Full tile icon: gradient background, glow, hairline border, monogram."""
    big = px * SS
    img = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    tile = Image.new("L", (big, big), 0)
    ImageDraw.Draw(tile).rounded_rectangle([0, 0, big - 1, big - 1],
                                           radius=int(big * 0.225), fill=255)
    bg = vgrad((big, big), TOP_BG, BOT_BG).convert("RGBA")
    img.paste(bg, (0, 0), tile)
    # soft emerald glow behind the glyph
    glow = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    r = int(big * 0.34)
    ImageDraw.Draw(glow).ellipse([big // 2 - r, big // 2 - r, big // 2 + r, big // 2 + r],
                                 fill=(16, 185, 129, 120))
    glow = glow.filter(ImageFilter.GaussianBlur(big * 0.09))
    img = Image.alpha_composite(img, Image.composite(glow, Image.new("RGBA", (big, big), (0, 0, 0, 0)), tile))
    # monogram rendered at full tile resolution
    img.alpha_composite(monogram(big, 0.62, ss=2))
    # hairline border
    ImageDraw.Draw(img).rounded_rectangle([3, 3, big - 4, big - 4],
                                          radius=int(big * 0.222), outline=(48, 66, 86, 255), width=SS)
    return img.resize((px, px), Image.LANCZOS)


def main():
    os.makedirs(os.path.join(ROOT, "mipmap-anydpi-v26"), exist_ok=True)
    os.makedirs(os.path.join(ROOT, "drawable"), exist_ok=True)
    dens = [("mdpi", 48, 108), ("hdpi", 72, 162), ("xhdpi", 96, 216),
            ("xxhdpi", 144, 324), ("xxxhdpi", 192, 432)]
    for name, legacy_px, fg_px in dens:
        d = os.path.join(ROOT, "mipmap-" + name)
        os.makedirs(d, exist_ok=True)
        legacy(legacy_px).save(os.path.join(d, "ic_launcher.png"))
        monogram(fg_px, 0.58, ss=4).save(os.path.join(d, "ic_fg.png"))
    mono = monogram(432, 0.58, ss=4)
    mono_white = Image.new("RGBA", mono.size, (0, 0, 0, 0))
    mono_white.paste((255, 255, 255, 255), (0, 0), mono.split()[3])
    mono_white.save(os.path.join(ROOT, "mipmap-xxxhdpi", "ic_mono.png"))

    with open(os.path.join(ROOT, "drawable", "ic_bg.xml"), "w", encoding="utf-8") as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n'
                '<shape xmlns:android="http://schemas.android.com/apk/res/android"\n'
                '    android:shape="rectangle">\n'
                '    <gradient android:type="linear" android:angle="270"\n'
                '        android:startColor="#121A24" android:endColor="#090C11"/>\n'
                '</shape>\n')
    with open(os.path.join(ROOT, "mipmap-anydpi-v26", "ic_launcher.xml"), "w", encoding="utf-8") as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n'
                '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
                '    <background android:drawable="@drawable/ic_bg"/>\n'
                '    <foreground android:drawable="@mipmap/ic_fg"/>\n'
                '    <monochrome android:drawable="@mipmap/ic_mono"/>\n'
                '</adaptive-icon>\n')
    print("icons written to", ROOT)


if __name__ == "__main__":
    main()
