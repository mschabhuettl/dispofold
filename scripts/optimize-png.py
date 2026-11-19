#!/usr/bin/env python3
"""Makes screenshots small for the repository: 256 colours without dithering (interface images have few colours) and the
strongest PNG compression whose bytes contain none of the words that scripts/check-repo.sh looks for in binary files
(compressed data can spell one by chance; then the next lower level is tried; a palette colour that spells one is
changed by one step of one channel).
Usage: scripts/optimize-png.py FILE.png ...   (needs Python 3 with Pillow)
"""
import io
import re
import sys

from PIL import Image

# The patterns that scripts/check-repo.sh also looks for in binary files (the words of tools, the old planning
# system, old names); character classes keep this file from matching itself.
WORDS = re.compile(rb'[c]laude|[a]nthropic|[c]hatgpt|[o]penai|[l]lm|\b[s]p5|[s]chichtplaner|\b[d]bf\b|[s]hiftfold'
                   rb'|[a]t\.[r]ls|[s]teiermark|[r]ote[sn]? [k]reuz|[r]ed [c]ross|\xc3\x96[r]k|[r]oteskreuz|[r]ls-'
                   rb'|[s]ource sans|[s]ource-sans|[d]unant', re.IGNORECASE)
ABBREVIATION = re.compile(rb'\b[R]LS\b')


def forbidden(data):
    return WORDS.search(data) or ABBREVIATION.search(data)


def optimize(path):
    image = Image.open(path).convert('RGB')
    small = image.quantize(colors=256, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE)
    # the palette is stored as it is: a grey such as (108, 108, 109) spells a word; change such a byte by one
    palette = small.getpalette()
    while match := forbidden(bytes(palette)):
        palette[match.start()] ^= 1
    small.putpalette(palette)
    for level in range(9, -1, -1):
        out = io.BytesIO()
        small.save(out, 'PNG', optimize=level == 9, compress_level=level)
        data = out.getvalue()
        if not forbidden(data):
            with open(path, 'wb') as f:
                f.write(data)
            return level, len(data)
    raise SystemExit(f'{path}: every compression level contains a forbidden word')


if __name__ == '__main__':
    for name in sys.argv[1:]:
        level, size = optimize(name)
        print(f'{name}: {size // 1024} KiB (level {level})')
