#!/usr/bin/env bash
# O3 Root repository self-check.
#
# Static checks (always): shell/Python syntax, required files, KernelSU symbol
# table count, release APK checksum, tracked secrets, and a scan of every file
# (including binaries) for device identifiers / host paths / credentials.
#
# Optional builds (only when their environment is configured):
#   NDK_CC=...                                  rebuild payload, compare sha256
#   ANDROID_JAR/BUILD_TOOLS/SHIZUKU_JARS/...    rebuild APK, check version
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

PY=${PY:-python3}
if ! command -v "$PY" >/dev/null 2>&1; then
  echo "FAIL: python interpreter '$PY' not found (set PY=python or PY=python3)" >&2
  exit 1
fi

fail=0
ok()   { printf '  ok    %s\n' "$*"; }
bad()  { printf '  FAIL  %s\n' "$*"; fail=1; }
note() { printf '  --    %s\n' "$*"; }

echo "== syntax"
while IFS= read -r f; do
  if bash -n "$f" 2>/dev/null; then ok "bash $f"; else bad "bash $f"; fi
done < <(find app payload scripts -name '*.sh' -type f 2>/dev/null | sort)

while IFS= read -r f; do
  if "$PY" -c 'import ast,sys; ast.parse(open(sys.argv[1], encoding="utf-8").read())' "$f" 2>/dev/null; then
    ok "py   $f"
  else
    bad "py   $f"
  fi
done < <(find app payload scripts tools -name '*.py' -type f -not -path '*/build/*' 2>/dev/null | sort)

echo "== required files"
for f in \
  AGENTS.md AGENTS.zh-CN.md README.md README.zh-CN.md LICENSE NOTICE.md NOTICE.zh-CN.md \
  third_party/NOTICE.zh-CN.md \
  app/AndroidManifest.xml app/build.sh app/src/o3/st/root1/Main.java \
  payload/o3p.c payload/mk_payload.py payload/build-payload.sh \
  payload/ksu/ksu_symtab.h payload/ksu/mksymtab.py payload/ksu/relink_ksu.py \
  payload/lkm/lkm-aarch64-android17-6.18_kernelsu.ko \
  payload/lkm/aarch64-next-android17-6.18_kernelsu.ko \
  payload/lkm/sukisu-aarch64-android17-6.18.ko \
  docs/ARCHITECTURE.md docs/SECURITY.md docs/BUILD.md docs/AGENT_HANDOFF.md \
  docs/AGENT_HANDOFF.zh-CN.md \
  docs/ARCHITECTURE.zh-CN.md docs/SECURITY.zh-CN.md docs/BUILD.zh-CN.md \
  docs/CVE-2026-98260_FEASIBILITY.md docs/CVE-2026-98260_FEASIBILITY.zh-CN.md \
  payload/README.md payload/README.zh-CN.md tools/README.md tools/README.zh-CN.md \
  docs/APP_O3_ROOT.md
do
  if [ -f "$f" ]; then ok "$f"; else bad "missing $f"; fi
done

echo "== KernelSU symbol table"
cnt=$("$PY" - <<'PY' 2>/dev/null
import re
s = open('payload/ksu/ksu_symtab.h', encoding='utf-8').read()
print(len(re.findall(r'\{"[^"]+",\s*0x[0-9a-fA-F]+ULL\}', s)))
PY
)
if [ "$cnt" = "248" ]; then ok "ksu_symtab.h has 248 entries"; else bad "ksu_symtab.h entries: ${cnt:-?} (expected 248)"; fi

echo "== release checksum"
"$PY" - <<'PY'
import hashlib, sys
want = open('release/SHA256SUMS.txt', encoding='utf-8').read().split()[0]
got = hashlib.sha256(open('release/o3root2-v2.0.apk', 'rb').read()).hexdigest()
print('  ok    release apk sha256 matches SHA256SUMS' if got == want else
      '  FAIL  release apk sha256 %s != %s' % (got, want))
sys.exit(0 if got == want else 1)
PY
[ $? -eq 0 ] || fail=1

echo "== tracked secrets"
if git rev-parse --git-dir >/dev/null 2>&1; then
  secrets=$(git ls-files 2>/dev/null | grep -Ei '(^|/)(env\.sh|[^/]*\.(p12|jks|keystore))$' || true)
  if [ -z "$secrets" ]; then ok "no keystore/env files tracked"; else bad "tracked secrets: $secrets"; fi
else
  note "not a git checkout; skipping tracked-file check"
fi

echo "== sensitive strings (text)"
PAT='AI-workspace|C:/Users/13778|08831C2049983540|ennea-patched-2026|NWPU-WLAN|Xiaomi_C336|192\.168\.31\.[0-9]|10\.30\.186\.|patched-key\.p12'
hits=$(grep -rInE --exclude-dir=.git --exclude-dir=build --exclude=verify.sh "$PAT" . 2>/dev/null || true)
if [ -z "$hits" ]; then ok "no host paths / serials / ssids / keystore names"; else bad "sensitive text:"; printf '%s\n' "$hits" | sed 's/^/        /'; fi

hits=$(grep -rInE --exclude-dir=.git --exclude-dir=build --exclude=verify.sh '([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}' . 2>/dev/null || true)
if [ -z "$hits" ]; then ok "no MAC/BSSID strings"; else bad "MAC/BSSID-like strings:"; printf '%s\n' "$hits" | sed 's/^/        /'; fi

echo "== sensitive strings (binary)"
BINPAT='08831C2049983540|ennea-patched-2026|AI-workspace|C:/Users/13778'
bhits=$(grep -arlE --exclude-dir=.git --exclude=verify.sh "$BINPAT" . 2>/dev/null || true)
if [ -z "$bhits" ]; then ok "no tokens in binaries"; else bad "tokens in binaries:"; printf '%s\n' "$bhits" | sed 's/^/        /'; fi

echo "== optional: payload rebuild"
if [ -n "${NDK_CC:-}" ]; then
  log=$(mktemp)
  if bash payload/build-payload.sh >"$log" 2>&1; then
    got=$("$PY" -c "import hashlib;print(hashlib.sha256(open('payload/build/libo3p.so','rb').read()).hexdigest())")
    want=98936d14b5f50b1fc4a9b35741404088a33e1a1fe68042b61e8e948c31f87dfa
    if [ "$got" = "$want" ]; then ok "libo3p.so sha256 matches reference"; else bad "libo3p.so sha256 $got != $want"; fi
  else
    bad "payload build failed"; tail -20 "$log" | sed 's/^/        /'
  fi
  rm -f "$log"
else
  note "NDK_CC not set; skipping payload build"
fi

echo "== optional: app rebuild"
if [ -n "${ANDROID_JAR:-}" ] && [ -n "${BUILD_TOOLS:-}" ] && [ -n "${SHIZUKU_JARS:-}" ] && [ -n "${KEYSTORE:-}" ] && [ -n "${KS_PASS:-}" ]; then
  log=$(mktemp)
  if bash app/build.sh >"$log" 2>&1; then
    if [ -x "$BUILD_TOOLS/aapt2" ] || [ -x "$BUILD_TOOLS/aapt2.exe" ]; then
      AAPT2="$BUILD_TOOLS/aapt2"; [ -x "$AAPT2" ] || AAPT2="$BUILD_TOOLS/aapt2.exe"
      v=$("$AAPT2" dump badging app/o3root2.apk 2>/dev/null | grep -o "versionName='[^']*'" | head -1)
      case "$v" in *"'2.0'"*) ok "APK rebuilt ($v)";; *) bad "APK version $v (expected 2.0)";; esac
    else
      note "APK rebuilt (aapt2 not found to verify version)"
    fi
  else
    bad "app build failed"; tail -20 "$log" | sed 's/^/        /'
  fi
  rm -f "$log"
else
  note "app build env incomplete; skipping app rebuild"
fi

echo "== git state"
if git rev-parse --git-dir >/dev/null 2>&1; then
  if [ -z "$(git status --porcelain)" ]; then ok "working tree clean"; else note "working tree has uncommitted changes"; fi
fi

if [ "$fail" -ne 0 ]; then
  echo
  echo "verify: FAILED"
  exit 1
fi
echo
echo "verify: OK"
