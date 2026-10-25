#!/usr/bin/env python3
"""Verify payload.bin SHA-256 against payload_properties FILE_HASH."""
import base64, hashlib, os, struct, sys, time, zipfile

OTA = os.environ.get("OTA_ZIP") or (sys.argv[1] if len(sys.argv) > 1 else "")
if not OTA:
    raise SystemExit("usage: python3 hash_payload.py <ota.zip> [expected_file_hash_b64]")
EXPECT = (sys.argv[2] if len(sys.argv) > 2
          else "XmVK3y/+rKnVAdfYUk3xePMkJVIaVbU8BYhv55OfudU=")

zf = zipfile.ZipFile(OTA)
info = zf.getinfo("payload.bin")
raw = open(OTA, "rb")
raw.seek(info.header_offset)
hdr = raw.read(30)
sig, ver, flags, method, mtime, mdate, crc, csize, usize, fnlen, extralen = struct.unpack("<IHHHHHIIIHH", hdr)
data_off = info.header_offset + 30 + fnlen + extralen
size = 10373757059
print(f"hashing payload.bin: offset={data_off} size={size} ({size/1e9:.2f} GB)")
raw.seek(data_off)
h = hashlib.sha256()
left = size
t0 = time.time()
step = 32 * 1024 * 1024
done = 0
while left > 0:
    b = raw.read(min(step, left))
    if not b:
        raise SystemExit("unexpected EOF")
    h.update(b)
    left -= len(b)
    done += len(b)
    if done % (1024 * 1024 * 1024) < step:
        el = time.time() - t0
        print(f"  {done/1e9:.2f} GB  {done/1e6/max(el,1e-9):.0f} MB/s", flush=True)
digest = h.digest()
ok = digest == base64.b64decode(EXPECT)
print("sha256:", digest.hex())
print("expect:", base64.b64decode(EXPECT).hex())
print("MATCH" if ok else "MISMATCH", f"({time.time()-t0:.1f}s)")
