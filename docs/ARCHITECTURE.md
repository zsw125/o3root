# Architecture

> Chinese translation: [`ARCHITECTURE.zh-CN.md`](ARCHITECTURE.zh-CN.md) — a translation of this file; if the two disagree, this English file is authoritative.

This document describes the exploit chain and the Android/KernelSU packaging.
Everything here is specific to the target profile below; see
[KERNEL_PROFILE](#kernel-profile) for the constants and how to port them.

## Target profile

| item | value |
|---|---|
| device | Xiaomi `yingtian` (Pad 9 Pro Max, M367FC); `lhasa` (Xiaomi 18 Fold) shares the same kernel |
| firmware | HyperOS `OS4.0.16.0.XBMCNXM` (yingtian) / `OS4.0.26.0.XPNCNXM` (lhasa), Android 17 |
| kernel | `6.18.21-android17-5-g805aa6adea19-abogki565292102-4k`, arm64, 4 KB pages |
| bootloader | locked, verified boot green |
| entry point | Shizuku server in **shell mode** (`uid=2000`, `u:r:shell:s0`), driven by the app |

The kernel image hashes to
`cde87cd70206f21db4359aa1148f5f68e693ffefb11108dffde99c07c8c5ad88`
(identical on both firmwares). See `docs/history/OTA_4.0.16_kernel_report.md`.

## Vulnerability: CVE-2026-98260

Missing upstream fix `acb03d388181` ("exec: Cleanup POSIX timers right after
de_thread()"). In this kernel the POSIX-timer cleanup in `begin_new_exec()`
still happens **after** `exec_task_namespaces()` instead of right after
`de_thread()`.

Trigger:

1. A **non-leader** thread `B` creates a `CLOCK_THREAD_CPUTIME_ID` POSIX timer
   (`timer_create` + `timer_settime`).
2. `B` calls `execve()`; `de_thread()` swaps the PIDs (`exchange_tids`) and
   releases the old leader, then the timer is unhashed while its RB node stays
   linked in the (now stale) `timerqueue`.
3. The exec path is interrupted before `exit_itimers()`/`posix_cpu_timers_exit()`
   completes, so the `k_itimer` is freed (`kfree_rcu`) but the node remains.
4. The parent catches `B` as a zombie (`waitid(P_PID, ..., WEXITED|WNOWAIT)`)
   and reclaims the freed order-1 slab page with user-controlled content.
5. Reaping the zombie (`release_task` → `posix_cpu_timers_exit` →
   `cleanup_timerqueue` → `timerqueue_del` → `rb_erase`) performs the second
   `timerqueue_del` on a node inside the reclaimed page.

## Write primitive

`rb_erase()` case "left child exists, right child does not":

```
ldr  x9, [x0]        ; pc = node->__rb_parent_color
ands x8, x9, #-4     ; parent = pc & ~3
str  x9, [x10]       ; *(node->rb_left) = pc        <-- arbitrary 8-byte write
...
ldr  x9, [x8, #0x10] ; dereference parent->rb_left
```

The forged node gives:

- `[N]      = V`  (the value to write)
- `[N+0x08] = 0`  (no right child → case above)
- `[N+0x10] = S`  (the target address)

Constraints:

- `parent = V & ~3` is dereferenced. To stay alive, `V` must be `0` (parent 0 →
  early return) or a readable address under our control (e.g. the perf page VA).
  Therefore the primitive can write **0** or **a pointer into a controlled page
  safely** — not an arbitrary constant such as `0x100`.
- The first erase also updates `rb_next`/`head->rb_leftmost`; `[N+8] = 0` and
  `[N+0x10] = S` make the second deletion land on the fake node with a
  tolerable `WARN` (requires `panic_on_warn=0`).

## Two stages

The payload repeats the whole UAF/reclaim cycle per stage (`g_ntg = 2`):

### Stage 0 — SELinux state

- v3 default (two-reap): `S = SELINUX_STATE_STATIC + slide - 7`, value `0` —
  an 8-byte unaligned zero that clears `enforcing` (and the adjacent
  `notif_lock` pending/tail bytes) while keeping `initialized`/`policycap[]`
  intact (observed `state=0x0100000101010100`).
- Legacy `O3P_SELDIRTY=1` keeps the old dirty write (`S = ...`,
  `V = fakekva | 0x100`), which also zeroes `enforcing` but leaves address
  garbage in `policycap[]` — inert while Permissive, breaks socket checks once
  Enforcing returns.

### Stage 1 — credentials

- `S = task_struct.cred (PTASK + 0x908)`, `V = perf_page_va`
- the perf page contains a fake `struct cred`: uid/gid/euid/fsuid = 0, full
  caps (`0x1ffffffffff`), `security` = the process's original SELinux blob
  (keeping `u:r:shell:s0`), valid `user_ns`, `group_info`, `ucounts`.
- After the reap, the payload repairs the page (`repair_fake_cred`) and becomes
  root with full capabilities.

`real_cred` (0x900) is **not** replaced. Any in-process `commit_creds()` would
BUG (`task->cred != task->real_cred`), which is why the module is loaded by a
**forked `sh`** running `insmod` (a fresh exec gives consistent creds) instead
of `finit_module` in-process.

## KASLR slide

A kretprobe on `__arm64_sys_getpid` reads the parent's `task_struct.cred`
pointer; from the cred it reads `user_ns`, and:

```
slide = leaked_user_ns - 0xffffffc0826d10d8   (INIT_USER_NS_STATIC)
```

The slide is validated against the alignment of kernel VAs and reused for all
static addresses, including the embedded KernelSU LKM.

## KernelSU loading

1. Three prebuilt LKMs ride after the payload in one v2 container (per-entry
   table + `O3KSKM02`/count trailer; the legacy single-LKM `O3KSKO01` trailer
   is still read): KernelSU v3.3.1 (375,328 B), KernelSU-Next v3.4.2
   (473,552 B) and SukiSU v4.2.1-r2 (569,752 B). Before loading, the payload
   scans `/data/app` for the installed manager (`me.weishu.kernelsu` → 1,
   `com.rifsxd.ksunext` → 2, `com.sukisu.ultra` → 3; `O3P_KSU_FORK`
   overrides) and slices out that LKM; the app performs the same probe and
   passes the fork to the payload.
2. Primary path — **runtime kallsyms resolution**: `kallsyms_patch()` walks the
   LKM's `.symtab`, collects every undefined symbol name, saves and zeroes
   `/proc/sys/kernel/kptr_restrict`, resolves each name through
   `/proc/kallsyms`, and rewrites the symbol to `SHN_ABS` with the runtime
   address. The old `kptr_restrict` value is always restored before loading.
   This removes the build-time dependency on `vmlinux.elf`; the measured runs
   resolved 225 (tiann), 227 (KernelSU-Next) and 245 (SukiSU) undefined
   symbols with zero misses.
3. Fallback path — **offline relink table**: `O3P_RELINK_ONLY=1` (or a
   kallsyms miss) uses the embedded union of 248 KernelSU symbols from the
   upstream forks and rewrites each undefined symbol to `vmlinux_addr + slide`.
   It also bypasses module symbol-namespace checks, `TRIM_UNUSED_KSYMS`
   exports, protected symbols and CRCs.
4. **Legacy only (`O3P_CODEPATCH=1`)**: one instruction is patched. The loader
   parses `.rela.init.text`, finds the relocation whose symbol is `setenforce`,
   and rewrites the 4 bytes at `r_offset - 4` (`mov w0,#1` → `mov w0,#0`;
   section-relative `.init.text+0x144` for tiann/KernelSU-Next and `+0x178` for
   SukiSU — the log prints absolute file offsets 0x22144 / 0x37144 / 0x3e178
   for these builds). A unique-scan fallback covers LKMs whose relocations cannot
   be resolved. The v3 default skips this entirely, so KernelSU's own
   `setenforce(1)` runs and is safe (clean `policycap`).
   (Patching the `bl setenforce` call site instead is not possible: the CALL26
   relocation rewrites those 4 bytes at load time, causing an undefined
   instruction / module stuck in `coming`.)
5. Write the (legacy: patched; v3: unmodified) LKM to `/data/local/tmp/o3p_ksu.ko`, then
   `fork()` + `exec("/system/bin/sh","-c","insmod ...")`, wait, and verify
   `/proc/modules` contains `kernelsu`.

Validated on `6.18.21` with three manager images (only KernelSU v3.3.1, only
KernelSU-Next v3.4.2, only SukiSU v4.2.1-r2 installed): in each cold boot the
app detected the manager, the blob yielded the matching LKM (the file written to
`/data/local/tmp/o3p_ksu.ko` was 375,328 / 473,552 / 569,752 bytes), the module
ended `Live` with SELinux still Permissive (v2.x era) and Wi-Fi working, and
the matching manager was brought to the foreground.

### v3 default (two-reap, no init patch)

Since 3.0 the exploit uses two independent reaps (one for `selinux_state-7`,
one for `task_cred`) and loads KernelSU with the runtime kallsyms loader and
**no init patch**. `policycap` stays clean, so KernelSU's own `setenforce(1)`
is harmless: the device ends in Enforcing with working Wi-Fi and the manager
opens. `KSU_CODEPATCH skip` is the expected log line; `O3P_CODEPATCH=1`
restores the legacy patch.

## rootd

After success the payload forks a detached daemon (`o3p --rootd`) and keeps it
alive for the rest of the boot:

- abstract unix socket `@o3p_rootd`; the daemon's own log is
  `/data/local/tmp/o3p_rootd.log` (mode 0600)
- `SO_PEERCRED` check: only `uid 0` or `uid 2000` may connect
- protocol: one command per connection, executed via `/system/bin/sh -c`, the
  combined stdout/stderr streamed back, terminated by `__EXIT=<code>`
- client: `/data/local/tmp/o3p_<millis>.bin --shell "<command>"`

## App packaging

- The app (`o3root2.apk`, package `o3.st.root1`) is a normal Android app that
  talks to Shizuku through `dev.rikka.shizuku:api/provider/aidl/shared`.
- The payload is shipped as `lib/arm64-v8a/libo3p.so` (an executable ELF — the
  `extractNativeLibs` path keeps it executable after install). On "Continue",
  the app spawns `sh` via `Shizuku.newProcess`, copies the file to
  `/data/local/tmp/o3p_<millis>.bin`, and executes it.
- Status probing: `grep -c kernelsu /proc/modules; getenforce; pm path
  me.weishu.kernelsu`.
- v3 has no Enforcing guard: the chain normally ends Enforcing with a clean
  `selinux_state`, so the app only shows status (v1.8–v2.1 had a warning plus
  a one-click `su -c setenforce 0`).
- Firmware gate: the kernel release string must match
  `6.18.21-android17-5-g805aa6adea19-abogki565292102-4k`, and the device build
  fingerprint must contain `/yingtian/` or `/lhasa/`.

## Kernel profile

### Constants (this kernel)

| symbol / field | value |
|---|---|
| `init_user_ns` | `0xffffffc0826d10d8` |
| `selinux_state` | `0xffffffc082959630` |
| `begin_new_exec` | `0xffffffc0802cf910` |
| `posix_cpu_timers_exit` | `0xffffffc08014bfbc` |
| `exit_itimers` | `0xffffffc0802a603c` |
| `timerqueue_del` | `0xffffffc080149ec0` |
| `rb_erase` | `0xffffffc080149f34` |
| `task_struct.cred` | `0x908` |
| `task_struct.real_cred` | `0x900` |
| `task_struct.posix_cputimers` | `0x868` |
| `task_struct.signal` / `sighand` | `0x960` / `0x968` |
| `cred` size / uid / euid / fsuid | `0xb8` / `0x08` / `0x18` / `0x20` |
| `cred.cap_*` | `0x30`..`0x50` |
| `cred.security` / `user_ns` / `group_info` | `0x80` / `0x90` / `0xa0` |
| `cred_security_struct` (osid, sid, …) | `0x18`, sid at `+0x04` |
| `k_itimer` size / `it` / `elist` / `handling` | `0x180` / `0xe0` / `0x110` / `0x128` |
| `selinux_state` (enforcing, initialized, policycap, policy) | `0` / `1` / `2` / `0x40` |
| `selinux_state - 7` adjacent object | bytes 1..3 of `avc_latest_notif_update.notif_lock` (`0xffffffc082959629..962b`) + the next 4 bytes of the same static object (`0x…962c..962f`); the lock reads 0 (unlocked) here — re-verify per kernel image |
| page constants | `PAGE_OFFSET=0xffffff8000000000`, `VMEMAP_BASE=0xfffffffec0000000`, `struct page=0x40` |

### Porting to another kernel

1. Get the boot image from the matching OTA and extract the kernel
   (`tools/extract_payload.py`, `tools/unpack_boot.py`).
2. Convert the raw `Image` with `vmlinux-to-elf` to recover symbols.
3. Dump BTF (`tools/kernel_cfg_btf.py` or `bpftool btf dump`) to confirm the
   `task_struct`, `cred`, `k_itimer`, `selinux_state` offsets above.
4. Regenerate `payload/ksu/ksu_symtab.h` with `mksymtab.py` (`VMLINUX=...`) for
   the relink fallback (the default kallsyms path needs no table).
5. Update the static addresses and constants in `payload/o3p.c`
   (`INIT_USER_NS_STATIC`, `SELINUX_STATE_STATIC`, the `selinux_state - 7`
   stage-0 write offset, probe offsets). The v3 stage-0 primitive writes 8
   bytes starting at `selinux_state - 7`; before trusting it, dump the 8 bytes
   before `selinux_state` in the new image and confirm the overlapped object
   (here bytes 1..3 of `avc_latest_notif_update.notif_lock` plus the next four
   bytes of the same static object) is zero/unlocked, then record it in the
   profile table above. Confirm the object's *identity*, not only its value:
   `PYELFTOOLS=... python3 tools/neighbor_lookup.py selinux_state --delta -7
   --elf vmlinux.elf --require avc_latest_notif_update.notif_lock`
   prints the owning symbols, their ranges and a `name source` line (`.symtab`
   as emitted — member-qualified names are not BTF-synthesized), and exits
   non-zero if the nearest symbol is not the expected one. When the symbol has
   a `st_size`, `--require` is validated by a real
   `st_value <= target < st_value+size` containment check; when `st_size` is 0
   (as for `notif_lock` in this image) the tool falls back to nearest-symbol
   matching and prints a warning — add `--strict` to refuse that fallback. A
   zero read alone proves nothing — another object can be zero too. If the
   neighbour or the lock's size/field layout differs, re-derive the write offset
   from the new image instead of reusing `-7`.
6. Only the legacy `O3P_CODEPATCH=1` path patches KernelSU: the instruction is
   located at runtime from `.rela.init.text` (the `setenforce` relocation minus
   4 bytes), so no per-build constant is required; the unique-scan fallback logs
   `KSU_CODEPATCH … -2` if it cannot find the sequence. The v3 default skips
   the patch entirely.
7. Update the firmware gate (`Main.checkFirmware`) and rebuild the APK.

KASLR itself needs no per-device work: the slide is derived at runtime.
