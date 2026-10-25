# Agent handoff — state, history, and open work

> 中文版：[`AGENT_HANDOFF.zh-CN.md`](AGENT_HANDOFF.zh-CN.md)。若中英文不一致，以英文版为准。

This is the second document an automated contributor should read after
`AGENTS.md`. It records what has actually been verified on hardware, the
experiments that already failed (so they are not repeated), and the remaining
work with rough effort estimates.

## Project state

| item | value |
|---|---|
| release | v3.0, `release/o3root2-v3.0.apk` (14,218,768 B, sha256 `c80f499a…`) |
| verified device | Xiaomi Pad 9 Pro Max `yingtian`, HyperOS `OS4.0.16.0.XBMCNXM`, kernel `6.18.21-android17-5-g805aa6adea19-abogki565292102-4k` |
| second target | `lhasa` (Xiaomi 18 Fold) `OS4.0.26.0.XPNCNXM` — same kernel sha256 `cde87cd7…` (boot header/ramdisk differ); **not device-tested** |
| persistence | none (current boot only) |
| entry path | Shizuku **shell** server (`uid=2000`, `u:r:shell:s0`) → app → payload |

Success markers in the app log (in order):
`★ UIDNOW uid=0` → `KSU_INSMOD_OUT:[rc=0]` → `★ KSU_MODULE LIVE` →
`✔ 全部完成，正在打开 KernelSU…` → `top -> …me.weishu.kernelsu/.ui.MainActivity`.
Health: `getenforce` = `Enforcing` (v3; legacy `O3P_SELDIRTY=1` ends
Permissive), network works, `boot_id` unchanged.
Dates/commits/credits: `docs/PRIOR-ART.md` §5–7; superseded reports:
`docs/history/README.md`.

## How it got here (compressed timeline)

1. **Shell-domain payload** (`payload/legacy/t32.c`, `t34.c`): CVE-2026-98260
   UAF → order-1 slab reclaim via kmalloc-8k spray → forged RB node → single
   8-byte write. Stage 0 wrote `selinux_state.enforcing=0`; stage 1 replaced
   `task_struct.cred` with a fake cred (uid 0, full caps) → `uid=0`, rootd.
2. **KernelSU**: official LKM for `android17-6.18`; the loader resolves all
   undefined symbols at runtime via `/proc/kallsyms` (no embedded table needed),
   falling back to the 248-symbol union + KASLR slide from `init_user_ns`, then
   `insmod` from a forked shell.
3. **APK**: Shizuku `newProcess` runs the payload in the shell domain; status
   probes (`/proc/modules`, `getenforce`, `pm path`); firmware gate; bundled
   KernelSU manager APK + `PackageInstaller` flow; themed UI; log export.
4. **v1.7 fix (network)**: KernelSU's init called `getenforce()==0 →
   setenforce(1)`; combined with the polluted `policycap[]` this froze Wi-Fi TX.
   The loader patches that immediate (`mov w0,#1`→`#0`) at the `setenforce`
   relocation in `.init.text` minus 4 (0x144 tiann/Next, 0x178 SukiSU), so KSU
   does not re-enable enforcing. Cold-boot verified: KSU Live, manager opens,
   network OK. (v3.0 no longer patches — see item 7.)
5. **v1.8 guard (historical; removed in v3.0)**: v1.8–v2.1 warned when KSU
   was loaded and SELinux had rebounded to Enforcing, offering one-click
   `su -c setenforce 0`. v3 removes it: stage 0 clears only `enforcing`, so
   Enforcing is a safe end state (see §5 and the v3.0 notes).
6. **v1.9 runtime loader**: the payload resolves KernelSU symbols through
   `/proc/kallsyms` at load time (fallback: 248-symbol union relink + slide),
   locates the `setenforce` patch point from `.rela.init.text`, and works with
   tiann, KernelSU-Next and SukiSU LKMs; the v1.9 APK was cold-boot verified.

7. **v3.0 (current, 2026-10-25)**: two reaps instead of one. Stage 0 is an
   unaligned 8-byte zero at `selinux_state-7` (clears only `enforcing`, keeps
   `initialized`/`policycap`), stage 1 is the `task_cred` swap; KernelSU loads
   through the runtime kallsyms loader with **no init patch**, so its own
   `setenforce(1)` leaves the network healthy and the device in Enforcing. The
   single-reap three-write variant (v3a–v3c) panicked three times and stays
   only behind `O3P_D3V3`. See `docs/V3-DESIGN.md` and the "v3.0" section below.

The full narrative with logs is in `docs/APP_O3_ROOT.md` (Chinese, original dev
log; directory names there are historical: `work/`→`payload/`, `app5/`→`app/`).

## Experiments already done — do not repeat

### 1. io_uring ring pages as an order-1 reclaim source — **negative**

Goal: replace the kmalloc-8k spray (which needs 8 KB of controlled content)
with `io_uring` mmap'able ring pages, which are unmovable `GFP_KERNEL` pages.

- `io_uring_setup` succeeds from the shell domain (no seccomp, `io_uring_disabled=0`).
- kprobes on `io_allocate_scq_urings`, `__alloc_pages_noprof`,
  `__alloc_frozen_pages_noprof`, `__vmalloc_node_range_noprof`,
  `vm_area_alloc_pages` saw **zero** calls during setup.
- buddy tracepoint `kmem:mm_page_alloc` with filter `order>=1`: **zero** events
  for 100 rings of 128 entries.
- Full reclaim test (4000 leaked timers + 1500 rings of nr=64, MAGIC fill):
  0/4000 hits; ring pages are order-0/vmalloc.
- Conclusion: io_uring ring pages cannot reclaim the freed `posix_timers`
  order-1 slab. The current spray path (`send(...,7872)` → kmalloc-8k) already
  has the right order.

### 2. `AF_PACKET` TX ring — **positive but unusable pre-root**

600 sockets × `tp_block_size=8192` mmap'ed and filled: reclaim hit **3738/4000
(93.5%)**, the strongest order-1 writable-page primitive found. But:

- shell domain `CapEff=0`; `socket(AF_PACKET)` → `EPERM`.
- So it is only available after root is already obtained — no help for the
  pre-root reclaim step; could still be useful for a second-stage write or as a
  faster post-root primitive.

### 3. Forged `cred_security_struct.sid=1` (kernel SID route) — **negative**

Goal: avoid writing `selinux_state` at all; make the payload process kernel
domain (sid=1) through a fake SELinux blob, then `setenforce 0` via sysfs.

- SID 1 maps to `u:object_r:unlabeled:s0` in this policy (not kernel).
- KernelSU's `hook_file_open` dereferenced the fake blob and oopsed:
  `pc : hook_file_open.cold+0x4`, `Comm: yt2-parent`, avc shows
  `scontext=u:object_r:unlabeled:s0`.
- With KSU loaded the route is unusable. Without KSU it might not crash, but it
  still grants at most `unlabeled`, not kernel.

### 4. Zero-write to `selinux_state` (avoid policycap pollution) — **negative**

Writing 8 zero bytes gives `enforcing=0, initialized=0, policycap=0`. Network
stays fine and KernelSU loads, but `initialized=0` breaks `zygote`'s
`selinux_android_setcontext` for new apps (`com.xiaomi.bluetooth` fatal), so the
KSU manager cannot start and the device is unusable until reboot.

Why not write `0x0100` (initialized=1, policycap=0)? The primitive writes
`[node]` and dereferences `parent = value & ~3`; only `0` keeps parent 0. A
pointer value is the only other safe option, and its high bytes land in
`policycap` anyway.

### 5. Legacy `policycap` side effect (pre-v3 paths) — **historical**

The old stage 0 wrote 8 bytes at `selinux_state`, so `policycap[]` received
address garbage; any later `setenforce 1` froze `wlan0` TX (RX kept working) and
the shell domain could not `setenforce 0` (no `mac_admin`) — recovery was
KernelSU `su -c setenforce 0`, or reboot. **v3 default avoids all of this** by
zeroing a single byte at `selinux_state-7` (see the v3.0 notes); the old
behaviour survives only behind `O3P_SELDIRTY=1`.

### 6. Label repair — **positive, operational note**

`/data/local/tmp` files created during the abandoned zero-write boot were
`u:object_r:unlabeled:s0` and blocked shell access. With root + non-enforcing:
`chcon -R u:object_r:shell_data_file:s0 /data/local/tmp` works (labels are
persistent xattrs); `restorecon -R` skips the directory itself. Normal v1.7+ (including v3.0)
runs create correctly labeled `shell_data_file` files.

## Environment notes (device work)

- adb: `adb -s <serial> ...`; on Windows-bash `export MSYS2_ARG_CONV_EXCL='*'`
  before remote paths.
- Shizuku must be **shell mode**; with `su` present ShizukuX restarts in root
  mode (`uid=0`, `u:r:ksu:s0`) and the chain cannot exec the payload from that
  bounded domain. Test cold boots that way.
- `/system/bin/su` appears only after KernelSU's first root request (manager
  "superuser" grant); absence before that is expected.
- MIUI: `adb install` may return `INSTALL_FAILED_USER_RESTRICTED`; allow
  install-via-USB or use root `pm install -r -d`.
- tracefs: `kprobe_events` is 0777 but `events/*/enable` is root-only; probes
  must be enabled by a root process.
- memlock default 64 KB limits `io_uring` contexts (`ulimit -l` needed);
  irrelevant for the shipped payload.
- seccomp (clean cold boot): init/adbd/zygote/shizuku/rootd 0/0; system_server,
  systemui, apps 2/1. The app's own seccomp is not changed by the exploit.
- kernel flags relevant here: `CONFIG_MODULES=y`, `MODULE_SIG_FORCE` unset,
  `MODVERSIONS`, `CFI`, `TRIM_UNUSED_KSYMS`, `KALLSYMS_ALL`, `DEBUG_INFO_BTF`,
  `kptr_restrict=2`, `panic_on_warn=0`, `panic_on_oops=0`.

## Open work / candidates

Ordered by value per effort. None are required for v3.0 to work; all are
research-grade improvements.

1. **Eliminate the second `timerqueue_del`** (easy → medium). Currently the
   fake tree causes a second deletion that runs as a `WARN` (oops with
   `panic_on_warn=0` tolerated). Design a tree shape where the first erase is
   terminal (e.g. write 0 with `[N]=0`, `[V]=0` invariants) so the reap never
   touches the corrupted queue again. This reduces crash surface and may allow
   `panic_on_warn=1` scenarios.
2. **rootd hardening** (easy): random socket suffix + a token in the first
   message in addition to `SO_PEERCRED`; restrict to the calling app's uid
   instead of all of 0/2000; keep logs 0600.
3. **Validate original Shizuku** (easy-medium): the code uses the official
   `dev.rikka.shizuku` 13.1.5 API (api/provider/aidl/shared + `newProcess`);
   only ShizukuX 13.7 was exercised. Test on a device with stock Shizuku in
   shell mode, both as provider and permission flows.
4. **Cold-boot success-rate harness** (medium): script N reboots + taps; record
   attempts-to-success, `VERDICT` failure distribution, time-to-root, network
   health. Useful to tune `g_att`, spray sizing and `g_mode`.
5. **Kernel profile JSON** (medium): move constants/offsets out of `o3p.c` into
   a generated header keyed by `(kernel release, fingerprint)`, and generate
   `ksu_symtab.h` per profile. This is the main prerequisite for supporting
   more devices (e.g. other XRING O3 products) instead of a rebuild per kernel.
6. **KSU userspace reliability** (medium): manager auto-open and `su` work in
   the normal flow, but with `initialized=0` (not shipped) they break; also the
   first `su` installation is lazy. Consider making the app explicitly run
   `ksud install` (or trigger a root request) as part of setup.
7. **App features** (easy): in-app root shell (the rootd socket already
   supports `--shell`), KSU allow-list shortcut, copy/share exported log,
   i18n, tablet two-pane layout, "restart required" guidance after failures.
8. **Crash forensics** (blocked): pstore/ramoops is empty and only readable
   from a kernel-domain context; our kernel-SID route is blocked (see above).
   Without a new way to read `/sys/fs/pstore`, panics cannot be captured.
9. **LKM ABI risk** (hard): the upstream LKM was built for `6.18.32-4k`; it is
   relinked and loaded on `6.18.21`. Building the module from KernelSU source
   against this kernel's config (or a matching GKI) would remove the ABI
   mismatch and the "modified module" concern; needs kernel sources/config.

## Reproduction workflow (dev loop)

```bash
# payload
NDK_CC=... bash payload/build-payload.sh          # -> libo3p.so (sha must match)
# app
ANDROID_JAR=... BUILD_TOOLS=... SHIZUKU_JARS=... \
KEYSTORE=... KS_PASS=... KEY_ALIAS=... KEY_PASS=... bash app/build.sh
# device
adb install -r app/o3root2.apk
adb shell 'am start -n o3.st.root1/.Main'
# (UI tap) then watch log:
adb shell 'tail -f /data/data/o3.st.root1/files/run.log'   # only with debuggable builds
# or use the in-app "Export log" -> /sdcard/Android/data/o3.st.root1/files/
```

The payload can be tested standalone (root or shell): push `libo3p.so` as
`o3p.bin`, `chmod 755`, run with `10000 14000 300 1 6 1 8000 800`.

## Do not

- Do not commit `tools/` toolchain, `app/build`, `payload/build`, keystores,
  `env.sh`, OTA zips, `.img` files, or device identifiers.
- Do not patch the KernelSU `bl setenforce` call site (CALL26 relocation).
- Do not "fix" the write value to `0x100` (dereference crash).
- Do not use the legacy `O3P_SELDIRTY=1` path on a device you cannot reboot or
  `su`-recover; the v3 default ends Enforcing and is safe.
- Do not run this on devices you don't own; see `docs/SECURITY.md`.

## v2.0 — adaptive LKM (2026-10-25)

- The blob now carries three LKMs (KernelSU v3.3.1 375,328 B, KernelSU-Next
  v3.4.2 473,552 B, SukiSU v4.2.1-r2 569,752 B) in a v2 container (per-entry
  tag table + `O3KSKM02`/count trailer; legacy `O3KSKO01` still readable).
- The payload detects the installed manager (`me.weishu.kernelsu` → tag 1,
  `com.rifsxd.ksunext` → 2, `com.sukisu.ultra` → 3; `O3P_KSU_FORK`
  overrides), slices out that LKM and loads it; the app probes the same manager,
  passes the fork to the payload and opens the matching manager through a
  resolved activity.
- Cold-boot verified on three images (only KernelSU / only KernelSU-Next / only
  SukiSU installed); `/data/local/tmp/o3p_ksu.ko` sizes and manager foregrounds
  matched the selection every time; SELinux Permissive (v2.x era) and Wi-Fi
  working.

## v2.1 — permission-aware probe (2026-10-25)

- The probe runs only after Shizuku is authorized (`checkSelfPermission`),
  detects the manager through the local `PackageManager` (three packages), and
  re-probes after a successful `PackageInstaller` install; payload files get a
  unique `o3p_<millis>.bin` name to avoid `ETXTBSY`.
- Fixes the 18 Fold deadlock (`ERR java.lang.reflect.InvocationTargetException`
  was parsed as a normal probe result, so "install manager" looped).

## v3.0 — two-reap, no KernelSU init patch (2026-10-25)

- Stage 0 now zeroes a single byte: 8-byte unaligned write at
  `selinux_state-7` (clears `enforcing`, keeps `initialized`/`policycap`); the
  old dirty write remains behind `O3P_SELDIRTY=1`.
- Stage 1 is still the `task_cred` swap; the two stages use two independent
  reaps (single-reap three-write panicked three times — kept only behind
  `O3P_D3V3`).
- KernelSU loads through the runtime kallsyms loader with **no init patch**
  (`KSU_CODEPATCH skip`); its `setenforce(1)` leaves Wi-Fi healthy because
  `policycap` stayed clean. The app therefore drops the v1.8 guard/recovery UI.
- Cold-boot verified (yingtian): `KALLSYMS_PATCH found=225 missing=0`,
  `KSU_INSMOD_OUT:[rc=0]`, `KSU_MODULE LIVE`, Enforcing, `ping` 22 ms,
  `nc 443` rc=0, manager auto-opened, `boot_id` unchanged.
