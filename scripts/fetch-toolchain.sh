#!/usr/bin/env bash
# Fetch the Android SDK pieces and Shizuku jars needed to build the app.
# Usage:  bash scripts/fetch-toolchain.sh
# Nothing here is committed to git; everything lands under tools/ (gitignored).
set -euo pipefail
cd "$(dirname "$0")/.."
PY=${PY:-python3}
MIRROR=${ANDROID_MIRROR:-https://dl.google.com/android/repository}
MVN=${MAVEN_MIRROR:-https://repo1.maven.org/maven2}
SHIZUKU_VER=${SHIZUKU_VER:-13.1.5}
mkdir -p tools/android tools/shizuku tools/pylib

fetch() { # url out
  if [ -s "$2" ]; then echo "have $2"; return; fi
  echo "fetch $1"
  "$PY" - "$1" "$2" <<'PY'
import sys, urllib.request, shutil
url, out = sys.argv[1], sys.argv[2]
req = urllib.request.Request(url, headers={'User-Agent': 'o3root-fetch'})
with urllib.request.urlopen(req, timeout=120) as r, open(out, 'wb') as f:
    shutil.copyfileobj(r, f)
print('saved', out)
PY
}

# --- android.jar (platform 34) ---------------------------------------------
ZIP=tools/android/platform-34-ext12_r01.zip
fetch "$MIRROR/platform-34-ext12_r01.zip" "$ZIP"
"$PY" - "$ZIP" <<'PY'
import sys, zipfile, os
z = zipfile.ZipFile(sys.argv[1])
for n in z.namelist():
    if n.endswith('android.jar'):
        open('tools/android/android.jar','wb').write(z.read(n)); print('android.jar <-', n); break
PY

# --- build-tools r34 -------------------------------------------------------
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*|Windows_NT) BT=build-tools_r34-windows.zip ;;
  Darwin) BT=build-tools_r34-mac.zip ;;
  *) BT=build-tools_r34-linux.zip ;;
esac
fetch "$MIRROR/$BT" "tools/android/$BT"
"$PY" - "tools/android/$BT" <<'PY'
import sys, zipfile, os, shutil
z = zipfile.ZipFile(sys.argv[1]); root = 'tools/android'
for n in z.namelist():
    t = os.path.join(root, n)
    if n.endswith('/'): os.makedirs(t, exist_ok=True); continue
    os.makedirs(os.path.dirname(t), exist_ok=True)
    with z.open(n) as s, open(t,'wb') as d: shutil.copyfileobj(s, d)
print('build-tools extracted under tools/android/android-*')
PY

# --- Shizuku jars ----------------------------------------------------------
for a in api provider aidl shared; do
  fetch "$MVN/dev/rikka/shizuku/$a/$SHIZUKU_VER/$a-$SHIZUKU_VER.aar" "tools/shizuku/$a.aar"
done
"$PY" - <<'PY'
import zipfile, os
for a in ('api','provider','aidl','shared'):
    with zipfile.ZipFile(f'tools/shizuku/{a}.aar') as z:
        open(f'tools/shizuku/{a}-classes.jar','wb').write(z.read('classes.jar'))
    print(a, 'classes.jar ok')
PY

# --- pyelftools (needed to regenerate ks u symbol table) --------------------
"$PY" -m pip install --quiet --target tools/pylib pyelftools || \
  echo "WARN: pip install pyelftools failed; ksu_symtab.h regeneration will not work" >&2

echo
echo "Done. Point env vars at:"
echo "  ANDROID_JAR=$PWD/tools/android/android.jar"
echo "  BUILD_TOOLS=$(ls -d $PWD/tools/android/android-* | head -1)"
echo "  SHIZUKU_JARS=$PWD/tools/shizuku"
echo
echo "NDK is NOT downloaded by this script. Install the Android NDK (r26+) and set NDK_CC."
