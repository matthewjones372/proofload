#!/usr/bin/env python3
"""
Produces `social-preview.png`, the 1280x640 card GitHub renders when somebody
shares a link to the repository. Committed because the PNG is a derived
artifact and everything derived here has a producer beside it. Otherwise the
next person wanting to change the tagline has to redraw the card by hand.

Not wired into Gradle. It runs on a Mac, needs Pillow, and reads the brand mark
through `qlmanage` because there is no SVG rasteriser in the build and adding
one to serve a single image would be the wrong trade.

    python3 -m pip install --user pillow
    python3 docs/assets/social-preview.py

Upload the result under Settings -> General -> Social preview. GitHub does not
read it from the repository.
"""
import pathlib
import subprocess
import tempfile

from PIL import Image, ImageDraw, ImageFont, ImageOps

HERE = pathlib.Path(__file__).parent

W, H = 1280, 640
BG, INK, MUTE, BODY, KOTLIN = (20, 23, 28), (232, 234, 237), (154, 163, 173), (200, 206, 214), (127, 82, 255)
PANEL, BORDER = (27, 31, 38), (46, 52, 62)
STR, TYPE, FN, PLAIN = (152, 195, 121), (224, 180, 110), (130, 170, 255), (200, 206, 214)

SFNS = "/System/Library/Fonts/SFNS.ttf"
SFMONO = "/System/Library/Fonts/SFNSMono.ttf"


def mark(size):
    """The brand mark, as an alpha mask.

    `proofload-mark-light.svg` is dark ink on white, so inverted luminance is a
    clean antialiased mask, which is how the mark lands on a dark card without
    the white box a naive paste would bring with it.
    """
    with tempfile.TemporaryDirectory() as tmp:
        subprocess.run(
            ["qlmanage", "-t", "-s", "512", "-o", tmp, str(HERE / "proofload-mark-light.svg")],
            check=True, capture_output=True,
        )
        rendered = next(pathlib.Path(tmp).glob("*.png"))
        alpha = ImageOps.invert(Image.open(rendered).convert("L")).resize((size, size), Image.LANCZOS)
    out = Image.new("RGBA", alpha.size, INK + (0,))
    out.putalpha(alpha)
    return out


def variable(path, size, name=None):
    font = ImageFont.truetype(path, size)
    if name:
        font.set_variation_by_name(name)
    return font


def main():
    img = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, W, 5], fill=KOTLIN)

    badge = mark(120)
    img.paste(badge, (96, 96), badge)

    title = variable(SFNS, 96, "Bold")
    sub = variable(SFNS, 40, "Medium")
    body = ImageFont.truetype(SFNS, 27)
    mono = ImageFont.truetype(SFMONO, 22)
    code = ImageFont.truetype(SFMONO, 19)

    x = 96
    d.text((x, 250), "Proofload", font=title, fill=INK)
    d.text((x, 366), "Load testing for Kotlin", font=sub, fill=MUTE)
    d.text((x, 452), "Each request is timed from when it was", font=body, fill=BODY)
    d.text((x, 490), "scheduled to start, and every run checks", font=body, fill=BODY)
    d.text((x, 528), "that the generator kept up.", font=body, fill=BODY)
    assert x + d.textlength("Virtual threads  ·  JUnit 5  ·  gRPC", font=mono) < 620
    d.text((x, 580), "Virtual threads  ·  JUnit 5  ·  gRPC", font=mono, fill=KOTLIN)

    # A test, rather than a claim about one.
    px0, py0, px1, py1 = 640, 86, 1200, 554
    d.rounded_rectangle([px0, py0, px1, py1], radius=14, fill=PANEL, outline=BORDER, width=1)

    lines = [
        [("val ", KOTLIN), ("checkout", PLAIN), (" = ", PLAIN), ("scenario", FN), ("(", PLAIN), ('"checkout"', STR), (") {", PLAIN)],
        [("    exec(placeOrder, api.", PLAIN), ("post", FN), ("(", PLAIN), ('"/orders"', STR), (")", PLAIN)],
        [("        .", PLAIN), ("expecting", FN), ("(", PLAIN), ("201", TYPE), ("))", PLAIN)],
        [("}", PLAIN)],
        [],
        [("@LoadTest", TYPE)],
        [("fun ", KOTLIN), ("checkout", FN), ("(proofload: ", PLAIN), ("Proofload", TYPE), (") {", PLAIN)],
        [("    val ", KOTLIN), ("result = proofload.", PLAIN), ("run", FN), ("(", PLAIN)],
        [("        checkout.", PLAIN), ("at", FN), ("(", PLAIN), ("50", TYPE), (".perSecond,", PLAIN)],
        [("           over = ", PLAIN), ("1", TYPE), (".minutes))", PLAIN)],
        [("    ", PLAIN), ("assertTrue", FN), ("(", PLAIN)],
        [("        result[placeOrder].responseTime.p99", PLAIN)],
        [("            < ", PLAIN), ("200", TYPE), (".milliseconds)", PLAIN)],
        [("}", PLAIN)],
    ]
    y = py0 + 24
    for line in lines:
        cx = px0 + 24
        for text, colour in line:
            d.text((cx, y), text, font=code, fill=colour)
            cx += d.textlength(text, font=code)
        assert cx < px1 - 12, f"line too wide: {cx}"
        y += 30

    out = HERE / "social-preview.png"
    img.save(out, "PNG", optimize=True)
    print(f"wrote {out} ({W}x{H})")


if __name__ == "__main__":
    main()
