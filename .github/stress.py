#!/usr/bin/env python3
"""Measures how the app behaves with a huge library: start-up, scrolling, screens, memory."""
import os, re, subprocess, time, xml.etree.ElementTree as ET

PKG = "com.galeria.app"
OUT = "stress_shots"
os.makedirs(OUT, exist_ok=True)

def strings(path):
    d = {}
    try:
        for m in re.finditer(r'<string name="([^"]+)"[^>]*>(.*?)</string>', open(path, encoding="utf-8").read(), re.S):
            d[m.group(1)] = m.group(2).replace("\\'", "'").replace("&amp;", "&")
    except Exception:
        pass
    return d
S = strings("app/src/main/res/values/strings.xml")

def adb(*a, capture=True, timeout=90):
    try:
        r = subprocess.run(["adb", *a], capture_output=True, timeout=timeout)
        return r.stdout.decode("utf-8", "ignore") if capture else ""
    except subprocess.TimeoutExpired:
        print("  (adb timed out)", a[:3], flush=True); return ""

def sh(cmd, timeout=90): return adb("shell", cmd, timeout=timeout)

def size():
    m = re.search(r"(\d+)x(\d+)", sh("wm size"))
    return (int(m.group(1)), int(m.group(2))) if m else (1080, 2400)
W, H = size()

n = [0]
def shot(name):
    n[0] += 1
    try:
        png = subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True, timeout=60).stdout
        open(f"{OUT}/{n[0]:02d}_{name}.png", "wb").write(png)
    except Exception:
        pass

def nodes():
    sh("uiautomator dump /sdcard/ui.xml", timeout=60)
    x = adb("exec-out", "cat", "/sdcard/ui.xml")
    try:
        root = ET.fromstring(x)
    except Exception:
        return []
    out = []
    for e in root.iter("node"):
        b = [int(v) for v in re.findall(r"\d+", e.get("bounds", ""))]
        if len(b) == 4: out.append((e.get("text", ""), e.get("content-desc", ""), b))
    return out

def tap_xy(x, y): sh(f"input tap {x} {y}"); time.sleep(.8)
def tap(key):
    w = S.get(key, key)
    for t, d, b in nodes():
        if t.strip() == w or d.strip() == w:
            tap_xy((b[0] + b[2]) // 2, (b[1] + b[3]) // 2); return True
    print("  not found:", key, flush=True); return False

def fling_up(k=15, ms=70):
    for _ in range(k): sh(f"input swipe {W//2} {int(H*.8)} {W//2} {int(H*.25)} {ms}"); time.sleep(.35)
def fling_down(k=15, ms=70):
    for _ in range(k): sh(f"input swipe {W//2} {int(H*.25)} {W//2} {int(H*.8)} {ms}"); time.sleep(.35)

def pid(): return sh(f"pidof {PKG}").strip()

def frames(label):
    g = sh(f"dumpsys gfxinfo {PKG}")
    keys = ["Total frames rendered", "Janky frames", "50th percentile", "90th percentile", "95th percentile", "99th percentile",
            "Number Slow UI thread", "Number Slow bitmap uploads", "Number Slow issue draw commands", "Number Frame deadline missed"]
    vals = []
    for k in keys:
        m = re.search(re.escape(k) + r":\s*([^\n]+)", g)
        if m: vals.append(f"{k.replace('Number ', '').replace(' percentile', 'p')}={m.group(1).strip()}")
    p = pid()
    lc = adb("logcat", "-d", "-v", "brief")
    stalls = [int(x) for x in re.findall(r"Skipped (\d+) frames", lc)]
    davey = [int(x) for x in re.findall(r"Davey! duration=(\d+)ms", lc)]
    crash = "FATAL EXCEPTION" in lc or f"ANR in {PKG}" in lc
    print(f"[{label}] " + "; ".join(vals), flush=True)
    print(f"[{label}] main-thread stalls: {len(stalls)} (max {max(stalls) if stalls else 0} frames), Davey: {len(davey)} (max {max(davey) if davey else 0} ms), alive={bool(p)}{', CRASH/ANR' if crash else ''}", flush=True)
    if crash:
        for line in lc.splitlines():
            if "FATAL" in line or "AndroidRuntime" in line or "ANR" in line or "at com.galeria" in line: print("   ", line[:220])
    sh(f"dumpsys gfxinfo {PKG} reset"); adb("logcat", "-c")

def perf_logs():
    lc = adb("logcat", "-d", "-s", "PhotosPerf:I")
    for line in lc.splitlines():
        if "PhotosPerf" in line: print("   ", line.split("PhotosPerf")[-1].strip(": "), flush=True)

def mem(label):
    m = sh(f"dumpsys meminfo {PKG}")
    tot = re.search(r"TOTAL PSS:\s*(\d+)", m) or re.search(r"\n\s*TOTAL\s+(\d+)", m)
    java = re.search(r"Java Heap:\s*(\d+)", m); native = re.search(r"Native Heap:\s*(\d+)", m); gfx = re.search(r"Graphics:\s*(\d+)", m)
    f = lambda x: f"{int(x.group(1))//1024} MB" if x else "?"
    print(f"[{label}] memory total={f(tot)} java={f(java)} native={f(native)} graphics={f(gfx)}", flush=True)

def start(label):
    sh(f"am force-stop {PKG}"); time.sleep(1); adb("logcat", "-c")
    t0 = time.time()
    r = sh(f"am start -W -n {PKG}/.MainActivity", timeout=120)
    tt = re.search(r"TotalTime:\s*(\d+)", r)
    time.sleep(10)
    print(f"[{label}] launch TotalTime={tt.group(1) if tt else '?'} ms (wall {int((time.time()-t0)*1000)} ms incl. 10 s settle)", flush=True)
    perf_logs()

print("## Start-up", flush=True)
start("first open (no saved library)")
shot("library_first_open")
start("second open (saved library)")
shot("library_second_open")
mem("after start")

print("## Library (All Photos)", flush=True)
sh(f"dumpsys gfxinfo {PKG} reset"); adb("logcat", "-c")
fling_up(); shot("library_after_flings_up")
fling_down(); shot("library_after_flings_down")
frames("library flings")
# Slow, finger-like scrolling (most frames, most sensitive to small hitches)
for _ in range(10): sh(f"input swipe {W//2} {int(H*.7)} {W//2} {int(H*.4)} 600"); time.sleep(.2)
frames("library slow scroll")

print("## Fast scroller drag", flush=True)
fling_up(2)
x = W - 70 if True else 70
sh(f"input swipe {x} {int(H*.25)} {x} {int(H*.85)} 2500"); time.sleep(1); shot("after_fast_scroller")
sh(f"input swipe 70 {int(H*.25)} 70 {int(H*.85)} 2500"); time.sleep(1)
frames("fast scroller")

print("## Months and Years", flush=True)
fling_down(1)
if tap("months"):
    time.sleep(2); shot("months"); fling_up(10); fling_down(10); frames("months")
if tap("years"):
    time.sleep(2); shot("years"); fling_up(6); fling_down(6); frames("years")
tap("all")
time.sleep(1)

print("## Viewer", flush=True)
tap_xy(W // 2, H // 2); time.sleep(2); shot("viewer")
for _ in range(20): sh(f"input swipe {int(W*.85)} {H//2} {int(W*.15)} {H//2} 120"); time.sleep(.5)
shot("viewer_after_swipes")
frames("viewer swipes")
adb("shell", "input", "keyevent", "4"); time.sleep(1.5)

print("## Collections", flush=True)
t0 = time.time()
tap("tab_collections"); time.sleep(3); shot("collections")
fling_up(5); fling_down(5); frames("collections")
for key in ("utilities", "duplicates"):
    for _ in range(4):
        if tap(key): break
        fling_up(1)
    time.sleep(4)
shot("duplicates")
frames("open duplicates")
adb("shell", "input", "keyevent", "4"); time.sleep(1); adb("shell", "input", "keyevent", "4"); time.sleep(1)

print("## Search", flush=True)
if tap("search"):
    time.sleep(1.5)
    sh("input text 2024"); time.sleep(3); shot("search_2024")
    frames("search typing")
    adb("shell", "input", "keyevent", "4"); time.sleep(.5); adb("shell", "input", "keyevent", "4"); time.sleep(1)

mem("after tour")
print("## Background understanding (5 min idle, app open)", flush=True)
sh(f"am start -n {PKG}/.MainActivity"); time.sleep(300)
frames("idle with background work")
mem("after 5 min")
print("done", flush=True)
