#!/usr/bin/env bash
# Build the native payload and pack it with the KernelSU LKM.
#
# Required: NDK_CC  (aarch64 clang from the Android NDK, e.g.
#   $NDK/toolchains/llvm/prebuilt/<host>/bin/aarch64-linux-android35-clang[.cmd])
# Optional:
#   KSU_LKM  path to the KernelSU .ko (default lkm/lkm-aarch64-android17-6.18_kernelsu.ko)
#   PY       python interpreter (default python3)
#   OUT      output dir (default build)
set -euo pipefail
cd "$(dirname "$0")"
: "${NDK_CC:?set NDK_CC to aarch64-linux-androidXX-clang}"
PY=${PY:-python3}
OUT=${OUT:-build}
KSU_LKM=${KSU_LKM:-$PWD/lkm/lkm-aarch64-android17-6.18_kernelsu.ko}
mkdir -p "$OUT"

"$NDK_CC" -std=gnu11 -O2 -I "$PWD/ksu" -o "$OUT/o3p.bin" o3p.c
echo "payload: $OUT/o3p.bin ($(wc -c < "$OUT/o3p.bin") bytes)"

"$PY" mk_payload.py --payload "$OUT/o3p.bin" --ko "$KSU_LKM" --out "$OUT/libo3p.so"
echo "blob:    $OUT/libo3p.so"
