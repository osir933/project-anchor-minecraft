"""Prints a PNG as a coarse grid of average colours, hex per cell, so a screenshot can be seen in a log."""
import struct
import sys
import zlib


def read_png(path):
    data = open(path, "rb").read()
    assert data[:8] == b"\x89PNG\r\n\x1a\n", "not a PNG"
    pos, idat = 8, b""
    width = height = channels = None
    while pos < len(data):
        (length,) = struct.unpack(">I", data[pos:pos + 4])
        kind = data[pos + 4:pos + 8]
        chunk = data[pos + 8:pos + 8 + length]
        pos += 12 + length
        if kind == b"IHDR":
            width, height, depth, colour, _, _, interlace = struct.unpack(">IIBBBBB", chunk)
            assert depth == 8 and interlace == 0, (depth, interlace)
            channels = {2: 3, 6: 4}[colour]
        elif kind == b"IDAT":
            idat += chunk
        elif kind == b"IEND":
            break
    raw = zlib.decompress(idat)
    stride = width * channels
    rows, previous, i = [], bytearray(stride), 0
    for _ in range(height):
        kind = raw[i]
        i += 1
        line = bytearray(raw[i:i + stride])
        i += stride
        for x in range(stride):
            a = line[x - channels] if x >= channels else 0
            b = previous[x]
            c = previous[x - channels] if x >= channels else 0
            if kind == 1:
                line[x] = (line[x] + a) & 255
            elif kind == 2:
                line[x] = (line[x] + b) & 255
            elif kind == 3:
                line[x] = (line[x] + ((a + b) >> 1)) & 255
            elif kind == 4:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                line[x] = (line[x] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
        rows.append(line)
        previous = line
    return width, height, channels, rows


def main():
    path, columns, lines = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
    width, height, channels, rows = read_png(path)
    print(f"GRID {width}x{height} -> {columns}x{lines}")
    for gy in range(lines):
        y0, y1 = gy * height // lines, (gy + 1) * height // lines
        cells = []
        for gx in range(columns):
            x0, x1 = gx * width // columns, (gx + 1) * width // columns
            total, count = [0, 0, 0], 0
            for y in range(y0, y1, 2):
                row = rows[y]
                for x in range(x0, x1, 2):
                    k = x * channels
                    total[0] += row[k]
                    total[1] += row[k + 1]
                    total[2] += row[k + 2]
                    count += 1
            cells.append("%02x%02x%02x" % tuple(v // max(count, 1) for v in total))
        print("ROW " + "".join(cells))


if __name__ == "__main__":
    main()
