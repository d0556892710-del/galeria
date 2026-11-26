#!/usr/bin/env bash
# Stress test: 20,000 photos on a virtual phone. Installs, loads the photos, then measures and tours.
set -u
APK=apk/app-release.apk
PKG=com.galeria.app
OUT=stress-result.txt
: > "$OUT"
log() { echo "$*" | tee -a "$OUT"; }
count() { adb shell content query --uri content://media/external/images/media --projection _id 2>/dev/null | grep -c "^Row" ; }

timeout 120 adb install -r "$APK" >/dev/null 2>&1
for p in READ_EXTERNAL_STORAGE ACCESS_MEDIA_LOCATION READ_MEDIA_IMAGES READ_MEDIA_VIDEO; do adb shell pm grant $PKG android.permission.$p >/dev/null 2>&1 || true; done
adb shell appops set $PKG MANAGE_MEDIA allow >/dev/null 2>&1 || true

t0=$(date +%s)
timeout 900 adb push Camera /sdcard/DCIM/ >/dev/null 2>&1
log "Pushed photos in $(( $(date +%s) - t0 )) s"
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/DCIM/Camera >/dev/null 2>&1
t0=$(date +%s); n=0
for i in $(seq 1 60); do n=$(count); [ "$n" -ge 19000 ] && break; sleep 5; done
if [ "$n" -lt 19000 ]; then
  log "Scanner slow ($n), rebooting the virtual phone to index"
  adb reboot; sleep 20; timeout 300 adb wait-for-device
  for i in $(seq 1 90); do [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ] && break; sleep 2; done
  for i in $(seq 1 120); do n=$(count); [ "$n" -ge 19000 ] && break; sleep 5; done
fi
log "Photos indexed: $n (after $(( $(date +%s) - t0 )) s)"
adb shell settings put system screen_off_timeout 1800000 >/dev/null 2>&1
adb shell input keyevent 82 >/dev/null 2>&1
timeout 2400 python3 .github/stress.py 2>&1 | tee -a "$OUT"
exit 0
