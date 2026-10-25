# payload

> Chinese translation: [`README.zh-CN.md`](README.zh-CN.md) — a translation of this file; if the two disagree, this English file is authoritative.

Native aarch64 exploit payload (C, NDK) that is packaged into the APK as
`lib/arm64-v8a/libo3p.so` — an executable ELF disguised as a shared library.
The app copies it to `/data/local/tmp` and runs it inside a Shizuku-spawned
shell-domain process.

## Build

```bash
export NDK_CC=/path/to/ndk/toolchains/llvm/prebuilt/<host>/bin/aarch64-linux-android35-clang
bash build-payload.sh
# -> build/o3p.bin      (payload executable)
# -> build/libo3p.so    (payload + three KernelSU-family LKMs + table/trailer)
```

`mk_payload.py` writes a v2 container: the payload, the three LKMs (KernelSU
v3.3.1, KernelSU-Next v3.4.2, SukiSU v4.2.1-r2), a 24-byte-per-entry tag table
and a `O3KSKM02` + `u32 count` trailer. The payload reads the trailer from
`/proc/self/exe`, detects the installed manager (or honours `O3P_KSU_FORK`),
slices out the matching LKM and loads it; the same file can still be executed
directly (`./libo3p.so`). The legacy single-LKM `O3KSKO01` layout remains
readable.

## What the payload does

1. **Trace setup**: kprobes/ftrace on `do_timer_create`, `__set_task_comm`,
   `posix_timer_unhash_and_free`, `exit_itimers`, `release_task`,
   `timerqueue_del`, `posix_cpu_timers_exit` (tracefs, 0777; shell domain is
   allowed to write it).
2. **KASLR slide**: leaks `init_user_ns` by reading the parent's `cred` through
   a kprobe (`__arm64_sys_getpid`), then `slide = leaked - INIT_USER_NS_STATIC`.
3. **CVE-2026-98260**: arms a `CLOCK_THREAD_CPUTIME_ID` POSIX timer in a
   non-leader thread, races `execve` through `de_thread`/`exit_itimers`, keeps
   the zombie (waitid WNOWAIT), then reclaims the freed order-1
   `posix_timers_cache` slab page with a kmalloc-8k (`send(...,7872)`) spray
   containing a forged `k_itimer` RB-tree node.
4. **Write primitive**: the reap (`release_task` → `posix_cpu_timers_exit` →
   `cleanup_timerqueue` → `timerqueue_del` → `rb_erase`) writes the forged
   node's `__rb_parent_color` into an arbitrary target address. The payload
   uses it to clear `selinux_state.enforcing` (stage 0, an unaligned zero at
   `selinux_state-7`) and to rewrite `task_struct.cred` (stage 1).
5. **Fake cred** (`stage 1`): a perf ring page (kernel VA leaked via kretprobe)
   holds a fake `struct cred` with uid/euid/fsuid=0, full caps, and the
   original SELinux blob pointer.
6. **KernelSU LKM (adaptive)**: before loading, the payload scans `/data/app`
   for the installed manager — `me.weishu.kernelsu` (KernelSU, tag 1),
   `com.rifsxd.ksunext` (KernelSU-Next, tag 2) or `com.sukisu.ultra`
   (SukiSU-Ultra, tag 3) — and picks that LKM from the embedded blob
   (`O3P_KSU_FORK` overrides). It then resolves the LKM's undefined symbols at
   runtime via `/proc/kallsyms` (temporarily lowering `kptr_restrict`, then
   restoring it); `O3P_RELINK_ONLY=1` falls back to the 248-symbol union +
   address+slide. With the v3 default the module is loaded **unmodified** (no
   init patch): `policycap` stays clean, so KernelSU's own `setenforce(1)` is
   harmless (`O3P_CODEPATCH=1` restores the legacy patch). It writes
   `/data/local/tmp/o3p_ksu.ko` and `insmod`s it from a forked `/system/bin/sh`.
7. **rootd**: forks a detached root daemon; the parent execs the payload with
   `--rootd`, which listens on the abstract unix socket `@o3p_rootd` and only
   accepts peers with uid 0 or 2000 (SO_PEERCRED). Clients: `--shell "<cmd>"`.

## Files

| file | role |
|---|---|
| `o3p.c` | complete payload (≈55 KB of C; generated from the t32/t34 lineage) |
| `ksu/ksu_symtab.h` | union of 248 KernelSU symbols (upstream forks) → vmlinux addresses; used by the relink fallback |
| `ksu/mksymtab.py` | regenerate `ksu_symtab.h` from `vmlinux.elf` (+ env `VMLINUX`) |
| `ksu/relink_ksu.py` | offline relinker, reference for the runtime `ksu_patch` |
| `mk_payload.py` | pack payload + three LKMs + tag table/trailer → `libo3p.so` |
| `lkm/lkm-aarch64-…kernelsu.ko` | official KernelSU v3.3.1 LKM for `android17-6.18` (GPL-3.0) |
| `lkm/aarch64-next-…kernelsu.ko` | official KernelSU-Next v3.4.2 LKM for `android17-6.18` (GPL-3.0) |
| `lkm/sukisu-aarch64-…ko` | official SukiSU-Ultra v4.2.1-r2 LKM for `android17-6.18` (GPL-3.0) |
| `legacy/t32.c`, `legacy/t34.c`, `legacy/mk_o3p.py` | earlier exploit revisions; `mk_o3p.py` shows how `o3p.c` was derived from `t34.c` |

## Notes

- The payload is **device/profile specific**: offsets and static addresses are
  compiled for `6.18.21-android17-5-g805aa6adea19-abogki565292102-4k` on
  yingtian/lhasa. See `docs/ARCHITECTURE.md` for the constants and how to
  re-derive them for another kernel.
- The v3 default is an unaligned zero at `selinux_state-7`: it clears only
  `enforcing`, so `initialized`/`policycap[]` stay intact and re-enabling
  Enforcing is safe; the app therefore ships no recovery guard. Only the legacy
  `O3P_SELDIRTY=1` path corrupts `policycap[]` (bytes 2..7) and can break the
  network once Enforcing returns; see `docs/SECURITY.md`.
