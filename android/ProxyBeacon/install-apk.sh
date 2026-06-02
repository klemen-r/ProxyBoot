#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
ADB="$SDK/platform-tools/adb"
APK="$ROOT/build/proxybeacon-debug.apk"

if [ ! -f "$APK" ]; then
  "$ROOT/build-apk.sh"
fi

"$ADB" install -r "$APK"
