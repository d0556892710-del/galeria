#!/usr/bin/env python3
"""Makes N unique test photos (with real EXIF capture dates spread over years) for the stress test."""
import os, random, sys, datetime
from multiprocessing import Pool
from PIL import Image, ImageDraw

N = int(sys.argv[1]) if len(sys.argv) > 1 else 20000
OUT = sys.argv[2] if len(sys.argv) > 2 else "Camera"
os.makedirs(OUT, exist_ok=True)
START = datetime.datetime(2020, 1, 1)
SPAN = (datetime.datetime(2026, 11, 1) - START).total_seconds()

def make(i):
    rnd = random.Random(i)
    w, h = (800, 600) if rnd.random() < .7 else (600, 800)
    c1 = tuple(rnd.randrange(256) for _ in range(3)); c2 = tuple(rnd.randrange(256) for _ in range(3))
    img = Image.new("RGB", (w, h), c1)
    d = ImageDraw.Draw(img)
    for y in range(0, h, 8):
        t = y / h
        d.rectangle([0, y, w, y + 8], fill=tuple(int(c1[k] * (1 - t) + c2[k] * t) for k in range(3)))
    for _ in range(rnd.randrange(3, 9)):
        x0, y0 = rnd.randrange(w), rnd.randrange(h)
        x1, y1 = x0 + rnd.randrange(40, 300), y0 + rnd.randrange(40, 300)
        col = tuple(rnd.randrange(256) for _ in range(3))
        (d.ellipse if rnd.random() < .5 else d.rectangle)([x0, y0, x1, y1], fill=col)
    d.text((20, 20), f"#{i}", fill=(255, 255, 255))
    when = START + datetime.timedelta(seconds=SPAN * (i / N) + rnd.randrange(0, 3600))
    stamp = when.strftime("%Y:%m:%d %H:%M:%S")
    exif = img.getexif()
    exif[0x0132] = stamp
    exif.get_ifd(0x8769)[36867] = stamp
    path = os.path.join(OUT, f"IMG_{when.strftime('%Y%m%d_%H%M%S')}_{i:05d}.jpg")
    img.save(path, quality=82, exif=exif.tobytes())
    ts = when.timestamp()
    os.utime(path, (ts, ts))
    return 0

if __name__ == "__main__":
    with Pool(os.cpu_count() or 4) as p:
        p.map(make, range(N), chunksize=200)
    print("made", len(os.listdir(OUT)))
