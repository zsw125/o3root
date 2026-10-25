#!/usr/bin/env python3
"""Resolve which symbol owns `symbol+delta` in a vmlinux/vmlinux-to-elf image.

Porting aid for the v3 stage-0 write: it happens 8 bytes before
`selinux_state`, so a port must prove *which object* occupies those bytes.
Symbol sizes are often missing for local symbols emitted by the kernel build
(the `avc_latest_notif_update.notif_lock` symbol here has `st_size = 0`), so
this tool is explicit about its evidence level:

* symbol has a size  -> containment check `st_value <= target < st_value+size`;
* symbol has no size -> nearest-symbol-below fallback, printed as a warning
  (fail-open). `--strict` turns that into a failure instead.

`--require NAME` validates the expected owner. It passes only on a real
containment check when the required symbol carries a size; with an unsized
symbol it passes on the nearest-symbol match and warns (or fails with
`--strict`).

Names are read from `.symtab` exactly as emitted. Member-qualified names such
as `avc_latest_notif_update.notif_lock` come from the image's own symbol table;
this tool does not synthesize them from BTF/DWARF.

Usage:
  PYELFTOOLS=... python3 tools/neighbor_lookup.py selinux_state --delta -7 \
      --elf out/vmlinux.elf --require avc_latest_notif_update.notif_lock
  python3 tools/neighbor_lookup.py 0xffffffc082959629 --elf out/vmlinux.elf --strict
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
                    help="expected owning symbol; validated by containment when "
                         "the symbol has a size, otherwise by nearest-symbol match")
    ap.add_argument("--strict", action="store_true",
                    help="refuse size-less fallbacks (requires a real "
                         "st_value <= target < st_value+st_size check)")
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

    below = [r for r in rows if r[0] <= t]
    nearest = below[-1] if below else None
    containing = [r for r in rows if r[2] and r[0] <= t < r[0] + r[2]]

    print("elf                %s" % a.elf)
    print("symbols            %d" % len(rows))
    print("name source        .symtab (as emitted; no BTF/DWARF member synthesis)")
    print("target             0x%x  (%s%+d)"
          % (t, a.target if not a.target.lower().startswith("0x") else "addr", a.delta))
    if containing:
        for v, n, sz in containing:
            print("containment        inside %s (0x%x..0x%x)" % (n, v, v + sz - 1))
    else:
        print("containment        no sized symbol contains the target")

    for v, n, sz in below[-a.window:]:
        rng = "0x%x..0x%x" % (v, v + sz - 1) if sz else "(size unknown)"
        mark = "  <-- nearest" if v == nearest[0] else ""
        print("  0x%-16x %-46s %-26s off=0x%x%s" % (v, n, rng, t - v, mark))

    warn_unsized = nearest is not None and nearest[2] == 0
    if warn_unsized:
        print("warning            nearest symbol has st_size=0; identity relies on "
              "the nearest-symbol fallback (fail-open), not a range check")
    if nearest is not None:
        print("nearest symbol     %s" % nearest[1])
    f.close()

    rc = 0
    if a.require is not None:
        cands = [r for r in rows if r[1] == a.require]
        if not cands:
            print("FAIL: required symbol %r not found in .symtab" % a.require,
                  file=sys.stderr)
            return 1
        sized = [r for r in cands if r[2]]
        inside = [r for r in sized if r[0] <= t < r[0] + r[2]]
        if inside:
            print("PASS               target is inside required symbol %r "
                  "(range check)" % a.require)
        elif sized:
            print("FAIL: required symbol %r exists but the target is outside its "
                  "sized range" % a.require, file=sys.stderr)
            return 1
        elif nearest is not None and nearest[1] == a.require:
            if a.strict:
                print("FAIL: --strict requires a sized containment check, but %r "
                      "has st_size=0" % a.require, file=sys.stderr)
                return 1
            print("PASS (fallback)    nearest symbol matches %r, but it has no "
                  "size — use --strict to refuse this" % a.require)
        else:
            print("FAIL: nearest symbol is %r, expected %r"
                  % (nearest[1] if nearest else None, a.require), file=sys.stderr)
            return 1
    elif a.strict and warn_unsized:
        print("FAIL: --strict requires a sized containment check, but the nearest "
              "symbol has st_size=0", file=sys.stderr)
        return 1
    return rc


if __name__ == "__main__":
    sys.exit(main())
