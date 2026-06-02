#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
PLATFORM="${ANDROID_PLATFORM:-android-35}"
BUILD_TOOLS="${ANDROID_BUILD_TOOLS:-$(ls "$SDK/build-tools" | tail -n 1)}"

AAPT2="$SDK/build-tools/$BUILD_TOOLS/aapt2"
D8="$SDK/build-tools/$BUILD_TOOLS/d8"
ZIPALIGN="$SDK/build-tools/$BUILD_TOOLS/zipalign"
APKSIGNER="$SDK/build-tools/$BUILD_TOOLS/apksigner"
ANDROID_JAR="$SDK/platforms/$PLATFORM/android.jar"

OUT="$ROOT/build"
GEN="$OUT/gen"
OBJ="$OUT/obj"
DEX="$OUT/dex"
UNSIGNED="$OUT/proxybeacon-unsigned.apk"
ALIGNED="$OUT/proxybeacon-aligned.apk"
APK="$OUT/proxybeacon-debug.apk"
KEYSTORE="$OUT/debug.keystore"

if [ ! -f "$ANDROID_JAR" ]; then
  echo "Missing $ANDROID_JAR"
  exit 1
fi

rm -rf "$OUT"
mkdir -p "$GEN" "$OBJ" "$DEX"

"$AAPT2" compile --dir "$ROOT/res" -o "$OUT/resources.zip"
"$AAPT2" link \
  -I "$ANDROID_JAR" \
  --manifest "$ROOT/AndroidManifest.xml" \
  --java "$GEN" \
  --min-sdk-version 26 \
  --target-sdk-version 35 \
  --version-code 1 \
  --version-name 1.0 \
  -o "$UNSIGNED" \
  "$OUT/resources.zip"

find "$ROOT/src/main/java" "$GEN" -name '*.java' > "$OUT/java-files.txt"
javac --release 17 -classpath "$ANDROID_JAR" -d "$OBJ" @"$OUT/java-files.txt"

find "$OBJ" -name '*.class' > "$OUT/classes.txt"
"$D8" --min-api 26 --lib "$ANDROID_JAR" --output "$DEX" @"$OUT/classes.txt"

zip -q -j -u "$UNSIGNED" "$DEX/classes.dex"
"$ZIPALIGN" -p -f 4 "$UNSIGNED" "$ALIGNED"

keytool -genkeypair \
  -keystore "$KEYSTORE" \
  -storepass android \
  -keypass android \
  -alias androiddebugkey \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -dname "CN=Android Debug,O=Android,C=US" \
  >/dev/null

"$APKSIGNER" sign \
  --ks "$KEYSTORE" \
  --ks-pass pass:android \
  --key-pass pass:android \
  --out "$APK" \
  "$ALIGNED"

"$APKSIGNER" verify "$APK"
echo "$APK"
