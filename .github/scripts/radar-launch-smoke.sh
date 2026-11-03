#!/usr/bin/env bash
set -euo pipefail

WORKSPACE="$(pwd)"
APK_ROOT="$WORKSPACE/app/build/outputs/apk"
LOG_FILE="$WORKSPACE/radar-launch-logcat.txt"

echo "Emulator script working directory: $WORKSPACE"
echo "Searching APKs under: $APK_ROOT"
find "$APK_ROOT" -type f -name '*.apk' -print

APK="$(find "$APK_ROOT" -type f -name '*.apk' | grep -i 'universal' | head -n 1 || true)"
if [ -z "$APK" ]; then
  APK="$(find "$APK_ROOT" -type f -name '*.apk' | head -n 1 || true)"
fi
if [ -z "$APK" ]; then
  echo "No APK found. Workspace output tree:"
  find "$WORKSPACE/app/build/outputs" -maxdepth 6 -type f -print || true
  exit 1
fi

echo "Installing APK: $APK"
adb wait-for-device
adb shell 'while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 2; done'

READY=0
for attempt in $(seq 1 20); do
  if adb shell cmd package list packages >/dev/null 2>&1; then
    READY=1
    break
  fi
  sleep 3
done
if [ "$READY" -ne 1 ]; then
  echo "Android package manager did not become responsive."
  adb logcat -d -v threadtime > "$LOG_FILE" || true
  exit 1
fi

adb install -r "$APK"
adb logcat -c
adb shell am start -W -n com.RDR.vpn/com.aras.client.ui.main.MainActivity
sleep 20
adb logcat -d -v threadtime > "$LOG_FILE"

if adb shell pidof com.RDR.vpn >/dev/null 2>&1; then
  echo "RADAR main process is still running after launch."
else
  echo "RADAR process exited after launch. Recent fatal logs:"
  grep -A 50 -B 10 -E 'FATAL EXCEPTION|AndroidRuntime|Fatal signal' "$LOG_FILE" || true
  exit 1
fi
