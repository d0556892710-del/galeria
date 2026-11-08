#!/usr/bin/env bash
# Smoke test on a virtual phone: install, update over itself, open, tap around, look for crashes.
set -u
APK=apk/app-release.apk
PKG=com.galeria.app
OUT=smoke-result.txt
: > "$OUT"
log() { echo "$*" | tee -a "$OUT"; }

log "## Install test"
adb shell mkdir -p /sdcard/DCIM/Camera
for f in testphotos/*.jpg; do adb push "$f" /sdcard/DCIM/Camera/ >/dev/null 2>&1; adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file:///sdcard/DCIM/Camera/$(basename "$f")" >/dev/null 2>&1; done
sleep 5
adb shell content call --uri content://media --method scan_volume --arg external_primary >/dev/null 2>&1 || true
adb shell am broadcast -a android.intent.action.MEDIA_MOUNTED -d file:///sdcard >/dev/null 2>&1 || true

R1=$(adb install "$APK" 2>&1 | tail -1); log "First install: $R1"
R2=$(adb install -r "$APK" 2>&1 | tail -1); log "Install again over it, as an update: $R2"
log "Installed: $(adb shell dumpsys package $PKG | grep -m1 versionName | tr -d ' ') $(adb shell dumpsys package $PKG | grep -m1 -o 'versionCode=[0-9]*')"

for p in READ_EXTERNAL_STORAGE ACCESS_MEDIA_LOCATION READ_MEDIA_IMAGES READ_MEDIA_VIDEO; do
  adb shell pm grant $PKG android.permission.$p >/dev/null 2>&1 || true
done
adb shell appops set $PKG MANAGE_MEDIA allow >/dev/null 2>&1 || true

adb logcat -c
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 20
if adb shell pidof $PKG >/dev/null; then log "Opened and still running after 20 s: yes"; else log "Opened and still running after 20 s: NO"; fi

# Screens tour in English, then the whole phone in Hebrew (right-to-left) and the tour again.
python3 .github/qa_tour.py en > tour_en.txt 2>&1 || true
adb root >/dev/null 2>&1; sleep 3; adb wait-for-device
adb shell setprop persist.sys.locale he-IL
adb shell stop; sleep 2; adb shell start
adb wait-for-device
for i in $(seq 1 90); do [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ] && break; sleep 2; done
sleep 15
adb shell input keyevent 82 >/dev/null 2>&1 || true
python3 .github/qa_tour.py he > tour_he.txt 2>&1 || true
grep -h "not found" tour_en.txt tour_he.txt | sed 's/^/tour: /' | tee -a "$OUT" || true
adb logcat -c

log ""
log "## Tapping around in Hebrew (monkey)"
adb shell monkey -p $PKG --throttle 300 --pct-syskeys 0 --pct-appswitch 0 --pct-anyevent 0 -s 42 -v 600 > monkey.txt 2>&1 || true
tail -3 monkey.txt | tee -a "$OUT"
sleep 5

CRASH=$(adb logcat -d -b crash,main | grep -E "FATAL EXCEPTION|Process: $PKG|Fatal signal|ANR in $PKG" | head -5)
if [ -n "$CRASH" ]; then
  log ""
  log "## Crash"
  adb logcat -d -b crash,main | grep -E "FATAL EXCEPTION|ANR in $PKG|Fatal signal" -A40 | head -120 | tee -a "$OUT"
fi
adb shell run-as $PKG cat files/last_crash.txt 2>/dev/null | head -60 | tee -a "$OUT" || true

if echo "$R1" | grep -q Success && echo "$R2" | grep -q Success && [ -z "$CRASH" ]; then
  log ""; log "RESULT: PASS"
else
  log ""; log "RESULT: FAIL"
fi
exit 0
