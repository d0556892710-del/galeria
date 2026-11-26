#!/usr/bin/env bash
set -u
PKG=com.galeria.app
# The virtual phone sometimes needs a moment after boot before it answers.
for i in 1 2 3 4 5 6; do adb wait-for-device; adb shell true >/dev/null 2>&1 && break; sleep 5; done
# The virtual phone's own apps can be slow; their "not responding" dialogs would cover the screen.
adb shell settings put global hide_error_dialogs 1 >/dev/null 2>&1 || true
timeout 300 adb install -r apk/app-release.apk 2>&1 | tail -1
for p in READ_EXTERNAL_STORAGE ACCESS_MEDIA_LOCATION READ_MEDIA_IMAGES READ_MEDIA_VIDEO POST_NOTIFICATIONS; do adb shell pm grant $PKG android.permission.$p >/dev/null 2>&1 || true; done
adb shell appops set $PKG MANAGE_MEDIA allow >/dev/null 2>&1 || true
adb shell mkdir -p /sdcard/DCIM/Camera
for f in astronaut.jpg coffee.jpg brick.jpg hebrew.jpg; do
  adb push $f /sdcard/DCIM/Camera/$f >/dev/null
  adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/DCIM/Camera/$f >/dev/null
done
sleep 6
timeout 1200 python3 .github/spatial_test.py 2>&1 | tee spatial-result.txt
exit 0
