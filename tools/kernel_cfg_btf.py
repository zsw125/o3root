#!/usr/bin/env python3
"""Kernel config + BTF struct layout + exploit probe-point checks for the 4.0.16 kernel."""
import gzip
import os
import struct
import sys

sys.path.insert(0, os.environ.get("PYELFTOOLS",
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".toolchain", "pylib")))
from elftools.elf.elffile import ELFFile
from capstone import Cs, CS_ARCH_ARM64, CS_MODE_ARM

KERNEL = os.environ.get("KERNEL_IMAGE") or (sys.argv[1] if len(sys.argv) > 1 else "ota_extract/kernel")
if not os.path.exists(KERNEL):
    raise SystemExit("usage: KERNEL_IMAGE=<kernel Image> python3 kernel_cfg_btf.py  (or: script <kernel Image>)")
data = open(KERNEL, "rb").read()
BASE = 0xFFFFFFC080000000

# ---------- 1. embedded kernel config (IKCONFIG) ----------
print("=" * 70)
print("[1] embedded kernel config")
i = data.find(b"IKCFG_ST")
j = data.find(b"IKCFG_ED", i if i >= 0 else 0)
print("IKCFG_ST at", hex(i) if i >= 0 else "NOT FOUND", " IKCFG_ED at", hex(j) if j >= 0 else "NOT FOUND")
cfg = {}
if i >= 0 and j >= 0:
    blob = data[i + 8:j]
    try:
        text = gzip.decompress(blob).decode("utf-8", "replace")
        for line in text.splitlines():
            if line.startswith("CONFIG_") and "=" in line:
                k, v = line.split("=", 1)
                cfg[k] = v
            elif line.startswith("# CONFIG_") and line.endswith(" is not set"):
                cfg[line[2:].split(" is not set")[0]] = "n"
        print("config lines:", len(cfg))
    except Exception as ex:
        print("decompress failed:", ex)
checks = ["CONFIG_KPROBES", "CONFIG_KALLSYMS", "CONFIG_KALLSYMS_BASE_RELATIVE",
          "CONFIG_DEBUG_INFO_BTF", "CONFIG_IKCONFIG", "CONFIG_IKCONFIG_PROC",
          "CONFIG_SECURITY_SELINUX", "CONFIG_TRACING", "CONFIG_FTRACE",
          "CONFIG_BPF_SYSCALL", "CONFIG_MODULES", "CONFIG_KPROBE_EVENTS",
          "CONFIG_RANDOMIZE_BASE", "CONFIG_ARM64_VA_BITS",
          "CONFIG_HARDENED_USERCOPY", "CONFIG_CFI_CLANG",
          "CONFIG_SECURITY_SELINUX_DISABLE", "CONFIG_LSM"]
for c in checks:
    if c in cfg:
        print(f"  {c}={cfg[c]}")
    else:
        print(f"  {c}=MISSING")

# ---------- 2. BTF ----------
print()
print("=" * 70)
print("[2] BTF struct layouts")
magic = b"\x9f\xeb\x01\x00"
pos = []
start = 0
while True:
    k = data.find(magic, start)
    if k < 0 or len(pos) > 5:
        break
    pos.append(k)
    start = k + 1
print("BTF magic candidates:", [hex(p) for p in pos])

def parse_btf(off):
    magic_, ver, flags = struct.unpack_from("<HBB", data, off)
    hdr_len, type_off, type_len, str_off, str_len = struct.unpack_from("<IIIII", data, off + 4)
    base = off + hdr_len
    types = base + type_off
    strs = base + str_off
    print(f"  header: ver={ver} hdr_len={hdr_len} type_len={type_len} str_len={str_len}")
    def s(no):
        e = data.find(b"\x00", strs + no)
        return data[strs + no:e].decode("utf-8", "replace")
    kinds = {1: 4, 2: 0, 3: 12, 4: 0, 5: 0, 6: 8, 7: 0, 8: 0, 9: 0, 10: 0, 11: 0,
             12: 0, 13: 8, 14: 4, 15: 12, 16: 0, 17: 4, 18: 0, 19: 12}
    out = []
    p = types
    tid = 1
    end = types + type_len
    while p < end:
        name_off, info, size_type = struct.unpack_from("<III", data, p)
        p += 12
        kind = (info >> 24) & 0x1F
        vlen = info & 0xFFFF
        name = s(name_off)
        members = []
        if kind in (4, 5):
            for _ in range(vlen):
                m_name, m_type, m_off = struct.unpack_from("<III", data, p)
                p += 12
                members.append((s(m_name), m_type, m_off))
        else:
            p += kinds.get(kind, 0) * vlen if kind in (6, 13, 15, 19) else kinds.get(kind, 0)
        out.append((tid, name, kind, size_type, members))
        tid += 1
    return out

btf = None
for p in pos:
    try:
        t = parse_btf(p)
        if any(n == "task_struct" for (_, n, k, _, _) in t):
            btf = t
            print("  using BTF at", hex(p), "types:", len(t))
            break
    except Exception as ex:
        print("  parse at", hex(p), "failed:", ex)

def find_struct(name):
    for tid, n, kind, size, members in btf or []:
        if n == name and kind == 4:
            return tid, size, members
    return None

if btf:
    for name, wanted in [("task_struct", ["cred", "comm"]),
                         ("cred", ["usage", "uid", "gid", "suid", "sgid", "euid", "egid", "fsuid", "fsgid", "security"])]:
        r = find_struct(name)
        if not r:
            print(f"  struct {name}: NOT FOUND")
            continue
        tid, size, members = r
        print(f"  struct {name}: type_id={tid} size=0x{size:x} members={len(members)}")
        for mname, mtype, moff in members:
            if mname in wanted:
                print(f"    {mname:18s} offset=0x{moff // 8:x}")
else:
    print("  no usable BTF found")

# ---------- 3. exploit probe points ----------
print()
print("=" * 70)
print("[3] exploit probe points")
with open("ota_extract/vmlinux.elf", "rb") as f:
    e = ELFFile(f)
    syms = {}
    for sym in e.get_section_by_name(".symtab").iter_symbols():
        if sym["st_value"]:
            syms[sym.name] = sym["st_value"]

md = Cs(CS_ARCH_ARM64, CS_MODE_ARM)
md.detail = True
for sname in ["posix_cpu_timer_del", "__set_task_comm", "ksys_read", "__arm64_sys_read",
              "pskb_expand_head", "fasync", "selinux_state", "misc_list", "init_task"]:
    print(f"  {sname:24s} = {hex(syms[sname]) if sname in syms else 'MISS'}")
pdel = syms.get("posix_cpu_timer_del")
if pdel:
    off = pdel - BASE
    probe = pdel + 72
    print(f"\n  posix_cpu_timer_del+72 = 0x{probe:x}")
    code = data[off:off + 128]
    n = 0
    for ins in md.disasm(code, pdel):
        mark = "  <-- probe point" if ins.address == probe else ""
        print(f"    0x{ins.address:x}: {ins.mnemonic:8s} {ins.op_str}{mark}")
        n += 1
        if n > 14:
            break
    # is there an instruction exactly at probe?
    print("  4-byte aligned:", probe % 4 == 0)
