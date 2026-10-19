#!/usr/bin/env bash
# Builds bare.apk with only the Android SDK command-line tools (no Gradle).
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
BT="$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)"
JAR="$(ls -d "$SDK"/platforms/android-* | sort -V | tail -1)/android.jar"
OUT=build
rm -rf "$OUT"; mkdir -p "$OUT/classes"

"$BT/aapt2" link --manifest AndroidManifest.xml -I "$JAR" \
    --min-sdk-version 21 --target-sdk-version 28 -o "$OUT/base.apk"

javac --release 8 -Xlint:-options -cp "$JAR" -d "$OUT/classes" src/dev/bare/launcher/*.java
"$BT/d8" --release --min-api 21 --lib "$JAR" --output "$OUT" $(find "$OUT/classes" -name '*.class')

( cd "$OUT" && "$BT/aapt" add -k base.apk classes.dex >/dev/null )
"$BT/zipalign" -f 4 "$OUT/base.apk" "$OUT/aligned.apk"

KS=debug.keystore
[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
    -alias bare -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Bare" >/dev/null 2>&1
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --out bare.apk "$OUT/aligned.apk"

"$BT/apksigner" verify bare.apk && ls -l bare.apk
