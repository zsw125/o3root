#!/usr/bin/env python3
"""Identify which symbol owns `symbol+delta` in a vmlinux/vmlinux-to-elf image.

Porting aid: the v3 stage-0 write is 8 bytes at `selinux_state - 7`, so the
image must show *which object* occupies those bytes (not merely that they read
zero). This prints the nearest symbols below the target address and can fail
the check with --require.

Usage:
  PYELFTOOLS=... python3 tools/neighbor_lookup.py selinux_state --delta -7 \
      --elf out/vmlinux.elf --require avc_latest_notif_update.notif_lock
  python3 tools/neighbor_lookup.py 0xffffffc082959629 --elf out/vmlinux.elf
"""
import argparse
import os
import sys

sys.path.insert(0, os.environ.get("PYELFTOOLS", os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "..", ".toolchain", "pylib")))
from elftools.elf.elffile import ELFFile  # noqa: E402


def load_symtab(path):
    f = open(path, "rb")
    elf = ELFFile(f)
    symtab = elf.get_section_by_name(".symtab")
    if symtab is None:
        raise SystemExit("no .symtab in %s (is this vmlinux-to-elf output?)" % path)
    rows = [(s["st_value"], s.name, s["st_size"])
            for s in symtab.iter_symbols() if s["st_value"]]
    rows.sort()
    return f, rows


def main():
    ap = argparse.ArgumentParser(
        description="resolve the symbol that owns symbol+delta in a vmlinux ELF")
    ap.add_argument("target", help="symbol name (e.g. selinux_state) or 0xADDRESS")
    ap.add_argument("--elf", default=os.environ.get("VMLINUX", "vmlinux.elf"))
    ap.add_argument("--delta", type=lambda v: int(v, 0), default=0,
                    help="signed offset applied to the target symbol (e.g. -7)")
    ap.add_argument("--require", default=None,
                    help="expected nearest symbol; exit 1 if it does not match")
    ap.add_argument("--window", type=int, default=8,
                    help="how many symbols below the target to print (default 8)")
    a = ap.parse_args()

    f, rows = load_symtab(a.elf)
    if a.target.lower().startswith("0x"):
        addr = int(a.target, 16)
    else:
        hit = [r for r in rows if r[1] == a.target]
        if not hit:
            raise SystemExit("symbol not found: %s" % a.target)
        addr = hit[0][0]
    t = addr + a.delta

    below = [r for r in rows if r[0] <= t][-a.window:]
    print("elf                %s" % a.elf)
    print("target             0x%x  (%s%+d)" % (t, a.target if not a.target.lower().startswith("0x") else "addr", a.delta))
    for v, n, sz in below:
        rng = "0x%x..0x%x" % (v, v + sz - 1) if sz else "(size unknown)"
        mark = "  <-- nearest" if v == below[-1][0] else ""
        print("  0x%-16x %-46s %-26s off=0x%x%s" % (v, n, rng, t - v, mark))

    nearest = below[-1][1]
    print("nearest symbol     %s" % nearest)
    f.close()

    if a.require is not None and nearest != a.require:
        print("FAIL: nearest symbol is %r, expected %r" % (nearest, a.require),
              file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
