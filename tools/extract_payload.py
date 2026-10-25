#!/usr/bin/env python3
"""Extract selected partitions from an A/B OTA payload.bin stored in a signed OTA zip.

Wire format notes (verified against this package):
  payload header is BIG-endian: 'CrAU' + u64 version + u64 manifest_size + u32 meta_sig_size
  manifest protobuf:
    field 3 = block_size, field 13 = repeated PartitionUpdate
    PartitionUpdate: 1=name, 7=PartitionInfo(size,hash), 8=repeated InstallOperation
    InstallOperation: 1=type, 2=data_offset, 3=data_length, 4=src_extents,
                      5=src_length, 6=dst_extents, 7=dst_length, 8=data_sha256
    Extent: 1=start_block, 2=num_blocks
  InstallOperation data_offset is absolute from the start of payload.bin.
"""
import hashlib
import json
import lzma
import bz2
import os
import struct
import sys
import zipfile

OTA = os.environ.get("OTA_ZIP") or (sys.argv[1] if len(sys.argv) > 1 else "")
OUTDIR = os.environ.get("OUTDIR") or (sys.argv[2] if len(sys.argv) > 2 else "ota_extract")
if not OTA or not os.path.exists(OTA):
    raise SystemExit("usage: OTA_ZIP=<ota.zip> OUTDIR=<dir> python3 extract_payload.py  (or: script <ota.zip> [outdir])")
TARGETS = ["boot", "init_boot", "vendor_boot", "dtbo", "vbmeta", "vbmeta_system", "vbmeta_vendor"]

OP_NAMES = {0: "REPLACE", 1: "REPLACE_BZ", 6: "ZERO", 7: "DISCARD", 8: "REPLACE_XZ"}


def rv(b, i):
    r = 0; s = 0
    while True:
        x = b[i]; i += 1
        r |= (x & 0x7f) << s; s += 7
        if not (x & 0x80):
            return r, i


def parse_proto(buf):
    out = {}; i = 0; n = len(buf)
    while i < n:
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
            raise ValueError(f"bad wire type {wt}")
        out.setdefault(fn, []).append(v)
    return out


def parse_extent(b):
    f = parse_proto(b)
    return f[1][0], f[2][0]


def zip_entry_data_offset(raw, info):
    raw.seek(info.header_offset)
    hdr = raw.read(30)
    sig, ver, flags, method, mtime, mdate, crc, csize, usize, fnlen, extralen = struct.unpack("<IHHHHHIIIHH", hdr)
    assert sig == 0x04034B50, hex(sig)
    return info.header_offset + 30 + fnlen + extralen


def main():
    os.makedirs(OUTDIR, exist_ok=True)
    zf = zipfile.ZipFile(OTA)
    info = zf.getinfo("payload.bin")
    raw = open(OTA, "rb")
    base = zip_entry_data_offset(raw, info)

    raw.seek(base)
    magic, pver, msize, mssize = struct.unpack(">4sQQI", raw.read(24))
    assert magic == b"CrAU" and pver == 2, (magic, pver)
    manifest = raw.read(msize)
    ops_base = base + 24 + msize + mssize
    print(f"[payload] version={pver} manifest={msize} metadata_sig={mssize} data_base={base} ops_base={ops_base}")

    top = parse_proto(manifest)
    block_size = top[3][0]
    parts = {}
    for pm in top[13]:
        f = parse_proto(pm)
        name = f[1][0].decode()
        npi = parse_proto(f[7][0]) if 7 in f else {}
        size = npi[1][0] if 1 in npi else 0
        nhash = npi[2][0] if 2 in npi else b""
        ops = []
        for opb in f.get(8, []):
            of = parse_proto(opb)
            op = {
                "type": of[1][0],
                "data_offset": of.get(2, [0])[0],
                "data_length": of.get(3, [0])[0],
                "dst_extents": [parse_extent(e) for e in of.get(6, [])],
                "dst_length": of.get(7, [0])[0],
                "sha256": of.get(8, [b""])[0],
            }
            ops.append(op)
        parts[name] = {"size": size, "hash": nhash, "ops": ops}

    print(f"[manifest] block_size={block_size} partitions={len(parts)}")
    summary = {}
    for name in TARGETS:
        p = parts.get(name)
        if p is None:
            print(f"[{name}] NOT IN MANIFEST")
            continue
        total = p["size"]
        outpath = os.path.join(OUTDIR, name + ".img")
        print(f"[{name}] size={total} ops={len(p['ops'])} -> {outpath}")
        written = 0
        types = {}
        with open(outpath, "wb") as out:
            out.truncate(total)
            for idx, op in enumerate(p["ops"]):
                t = op["type"]
                types[t] = types.get(t, 0) + 1
                if t in (0, 1, 8):
                    raw.seek(ops_base + op["data_offset"])
                    blob = raw.read(op["data_length"])
                    if t == 0:
                        data = blob
                    elif t == 1:
                        data = bz2.decompress(blob)
                    else:
                        data = lzma.decompress(blob)
                    if op["sha256"] and hashlib.sha256(blob).digest() != op["sha256"]:
                        raise SystemExit(f"{name} op#{idx}: data sha256 mismatch")
                    src = data
                elif t in (6, 7):
                    src = b"\x00" * (op["dst_length"] or sum(nb * block_size for _, nb in op["dst_extents"]))
                else:
                    raise SystemExit(f"{name} op#{idx}: unsupported op type {t}")
                need = sum(nb * block_size for _, nb in op["dst_extents"])
                if op["dst_length"]:
                    need = op["dst_length"]
                if len(src) < need:
                    raise SystemExit(f"{name} op#{idx}: decompressed {len(src)} < needed {need}")
                off = 0
                for start_block, num_blocks in op["dst_extents"]:
                    chunk = min(num_blocks * block_size, need - off)
                    out.seek(start_block * block_size)
                    out.write(src[off:off + chunk])
                    off += chunk
                    written += chunk
        # whole-partition hash check
        h = hashlib.sha256()
        with open(outpath, "rb") as f:
            while True:
                b = f.read(1 << 20)
                if not b:
                    break
                h.update(b)
        ok = (p["hash"] == h.digest()) if p["hash"] else None
        summary[name] = {"size": total, "ops": len(p["ops"]), "types": types,
                         "written": written, "partition_hash_ok": ok,
                         "sha256": h.hexdigest()}
        print(f"[{name}] written={written} types={types} partition_sha256_ok={ok}")
    with open(os.path.join(OUTDIR, "extract_summary.json"), "w") as f:
        json.dump(summary, f, indent=2)
    print("\nDONE")


if __name__ == "__main__":
    main()