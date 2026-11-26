#!/usr/bin/env python3
"""Spatial Scenes on a virtual phone (Android 13): the hexagon shows for photos with a clear subject, a tap
turns the photo into a 3D scene that moves, and photos without depth (a brick wall) get no hexagon."""
import os, re, subprocess, time, xml.etree.ElementTree as ET

PKG = "com.galeria.app"; OUT = "spatial_shots"; os.makedirs(OUT, exist_ok=True)
def adb(*a, timeout=120):
    try: return subprocess.run(["adb", *a], capture_output=True, timeout=timeout).stdout
    except subprocess.TimeoutExpired: return b""
def sh(c, timeout=120): return adb("shell", c, timeout=timeout).decode("utf-8", "ignore")
def shot(name):
    png = adb("exec-out", "screencap", "-p"); open(f"{OUT}/{name}.png", "wb").write(png)
def nodes():
    sh("uiautomator dump /sdcard/ui.xml"); x = adb("exec-out", "cat", "/sdcard/ui.xml")
    try: root = ET.fromstring(x)
    except Exception: return []
    return [(e.get("text", ""), e.get("content-desc", ""), [int(v) for v in re.findall(r"\d+", e.get("bounds", ""))]) for e in root.iter("node")]
def find(label):
    for t, d, b in nodes():
        if (t.strip() == label or d.strip() == label) and len(b) == 4: return b
    return None
def tap(label):
    b = find(label)
    if b: sh(f"input tap {(b[0]+b[2])//2} {(b[1]+b[3])//2}"); time.sleep(1.2); return True
    print("not found:", label, flush=True); return False
def log(): return adb("logcat", "-d").decode("utf-8", "ignore")
def wait_for(pattern, secs):
    end = time.time() + secs
    while time.time() < end:
        m = re.search(pattern, log())
        if m: return m.group(0)
        time.sleep(2)
    return None
def media_id(name):
    q = sh("content query --uri content://media/external/images/media --projection _id:_display_name")
    return next((re.search(r"_id=(\d+)", l).group(1) for l in q.splitlines() if name in l), None)
def open_photo(mid):
    sh(f"am start -a android.intent.action.VIEW -d content://media/external/images/media/{mid} -t image/jpeg -n {PKG}/.MainActivity")

sh(f"am force-stop {PKG}"); adb("logcat", "-c")
sh(f"monkey -p {PKG} -c android.intent.category.LAUNCHER 1"); time.sleep(8)
tap("Cancel"); shot("01_library")
ok = True
for name, expect in [("astronaut", True), ("coffee", True), ("brick", False)]:
    mid = media_id(name); print(f"== {name} (id {mid}), expect hexagon: {expect}", flush=True)
    adb("logcat", "-c"); open_photo(mid); time.sleep(3)
    t0 = time.time(); line = wait_for(r"spatial analyse id=\d+ eligible=\w+ score=[\d.]+ ms=\d+", 120)
    print("  ", line or "NO ANALYSIS", f"after {time.time()-t0:.0f}s", flush=True)
    time.sleep(1.5); shot(f"{name}_1_viewer")
    has = find("Spatial Scene") is not None
    print("   hexagon shown:", has, flush=True)
    if has != expect: ok = False
    if has:
        tap("Spatial Scene")
        t1 = time.time(); line = wait_for(r"spatial scene id=\d+ \d+x\d+ ms=\d+", 90)
        print("  ", line or "NO SCENE", f"after {time.time()-t1:.0f}s", flush=True)
        if not line: ok = False
        # The opening sweep: a few frames to see it move.
        for i in range(5): shot(f"{name}_2_sweep{i}"); time.sleep(0.3)
        line = wait_for(r"spatial fill ok=\w+ ms=\d+", 150)
        print("  ", line or "NO FILL", flush=True)
        time.sleep(1); shot(f"{name}_3_filled")
        tap("Spatial Scene"); time.sleep(1); shot(f"{name}_4_off")
    sh("input keyevent 4"); time.sleep(2)
for l in log().splitlines():
    if "PhotosPerf" in l or " Photos" in l or "FATAL" in l or "AndroidRuntime" in l: print(l[:300], flush=True)
m = sh(f"dumpsys meminfo {PKG}"); tot = re.search(r"TOTAL PSS:\s*(\d+)", m) or re.search(r"\n\s*TOTAL\s+(\d+)", m)
print("memory", (int(tot.group(1)) // 1024 if tot else "?"), "MB", "alive" if sh(f"pidof {PKG}").strip() else "DEAD", flush=True)
print("RESULT:", "PASS" if ok else "FAIL", flush=True)
