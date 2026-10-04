"""Checks the pictures the render test takes in the game: that hot iron was drawn glowing, and cooled iron not.

The render test (RenderTest in the mod) builds a dark room with two iron blocks in front of the camera, the left
one at 1100 K and the right one at 1600 K, photographs them as hot.png, cools both to 300 K and photographs them
again as cold.png. Whatever got brighter and redder between the two pictures is the glow. The check wants it on
both blocks, where the blocks are, coloured as a black body is (red at 1100 K, a yellower orange at 1600 K, the
hotter brighter) and gone once they are cool; and not spilling over the rest of the picture.

    render_check.py DIR

DIR holds hot.png and cold.png. Only the standard library is used.
"""

import struct
import sys
import zlib
from pathlib import Path

# How much redder, out of 255, a pixel must be when hot than when cool to count as glowing.
GLOW_RED = 40
# The least part of the picture each block's glow must cover. Each block's face takes about 4% of it.
LEAST_SHARE = 0.005
# The most of the picture the glow may cover; more means it is drawn where there are no hot blocks.
MOST_SHARE = 0.15
# How much greener the 1600 K glow must be than the 1100 K glow, as green over red.
LEAST_GREEN_GAIN = 0.05
# The brightest a glowing spot may be, out of 255, once cooled.
DARKEST_COOLED = 60


def read_png(path):
    """Reads an 8-bit, non-interlaced RGB or RGBA PNG as (width, height, rows of (r, g, b) tuples)."""
    data = Path(path).read_bytes()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError(f"{path} is not a PNG")
    pos, idat = 8, bytearray()
    width = height = channels = None
    while pos < len(data):
        (length,) = struct.unpack(">I", data[pos:pos + 4])
        kind = data[pos + 4:pos + 8]
        chunk = data[pos + 8:pos + 8 + length]
        pos += 12 + length
        if kind == b"IHDR":
            width, height, depth, colour, _, _, interlace = struct.unpack(">IIBBBBB", chunk)
            if depth != 8 or interlace != 0 or colour not in (2, 6):
                raise ValueError(f"{path}: only 8-bit, non-interlaced RGB or RGBA is read")
            channels = 3 if colour == 2 else 4
        elif kind == b"IDAT":
            idat += chunk
        elif kind == b"IEND":
            break
    raw = zlib.decompress(bytes(idat))
    stride = width * channels
    if len(raw) != height * (stride + 1):
        raise ValueError(f"{path}: the image data has the wrong length")
    rows, previous = [], bytearray(stride)
    for y in range(height):
        start = y * (stride + 1)
        kind, line = raw[start], bytearray(raw[start + 1:start + 1 + stride])
        unfilter(kind, line, previous, channels)
        rows.append([tuple(line[x:x + 3]) for x in range(0, stride, channels)])
        previous = line
    return width, height, rows


def unfilter(kind, line, previous, channels):
    """Undoes a row's PNG filter in place, given the unfiltered row above it."""
    if kind == 0:
        return
    if kind == 2:
        for x in range(len(line)):
            line[x] = (line[x] + previous[x]) & 255
        return
    for x in range(len(line)):
        a = line[x - channels] if x >= channels else 0
        b = previous[x]
        if kind == 1:
            line[x] = (line[x] + a) & 255
        elif kind == 3:
            line[x] = (line[x] + ((a + b) >> 1)) & 255
        elif kind == 4:
            c = previous[x - channels] if x >= channels else 0
            p = a + b - c
            pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
            line[x] = (line[x] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
        else:
            raise ValueError(f"unknown PNG filter {kind}")


class Glow:
    """The pixels of one half of the picture that glow, with their colours hot and cooled."""

    def __init__(self, name):
        self.name = name
        self.count = 0
        self.x = self.y = 0
        self.hot = [0, 0, 0]
        self.cold = [0, 0, 0]

    def add(self, x, y, hot, cold):
        self.count += 1
        self.x += x
        self.y += y
        for i in range(3):
            self.hot[i] += hot[i]
            self.cold[i] += cold[i]

    def centre(self):
        return (self.x / self.count, self.y / self.count) if self.count else (float("nan"), float("nan"))

    def mean_hot(self):
        return [v / max(self.count, 1) for v in self.hot]

    def mean_cold(self):
        return [v / max(self.count, 1) for v in self.cold]

    def describe(self, area):
        hot = "%.0f %.0f %.0f" % tuple(self.mean_hot())
        cold = "%.0f %.0f %.0f" % tuple(self.mean_cold())
        x, y = self.centre()
        return (f"{self.name}: {self.count} glowing pixels ({100.0 * self.count / area:.1f}% of the picture) "
                f"around ({x:.0f}, {y:.0f}); hot {hot}, cooled {cold}")


def check(hot_image, cold_image):
    """Compares the hot and cooled pictures; returns (lines describing the glow, problems found)."""
    width, height, hot_rows = hot_image
    cold_width, cold_height, cold_rows = cold_image
    if (width, height) != (cold_width, cold_height):
        return [], [f"the pictures differ in size: {width}x{height} hot, {cold_width}x{cold_height} cooled"]
    left, right = Glow("left block, 1100 K"), Glow("right block, 1600 K")
    for y in range(height):
        hot_row, cold_row = hot_rows[y], cold_rows[y]
        for x in range(width):
            hot, cold = hot_row[x], cold_row[x]
            if hot[0] - cold[0] > GLOW_RED:
                (left if x < width / 2 else right).add(x, y, hot, cold)
    area = width * height
    lines = [f"{width}x{height} pictures", left.describe(area), right.describe(area)]
    problems = []
    for glow, low, high in ((left, 0.1, 0.5), (right, 0.5, 0.9)):
        if glow.count < LEAST_SHARE * area:
            problems.append(f"the {glow.name} hardly glows: {glow.count} pixels")
            continue
        x, y = glow.centre()
        if not (low * width <= x <= high * width and 0.25 * height <= y <= 0.75 * height):
            problems.append(f"the {glow.name} glows in the wrong place, around ({x:.0f}, {y:.0f})")
        r, g, b = glow.mean_hot()
        if not r > g > b:
            problems.append(f"the {glow.name} does not glow red to orange: {r:.0f} {g:.0f} {b:.0f}")
        if max(glow.mean_cold()) > DARKEST_COOLED:
            problems.append(f"the {glow.name} is still bright once cooled: %.0f %.0f %.0f" % tuple(glow.mean_cold()))
    if left.count + right.count > MOST_SHARE * area:
        problems.append(f"the glow covers {100.0 * (left.count + right.count) / area:.0f}% of the picture")
    if left.count >= LEAST_SHARE * area and right.count >= LEAST_SHARE * area:
        lr, lg, lb = left.mean_hot()
        rr, rg, rb = right.mean_hot()
        if rg / max(rr, 1) < lg / max(lr, 1) + LEAST_GREEN_GAIN:
            problems.append("the hotter block's glow is not yellower than the cooler one's")
        if rr + rg + rb <= lr + lg + lb:
            problems.append("the hotter block does not glow brighter than the cooler one")
    return lines, problems


def grid(image, columns=48, lines=27):
    """Samples a picture on a coarse grid, a hex colour per cell, so that it can be seen in a log."""
    width, height, rows = image
    out = []
    for gy in range(lines):
        cells = []
        for gx in range(columns):
            x, y = (2 * gx + 1) * width // (2 * columns), (2 * gy + 1) * height // (2 * lines)
            cells.append("%02x%02x%02x" % rows[y][x])
        out.append(" ".join(cells))
    return out


def main(argv):
    if len(argv) != 2:
        print(__doc__, file=sys.stderr)
        return 2
    directory = Path(argv[1])
    try:
        hot, cold = read_png(directory / "hot.png"), read_png(directory / "cold.png")
    except (OSError, ValueError) as e:
        print(f"Render check failed: {e}")
        return 1
    lines, problems = check(hot, cold)
    for line in lines:
        print(line)
    if problems:
        for name, image in (("hot", hot), ("cooled", cold)):
            print(f"The {name} picture, sampled:")
            for row in grid(image):
                print("  " + row)
        print("Render check failed: " + "; ".join(problems))
        return 1
    print("Render check passed: the hot iron glowed as it should, and stopped once cooled.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
