#!/bin/bash
# Builds dist/morphe-nomount-hook.apk. Runs in Git Bash on Windows and on Linux (CI).
#
# Needs a JDK 17 and the Android SDK (platform android-34, build-tools 34.0.0).
# Environment (all optional):
#   JAVA_HOME     JDK to use (default: ../tools/androidbuild/jdk-* if present, else whatever is on PATH)
#   ANDROID_SDK   SDK root (default: $ANDROID_HOME, $ANDROID_SDK_ROOT, or ../tools/androidbuild/sdk)
#   BUILD_TOOLS   build-tools version (default 34.0.0)
#   PLATFORM      platform (default android-34)
#   KEYSTORE      signing keystore (default debug.keystore; generated if missing)
#   KS_PASS       keystore password (default android)
set -euo pipefail
cd "$(dirname "$0")"

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) WIN=1; EXE=.exe; BAT=.bat; SEP=';' ;;
  *)                    WIN=0; EXE='';   BAT='';   SEP=':' ;;
esac

if [ -z "${JAVA_HOME:-}" ]; then
  for d in ../tools/androidbuild/jdk-*; do [ -d "$d" ] && JAVA_HOME="$PWD/$d"; done
fi
[ -n "${JAVA_HOME:-}" ] && export JAVA_HOME PATH="$JAVA_HOME/bin:$PATH"

SDK="${ANDROID_SDK:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-../tools/androidbuild/sdk}}}"
BT="$SDK/build-tools/${BUILD_TOOLS:-34.0.0}"
AJ="$SDK/platforms/${PLATFORM:-android-34}/android.jar"
[ -x "$BT/aapt2$EXE" ] || { echo "build-tools not found at $BT (set ANDROID_SDK)"; exit 1; }
[ -f "$AJ" ] || { echo "android.jar not found at $AJ"; exit 1; }
# javac/d8 are native Windows programs under Git Bash, so they need Windows-style paths
[ "$WIN" = 1 ] && AJ="$(cygpath -m "$AJ")"

KEYSTORE="${KEYSTORE:-debug.keystore}"
KS_PASS="${KS_PASS:-android}"

rm -rf out dist
mkdir -p out/classes out/stubs dist

# The real Xposed API is provided by LSPosed at runtime; these stubs only satisfy the compiler
# and are not packaged.
javac -source 8 -target 8 -Xlint:-options -d out/stubs $(find stubs -name '*.java')
javac -source 8 -target 8 -Xlint:-options -cp "$AJ$SEP""out/stubs" -d out/classes $(find src -name '*.java')
"$BT/d8$BAT" --min-api 26 --lib "$AJ" --output out out/classes/local/morphe/nomount/*.class

"$BT/aapt2$EXE" compile --dir res -o out/res.zip
"$BT/aapt2$EXE" link -o out/base.apk -I "$AJ" --manifest AndroidManifest.xml -A assets out/res.zip
jar uf out/base.apk -C out classes.dex
"$BT/zipalign$EXE" -f 4 out/base.apk out/aligned.apk

if [ ! -f "$KEYSTORE" ]; then
  echo "No $KEYSTORE found: generating one. Keep it, updates only install over builds signed with the same key."
  keytool -genkeypair -keystore "$KEYSTORE" -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -alias k -keyalg RSA -keysize 2048 -validity 36500 -dname "CN=local"
fi
"$BT/apksigner$BAT" sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --v4-signing-enabled false --out dist/morphe-nomount-hook.apk out/aligned.apk
echo "built dist/morphe-nomount-hook.apk"
