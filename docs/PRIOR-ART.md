# Prior art, credits, and what we adopted

> 中文版：[`PRIOR-ART.zh-CN.md`](PRIOR-ART.zh-CN.md) — 中文翻译；若两者不一致，以本文件为准。

This file records the external work this project builds on **and** what we
verified ourselves. It is deliberately concrete: every claim that shaped the
design is tied to a constant, a file in this repository, or a commit, so a
future contributor can re-check it instead of trusting prose.

Snapshots: the Xiaomi-specific prior art was reviewed on **2026-10-25**; the
CVE research on **2026-10-24/25**. External repositories may have changed
since — re-read them before relying on details.

> If a number here disagrees with `payload/o3p.c` or our `vmlinux`, the code and
> the kernel image win. Fix this file, not the constant.

---

## 1. CVE research: V4bel / InfinityCore (`timer/`)

Source: V4bel/InfinityCore, directory `timer/` (CVE-2026-98260). Public material
we read: `README.md` (~20 KB) and `CVE-2026-98260-ubuntu.c` (39,171 B; targets
Ubuntu 26.04 / 7.0.0-30-generic). The Android exploit is **not** public —
`CVE-2026-98260-android.c` is a 239-byte stub (reported exploit sha256
`713a294f72abc46b6e12633117d40e85629ca2b998698fd75c76e523e0d53253`).

What our first-generation chain took from it (re-implemented for our kernel in
`payload/legacy/t32.c`, `t34.c`; the earlier `t31.c` stage stayed in the
workspace history):

- **Zombie retention**: `waitid(..., WNOWAIT)` keeps the victim task a zombie
  while its `posix_cputimers` timerqueues still reference a freed `k_itimer`.
- **Page reclaim + spray**: free the order-1 `posix_timers_cache` slab, then
  spray user-writable pages so one lands on the freed slab; the fake RB node is
  laid out at a guessed object offset inside that page.
- **Forged RB erase as an 8-byte write**: `timerqueue_del` → `rb_erase` gives
  `*(target) = parent_color`; with a fake root the write can be zero or a
  controlled-page pointer.
- **AIO ring pages as a reclaim target** (their `io_setup` spray). Our first
  versions used a kmalloc-8k socket-buffer spray instead, because the shell
  domain cannot rely on AIO ordering.
- **Oracle leak idea**: `cpu_timer.handling = current` spills a task pointer
  into a user-readable page, and the `elist` `list_del_init` self-pointer leaks
  the object's kernel VA. We kept the concept but used kprobes for v1.x (we
  started from the shell domain, not the App domain).

Not taken: their Ubuntu-specific layout constants (`sizeof(k_itimer)` 0x180,
`it.cpu` 0xe0, `elist` 0x110, `handling` 0x128 — we re-derived every offset from
our own BTF) and their timing constants (392 workers, 64,000 AIO pages), which
assume a different allocator/CPU budget.

## 2. Android prior art specific to yingtian — `EAEdward/xiaomi9pmroot` (focus)

Source: <https://github.com/EAEdward/xiaomi9pmroot>, MIT, published 2026-10-25
(the snapshot we reviewed the same day). Independent root toolkit for the same
device and CVE. We read `docs/HOW-IT-WORKS.md`, `plant/uaf_plant_v6.c`,
`plant/pc98260_*`, `launcher/ytrl.sh`, and release artifacts; constants below
were cross-checked against our `vmlinux`.

### What it is

- Distribution: **AxManager plugin** (boot hook) + shell launcher `ytrl.sh` +
  `plant` / `su98260` / `ksud` binaries; no APK UI.
- Chain: Shizuku shell → `plant` (CVE-2026-98260) → rootd socket
  `pc98260-root.sock` → upstream `ksud late-load --kmi android17-6.18
  --allow-shell --package-name me.weishu.kernelsu` → KernelSU userspace `su`.
- KernelSU: official LKM via upstream `ksud`; **no init patch**. Their
  `selinux_state` write is placed so that KernelSU's own `setenforce(1)` leaves
  a *clean* state (no `policycap` garbage), which is why networking survives —
  the same conclusion our experiment A reached independently.

### Techniques adopted by this project

1. **(adopted, B) Tracepoint-only KASLR slide + cred anchor.** A static
   `kmem_cache_alloc` tracepoint records `call_site` for the 0x180-byte
   `alloc_posix_timer` and the 0xb8-byte `prepare_creds` requests; each
   `call_site = static_link + slide`. Implemented in `experiments/tp_slide.c`
   (commit `57490bf`), reading `/sys/kernel/tracing/per_cpu/cpu0/trace_pipe_raw`
   (the text trace hashes pointers — raw is mandatory). Anchors from our image:
   `TIMER_LINK = 0xffffffc0803a726c`, `CRED_LINK = 0xffffffc08026d1b4`.
   Validated from the Enforcing shell domain: measured slide
   `0x1db3000000` equals the payload's independent `SLIDE_CALC`, and the cred
   anchor reproduces it (`match=1`; needs `setresuid(2000,2000,2000)` to fire
   `prepare_creds`).
2. **(adopted, C) PFN-verified reclaim.** Instrument `mm_page_free` and
   page-cache insertion, derive the victim PFN, and only proceed once an AIO
   ring provably owns that page. Implemented in `experiments/tp_reclaim.c`
   (commits `c924964`, `812a96f`); the one-shot run matched
   `ring=492 pfn=0x334fa3 magic=0xa10a10a1`. Not shipped: in the same boot a
   second run always misses (12,000 `io_destroy` calls poison the allocator),
   so v3 keeps the proven blind spray.
3. **(adopted as research, D) One UAF, three trees.** `posix_cputimers` has
   three queues (`CPUCLOCK_PROF/VIRT/SCHED`); arming one timer per queue and
   reaping once yields three `rb_erase` writes in one pass. In our image the
   **rb_node** of the three queues is at `TASK+0x870/0x888/0x8a0` (cached
   `next` at `+0x878/+0x890/+0x8a8`; their document names the queues by the
   latter — we use the rb_node addresses). Implemented as `O3P_D3` (commit
   `af0de5b`): three independent writes were validated in one reap
   (`SEL_OK state=0x0100000101010100`,
   `D3PAGE slot0=0x100000 slot1=0 slot2=0 slot3=0`). Shipping the single-reap
   combination (v3a–v3c) **panicked three times** after `D3ADDR`; v3.0 ships the
   two-reap variant instead (see `docs/V3-DESIGN.md` §Outcome).
4. **(adopted, A → v3.0 default) Unaligned enforcing-only zero.** Write 8 zero
   bytes at `selinux_state - 7`: `enforcing` becomes 0 while `initialized` and
   `policycap[]` stay intact, so a later `setenforce(1)` is safe. Implemented in
   `payload/o3p.c` (`selzero_mode()`; commit `b6d67de`, default since
   `e3d25f6`). On our image the zero target is `0xffffffc082959629`; read-back
   was `state=0x0100000101010100`, end state Enforcing with working network.
   The write touches the tail of `avc_latest_notif_update.notif_lock`
   (`0xffffffc082959628..2f`); the lock is 0 (unlocked) on this build and this
   is re-verified per kernel image.
5. **Official `ksud late-load` (not adopted).** Upstream `late_load.rs` does
   runtime kallsyms recovery and is a valid alternative. We keep our own loader
   (`kallsyms_patch()`, commit `69f7375`; fallback `O3P_RELINK_ONLY=1` with the
   248-symbol union + slide) so the app stays a single self-contained blob with
   bounded error reporting. Their path is the better reference if you want to
   drop the embedded table.

### Where we deliberately differ

- **Self-contained APK vs AxManager plugin.** Our app bundles the payload and
  the manager installer; no third-party plugin manager.
- **Bounded retry vs auto-reboot.** They auto-reboot up to 6 times/day on
  failure; we bound attempts and print a diagnosis (`NO_RECLAIM`,
  `NO_UAF_TRIGGER`, …) so a human can decide.
- **Loader ownership.** In-house runtime loader + relink fallback, not
  `ksud late-load`.
- **Cred handling.** We keep the real `cred_security_struct` (valid SID) and
  never forge `sid=1`: that route yields `unlabeled`, and KernelSU's
  `hook_file_open` dereferences the fake blob → oops (`AGENTS.md` negative
  result). Their sid-0 fake blob is usable only while Permissive.
- **Distribution size.** Their release ships `plant` + `su98260` + `ksud`
  (multi-MB); our three-LKM blob is 1.49 MB.

Re-check these claims against their current repository before citing them; this
section is a snapshot, not a maintained mirror.

## 3. Adjacent Android ports (informative, not adopted)

- `ylh440104/InfinityCore-Android-CVE-2026-98260` (2026-10-18): ARM64 port
  template whose target offsets are all `0x0000` placeholders — not runnable.
- `quyicheng03-boop/CVE-2026-98260-nezha-android` and `…-popsicle-android`
  (2026-10-23): Xiaomi 17 Ultra / 6.12.69 targets; useful only for their method
  of locating an inlined `alloc_posix_timer` (read `x19` at
  `do_timer_create+0xb0`), not for our 6.18.21 layout.
- EnneaO3V2 (`LittlenineEnna`, `o3.ennea.pocv1`): different CVEs
  (2026-64560 / 2026-49881); we borrowed the APK architecture idea (native
  executable shipped as a `.so` payload), nothing kernel-level.
- KernelSU upstream forks — [tiann/KernelSU](https://github.com/tiann/KernelSU)
  v3.3.1, KernelSU-Next v3.4.2, SukiSU-Ultra v4.2.1-r2: the LKMs in
  `payload/lkm/` are **unmodified** GPL-3.0 binaries; the payload's loader
  adapts at runtime (`O3P_KSU_FORK` overrides the manager-based selection).

## 4. Adoption matrix

| technique | source | status in this repo | code / commit |
|---|---|---|---|
| tracepoint-only slide + cred anchor | EAEdward | validated prototype (B) | `experiments/tp_slide.c`, `57490bf` |
| PFN-verified AIO reclaim | EAEdward + V4bel AIO spray | prototype only (C) | `experiments/tp_reclaim.c`, `c924964`/`812a96f` |
| one UAF, three rb_erase writes | EAEdward | lab-validated; unsafe when shipped | `O3P_D3`, `af0de5b` |
| unaligned enforcing-only zero | EAEdward | **shipped default (v3.0)** | `payload/o3p.c` `selzero_mode()`, `b6d67de`, `e3d25f6` |
| no KernelSU init patch | EAEdward (independently reached in A) | **shipped default (v3.0)** | `O3P_CODEPATCH` legacy only |
| runtime kallsyms loader | this project (upstream `late_load.rs` as reference) | **shipped** | `69f7375`, `O3P_RELINK_ONLY` fallback |
| zombie + fake RB erase + spray | V4bel | shipped lineage (v1.x → v3) | `payload/legacy/`, `payload/o3p.c` |

## 5. Timeline anchors (this repository)

| date (UTC+8) | commit | milestone |
|---|---|---|
| 2026-10-23 | — | OTA extraction; CVE-2026-98260 feasibility report (now `docs/history/`) |
| 2026-10-24 | — | first full root (t32 chain); KernelSU late-load proof |
| 2026-10-25 15:42 | `e0fa7f8` | v1.8 release (relink-only loader) |
| 2026-10-25 17:28 | `69f7375` | runtime kallsyms loader + dynamic KSU patch point (v1.9) |
| 2026-10-25 17:59 | `78199e2` | v2.0 multi-LKM blob + manager selection |
| 2026-10-25 19:16 | `5dcbf68` | v2.1 permission-aware probe |
| 2026-10-25 20:43/20:59 | `18a46eb`/`b833925` | CI + badges/templates/security policy |
| 2026-10-25 21:29–21:59 | `57490bf`…`af0de5b` | experiments B/A/C/D |
| 2026-10-25 22:29–22:42 | `e3d25f6`…`17a8cb6` | two-reap v3 default, release v3.0, docs |
| 2026-10-26 02:17 | `291c5a0` | timeline/accuracy audit (v3 narrative everywhere) |
| 2026-10-26 | after `291c5a0` | superseded reports archived under `docs/history/`; this file expanded |

## 6. How to verify

- Constants: `ota_extract/vmlinux.elf` (symtab/BTF) or the workspace copy;
  `grep -n "TIMER_LINK\|CRED_LINK\|SELINUX_STATE_STATIC" payload/o3p.c
  experiments/*.c`.
- Offsets: `docs/ARCHITECTURE.md` §BTF and `payload/README.md`; `tools/`
  regenerates the tables.
- Payload behavior: `bash payload/build-payload.sh` and compare the printed
  `libo3p.so` sha256 with `README.md` / `docs/BUILD.md`.
- Historical claims: every number above carries a commit or file; when in
  doubt prefer the code path that actually runs (see the `O3P_*` table in
  `payload/README.md`).

## 7. Credit and licensing

- V4bel / InfinityCore — CVE-2026-98260 research and the public Ubuntu PoC; we
  re-implemented for 6.18.21 rather than copying code.
- EAEdward/xiaomi9pmroot (MIT) — the techniques marked *(adopted)* above; no
  source code copied; attribution given here and in `NOTICE.md`.
- KernelSU upstreams (tiann, KernelSU-Next, SukiSU-Ultra) — unmodified GPL-3.0
  LKMs; see `third_party/NOTICE.md`.
- Everything else in this repository is our own work; see `LICENSE` (MIT) and
  the disclaimer in `README.md`.
