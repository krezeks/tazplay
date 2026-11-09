#!/usr/bin/env bash
# Build TazPlay.apk tanpa Gradle (aapt2 + javac + d8 + NDK clang + apksigner),
# karena unduhan plugin Gradle tidak tembus proxy lingkungan ini.
# Core PS1 (PCSX-ReARMed libretro) dibangun terpisah dari source-nya
# (~/workspace/study/pcsx_rearmed, ndk-build) lalu disalin ke app/src/main/.
set -e
source "$HOME/tools/env.sh"
BT=$ANDROID_HOME/build-tools/34.0.0
AJ=$ANDROID_HOME/platforms/android-34/android.jar
NDKBIN=$HOME/tools/ndk/toolchains/llvm/prebuilt/linux-x86_64/bin
P=$(cd "$(dirname "$0")" && pwd)
B=$P/build-manual
rm -rf "$B"; mkdir -p "$B/gen" "$B/classes" "$B/dex" "$B/lib/arm64-v8a"
cd "$P/app/src/main"

# 1) frontend native (jembatan libretro)
"$NDKBIN/aarch64-linux-android26-clang" -shared -fPIC -O2 \
  -I"$HOME/workspace/study/pcsx_rearmed/deps/libretro-common/include" \
  -o "$B/lib/arm64-v8a/libtazcore.so" cpp/tazcore.c \
  -ldl -landroid -laaudio -llog
cp libpcsx_rearmed_libretro.so "$B/lib/arm64-v8a/"

# 2) resource + manifest
$BT/aapt2 compile --dir res -o "$B/compiled.zip"
$BT/aapt2 link -o "$B/unsigned.apk" -I "$AJ" --manifest AndroidManifest.xml \
  -R "$B/compiled.zip" --java "$B/gen" --auto-add-overlay \
  --min-sdk-version 26 --target-sdk-version 34 --version-code 6 --version-name 0.4.2

# 3) kode Java -> dex
RJ=$(find "$B/gen" -name 'R.java')
$JAVA_HOME/bin/javac -encoding UTF-8 --release 11 -cp "$AJ" -d "$B/classes" \
  "$RJ" java/id/taz/tazplay/*.java
$JAVA_HOME/bin/jar cf "$B/classes.jar" -C "$B/classes" .
$BT/d8 --lib "$AJ" --min-api 26 --output "$B/dex" "$B/classes.jar"

# 4) gabung dex + lib native, align, sign
cp "$B/unsigned.apk" "$B/withdex.apk"
(cd "$B/dex" && zip -q ../withdex.apk classes.dex)
(cd "$B" && zip -q -0 -r withdex.apk lib)
$BT/zipalign -p -f 4 "$B/withdex.apk" "$B/aligned.apk"
KEYSTORE=$HOME/tools/debug.keystore
[ -f "$KEYSTORE" ] || $JAVA_HOME/bin/keytool -genkeypair -keystore "$KEYSTORE" \
  -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass android -keypass android -dname 'CN=Android Debug,O=Android,C=US'
$BT/apksigner sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
  --out "$B/TazPlay.apk" "$B/aligned.apk"
$BT/apksigner verify "$B/TazPlay.apk"
echo "OK -> $B/TazPlay.apk"
