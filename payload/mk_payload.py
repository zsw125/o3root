#!/usr/bin/env python3
"""Pack the payload executable and the embedded KernelSU LKMs into one blob.

Layout (v2, multi-LKM):
  [payload][ko_tiann][ko_next][ko_sukisu][table: n*24 bytes]
  [magic "O3KSKM02"][u32 count]

Table entry (little endian): <u32 tag><u32 reserved><u64 offset><u64 length>
  tag 1 = KernelSU (tiann)      lkm/lkm-aarch64-android17-6.18_kernelsu.ko
  tag 2 = KernelSU-Next         lkm/aarch64-next-android17-6.18_kernelsu.ko
  tag 3 = SukiSU-Ultra          lkm/sukisu-aarch64-android17-6.18.ko

The payload reads the trailer, detects the installed manager
(me.weishu.kernelsu / com.rifsxd.ksunext / com.sukisu.ultra) and picks the
matching LKM (env O3P_KSU_FORK overrides). Legacy single-LKM layout
("O3KSKO01" + payload_len + ko_len as the last 24 bytes) is still readable.

Usage:
  python3 mk_payload.py [--payload build/o3p.bin] [--out build/libo3p.so] [--legacy]
"""
import argparse
import hashlib
import struct

MAGIC_V1 = b'O3KSKO01'
MAGIC_V2 = b'O3KSKM02'

# (tag, name, path) -- order defines the on-disk order
LKM_SET = [
    (1, 'tiann', 'lkm/lkm-aarch64-android17-6.18_kernelsu.ko'),
    (2, 'next', 'lkm/aarch64-next-android17-6.18_kernelsu.ko'),
    (3, 'sukisu', 'lkm/sukisu-aarch64-android17-6.18.ko'),
]


def build_v2(payload: bytes) -> bytes:
    body = bytearray(payload)
    entries = []
    for tag, name, path in LKM_SET:
        d = open(path, 'rb').read()
        off = len(body)
        body += d
        entries.append((tag, name, off, len(d)))
    table = b''.join(struct.pack('<IIQQ', tag, 0, off, ln) for tag, _n, off, ln in entries)
    trailer = MAGIC_V2 + struct.pack('<I', len(entries))
    return bytes(body) + table + trailer


def build_v1(payload: bytes, ko: bytes) -> bytes:
    tr = MAGIC_V1 + struct.pack('<QQ', len(payload), len(ko))
    return payload + ko + tr


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument('--payload', default='build/o3p.bin')
    ap.add_argument('--ko', default='lkm/lkm-aarch64-android17-6.18_kernelsu.ko')
    ap.add_argument('--out', default='build/libo3p.so')
    ap.add_argument('--legacy', action='store_true', help='single-LKM v1 layout')
    a = ap.parse_args()

    pay = open(a.payload, 'rb').read()
    if a.legacy:
        ko = open(a.ko, 'rb').read()
        out = build_v1(pay, ko)
        print('layout v1 payload', len(pay), 'lkm', len(ko), 'total', len(out))
    else:
        out = build_v2(pay)
        print('layout v2 payload', len(pay), 'total', len(out))
    open(a.out, 'wb').write(out)
    print('sha256', hashlib.sha256(out).hexdigest())

    if not a.legacy:
        n = struct.unpack('<I', out[-4:])[0]
        tbl = out[-(12 + n * 24):-12]
        for i in range(n):
            tag, _r, off, ln = struct.unpack('<IIQQ', tbl[i * 24:(i + 1) * 24])
            print('entry tag', tag, 'off', off, 'len', ln)
    else:
        m, off, ln = struct.unpack('<8sQQ', out[-24:])
        print('trailer', m, 'off', off, 'len', ln)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
