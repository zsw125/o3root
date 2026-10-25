#!/usr/bin/env python3
"""Probe an Android A/B OTA payload.bin stored inside a (signed) OTA zip.

Reads the payload header + protobuf manifest, prints structure so we can
confirm field numbers before writing the real partition extractor.
"""
import os, struct, sys, zipfile

OTA = os.environ.get("OTA_ZIP") or (sys.argv[1] if len(sys.argv) > 1 else "")
if not OTA:
    raise SystemExit("usage: python3 probe_payload.py <ota.zip>")
ENTRY = "payload.bin"


def read_varint(b, i):
    r = 0; s = 0
    while True:
        x = b[i]; i += 1
        r |= (x & 0x7f) << s; s += 7
        if not (x & 0x80):
            return r, i


def parse_proto(buf):
    out = []; i = 0; n = len(buf)
    while i < n:
        key, i = read_varint(buf, i)
        fn = key >> 3; wt = key & 7
        if wt == 0:
            v, i = read_varint(buf, i)
            out.append((fn, wt, v))
        elif wt == 1:
            out.append((fn, wt, buf[i:i + 8])); i += 8
        elif wt == 2:
            ln, i = read_varint(buf, i)
            out.append((fn, wt, buf[i:i + ln])); i += ln
        elif wt == 5:
            out.append((fn, wt, buf[i:i + 4])); i += 4
        else:
            raise ValueError(f"bad wire type {wt} at {i} (fn={fn})")
    return out


def describe(fields, indent="  "):
    counts = {}
    for fn, wt, v in fields:
        counts.setdefault((fn, wt), []).append(v)
    for (fn, wt), vals in sorted(counts.items()):
        sample = vals[0]
        if wt == 0:
            print(f"{indent}field {fn:3d} varint  x{len(vals):5d}  sample={sample}")
        elif wt == 2:
            printable = ""
            try:
                s = sample.decode("utf-8")
                if s.isprintable() and len(s) < 60:
                    printable = f"  text={s!r}"
            except Exception:
                pass
            print(f"{indent}field {fn:3d} bytes   x{len(vals):5d}  sample_len={len(sample)}{printable}")
        else:
            print(f"{indent}field {fn:3d} wt{wt}    x{len(vals):5d}")


zf = zipfile.ZipFile(OTA)
info = zf.getinfo(ENTRY)
raw = open(OTA, "rb")
raw.seek(info.header_offset)
hdr = raw.read(30)
sig, ver, flags, method, mtime, mdate, crc, csize, usize, fnlen, extralen = struct.unpack("<IHHHHHIIIHH", hdr)
assert sig == 0x04034B50, hex(sig)
data_off = info.header_offset + 30 + fnlen + extralen
print(f"[zip] entry={ENTRY} method={method} csize={csize} data_offset={data_off}")

raw.seek(data_off)
magic, fmtver, manifest_size, meta_sig_size = struct.unpack(">4sQQI", raw.read(24))
print(f"[payload] magic={magic} version={fmtver} manifest_size={manifest_size} metadata_sig_size={meta_sig_size}")
assert magic == b"CrAU"
manifest = raw.read(manifest_size)
print(f"[payload] manifest bytes read={len(manifest)}")
open("ota_extract_manifest.bin", "wb").write(manifest)

top = parse_proto(manifest)
print("[manifest] top-level fields:")
describe(top)

# locate partitions: repeated message whose field 1 is a plausible name
print("\n[manifest] candidate partitions:")
for fn, wt, v in top:
    if wt != 2:
        continue
    try:
        sub = parse_proto(v)
    except Exception:
        continue
    name = None
    for sfn, swt, sv in sub:
        if sfn == 1 and swt == 2:
            try:
                cand = sv.decode("utf-8")
            except Exception:
                continue
            if cand and all(32 <= ord(c) < 127 for c in cand):
                name = cand
                break
    if name:
        print(f"  top-field {fn} -> partition name={name!r} (msg bytes={len(v)})")
        describe(sub, indent="      ")