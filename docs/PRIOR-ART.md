# Prior art — `EAEdward/xiaomi9pmroot`

[EAEdward/xiaomi9pmroot](https://github.com/EAEdward/xiaomi9pmroot) (MIT, first
published 2026-10-25) is an independent root toolkit for the same device
(Xiaomi Pad 9 Pro Max / `yingtian`, `OS4.0.16.0.XBMCNXM`, kernel
`6.18.21-android17-5-g805aa6adea19-…`). It exploits the same CVE-2026-98260
POSIX-CPU-timer UAF with a different, and in several places leaner, design.
We read its `docs/HOW-IT-WORKS.md`, `plant/uaf_plant_v6.c`, `plant/pc98260_*`,
`launcher/ytrl.sh` and release artifacts, and cross-checked its constants
against our `vmlinux`.

Credit: the ideas marked *(adopted)* below come from that project; the
tracepoint-only slide measurement in `experiments/` is a port of their
technique, re-derived against our kernel image.

## What it is

- Distribution: AxManager plugin (boot hook) + shell launcher `ytrl.sh` +
  `plant` / `su98260` / `ksud` binaries; no APK UI.
- Chain: Shizuku shell → `plant` (CVE-2026-98260) → `pc98260-root.sock`
  rootd → `ksud late-load --kmi android17-6.18 --allow-shell --package-name
  me.weishu.kernelsu` → KernelSU userspace `su` → run `/data/local/tmp/yt-scripts/*.sh`.
- KernelSU: official LKM loaded through upstream `ksud late-load`; the LKM is
  **not patched**, so KernelSU's init hook restores `enforcing=1`. Their
  `selinux_state` write is arranged so that the restored Enforcing state is
  clean (no `policycap` garbage), which is why networking keeps working.

## Techniques worth adopting

1. **(adopted) Tracepoint-only KASLR slide + cred leak.** A static
   `kmem_cache_alloc` tracepoint records `call_site` of `alloc_posix_timer`
   (0x180-byte request) and of `prepare_creds` (0xb8-byte request); both are
   `static_link + slide`. No kprobes, no perf, no root. Validated here in the
   Enforcing shell domain: `experiments/README.md`.
2. **PFN-verified reclaim.** Instead of blind spray + retry, they instrument
   `mm_page_free` / `mm_page_alloc` / `mm_filemap_add_to_page_cache`, derive
   the victim PFN, and only release the parked zombie once the N-th AIO ring
   provably owns that page. Fail-closed.
3. **One UAF, three trees.** `task_struct.posix_cputimers` has three timer
   queues (`CPUCLOCK_PROF/VIRT/SCHED` at offsets 0x878/0x890/0x8a8). Arming
   one timer per queue and reaping once performs three independent `rb_erase`
   writes from a single reclaimed page: fake cred, `enforcing`-only zero, and
   a no-op.
4. **Unaligned enforcing-only zero.** Write 8 zero bytes at
   `selinux_state - 7`: the byte at `selinux_state + 0` (`enforcing`) becomes
   0 while `initialized` (+1) and `policycap` (+2..+7) stay intact. Cheap way
   to get Permissive without the `policycap` pollution our current stage0
   causes (we patch the LKM init and keep Permissive instead).
5. **Official `ksud late-load`.** Upstream already implements runtime
   kallsyms recovery for the LKM (`late_load.rs`, `cannot uniquely recover GKI
   kallsyms from ARM64 Image`, `CONFIG_KALLSYMS_ALL is required`), so an
   in-house relinker may not be needed for non-stock kernels.

## Where we stay different (and why)

- `selinux_state - 7` overlaps the tail of `avc_latest_notif_update.notif_lock`
  (0xffffffc082959628..0xffffffc08295962f). The lock is 0 (unlocked) in
  practice, but this is an adjacent-object write that must be re-verified for
  every kernel build; our write-primitive stays inside `selinux_state`.
- Their fake cred's `security` pointer targets a zeroed blob (sid 0), usable
  only while Permissive; we keep the real `cred_security_struct` and get an
  explicit capability/mac cleanup path.
- Auto-reboot up to 6 times/day on failure is not acceptable for our tool;
  we prefer bounded retry and an explicit error.
- AxManager is a third-party plugin manager; our APK is self-contained.
- Their binaries are ~1.4 MB (plant) + ~1 MB (su) + 4.99 MB (ksud) static
  payloads; our three-LKM blob is 1.49 MB.

## Status in this repository

- Experiment **B** (tracepoint-only slide) — implemented and validated, see
  `experiments/README.md`.
- Experiment **A** (unaligned enforcing-only zero + KSU without the
  `KSU_CODEPATCH` init patch) and **C/D** (PFN-verified reclaim; three-tree
  single reap) — prototypes pending on branch `exp/v3-tracepoint`.
