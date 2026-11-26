#!/usr/bin/env python3
"""End-to-end Clean Up test on the virtual phone: open a real photo, circle the spoon, check it's removed."""
import os, re, subprocess, time, xml.etree.ElementTree as ET
from PIL import Image
import numpy as np

PKG = "com.galeria.app"; OUT = "cleanup_shots"; os.makedirs(OUT, exist_ok=True)
def adb(*a, timeout=120):
    try: return subprocess.run(["adb", *a], capture_output=True, timeout=timeout).stdout
    except subprocess.TimeoutExpired: return b""
def sh(c, timeout=120): return adb("shell", c, timeout=timeout).decode("utf-8", "ignore")
def shot(name):
    png = adb("exec-out", "screencap", "-p"); p = f"{OUT}/{name}.png"; open(p, "wb").write(png); return p
def nodes():
    sh("uiautomator dump /sdcard/ui.xml"); x = adb("exec-out", "cat", "/sdcard/ui.xml")
    try: root = ET.fromstring(x)
    except Exception: return []
    return [(e.get("text", ""), e.get("content-desc", ""), [int(v) for v in re.findall(r"\d+", e.get("bounds", ""))]) for e in root.iter("node")]
def tap(label):
    for t, d, b in nodes():
        if (t.strip() == label or d.strip() == label) and len(b) == 4:
            sh(f"input tap {(b[0]+b[2])//2} {(b[1]+b[3])//2}"); time.sleep(1.2); return True
    print("not found:", label, flush=True); return False

W, H = [int(v) for v in re.search(r"(\d+)x(\d+)", sh("wm size")).groups()]
sh(f"am force-stop {PKG}"); adb("logcat", "-c")
q = sh("content query --uri content://media/external/images/media --projection _id:_display_name")
mid = next((re.search(r"_id=(\d+)", l).group(1) for l in q.splitlines() if "coffee" in l), None)
print("media id", mid, flush=True)
sh(f"monkey -p {PKG} -c android.intent.category.LAUNCHER 1"); time.sleep(8)
tap("Cancel"); time.sleep(1); shot("01_library")
sh(f"am start -a android.intent.action.VIEW -d content://media/external/images/media/{mid} -t image/jpeg -n {PKG}/.MainActivity")
time.sleep(4); shot("02_viewer")
tap("Edit"); time.sleep(4); shot("03_editor")
tap("Clean Up"); time.sleep(2); p = shot("04_cleanup_mode")
# Find where the photo is drawn (the editor background is black).
a = np.asarray(Image.open(p).convert("RGB")).astype(int)
lum = a.sum(axis=2)
rows = np.where((lum > 60).sum(axis=1) > W * 0.5)[0]; cols = np.where((lum > 60).sum(axis=0) > 50)[0]
rows = rows[(rows > H * 0.08) & (rows < H * 0.75)]
top, bottom = rows.min(), rows.max(); left, right = cols.min(), cols.max()
print("photo rect", left, top, right, bottom, flush=True)
iw, ih = right - left, bottom - top
# Spoon in the 600x400 coffee picture: centre ~(372, 230), circle radius ~ (75, 150) px of the picture.
cx, cy = left + iw * 372 / 600, top + ih * 225 / 400
rx, ry = iw * 78 / 600, ih * 150 / 400
pts = [(int(cx + rx * np.cos(t)), int(cy + ry * np.sin(t))) for t in np.linspace(0, 2 * np.pi * 1.04, 70)]
sh(f"input motionevent DOWN {pts[0][0]} {pts[0][1]}")
for x, y in pts[1:]: sh(f"input motionevent MOVE {x} {y}")
sh(f"input motionevent UP {pts[-1][0]} {pts[-1][1]}")
t0 = time.time(); done = False
while time.time() - t0 < 240:
    lc = adb("logcat", "-d", "-s", "PhotosPerf:I").decode("utf-8", "ignore")
    if "cleanup model" in lc: done = True; break
    time.sleep(2)
time.sleep(3)
shot("05_after_cleanup_quick")
print(f"quick result after {time.time()-t0:.0f}s", flush=True)
# A sharper second pass may follow for big objects.
t1 = time.time()
while time.time() - t1 < 200:
    lc = adb("logcat", "-d", "-s", "PhotosPerf:I").decode("utf-8", "ignore")
    if "cleanup model=1024" in lc: break
    time.sleep(3)
time.sleep(4)
shot("06_after_cleanup_sharp")
lc = adb("logcat", "-d").decode("utf-8", "ignore")
for line in lc.splitlines():
    if "PhotosPerf" in line or "clean up" in line or "FATAL" in line or "OutOfMemory" in line or "onnxruntime" in line.lower(): print(line[:300], flush=True)
print("RESULT:", "DONE" if done else "TIMEOUT", f"{time.time()-t0:.0f}s", flush=True)
m = sh(f"dumpsys meminfo {PKG}"); tot = re.search(r"TOTAL PSS:\s*(\d+)", m) or re.search(r"\n\s*TOTAL\s+(\d+)", m)
print("memory", (int(tot.group(1)) // 1024 if tot else "?"), "MB", "alive" if sh(f"pidof {PKG}").strip() else "DEAD", flush=True)
