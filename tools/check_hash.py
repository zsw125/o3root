#!/usr/bin/env python3
"""Check what the operation sha256 field hashes (stored blob vs decompressed)."""
import hashlib, lzma, os, struct, sys, zipfile

OTA = os.environ.get("OTA_ZIP") or (sys.argv[1] if len(sys.argv) > 1 else "")
if not OTA:
    raise SystemExit("usage: python3 check_hash.py <ota.zip>")

def rv(b, i):
    r = 0; s = 0
    while True:
        x = b[i]; i += 1
        r |= (x & 0x7f) << s; s += 7
        if not (x & 0x80):
            return r, i

def pp(buf):
    out = {}; i = 0
    while i < len(buf):
        key, i = rv(buf, i)
        fn, wt = key >> 3, key & 7
        if wt == 0:
            v, i = rv(buf, i)
        elif wt == 1:
            v = buf[i:i + 8]; i += 8
        elif wt == 2:
            ln, i = rv(buf, i); v = buf[i:i + ln]; i += ln
        elif wt == 5:
            v = buf[i:i + 4]; i += 4
        else:
            raise ValueError(wt)
        out.setdefault(fn, []).append(v)
    return out

zf = zipfile.ZipFile(OTA)
info = zf.getinfo("payload.bin")
raw = open(OTA, "rb")
raw.seek(info.header_offset)
hdr = raw.read(30)
sig, ver, flags, method, mtime, mdate, crc, csize, usize, fnlen, extralen = struct.unpack("<IHHHHHIIIHH", hdr)
data_base = info.header_offset + 30 + fnlen + extralen
raw.seek(data_base)
magic, pver, msize, mssize = struct.unpack(">4sQQI", raw.read(24))
manifest = raw.read(msize)
ops_base = data_base + 24 + msize + mssize
top = pp(manifest)

def get_ops(part):
    for pm in top[13]:
        f = pp(pm)
        if f[1][0].decode() == part:
            return f.get(8, [])
    return []

for pname, idx in [("adsp", 0), ("boot", 0), ("boot", 1), ("vendor_boot", 0)]:
    opb = get_ops(pname)[idx]
    of = pp(opb)
    t = of[1][0]; doff = of[2][0]; dlen = of[3][0]; sha = of.get(8, [b""])[0]
    raw.seek(ops_base + doff)
    blob = raw.read(dlen)
    print(f"== {pname}#{idx} type={t} doff={doff} dlen={dlen}")
    print("   store sha256:", hashlib.sha256(blob).hexdigest(), "match_sha:", hashlib.sha256(blob).digest() == sha)
    try:
        data = blob if t == 0 else lzma.decompress(blob)
        print("   decomp len:", len(data), "decomp sha256:", hashlib.sha256(data).hexdigest(), "match_sha:", hashlib.sha256(data).digest() == sha)
    except Exception as e:
        print("   decompress error:", e)
    print("   field8 sha:", sha.hex())
