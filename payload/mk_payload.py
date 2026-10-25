#!/usr/bin/env python3
"""Pack the payload executable and the KernelSU LKM into one blob.

Layout:  [payload][lkm][24-byte trailer: "O3KSKO01" + u64 payload_len + u64 lkm_len]

The payload reads the last 24 bytes of /proc/self/exe, locates the embedded LKM,
relinks its undefined symbols to the running kernel's absolute addresses
(vmlinux addresses + KASLR slide) and insmods it. See payload/o3p.c:ksu_load().

Usage:
  python3 mk_payload.py [--payload build/o3p.bin] [--ko lkm/xxx.ko] [--out build/libo3p.so]
"""
import argparse
import hashlib
import struct

MAGIC = b'O3KSKO01'


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument('--payload', default='build/o3p.bin')
    ap.add_argument('--ko', default='lkm/lkm-aarch64-android17-6.18_kernelsu.ko')
    ap.add_argument('--out', default='build/libo3p.so')
    a = ap.parse_args()

    pay = open(a.payload, 'rb').read()
    ko = open(a.ko, 'rb').read()
    tr = MAGIC + struct.pack('<QQ', len(pay), len(ko))
    out = pay + ko + tr
    open(a.out, 'wb').write(out)

    print('payload', len(pay), 'lkm', len(ko), 'total', len(out))
    print('sha256', hashlib.sha256(out).hexdigest())
    m, off, ln = struct.unpack('<8sQQ', out[-24:])
    print('trailer', m, 'off', off, 'len', ln, 'match', out[off:off + ln] == ko)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
