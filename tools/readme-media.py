# SPDX-License-Identifier: GPL-3.0-or-later
# Usage: python3 tools/readme-media.py <repo-root>  (needs Pillow and the Inter font)
"""Build README media (hero banner, demo GIF, gallery) from real CatchWave UI captures."""
import sys
from PIL import Image, ImageDraw, ImageFilter, ImageFont

REPO = sys.argv[1]
UI = f"{REPO}/docs/ui"
OUT = f"{REPO}/docs/media"
BG = (16, 21, 16)
PANEL = (24, 31, 23)
LINE = (52, 64, 50)
ACCENT = (185, 246, 107)
TEXT = (236, 242, 232)
MUTED = (150, 163, 146)
F = "/usr/share/fonts/opentype/inter/Inter-{}.otf"


def font(weight, size):
    return ImageFont.truetype(F.format(weight), size)


def load(name):
    return Image.open(f"{UI}/{name}").convert("RGB")


def rounded(img, radius):
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, img.size[0] - 1, img.size[1] - 1), radius, fill=255)
    out = Image.new("RGBA", img.size)
    out.paste(img, (0, 0), mask)
    return out


def shadowed(canvas, img, xy, radius, blur=18, offset=10):
    """Paste a rounded RGBA image with a soft drop shadow."""
    w, h = img.size
    shadow = Image.new("RGBA", (w + blur * 4, h + blur * 4), (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle((blur * 2, blur * 2, blur * 2 + w, blur * 2 + h), radius, fill=(0, 0, 0, 170))
    shadow = shadow.filter(ImageFilter.GaussianBlur(blur))
    canvas.alpha_composite(shadow, (xy[0] - blur * 2, xy[1] - blur * 2 + offset))
    canvas.alpha_composite(img, xy)


def phone(name, height):
    """Phone screenshot inside a simple bezel."""
    shot = load(name)
    w = round(shot.width * height / shot.height)
    shot = shot.resize((w, height), Image.LANCZOS)
    pad = max(6, height // 70)
    body = Image.new("RGBA", (w + pad * 2, height + pad * 2), (0, 0, 0, 0))
    ImageDraw.Draw(body).rounded_rectangle((0, 0, body.width - 1, body.height - 1), pad * 4, fill=(8, 10, 8, 255), outline=LINE, width=2)
    body.alpha_composite(rounded(shot, pad * 3), (pad, pad))
    return body


def window(name, width):
    shot = load(name)
    h = round(shot.height * width / shot.width)
    shot = shot.resize((width, h), Image.LANCZOS)
    framed = rounded(shot, 12)
    ImageDraw.Draw(framed).rounded_rectangle((0, 0, width - 1, h - 1), 12, outline=LINE, width=2)
    return framed


def glow(canvas, center, radius, color, alpha):
    layer = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    x, y = center
    ImageDraw.Draw(layer).ellipse((x - radius, y - radius, x + radius, y + radius), fill=color + (alpha,))
    canvas.alpha_composite(layer.filter(ImageFilter.GaussianBlur(radius // 2)))


def text(draw, xy, s, f, fill, anchor="la"):
    draw.text(xy, s, font=f, fill=fill, anchor=anchor)


def pill(draw, xy, label, f):
    x, y = xy
    w = draw.textlength(label, font=f) + 28
    draw.rounded_rectangle((x, y, x + w, y + 34), 17, outline=ACCENT, width=2)
    text(draw, (x + 14, y + 17), label, f, ACCENT, "lm")
    return x + w + 10


def hero():
    c = Image.new("RGBA", (1600, 820), BG + (255,))
    glow(c, (1220, 430), 340, ACCENT, 38)
    d = ImageDraw.Draw(c)
    fb = font("Bold", 96)
    text(d, (88, 140), "Catch", fb, TEXT)
    text(d, (88 + d.textlength("Catch", font=fb), 140), "Wave", fb, ACCENT)
    text(d, (92, 280), "Your PC's sound on your phone.", font("SemiBold", 38), TEXT)
    for i, line in enumerate(("Stream Windows audio to Android over Wi-Fi or USB,", "control playback with gestures,", "and catch songs playing around you.")):
        text(d, (92, 342 + i * 36), line, font("Regular", 25), MUTED)
    x = 92
    for label in ("Free", "Open source", "No account", "No ads"):
        x = pill(d, (x, 480), label, font("Medium", 19))
    text(d, (92, 568), "Android 11+   ·   64-bit Windows 10/11", font("Medium", 22), MUTED)
    shadowed(c, window("windows-ready.png", 640), (780, 150), 12)
    shadowed(c, phone("lan-ready.png", 560), (1270, 210), 28)
    c.convert("RGB").save(f"{OUT}/hero.png", optimize=True)


def gallery():
    c = Image.new("RGBA", (1600, 900), BG + (255,))
    d = ImageDraw.Draw(c)
    x = 150
    for name, label in (("home.png", "Home"), ("lan-ready.png", "PC audio + gestures"), ("recognition.png", "Music around you")):
        p = phone(name, 700)
        shadowed(c, p, (x, 60), 28)
        text(d, (x + p.width // 2, 800), label, font("SemiBold", 30), TEXT, "ma")
        x += p.width + 110
    c.convert("RGB").save(f"{OUT}/gallery-phone.png", optimize=True)
    c = Image.new("RGBA", (1600, 640), BG + (255,))
    d = ImageDraw.Draw(c)
    for i, (name, label) in enumerate((("windows-waiting-fixture.png", "Waiting: private QR pairing"), ("windows-ready.png", "Streaming: gestures ready"))):
        w = window(name, 700)
        shadowed(c, w, (70 + i * 760, 50), 12)
        text(d, (70 + i * 760 + w.width // 2, 50 + w.height + 36), label, font("SemiBold", 30), TEXT, "ma")
    c.convert("RGB").save(f"{OUT}/gallery-host.png", optimize=True)


W, H = 960, 540


def scene(caption, sub, items):
    """items: list of (kind, name, size, (x, y))."""
    c = Image.new("RGBA", (W, H), BG + (255,))
    glow(c, (W * 2 // 3, H // 2), 240, ACCENT, 26)
    for kind, name, size, xy in items:
        img = phone(name, size) if kind == "phone" else window(name, size)
        shadowed(c, img, xy, 20 if kind == "phone" else 10, blur=12, offset=6)
    d = ImageDraw.Draw(c)
    d.rectangle((0, H - 92, W, H), fill=BG + (235,))
    d.line((0, H - 92, W, H - 92), fill=LINE, width=1)
    text(d, (40, H - 64), caption, font("SemiBold", 26), ACCENT)
    text(d, (40, H - 30), sub, font("Regular", 19), MUTED)
    return c.convert("RGB")


def title(big, sub, tags):
    c = Image.new("RGBA", (W, H), BG + (255,))
    glow(c, (W // 2, H // 2), 260, ACCENT, 34)
    d = ImageDraw.Draw(c)
    fb = font("Bold", 84)
    left = (W - d.textlength("CatchWave", font=fb)) / 2
    text(d, (left, 150), "Catch", fb, TEXT)
    text(d, (left + d.textlength("Catch", font=fb), 150), "Wave", fb, ACCENT)
    text(d, (W // 2, 285), big, font("SemiBold", 32), TEXT, "ma")
    text(d, (W // 2, 335), sub, font("Regular", 22), MUTED, "ma")
    f = font("Medium", 18)
    total = sum(d.textlength(t, font=f) + 38 for t in tags) - 10
    x = (W - total) / 2
    for t in tags:
        x = pill(d, (x, 400), t, f)
    return c.convert("RGB")


def demo():
    scenes = [
        title("Your PC's sound on your phone", "Wi-Fi or USB · gesture remote · song recognition", ["Free", "Open source", "No account"]),
        scene("1 · Start CatchWave Host on Windows", "It shows a private QR code. Nothing is installed or left running in the background.",
              [("window", "windows-waiting-fixture.png", 700, (130, 30))]),
        scene("2 · Open PC audio and scan the QR", "One scan pins the PC's certificate: the stream is encrypted end to end.",
              [("phone", "home.png", 420, (90, 16)), ("window", "windows-waiting-fixture.png", 520, (380, 60))]),
        scene("3 · Music plays on the phone", "Any Windows output, converted automatically to 48 kHz stereo.",
              [("window", "windows-ready.png", 620, (40, 50)), ("phone", "lan-ready.png", 420, (690, 16))]),
        scene("4 · Control the PC without touching it", "Tilt for volume · 3 taps play/pause · 4 next · 5 previous · 6 mute",
              [("phone", "lan-ready.png", 420, (370, 16))]),
        scene("5 · Hear a song nearby? Catch it", "Recognize it and continue from the same moment in YouTube Music.",
              [("phone", "recognition.png", 420, (370, 16))]),
        title("Free, private, made with care", "No ads · no analytics · no CatchWave account", ["GPL-3.0", "Android 11+", "Windows x64"]),
    ]
    frames, durations = [], []
    for i, s in enumerate(scenes):
        frames.append(s)
        durations.append(2600 if 0 < i < len(scenes) - 1 else 2200)
        nxt = scenes[(i + 1) % len(scenes)]
        for k in range(1, 6):
            frames.append(Image.blend(s, nxt, k / 6))
            durations.append(60)
    pal = [f.quantize(colors=128, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE) for f in frames]
    pal[0].save(f"{OUT}/demo.gif", save_all=True, append_images=pal[1:], duration=durations, loop=0, optimize=True, disposal=1)


if __name__ == "__main__":
    import os
    os.makedirs(OUT, exist_ok=True)
    hero()
    gallery()
    demo()
