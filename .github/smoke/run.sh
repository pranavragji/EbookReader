#!/usr/bin/env bash
# Emulator smoke test: install, seed sample books, open every reader and tab, fail on any crash.
set -u
PKG=io.github.pranavragji.folio
DIR=/sdcard/Android/data/$PKG/files/books
mkdir -p shots

shot() { adb exec-out screencap -p > "shots/$1.png"; }
launch() { adb shell am force-stop $PKG; adb shell am start -W -n $PKG/.MainActivity >/dev/null; sleep "${1:-8}"; }
# Tap the centre of the first node whose text/content-desc contains $1 (case-insensitive).
tap_text() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb pull /sdcard/ui.xml ui.xml >/dev/null 2>&1
  local xy
  xy=$(python3 - "$1" <<'PY'
import re, sys
s = open("ui.xml", encoding="utf-8").read()
for node in re.findall(r"<node [^>]*>", s):
    t = " ".join(re.findall(r'(?:text|content-desc)="([^"]*)"', node))
    if sys.argv[1].lower() in t.lower():
        a, b, c, d = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node).groups())
        if c > a and d > b:
            print((a + c) // 2, (b + d) // 2)
            break
PY
)
  if [ -n "$xy" ]; then adb shell input tap $xy; echo "tapped '$1' at $xy"; return 0; fi
  return 1
}
# Find-and-tap, scrolling down up to 3 times.
find_tap() {
  for _ in 1 2 3 4; do tap_text "$1" && return 0; adb shell input swipe 540 1700 540 700 300; sleep 1; done
  echo "NOT FOUND: '$1'"
}

adb install -r folio.apk
adb logcat -c
launch 6
shot 00-first-run
# Android 11+ blocks adb from the app's external dir; google_apis images allow adb root, so write it directly.
adb root >/dev/null; sleep 3; adb wait-for-device
REAL=/data/media/0/Android/data/$PKG/files
adb shell mkdir -p $REAL/books
adb push samples/. $REAL/books/
OWNER=$(adb shell stat -c %u:%g $REAL | tr -d '')
adb shell chown -R $OWNER $REAL/books
adb shell ls -la $REAL/books
launch 12
shot 01-library

for title in "smoke novel" "smoke manga" "smoke doc" "smoke notes"; do
  slug=${title// /-}
  find_tap "$title"; sleep 8; shot "10-$slug"
  adb shell input tap 540 1200; sleep 2; shot "11-$slug-chrome"
  adb shell input keyevent KEYCODE_BACK; sleep 1
  launch 8
done
shot 02-library-after-reading

tap_text "Browse"; sleep 3; shot 20-browse
tap_text "MangaDex"; sleep 10; shot 21-mangadex
launch 6
tap_text "Sync"; sleep 3; shot 30-sync
tap_text "Settings"; sleep 3; shot 40-settings
adb shell input swipe 540 1800 540 500 300; sleep 1; shot 41-settings-scrolled

adb logcat -d > logcat.txt
if grep -E "FATAL EXCEPTION|ANR in $PKG" logcat.txt; then
  grep -A 30 "FATAL EXCEPTION" logcat.txt | head -80
  exit 1
fi
echo "smoke ok"
