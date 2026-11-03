#!/usr/bin/env bash
set -euo pipefail

WORKSPACE="$(pwd)"
APK_ROOT="$WORKSPACE/app/build/outputs/apk"
LOG_FILE="$WORKSPACE/radar-launch-logcat.txt"

echo "Emulator script working directory: $WORKSPACE"
echo "Searching APKs under: $APK_ROOT"
find "$APK_ROOT" -type f -name '*.apk' -print

APK="$(find "$APK_ROOT" -type f -name '*universal*.apk' | head -n 1 || true)"
if [ -z "$APK" ]; then
  APK="$(find "$APK_ROOT" -type f -name '*.apk' | head -n 1 || true)"
fi
if [ -z "$APK" ]; then
  echo "No APK found."
  find "$WORKSPACE/app/build/outputs" -maxdepth 6 -type f -print || true
  exit 1
fi

echo "Installing APK: $APK"
adb wait-for-device
adb shell 'while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 2; done'
adb install -r "$APK"
adb logcat -c

# The debug-only launcher alias targets the real, non-exported TelegramActivity.
# Production manifests do not contain this test entry point.
echo "Launching Telegram through the debug-only test alias."
adb shell am start -W -n com.RDR.vpn/com.aras.client.ui.telegram.TelegramSmokeLauncherAlias || true
sleep 8
adb logcat -d -v threadtime > "$LOG_FILE"

if grep -E -q 'FATAL EXCEPTION|Fatal signal|Process: com.RDR.vpn' "$LOG_FILE"; then
  echo "A fatal Android crash marker was found:"
  grep -A 50 -B 10 -E 'FATAL EXCEPTION|Fatal signal|Process: com.RDR.vpn' "$LOG_FILE" || true
  exit 1
fi

if ! adb shell pidof com.RDR.vpn >/dev/null 2>&1; then
  echo "RADAR process exited after launch."
  grep -A 50 -B 10 -E 'FATAL EXCEPTION|AndroidRuntime|Fatal signal' "$LOG_FILE" || true
  exit 1
fi

ACTIVITY_DUMP="$(adb shell dumpsys activity activities | tr -d '\r')"
if ! printf '%s' "$ACTIVITY_DUMP" | grep -Fq 'com.aras.client.ui.telegram.TelegramActivity'; then
  echo "TelegramActivity was not found in the activity stack."
  printf '%s\n' "$ACTIVITY_DUMP" | grep -E 'mResumedActivity|topResumedActivity|Resumed' || true
  exit 1
fi

echo "RADAR process is alive and TelegramActivity is present in the activity stack."
