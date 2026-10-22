#!/bin/bash
# Build Cute Reads with plain Android SDK tools (no Gradle).
#   ./build.sh            debug-signed CuteReads.apk
#   ./build.sh install    ...and install it over ADB
#   ./build.sh release    CuteReads-<version>.apk signed with the release key (see README)
set -euo pipefail
cd "$(dirname "$0")"

VERSION_CODE=1
VERSION_NAME=1.0

SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
BT=$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)
JAR=$(ls -d "$SDK"/platforms/android-* | sort -V | tail -1)/android.jar
B=build

rm -rf $B && mkdir -p $B/classes $B/gen $B/assets/fonts
cp fonts/*.ttf $B/assets/fonts/ && cp -r fonts/licenses $B/assets/fonts/

"$BT"/aapt2 compile --dir app/res -o $B/res.zip
"$BT"/aapt2 link -o $B/base.apk -I "$JAR" --manifest app/AndroidManifest.xml $B/res.zip \
  -A $B/assets --java $B/gen --min-sdk-version 26 --target-sdk-version 30 \
  --version-code $VERSION_CODE --version-name $VERSION_NAME
if ! javac -nowarn -Xlint:-options -source 8 -target 8 -classpath "$JAR" -d $B/classes \
    $(find app/src $B/gen -name '*.java') 2> $B/javac.log; then
  cat $B/javac.log; exit 1
fi
"$BT"/d8 --lib "$JAR" --min-api 26 --output $B $(find $B/classes -name '*.class')
(cd $B && zip -q base.apk classes.dex)
"$BT"/zipalign -f 4 $B/base.apk $B/aligned.apk

if [ "${1:-}" = release ]; then
  KS=${READ_KEYSTORE:-$HOME/.config/cute-apps/release.jks}
  PASS_FILE=${READ_KEYSTORE_PASS_FILE:-$HOME/.config/cute-apps/release.pass}
  OUT=CuteReads-$VERSION_NAME.apk
  "$BT"/apksigner sign --ks "$KS" --ks-pass file:"$PASS_FILE" --out $OUT $B/aligned.apk
else
  KS=$HOME/.android/debug.keystore
  [ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
  OUT=CuteReads.apk
  "$BT"/apksigner sign --ks "$KS" --ks-pass pass:android --out $OUT $B/aligned.apk
fi
rm -f $OUT.idsig
echo "built $(pwd)/$OUT"

if [ "${1:-}" = install ]; then
  adb install -r $OUT
  # Lets the app turn on Do Not Disturb without the permission screen (ColorOS may refuse; harmless).
  adb shell cmd notification allow_dnd com.vaib215.cutereads || true
fi
