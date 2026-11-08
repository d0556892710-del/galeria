#!/usr/bin/env python3
"""Walks through the main screens on the virtual phone and saves a screenshot of each (for review)."""
import os, re, subprocess, sys, time, xml.etree.ElementTree as ET

LANG = sys.argv[1] if len(sys.argv) > 1 else "en"
OUT = "shots"
PKG = "com.galeria.app"
os.makedirs(OUT, exist_ok=True)

def strings(path):
    d = {}
    try:
        for m in re.finditer(r'<string name="([^"]+)"[^>]*>(.*?)</string>', open(path, encoding="utf-8").read(), re.S):
            d[m.group(1)] = m.group(2).replace("\\'", "'").replace("&amp;", "&")
    except Exception:
        pass
    return d

EN = strings("app/src/main/res/values/strings.xml")
HE = strings("app/src/main/res/values-iw/strings.xml")
S = HE if LANG == "he" else EN

def adb(*a, capture=False):
    r = subprocess.run(["adb", *a], capture_output=True)
    return r.stdout if capture else None

n = [0]
def shot(name):
    n[0] += 1
    time.sleep(1.2)
    png = adb("exec-out", "screencap", "-p", capture=True)
    with open(f"{OUT}/{LANG}_{n[0]:02d}_{name}.png", "wb") as f:
        f.write(png)
    print("shot", name, flush=True)

def nodes():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    x = adb("exec-out", "cat", "/sdcard/ui.xml", capture=True)
    try:
        root = ET.fromstring(x)
    except Exception:
        return []
    out = []
    for e in root.iter("node"):
        b = re.findall(r"\d+", e.get("bounds", ""))
        if len(b) == 4:
            out.append((e.get("text", ""), e.get("content-desc", ""), [int(v) for v in b]))
    return out

def tap_xy(x, y):
    adb("shell", "input", "tap", str(x), str(y)); time.sleep(1.0)

def tap(*keys, literal=None):
    want = [S.get(k, "") for k in keys if S.get(k)] + ([literal] if literal else [])
    for t, d, b in nodes():
        for w in want:
            if w and (t.strip() == w or d.strip() == w):
                tap_xy((b[0] + b[2]) // 2, (b[1] + b[3]) // 2)
                return True
    print("not found:", keys, literal, flush=True)
    return False

def back():
    adb("shell", "input", "keyevent", "4"); time.sleep(1.0)

def size():
    m = re.search(rb"(\d+)x(\d+)", adb("shell", "wm", "size", capture=True) or b"")
    return (int(m.group(1)), int(m.group(2))) if m else (1080, 2400)

W, H = size()
def swipe_up(): adb("shell", "input", "swipe", str(W // 2), str(H * 3 // 4), str(W // 2), str(H // 4), "250"); time.sleep(1.0)
def swipe_down(): adb("shell", "input", "swipe", str(W // 2), str(H // 3), str(W // 2), str(H * 3 // 4), "250"); time.sleep(1.0)

adb("shell", "am", "force-stop", PKG)
adb("shell", "monkey", "-p", PKG, "-c", "android.intent.category.LAUNCHER", "1")
time.sleep(7)
shot("library_all")
tap("months"); shot("library_months")
tap("years"); shot("library_years")
tap("all_photos", "all"); shot("library_all_again")
swipe_down(); shot("library_scrolled_up")
swipe_up(); shot("library_scrolled_down")
# Open a photo from the middle of the grid.
tap_xy(W // 2, H // 2); time.sleep(1.5); shot("viewer")
if tap("more"): shot("viewer_menu")
if tap("use_as_wallpaper"): shot("viewer_wallpaper_menu")
back(); back()
if tap("edit"): time.sleep(2); shot("editor"); back(); time.sleep(1)
if tap("info"): shot("viewer_info"); back()
back(); time.sleep(1)
tap("tab_collections"); time.sleep(2); shot("collections")
swipe_up(); shot("collections_2")
swipe_up(); shot("collections_3")
swipe_up(); shot("collections_4")
tap("memories"); time.sleep(1.5); shot("memories_page"); back()
tap("media_types"); time.sleep(1.5); shot("media_types_page"); back()
tap("search"); time.sleep(1.5); shot("search")
back()
tap("settings"); time.sleep(1.5); shot("settings")
swipe_up(); shot("settings_2")
print("done", flush=True)
