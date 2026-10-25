#!/usr/bin/env python3
"""Regenerate ksu_symtab.h: KernelSU LKM undefined symbols -> vmlinux addresses.

The payload links the LKM at runtime by rewriting its undefined symbols to
SHN_ABS + (vmlinux address + KASLR slide). This script produces the static
name->address table that is compiled into the payload (ksu_symtab.h).

Environment:
  VMLINUX     path to vmlinux.elf (extracted from the target boot image)
  KSU_LKM     path to the KernelSU LKM  (default: ../lkm/lkm-aarch64-android17-6.18_kernelsu.ko)
  OUT         output header             (default: ./ksu_symtab.h next to this script)
  PYELFTOOLS  directory containing elftools/ (optional; otherwise pip pyelftools)
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
OUT = os.environ.get('OUT', os.path.join(HERE, 'ksu_symtab.h'))


def load_vmlinux(path):
    """name -> (st_value, type, bind), preferring GLOBAL FUNC/OBJECT entries."""
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


def main():
    if not VMLINUX:
        raise SystemExit('set VMLINUX to a vmlinux.elf path')
    syms = load_vmlinux(VMLINUX)
    data = open(KO, 'rb').read()
    with open(KO, 'rb') as f:
        e = ELFFile(f)
        st = e.get_section_by_name('.symtab')
        if st is None:
            raise SystemExit('no .symtab in ' + KO)
        sec = e.get_section(e.get_section_index('.symtab'))
        off = sec['sh_offset']
        entsize = sec['sh_entsize'] or 24
        count = sec['sh_size'] // entsize
        sdata = e.get_section(sec['sh_link']).data()

    rows = []
    for i in range(count):
        base = off + i * entsize
        st_name, = struct.unpack_from('<I', data, base)
        shndx, = struct.unpack_from('<H', data, base + 6)
        if shndx != 0 or st_name == 0:
            continue  # only undefined symbols
        end = sdata.find(b'\x00', st_name)
        name = sdata[st_name:end].decode('ascii', 'replace')
        hit = syms.get(name)
        if hit is None:
            print('MISS', name)
            continue
        rows.append((name, hit[0]))

    rows.sort()
    with open(OUT, 'w', encoding='ascii') as o:
        o.write('/* auto-generated: KernelSU LKM undefined symbols -> vmlinux static addresses */\n')
        o.write('#define KSU_SYM_COUNT %d\n' % len(rows))
        o.write('struct ksu_sym_entry { const char *name; unsigned long long addr; };\n')
        o.write('static const struct ksu_sym_entry ksu_syms[KSU_SYM_COUNT] = {\n')
        for n, a in rows:
            o.write('  {"%s", 0x%xULL},\n' % (n, a))
        o.write('};\n')
    print('wrote', OUT, 'count', len(rows))


if __name__ == '__main__':
    main()
