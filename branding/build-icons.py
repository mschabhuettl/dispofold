#!/usr/bin/env python3
"""Builds the Dispofold icons from the SVG sources in this folder.

Needs rsvg-convert (librsvg2-bin) and Pillow; for the wordmark also fontTools with brotli
(python3-fonttools, python3-brotli). Run from anywhere: python3 branding/build-icons.py

Writes:
  branding/png/icon-<size>.png   16 ... 1024, rendered per size from icon.svg
  branding/icon.ico              Windows (16, 24, 32, 48, 64, 128, 256)
  branding/icon.icns             macOS (16 ... 1024)
  branding/wordmark.svg          logo plus "Dispofold" as outlines (IBM Plex Sans SemiBold)
  frontend/public/favicon.svg, favicon.ico, img/logo.svg  copies for the web interface

The repository check also scans binary files, so every generated binary is searched for the
forbidden byte sequences; on a hit the file is encoded again with a different (invisible) salt.
"""
import io
import re
import shutil
import subprocess
from pathlib import Path

from PIL import Image

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
PUBLIC = ROOT / "frontend" / "public"
PNG_SIZES = [16, 32, 48, 64, 128, 256, 512, 1024]
ICO_SIZES = [16, 24, 32, 48, 64, 128, 256]
ICNS_SIZES = [16, 32, 64, 128, 256, 512, 1024]

# Same patterns as scripts/check-repo.sh (written with character classes so this file does not match itself).
FORBIDDEN = [
    re.compile(rb"[c]laude|[a]nthropic|[c]hatgpt|[o]penai|[l]lm", re.I),
    re.compile(rb"[g]ithub\.com|[g]hcr|[G]itHub|[G]H_"),
    re.compile(rb"\b[s]p5|[s]chichtplaner|\b[d]bf\b", re.I),
    re.compile(rb"[a]t\.[r]ls|[S]teiermark|[R]ote[sn]? [K]reuz|[R]ed [C]ross|[r]oteskreuz|[r]ls-|[S]ource Sans|[s]ource-sans|[D]unant", re.I),
    re.compile("[Ö]RK".encode(), re.I),
    re.compile("[ö]rk".encode()),
    re.compile(rb"\b[R]LS\b"),
]


def clean(data: bytes) -> bool:
    return not any(p.search(data) for p in FORBIDDEN)


def render(svg: Path, size: int) -> Image.Image:
    png = subprocess.run(["rsvg-convert", "-w", str(size), "-h", str(size), str(svg)],
                         check=True, capture_output=True).stdout
    return Image.open(io.BytesIO(png)).convert("RGBA")


def salted(img: Image.Image, salt: int) -> Image.Image:
    """Changes the alpha of the (transparent) corner pixel by a tiny amount to get different bytes."""
    if salt == 0:
        return img
    out = img.copy()
    r, g, b, a = out.getpixel((0, 0))
    out.putpixel((0, 0), (r, g, b, (a + salt) % 256 if a < 200 else a - salt))
    return out


def write_clean(path: Path, encode) -> None:
    """encode(salt) -> bytes; tries salts until the bytes contain no forbidden sequence."""
    for salt in range(0, 50):
        data = encode(salt)
        if clean(data):
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
            print(f"wrote {path.relative_to(ROOT)} ({len(data)} bytes{', salt %d' % salt if salt else ''})")
            return
    raise SystemExit(f"could not encode {path} without forbidden byte sequences")


def png_bytes(img: Image.Image) -> bytes:
    buf = io.BytesIO()
    img.save(buf, "PNG", optimize=True)
    return buf.getvalue()


def multi_bytes(fmt: str, images: dict[int, Image.Image], salt: int) -> bytes:
    sizes = sorted(images)
    big = salted(images[sizes[-1]], salt)
    rest = [salted(images[s], salt) for s in sizes[:-1]]
    buf = io.BytesIO()
    if fmt == "ICO":
        big.save(buf, "ICO", sizes=[(s, s) for s in sizes], append_images=rest)
    else:
        big.save(buf, "ICNS", append_images=rest)
    return buf.getvalue()


def wordmark() -> None:
    try:
        from fontTools.pens.svgPathPen import SVGPathPen
        from fontTools.pens.transformPen import TransformPen
        from fontTools.ttLib import TTFont
    except ImportError:
        print("fontTools not available: wordmark.svg left as it is")
        return
    font = TTFont(str(PUBLIC / "fonts" / "ibm-plex-sans-latin-600-normal.woff2"))
    glyphs = font.getGlyphSet()
    cmap = font.getBestCmap()
    upm = font["head"].unitsPerEm
    size = 34  # font size in px within the 64 px high logo box
    scale = size / upm
    x = 0.0
    pen = SVGPathPen(glyphs)
    for ch in "Dispofold":
        name = cmap[ord(ch)]
        # baseline at y=44, flip y
        glyphs[name].draw(TransformPen(pen, (scale, 0, 0, -scale, 74 + x, 44)))
        x += glyphs[name].width * scale
    width = round(74 + x + 4)
    logo = (HERE / "logo.svg").read_text()
    shapes = re.search(r"<g>(.*?)</g>", logo, re.S).group(1)
    svg = f"""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {width} 64" width="{width * 2}" height="128">
  <title>Dispofold</title>
  <!-- Logo and name; the letters are outlines of IBM Plex Sans SemiBold (SIL OFL 1.1). Built by build-icons.py. -->
  <g>{shapes}</g>
  <path fill="#16303D" d="{pen.getCommands()}"/>
</svg>
"""
    (HERE / "wordmark.svg").write_text(svg)
    print("wrote branding/wordmark.svg")


def main() -> None:
    icon = HERE / "icon.svg"
    renders = {s: render(icon, s) for s in sorted(set(PNG_SIZES + ICO_SIZES + ICNS_SIZES))}
    for s in PNG_SIZES:
        write_clean(HERE / "png" / f"icon-{s}.png", lambda salt, s=s: png_bytes(salted(renders[s], salt)))
    write_clean(HERE / "icon.ico", lambda salt: multi_bytes("ICO", {s: renders[s] for s in ICO_SIZES}, salt))
    write_clean(HERE / "icon.icns", lambda salt: multi_bytes("ICNS", {s: renders[s] for s in ICNS_SIZES}, salt))
    write_clean(PUBLIC / "favicon.ico", lambda salt: multi_bytes("ICO", {s: renders[s] for s in (16, 32, 48)}, salt))
    shutil.copyfile(icon, PUBLIC / "favicon.svg")
    (PUBLIC / "img").mkdir(exist_ok=True)
    shutil.copyfile(HERE / "logo.svg", PUBLIC / "img" / "logo.svg")
    wordmark()


if __name__ == "__main__":
    main()
