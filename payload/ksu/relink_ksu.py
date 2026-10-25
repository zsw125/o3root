#!/usr/bin/env python3
"""Offline relinker for the KernelSU LKM (reference / verification tool).

Rewrites every undefined symbol of the LKM to SHN_ABS with the absolute
address (vmlinux static address + KASLR slide). This is exactly what the
payload does at runtime (payload/o3p.c: ksu_patch / ksu_load); this script is
useful to pre-build a relinked .ko for a known slide or to diff against the
payload's in-memory result.

Usage:
  python3 relink_ksu.py                 # dry run: list unresolved symbols
  python3 relink_ksu.py --patch 0x2c2ea00000

Environment:
  VMLINUX     path to vmlinux.elf (required)
  KSU_LKM     path to the input LKM  (default: ../lkm/lkm-aarch64-android17-6.18_kernelsu.ko)
  OUT         output .ko path         (default: ./kernelsu_relinked.ko)
  PYELFTOOLS  dir containing elftools/
"""
import os
import struct
import sys


def _bootstrap_elftools():
    p = os.environ.get('PYELFTOOLS')
    if p:
        sys.path.insert(0, p)


_bootstrap_elftools()
from elftools.elf.elffile import ELFFile  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
VMLINUX = os.environ.get('VMLINUX', '')
KO = os.environ.get('KSU_LKM', os.path.join(HERE, '..', 'lkm', 'lkm-aarch64-android17-6.18_kernelsu.ko'))
OUT = os.environ.get('OUT', os.path.join(HERE, 'kernelsu_relinked.ko'))
SHN_ABS = 0xfff1


def load_vmlinux(path):
    m = {}
    with open(path, 'rb') as f:
        e = ELFFile(f)
        st = e.get_section_by_name('.symtab')
        if st is None:
            raise SystemExit('no .symtab in ' + path)
        for s in st.iter_symbols():
            n = s.name
            if not n:
                continue
            cand = (s['st_value'], s['st_info']['type'], s['st_info']['bind'])
            old = m.get(n)
            if old is None:
                m[n] = cand
            else:
                score = lambda x: (x[2] != 'STB_LOCAL', x[1] in ('STT_FUNC', 'STT_OBJECT'))
                if score(cand) > score(old):
                    m[n] = cand
    return m


def patch(check_only, slide):
    if not VMLINUX:
        raise SystemExit('set VMLINUX to a vmlinux.elf path')
    syms = load_vmlinux(VMLINUX)
    print('vmlinux syms', len(syms))
    with open(KO, 'rb') as fh:
        data = bytearray(fh.read())
    with open(KO, 'rb') as fh:
        e = ELFFile(fh)
        st = e.get_section_by_name('.symtab')
        if st is None:
            raise SystemExit('no .symtab in ' + KO)
        sec = e.get_section(e.get_section_index('.symtab'))
        off = sec['sh_offset']
        entsize = sec['sh_entsize'] or 24
        count = sec['sh_size'] // entsize
        sdata = e.get_section(sec['sh_link']).data()

    found, missing, weakmiss = [], [], []
    for i in range(count):
        base = off + i * entsize
        st_name, = struct.unpack_from('<I', data, base)
        info = data[base + 4]
        shndx, = struct.unpack_from('<H', data, base + 6)
        if shndx != 0 or st_name == 0:
            continue
        end = sdata.find(b'\x00', st_name)
        name = sdata[st_name:end].decode('ascii', 'replace')
        bind = info >> 4
        hit = syms.get(name)
        if hit is None:
            (weakmiss if bind == 2 else missing).append(name)
            continue
        found.append(name)
        if not check_only:
            struct.pack_into('<H', data, base + 6, SHN_ABS)
            struct.pack_into('<Q', data, base + 8, hit[0] + slide)

    print('undef symbols:', len(found) + len(missing) + len(weakmiss),
          'found', len(found), 'missing', len(missing), 'weakmissing', len(weakmiss))
    print('MISSING:', missing)
    print('WEAKMISSING:', weakmiss)
    if not check_only:
        open(OUT, 'wb').write(data)
        print('wrote', OUT, len(data), 'slide=', hex(slide))


def main():
    check_only = '--patch' not in sys.argv
    slide = 0
    if not check_only:
        slide = int(sys.argv[sys.argv.index('--patch') + 1], 0)
    patch(check_only, slide)


if __name__ == '__main__':
    main()
