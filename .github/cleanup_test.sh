#!/usr/bin/env bash
set -u
PKG=com.galeria.app
timeout 300 adb install -r apk/app-release.apk 2>&1 | tail -1
for p in READ_EXTERNAL_STORAGE ACCESS_MEDIA_LOCATION READ_MEDIA_IMAGES; do adb shell pm grant $PKG android.permission.$p >/dev/null 2>&1 || true; done
adb shell appops set $PKG MANAGE_MEDIA allow >/dev/null 2>&1 || true
adb shell mkdir -p /sdcard/DCIM/Camera
adb push coffee.jpg /sdcard/DCIM/Camera/coffee.jpg >/dev/null
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/DCIM/Camera/coffee.jpg >/dev/null
sleep 5
timeout 900 python3 .github/cleanup_test.py 2>&1 | tee cleanup-result.txt
exit 0
