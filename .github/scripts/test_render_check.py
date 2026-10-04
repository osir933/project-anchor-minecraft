"""Tests for render_check.py on made-up pictures; CI runs them with the other script tests."""

import contextlib
import io
import struct
import tempfile
import unittest
import zlib
from pathlib import Path

import render_check

WIDTH, HEIGHT = 160, 90
DARK = (12, 12, 14)
# The colours the game draws iron in a dark room with the glow added: 1100 K and 1600 K.
WARM = (160, 50, 14)
HOT = (255, 128, 14)
LEFT_BLOCK = (50, 30, 71, 61)
RIGHT_BLOCK = (90, 30, 111, 61)


def picture(*boxes, background=DARK):
    """A picture of the background colour with boxes (x0, y0, x1, y1, colour) drawn on it, as read_png gives it."""
    rows = []
    for y in range(HEIGHT):
        row = []
        for x in range(WIDTH):
            colour = background
            for x0, y0, x1, y1, box_colour in boxes:
                if x0 <= x < x1 and y0 <= y < y1:
                    colour = box_colour
            row.append(colour)
        rows.append(row)
    return WIDTH, HEIGHT, rows


def write_png(path, image, alpha=True):
    """Writes a picture as a PNG, filtering each row with the next of PNG's five filters, so all get read back."""
    width, height, rows = image
    channels = 4 if alpha else 3
    stride = width * channels
    raw, previous = bytearray(), bytearray(stride)
    for y, row in enumerate(rows):
        line = bytearray()
        for colour in row:
            line += bytes(colour) + (b"\xff" if alpha else b"")
        kind = y % 5
        filtered = bytearray(stride)
        for x in range(stride):
            a = line[x - channels] if x >= channels else 0
            b = previous[x]
            c = previous[x - channels] if x >= channels else 0
            if kind == 0:
                predicted = 0
            elif kind == 1:
                predicted = a
            elif kind == 2:
                predicted = b
            elif kind == 3:
                predicted = (a + b) >> 1
            else:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                predicted = a if pa <= pb and pa <= pc else b if pb <= pc else c
            filtered[x] = (line[x] - predicted) & 255
        raw += bytes([kind]) + filtered
        previous = line

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))

    header = struct.pack(">IIBBBBB", width, height, 8, 6 if alpha else 2, 0, 0, 0)
    Path(path).write_bytes(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(raw))
                           + chunk(b"IEND", b""))


class ReadPngTest(unittest.TestCase):

    def test_reads_back_every_filter_with_and_without_alpha(self):
        image = picture(LEFT_BLOCK + (WARM,), (0, 0, 20, 90, (200, 100, 50)), (100, 10, 160, 20, (1, 254, 128)))
        with tempfile.TemporaryDirectory() as directory:
            for alpha in (True, False):
                path = Path(directory) / f"picture-{alpha}.png"
                write_png(path, image, alpha)
                self.assertEqual(image, render_check.read_png(path))

    def test_refuses_what_is_not_a_png(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "hot.png"
            path.write_bytes(b"GIF89a")
            with self.assertRaises(ValueError):
                render_check.read_png(path)


class CheckTest(unittest.TestCase):

    def problems(self, hot, cold):
        return render_check.check(hot, cold)[1]

    def test_two_blocks_glowing_as_black_bodies_pass(self):
        lines, problems = render_check.check(picture(LEFT_BLOCK + (WARM,), RIGHT_BLOCK + (HOT,)), picture())
        self.assertEqual([], problems)
        self.assertIn("left block, 1100 K: 651 glowing pixels", lines[1])
        self.assertIn("around (60, 45)", lines[1])

    def test_no_glow_fails(self):
        problems = self.problems(picture(), picture())
        self.assertTrue(any("left block, 1100 K hardly glows" in p for p in problems), problems)
        self.assertTrue(any("right block, 1600 K hardly glows" in p for p in problems), problems)

    def test_glow_that_stays_once_cooled_fails(self):
        both = picture(LEFT_BLOCK + (WARM,), RIGHT_BLOCK + (HOT,))
        self.assertEqual(2, len(self.problems(both, both)), "neither block counts as glowing")
        dim = picture(LEFT_BLOCK + ((100, 40, 14),), RIGHT_BLOCK + ((180, 100, 14),))
        problems = self.problems(picture(LEFT_BLOCK + (WARM,), RIGHT_BLOCK + (HOT,)), dim)
        self.assertTrue(any("still bright once cooled" in p for p in problems), problems)

    def test_glow_in_the_wrong_place_or_everywhere_fails(self):
        problems = self.problems(picture((0, 0, 20, 20, WARM), (140, 0, 160, 20, HOT)), picture())
        self.assertEqual(2, sum("glows in the wrong place" in p for p in problems), problems)
        problems = self.problems(picture((0, 0, 80, 90, WARM), (80, 0, 160, 90, HOT)), picture())
        self.assertTrue(any("the glow covers 100%" in p for p in problems), problems)

    def test_colours_must_follow_the_temperatures(self):
        problems = self.problems(picture(LEFT_BLOCK + (WARM,), RIGHT_BLOCK + (WARM,)), picture())
        self.assertTrue(any("not yellower" in p for p in problems), problems)
        self.assertTrue(any("does not glow brighter" in p for p in problems), problems)
        problems = self.problems(picture(LEFT_BLOCK + ((200, 220, 240),), RIGHT_BLOCK + (HOT,)), picture())
        self.assertTrue(any("does not glow red to orange" in p for p in problems), problems)

    def test_pictures_of_different_sizes_fail(self):
        width, height, rows = picture()
        self.assertEqual(1, len(self.problems((width, height - 1, rows[1:]), picture())))


class MainTest(unittest.TestCase):

    def run_main(self, directory):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            code = render_check.main(["render_check.py", str(directory)])
        return code, out.getvalue()

    def test_passes_and_fails_with_a_message(self):
        with tempfile.TemporaryDirectory() as directory:
            write_png(Path(directory) / "hot.png", picture(LEFT_BLOCK + (WARM,), RIGHT_BLOCK + (HOT,)))
            write_png(Path(directory) / "cold.png", picture())
            code, out = self.run_main(directory)
            self.assertEqual(0, code, out)
            self.assertIn("Render check passed", out)

            write_png(Path(directory) / "hot.png", picture())
            code, out = self.run_main(directory)
            self.assertEqual(1, code)
            self.assertIn("Render check failed: the left block, 1100 K hardly glows", out)
            self.assertIn("The hot picture, sampled:", out)

    def test_missing_pictures_fail(self):
        with tempfile.TemporaryDirectory() as directory:
            code, out = self.run_main(directory)
            self.assertEqual(1, code)
            self.assertIn("Render check failed", out)


if __name__ == "__main__":
    unittest.main()
