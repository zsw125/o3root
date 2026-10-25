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
set -euo pipefail
cd "$(dirname "$0")"

: "${ANDROID_JAR:?set ANDROID_JAR}"      ; : "${BUILD_TOOLS:?set BUILD_TOOLS}"
: "${SHIZUKU_JARS:?set SHIZUKU_JARS}"    ; : "${KEYSTORE:?set KEYSTORE}"
: "${KS_PASS:?set KS_PASS}"              ; : "${KEY_ALIAS:?set KEY_ALIAS}"
: "${KEY_PASS:?set KEY_PASS}"
PY=${PY:-python3}
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
mkdir -p build/cp build/classes build/dex build/lib/arm64-v8a
cp "$ANDROID_JAR" build/cp/android.jar
for a in api provider aidl shared; do cp "$SHIZUKU_JARS/$a-classes.jar" "build/cp/$a.jar"; done
cp "$PAYLOAD" build/lib/arm64-v8a/libo3p.so

"$JAVAC" -J-Duser.language=en --release 17 -classpath "build/cp/*" -d build/classes src/o3/st/root1/*.java

"$JAVA" -cp "$D8_JAR" com.android.tools.r8.D8 \
  --release --min-api 31 --lib build/cp/android.jar --output build/dex \
  build/classes/o3/st/root1/*.class build/cp/api.jar build/cp/provider.jar build/cp/aidl.jar build/cp/shared.jar

"$AAPT2" compile --dir res -o build/res.zip
"$AAPT2" link --manifest AndroidManifest.xml -I build/cp/android.jar \
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
