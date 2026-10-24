#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
SDK="${ANDROID_SDK_ROOT:-$HOME/.local/share/android-sdk}"
JDK="${LUMA_JDK:-/usr/lib/jvm/java-17-openjdk}"
BT="$SDK/build-tools/36.0.0"
ANDROID_JAR="$SDK/platforms/android-34/android.jar"
export JAVA_HOME="$JDK"
export PATH="$JDK/bin:$SDK/platform-tools:$PATH"

mkdir -p build/classes build/dex build/generated dist
rm -rf build/resources.zip build/resources.apk build/unsigned.apk build/aligned.apk

"$BT/aapt2" compile --dir res -o build/resources.zip
"$BT/aapt2" link -o build/resources.apk -I "$ANDROID_JAR" --manifest AndroidManifest.xml --java build/generated build/resources.zip
find src build/generated -name '*.java' > build/sources.txt
javac -encoding UTF-8 --release 8 -classpath "$ANDROID_JAR" -d build/classes @build/sources.txt
find build/classes -name '*.class' > build/classes.txt
"$BT/d8" --lib "$ANDROID_JAR" --min-api 26 --output build/dex @build/classes.txt
cp build/resources.apk build/unsigned.apk
(cd build/dex && if command -v zip >/dev/null 2>&1; then zip -q -u ../unsigned.apk classes.dex; else jar uf ../unsigned.apk classes.dex; fi)
"$BT/zipalign" -f -p 4 build/unsigned.apk build/aligned.apk

KEYSTORE=".signing/local.keystore"
if [ ! -f "$KEYSTORE" ]; then
    mkdir -p .signing
    keytool -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android -alias bravetv-local -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Brave TV, O=Billy Build'
fi
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android --ks-key-alias bravetv-local --out dist/brave-tv.apk build/aligned.apk
"$BT/apksigner" verify dist/brave-tv.apk
ls -lh dist/brave-tv.apk
