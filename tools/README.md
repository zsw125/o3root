# tools

> Chinese translation: [`README.zh-CN.md`](README.zh-CN.md) — a translation of this file; if the two disagree, this English file is authoritative.

Helper scripts used to pull the kernel out of the OTA, recover symbols and
verify the constants used by the payload. They are ordinary Python 3 scripts;
each one accepts its inputs from the environment or argv (they were originally
written against one specific OTA, the defaults reflect that).

| script | purpose |
|---|---|
| `probe_payload.py <ota.zip>` | read the A/B OTA `payload.bin` header + protobuf manifest and print the partition/operation structure |
| `extract_payload.py` | extract `boot`, `init_boot`, `vendor_boot`, `dtbo`, `vbmeta*` from the OTA; verifies every partition SHA-256 against the manifest and writes `extract_summary.json`. Env/args: `OTA_ZIP`, `OUTDIR` (`<ota.zip> [outdir]`) |
| `unpack_boot.py <boot.img>` | parse an Android boot image (v0–v4), print the header, write `kernel`, `ramdisk`, `second`, `dtb` |
| `check_hash.py <ota.zip>` | research helper: check whether the operation `data_sha256` covers the stored (compressed) blob or the decompressed data |
| `hash_payload.py <ota.zip> [expected_b64]` | verify `payload.bin` SHA-256 against `payload_properties.txt` `FILE_HASH` |
| `neighbor_lookup.py <symbol> [--delta N] [--require NAME]` | print the symbols around `symbol+N` in a vmlinux ELF (nearest-symbol resolution); `--require` exits non-zero on mismatch. Used by the porting check for the `selinux_state - 7` write. Needs `pyelftools` (set `PYELFTOOLS`) |
| `kernel_cfg_btf.py <kernel>` | print the embedded kernel config (`IKCONFIG`), BTF layouts (`task_struct`, `cred`, `k_itimer`, `selinux_state`, …) and disassemble the probe points (`begin_new_exec`, `timerqueue_del`, `rb_erase`, `selinux_inode_permission`). Needs `pyelftools` + `capstone` (set `PYELFTOOLS`) |

## Getting the kernel

```bash
python3 tools/probe_payload.py device-ota.zip
OTA_ZIP=device-ota.zip OUTDIR=out python3 tools/extract_payload.py
python3 tools/unpack_boot.py out/boot.img          # -> out/kernel
```

`out/kernel` is the raw arm64 `Image`. To get an ELF with symbols use the
external [vmlinux-to-elf](https://github.com/clabreti/vmlinux-to-elf) tool
(`vmlinux-to-elf out/kernel out/vmlinux.elf`), or `pahole`/`bpftool btf dump`
on a matching build for structure layouts.

## Notes

- `kernel_cfg_btf.py` contains this kernel's static base address
  (`0xffffffc080000000`) and the probe-point offsets; adjust them for another
  kernel.
- The OTA itself (Xiaomi proprietary) is **not** included in this repository.
  Only hashes and derived analysis in `docs/` are.
