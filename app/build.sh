#!/usr/bin/env bash
# Build the O3Root Android app (APK).
#
# Required environment:
#   ANDROID_JAR    platform-34 android.jar
#   BUILD_TOOLS    build-tools dir (aapt2[.exe], zipalign[.exe], lib/d8.jar, lib/apksigner.jar)
#   SHIZUKU_JARS   dir with api-/provider-/aidl-/shared-classes.jar
#   KEYSTORE / KS_PASS / KEY_ALIAS / KEY_PASS   signing material (your own!)
# Optional:
#   JAVA_HOME      JDK 17+ home (default: use javac/java from PATH)
#   D8_JAR         override path to d8.jar
#   PAYLOAD        path to libo3p.so (default ../payload/build/libo3p.so)
#   PY             python interpreter (default python3)
#   BETA=1         build the test variant: hidden root console, version 3.2.1-console
set -euo pipefail
cd "$(dirname "$0")"

: "${ANDROID_JAR:?set ANDROID_JAR}"      ; : "${BUILD_TOOLS:?set BUILD_TOOLS}"
: "${SHIZUKU_JARS:?set SHIZUKU_JARS}"    ; : "${KEYSTORE:?set KEYSTORE}"
: "${KS_PASS:?set KS_PASS}"              ; : "${KEY_ALIAS:?set KEY_ALIAS}"
: "${KEY_PASS:?set KEY_PASS}"
PY=${PY:-python3}
BETA=${BETA:-0}
PAYLOAD=${PAYLOAD:-$PWD/../payload/build/libo3p.so}
D8_JAR=${D8_JAR:-$BUILD_TOOLS/lib/d8.jar}
JBIN=${JAVA_HOME:+$JAVA_HOME/bin/}
JAVAC=${JBIN}javac
JAVA=${JBIN}java

pick() { for c in "$1" "$1.exe"; do [ -x "$c" ] && { echo "$c"; return 0; }; done; echo "missing: $1" >&2; return 1; }
AAPT2=$(pick "$BUILD_TOOLS/aapt2")
ZIPALIGN=$(pick "$BUILD_TOOLS/zipalign")
[ -f "$PAYLOAD" ] || { echo "payload not found: $PAYLOAD (build it first: payload/build-payload.sh)" >&2; exit 2; }
[ -f "$D8_JAR" ] || { echo "d8.jar not found: $D8_JAR" >&2; exit 2; }

rm -rf build
mkdir -p build/cp build/classes build/dex build/lib/arm64-v8a build/gen
cp "$ANDROID_JAR" build/cp/android.jar
for a in api provider aidl shared; do cp "$SHIZUKU_JARS/$a-classes.jar" "build/cp/$a.jar"; done
cp "$PAYLOAD" build/lib/arm64-v8a/libo3p.so

SRCS=(src/o3/st/root1/*.java)
if [ "$BETA" = "1" ]; then
  echo "variant: beta (root console, 3.2.1-console)"
  cat > build/gen/BuildInfo.java <<'JAVA'
package o3.st.root1;

final class BuildInfo {
    static final boolean CONSOLE = true;
    static final String VERSION = "3.2.1-console";
}
JAVA
  KEEP=()
  for f in "${SRCS[@]}"; do
    [ "$f" = "src/o3/st/root1/BuildInfo.java" ] && continue
    KEEP+=("$f")
  done
  SRCS=("${KEEP[@]}" "build/gen/BuildInfo.java")
  MANIFEST="$PWD/build/AndroidManifest.xml"
  "$PY" - "$MANIFEST" <<'PY'
import sys
src = open('AndroidManifest.xml', encoding='utf-8').read()
out = src.replace('android:versionCode="17"', 'android:versionCode="18"')
out = out.replace('android:versionName="3.2"', 'android:versionName="3.2.1-console"')
act = ('        <activity\n'
       '            android:name=".ConsoleActivity"\n'
       '            android:exported="false"\n'
       '            android:configChanges="orientation|screenSize|keyboardHidden" />\n\n')
out = out.replace('</application>', act + '</application>')
open(sys.argv[1], 'w', encoding='utf-8').write(out)
PY
else
  echo "variant: stable (no console, 3.2)"
  KEEP=()
  for f in "${SRCS[@]}"; do
    case "$f" in
      src/o3/st/root1/ConsoleActivity.java|src/o3/st/root1/ConsoleGate.java) continue;;
    esac
    KEEP+=("$f")
  done
  SRCS=("${KEEP[@]}")
  MANIFEST="$PWD/AndroidManifest.xml"
fi

"$JAVAC" -J-Duser.language=en --release 17 -classpath "build/cp/*" -d build/classes "${SRCS[@]}"

"$JAVA" -cp "$D8_JAR" com.android.tools.r8.D8 \
  --release --min-api 31 --lib build/cp/android.jar --output build/dex \
  build/classes/o3/st/root1/*.class build/cp/api.jar build/cp/provider.jar build/cp/aidl.jar build/cp/shared.jar

"$AAPT2" compile --dir res -o build/res.zip
"$AAPT2" link --manifest "$MANIFEST" -I build/cp/android.jar \
  --min-sdk-version 31 --target-sdk-version 34 -o build/base.apk -A assets build/res.zip

BASE="$PWD/build" "$PY" - <<'PY'
import zipfile, os
base = os.environ['BASE']
z = zipfile.ZipFile(os.path.join(base, 'base.apk'))
with zipfile.ZipFile(os.path.join(base, 'unsigned.apk'), 'w', zipfile.ZIP_DEFLATED) as w:
    for it in z.infolist():
        w.writestr(it, z.read(it.filename))
    w.write(os.path.join(base, 'dex', 'classes.dex'), 'classes.dex')
    w.write(os.path.join(base, 'lib', 'arm64-v8a', 'libo3p.so'), 'lib/arm64-v8a/libo3p.so')
print('staged', os.path.getsize(os.path.join(base, 'unsigned.apk')))
PY

"$ZIPALIGN" -f -p 4 build/unsigned.apk build/aligned.apk
"$JAVA" -jar "$BUILD_TOOLS/lib/apksigner.jar" sign \
  --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" --ks-key-alias "$KEY_ALIAS" \
  --key-pass "pass:$KEY_PASS" --out o3root2.apk build/aligned.apk

ls -l o3root2.apk
"$PY" -c "import hashlib;print('sha256',hashlib.sha256(open('o3root2.apk','rb').read()).hexdigest())"
