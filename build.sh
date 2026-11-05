#!/usr/bin/env bash
# Builds build/NowPlaying.apk with the plain Android SDK tools (no Gradle).
# Settings come from the environment or from an optional, untracked ./local.env:
#   ANDROID_SDK   Android SDK root (needs build-tools/35.0.0 and platforms/android-30)
#   KEYSTORE      signing keystore (default: ~/.android/debug.keystore)
#   KS_PASS, KS_ALIAS
set -euo pipefail
cd "$(dirname "$0")"
[ -f local.env ] && . ./local.env

VERSION_CODE=13
VERSION_NAME=1.0.0

SDK=${ANDROID_SDK:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}}
[ -n "$SDK" ] || { echo "Set ANDROID_SDK to your Android SDK folder (or put it in local.env)" >&2; exit 1; }
BT=$SDK/build-tools/35.0.0
JAR=$SDK/platforms/android-30/android.jar
KEYSTORE=${KEYSTORE:-$HOME/.android/debug.keystore}
KS_PASS=${KS_PASS:-android}
KS_ALIAS=${KS_ALIAS:-androiddebugkey}
[ -f "$KEYSTORE" ] || { echo "Keystore not found: $KEYSTORE (set KEYSTORE)" >&2; exit 1; }

tool() {  # SDK tools are .bat/.exe on Windows, plain scripts elsewhere
  for ext in "" .exe .bat; do [ -e "$BT/$1$ext" ] && { echo "$BT/$1$ext"; return; }; done
  echo "Missing build tool: $1" >&2; exit 1
}
PY=$(command -v python3 || command -v python)

rm -rf build
mkdir -p build/gen build/obj build/dex build/apk

"$(tool aapt2)" compile --dir res -o build/res.zip
"$(tool aapt2)" link -I "$JAR" --manifest AndroidManifest.xml --java build/gen \
  --min-sdk-version 26 --target-sdk-version 30 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  -o build/apk/unsigned.apk build/res.zip

javac -nowarn --release 8 -classpath "$JAR" -encoding UTF-8 -d build/obj \
  $(find src build/gen -name '*.java')

"$(tool d8)" --min-api 26 --lib "$JAR" --output build/dex $(find build/obj -name '*.class')

"$PY" -c "import zipfile,sys; z=zipfile.ZipFile(sys.argv[1],'a'); z.write(sys.argv[2],'classes.dex'); z.close()" \
  build/apk/unsigned.apk build/dex/classes.dex

"$(tool zipalign)" -f 4 build/apk/unsigned.apk build/apk/aligned.apk
"$(tool apksigner)" sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" --ks-key-alias "$KS_ALIAS" \
  --out build/NowPlaying.apk build/apk/aligned.apk
echo "built: $(pwd)/build/NowPlaying.apk ($VERSION_NAME)"
