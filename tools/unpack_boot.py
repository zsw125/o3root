#!/usr/bin/env python3
"""Unpack an Android boot image (v0-v4) and extract the kernel blob."""
import gzip
import bz2
import lzma
import os
import struct
import sys

try:
    from compression import zstd  # Python 3.14+
except Exception:
    zstd = None


def u32(b, off):
    return struct.unpack_from("<I", b, off)[0]


def detect_and_decompress(blob):
    """Return (compression_name, decompressed_bytes)."""
    if blob[:2] == b"\x1f\x8b":
        return "gzip", gzip.decompress(blob)
    if blob[:3] == b"BZh":
        return "bzip2", bz2.decompress(blob)
    if blob[:6] == b"\xfd7zXZ\x00":
        return "xz", lzma.decompress(blob)
    if blob[:4] == b"\x28\xb5\x2f\xfd":
        if zstd is None:
            raise SystemExit("zstd magic but no zstd module")
        return "zstd", zstd.decompress(blob)
    if blob[:4] in (b"\x04\x22\x4d\x18", b"\x02\x21\x4c\x18"):
        return "lz4", lz4_frame_decompress(blob)
    if blob[:3] == b"\x5d\x00\x00":
        return "lzma", lzma.decompress(blob)
    if len(blob) > 0x3C and blob[0x38:0x3C] == b"ARM\x64":
        return "none(Image)", blob
    return "unknown", blob


def lz4_frame_decompress(data):
    """Minimal LZ4 frame decoder (legacy 0x184C2102 and modern 0x184D2204)."""
    import struct as _s
    magic = _s.unpack_from("<I", data, 0)[0]
    out = bytearray()
    if magic == 0x184C2102:  # legacy frame: 4-byte blocks
        pos = 4
        while pos + 4 <= len(data):
            bsize = _s.unpack_from("<I", data, pos)[0]
            pos += 4
            if bsize == 0:
                break
            block = data[pos:pos + bsize]
            pos += bsize
            out += lz4_block_decode(block, len(out))
        return bytes(out)
    if magic != 0x184D2204:
        raise SystemExit(f"unknown lz4 magic {magic:08x}")
    # modern frame
    flg = data[4]
    bd = data[5]
    pos = 6
    content_size = None
    if flg & 0x08:  # content size present
        content_size = _s.unpack_from("<Q", data, pos)[0]
        pos += 8
    if flg & 0x01:  # dict id present
        pos += 4
    pos += 1  # header checksum
    block_max = {4: 1 << 16, 5: 1 << 18, 6: 1 << 20, 7: 1 << 22}[(bd >> 4) & 7]
    block_indep = bool(flg & 0x20)
    while True:
        bsize = _s.unpack_from("<I", data, pos)[0]
        pos += 4
        if bsize == 0:
            break
        uncompressed = bsize & 0x80000000
        bsize &= 0x7FFFFFFF
        block = data[pos:pos + bsize]
        pos += bsize
        if uncompressed:
            out += block
        else:
            out += lz4_block_decode(block, len(out))
        if flg & 0x10:  # block checksum present
            pos += 4
    return bytes(out)


def lz4_block_decode(block, out_len):
    out = bytearray()
    pos = 0
    while pos < len(block):
        token = block[pos]
        pos += 1
        llen = token >> 4
        if llen == 15:
            while True:
                x = block[pos]; pos += 1
                llen += x
                if x != 255:
                    break
        out += block[pos:pos + llen]
        pos += llen
        if pos >= len(block):
            break
        offset = block[pos] | (block[pos + 1] << 8)
        pos += 2
        mlen = token & 0x0F
        if mlen == 15:
            while True:
                x = block[pos]; pos += 1
                mlen += x
                if x != 255:
                    break
        mlen += 4
        start = len(out) - offset
        if offset >= mlen:
            out += out[start:start + mlen]
        else:
            for i in range(mlen):
                out.append(out[start + i])
    return bytes(out)


def main(path):
    data = open(path, "rb").read()
    magic = data[:8]
    print(f"[boot] file={path} size={len(data)} magic={magic!r}")
    if magic != b"ANDROID!":
        raise SystemExit("not an Android boot image")
    header_version = u32(data, 40)
    print(f"[boot] header_version={header_version}")
    if header_version >= 3:
        kernel_size = u32(data, 8)
        ramdisk_size = u32(data, 12)
        os_version = u32(data, 16)
        header_size = u32(data, 20)
        cmdline = data[44:44 + 1536].split(b"\x00")[0].decode("utf-8", "replace")
        print(f"[boot] kernel_size={kernel_size} ramdisk_size={ramdisk_size} os_version=0x{os_version:08x} header_size={header_size}")
        print(f"[boot] cmdline={cmdline!r}")
        page = 4096
        k_off = page
        r_off = k_off + ((kernel_size + page - 1) // page) * page
        signature_size = 0
        if header_version >= 4 and header_size >= 1584:
            signature_size = u32(data, 1580)
            print(f"[boot] signature_size={signature_size}")
        kernel = data[k_off:k_off + kernel_size]
        ramdisk = data[r_off:r_off + ramdisk_size] if ramdisk_size else b""
    else:
        page = u32(data, 36)
        kernel_size = u32(data, 8)
        ramdisk_size = u32(data, 16)
        print(f"[boot] page_size={page} kernel_size={kernel_size} ramdisk_size={ramdisk_size}")
        k_off = page
        r_off = k_off + ((kernel_size + page - 1) // page) * page
        kernel = data[k_off:k_off + kernel_size]
        ramdisk = data[r_off:r_off + ramdisk_size] if ramdisk_size else b""

    cd = os.path.dirname(path) or "."
    open(os.path.join(cd, "kernel.compressed"), "wb").write(kernel)
    print(f"[kernel] compressed blob={len(kernel)} bytes head={kernel[:16].hex()}")
    name, raw = detect_and_decompress(kernel)
    print(f"[kernel] compression={name} uncompressed={len(raw)} head={raw[:16].hex()}")
    raw_path = os.path.join(cd, "kernel")
    open(raw_path, "wb").write(raw)
    is_arm64 = len(raw) > 0x3C and raw[0x38:0x3C] == b"ARM\x64"
    print(f"[kernel] arm64 Image magic present={is_arm64}")
    i = raw.find(b"Linux version ")
    if i >= 0:
        print("[kernel] version string:", raw[i:i + 200].split(b"\x00")[0].decode("utf-8", "replace"))
    else:
        print("[kernel] 'Linux version' string not found in raw image")
    if ramdisk:
        open(os.path.join(cd, "ramdisk"), "wb").write(ramdisk)
        print(f"[ramdisk] {len(ramdisk)} bytes head={ramdisk[:8].hex()}")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "ota_extract/boot.img")
