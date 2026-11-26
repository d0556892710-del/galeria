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
def dump():
    sh("uiautomator dump /sdcard/ui.xml"); x = adb("exec-out", "cat", "/sdcard/ui.xml")
    try: root = ET.fromstring(x)
    except Exception: return []
    return [(e.get("text", ""), e.get("content-desc", ""), [int(v) for v in re.findall(r"\d+", e.get("bounds", ""))]) for e in root.iter("node")]
def nodes():
    # The slow virtual phone sometimes shows "<app> isn't responding" for its own launcher: wait it out.
    for _ in range(3):
        ns = dump()
        if not any("isn't responding" in t or "isn’t responding" in t for t, d, b in ns): return ns
        for t, d, b in ns:
            if t.strip() == "Close app" and len(b) == 4: sh(f"input tap {(b[0]+b[2])//2} {(b[1]+b[3])//2}"); print("   (closed a system 'not responding' dialog)", flush=True); break
        time.sleep(1.5)
    return dump()
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
    # Opens the photo in the app's viewer; if the phone shows something else, tries again once.
    for attempt in range(2):
        sh(f"am start -a android.intent.action.VIEW -d content://media/external/images/media/{mid} -t image/jpeg -n {PKG}/.MainActivity")
        time.sleep(3)
        if find("Edit") is not None or find("Share") is not None: return True
        print("   (viewer not showing, opening again)", flush=True)
        sh(f"am force-stop {PKG}"); time.sleep(1)
    return False

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
        # The opening sweep is recorded on video (screenshots are too slow to catch it).
        rec = subprocess.Popen(["adb", "shell", f"screenrecord --time-limit 7 /sdcard/{name}_sweep.mp4"])
        time.sleep(0.8)
        tap("Spatial Scene")
        t1 = time.time(); line = wait_for(r"spatial scene id=\d+ \d+x\d+ ms=\d+", 90)
        print("  ", line or "NO SCENE", f"after {time.time()-t1:.0f}s", flush=True)
        if not line: ok = False
        try: rec.wait(timeout=30)
        except Exception: rec.kill()
        time.sleep(1)
        adb("pull", f"/sdcard/{name}_sweep.mp4", f"{OUT}/{name}_sweep.mp4")
        if os.path.exists(f"{OUT}/{name}_sweep.mp4"):
            try: subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", f"{OUT}/{name}_sweep.mp4", "-vf", "fps=4,scale=540:-1", f"{OUT}/{name}_f%02d.png"], timeout=120)
            except Exception as e: print("   ffmpeg failed:", e, flush=True)
            try:
                from PIL import Image
                import numpy as np
                fr = sorted(f for f in os.listdir(OUT) if f.startswith(f"{name}_f") and f.endswith(".png"))
                arr = [np.asarray(Image.open(f"{OUT}/{f}").convert("L"), dtype=float) for f in fr]
                h = arr[0].shape[0]
                diffs = [round(float(np.abs(arr[i][h//4:3*h//4] - arr[i-1][h//4:3*h//4]).mean()), 2) for i in range(1, len(arr))]
                print("   frame-to-frame motion:", diffs, flush=True)
                if max(diffs[2:] or [0]) < 0.8: print("   WARNING: hardly any motion", flush=True)
            except Exception as e: print("   motion check failed:", e, flush=True)
        else: print("   no recording", flush=True)
        line = wait_for(r"spatial fill ok=\w+ ms=\d+", 150)
        print("  ", line or "NO FILL", flush=True)
        time.sleep(1); shot(f"{name}_3_filled")
        tap("Spatial Scene"); time.sleep(1); shot(f"{name}_4_off")
    sh("input keyevent 4"); time.sleep(2)
# ---- Subject lift: touch and hold the astronaut; she lifts out with Copy / Share ----
print("== lift", flush=True)
adb("logcat", "-c"); open_photo(media_id("astronaut")); time.sleep(4)
W, H = [int(v) for v in re.search(r"(\d+)x(\d+)", sh("wm size")).groups()]
x, y = int(W * .42), int(H / 2 + W * .1)   # her suit, below the face (the photo is square, centred)
sh(f"input swipe {x} {y} {x} {y} 1200")
line = wait_for(r"lift quick found=\w+ ms=\d+", 120)
print("  ", line or "NO LIFT", flush=True)
time.sleep(1.5); shot("lift_1_lifted")
has_menu = find("Copy") is not None
print("   menu shown:", has_menu, flush=True)
if not (line and "found=true" in line and has_menu): ok = False
line = wait_for(r"lift refine ok=\w+ ms=\d+", 150)
print("  ", line or "NO REFINE", flush=True)
time.sleep(1); shot("lift_2_refined")
if tap("Copy"): time.sleep(1); shot("lift_3_copied")
sh("input keyevent 4"); time.sleep(1.5)

# ---- Hebrew Live Text: the text is read in Hebrew, copied, and pasted into Search to check it ----
print("== hebrew live text", flush=True)
adb("logcat", "-c"); open_photo(media_id("hebrew")); time.sleep(3)
line = wait_for(r"livetext blocks=\d+ hebrew=\d+ ms=\d+", 90)
print("  ", line or "NO LIVE TEXT", flush=True)
if not line or re.search(r"hebrew=0\b", line): ok = False
if tap("Live Text"):
    time.sleep(1); shot("hebrew_1_livetext")
    W, H = [int(v) for v in re.search(r"(\d+)x(\d+)", sh("wm size")).groups()]
    ph = W * 900 / 1400; top = (H - ph) / 2
    sh(f"input tap {W // 2} {int(top + ph * 290 / 900)}"); time.sleep(1.5); shot("hebrew_2_copied")
    sh(f"am force-stop {PKG}"); time.sleep(1)
    sh(f"monkey -p {PKG} -c android.intent.category.LAUNCHER 1"); time.sleep(6)
    tap("Cancel")
    if tap("Search"):
        time.sleep(1.5); sh("input keyevent 279"); time.sleep(2); shot("hebrew_3_pasted")
        texts = [t for t, d, b in nodes() if t]
        pasted = next((t for t in texts if any("\u05D0" <= c <= "\u05EA" for c in t)), "")
        print("   pasted:", pasted, flush=True)
        if "שלום" not in pasted: ok = False
        sh("input keyevent 4"); time.sleep(1)

# ---- Portrait on any photo: the astronaut gets the Portrait tab; the blur appears when switched on ----
print("== portrait", flush=True)
adb("logcat", "-c"); open_photo(media_id("astronaut")); time.sleep(4)
tap("Edit"); time.sleep(3)
end = time.time() + 40
while time.time() < end and find("Portrait") is None: time.sleep(2)
has = find("Portrait") is not None
print("   portrait tab:", has, flush=True)
if not has: ok = False
else:
    tap("Portrait"); time.sleep(1.5); shot("portrait_1_tab")
    tap("PORTRAIT"); time.sleep(6); shot("portrait_2_on")
    # Most blur (drag the dial to ƒ1.4), then a tap on the background moves the focus there.
    b = find("PORTRAIT")
    if b:
        y = b[3] + 120
        sh(f"input swipe 300 {y} 1000 {y} 600"); time.sleep(5); shot("portrait_3_f14")
    sh("input tap 900 700"); time.sleep(5); shot("portrait_4_focus_back")
    sh("input keyevent 4"); time.sleep(1.5); shot("portrait_5_discard")
    tap("Discard Changes"); time.sleep(1)
for l in log().splitlines():
    if "PhotosPerf" in l or " Photos" in l or "FATAL" in l or "AndroidRuntime" in l: print(l[:300], flush=True)
m = sh(f"dumpsys meminfo {PKG}"); tot = re.search(r"TOTAL PSS:\s*(\d+)", m) or re.search(r"\n\s*TOTAL\s+(\d+)", m)
print("memory", (int(tot.group(1)) // 1024 if tot else "?"), "MB", "alive" if sh(f"pidof {PKG}").strip() else "DEAD", flush=True)
print("RESULT:", "PASS" if ok else "FAIL", flush=True)
