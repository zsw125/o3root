#!/usr/bin/env bash
# Build payload -> app. Requires env.sh (see scripts/env.example.sh).
set -euo pipefail
cd "$(dirname "$0")/.."
if [ -f scripts/env.sh ]; then . scripts/env.sh; elif [ -f env.sh ]; then . env.sh; fi

bash payload/build-payload.sh
PAYLOAD_OUT="$PWD/payload/build/libo3p.so" bash app/build.sh
echo
echo "APK: $PWD/app/o3root2.apk"
ls -l app/o3root2.apk 2>/dev/null || true
