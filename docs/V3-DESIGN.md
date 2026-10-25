# v3 payload design (historical; shipped as two-reap v3.0)

Status: this document records the **single-reap** design explored on branch
`exp/v3-tracepoint`. That variant (v3a/v3b/v3c) panicked three times and was
abandoned; v3.0 ships a **two-reap** variant that keeps the enforcing-only zero
and the no-init-patch loader. See “Outcome” at the end.

## Goal

One UAF, one reclaimed slab page, one reap — and in that single reap do both
things v2.x needed two separate exploitations for:

1. install the fake `cred` (`task->cred = fake cred page`);
2. clear only `selinux_state.enforcing` (unaligned zero write), leaving
   `initialized` / `policycap` intact.

Side effects we want at the end:

- `getuid()==0`, full caps; SELinux ends **Enforcing** after KSU's `setenforce(1)` in v3.0 (Permissive only on the legacy `O3P_SELDIRTY=1` path);
- KernelSU LKM loads **without** the `mov w0,#0` init patch (`KSU_CODEPATCH`
  skipped), and when KernelSU sets `enforcing=1` again the network keeps
  working (clean `policycap`);
- no `kprobe_events`, no perf dependency where possible (tracked as v3b).

## Building blocks (already validated on this device)

| piece | where | evidence |
|---|---|---|
| B: slide + cred via static `kmem_cache_alloc` tracepoint | `experiments/tp_slide.c` | `SLIDE 0x1db3000000` matched payload `SLIDE_CALC`, `CREDSITE match=1` |
| A: unaligned `selinux_state-7` zero write | v3.0 default (`O3P_SELDIRTY=1` = old dirty write) | `SEL_OK state=0x0100000101010100`, KSU loaded, Enforcing + network OK |

`printf` uses `%llx`, which drops the leading zero: raw logs may show
`0x100000101010100` for the same value.
| C: PFN-verified reclaim (AIO rings) | `experiments/tp_reclaim.c` | clean boot single run: 65/152 node pages captured, `MATCH ring=492 node_page=1` |
| D: three trees, three writes in one reap | `O3P_D3=1` | `D3PAGE slot2 0`, `slot3 0xdeadbeef -> 0`, `SEL_OK` |

## Architecture

`posix_cputimers` has three timerqueue bases (PROF/VIRT/SCHED; rb_node at
`TASK+0x870/0x888/0x8a0`). The exec thread arms one timer per base with the
per-thread clockid encoding `((~tid)<<3)|4|base`, so the three `k_itimer`
nodes share one 8 KiB slab page. When that page is reclaimed by our sprayed
page, `posix_cpu_timers_exit()` walks all three trees and we get three
independent `rb_erase` fast-path writes.

Tile layout (each node: `+0xe0 = pc`, `+0xe8 = rb_right = 0`,
`+0xf0 = rb_left = S`; slow path writes `*(S) = pc`):

| tree | `pc` | `S` | effect |
|---|---|---|---|
| PROF | `fake_page_va` | `PTASK+0x908` | `task->cred = fake_page_va` (user becomes uid 0) |
| VIRT | `0` | `selinux_state-7` | 8-byte unaligned zero: `enforcing=0` only |
| SCHED | `0` | `fake_page_va+0x10` | scratch zero write (witness / no-op) |

The PROF tile also makes `rb_erase` write into the fake page through the
parent link: `pc = fake_page_va`, `parent = fake_page_va`, and
`[fake_page+0x10]` currently holds the victim node address (`q[2] = N`), so
the "parent's child" branch rewrites `q[2] = PTASK+0x908`. That slot is not
used after the reap, so it is harmless (`repair_fake_cred()` rewrites the
identity fields afterwards).

## Staged delivery

### v3a — merged single-reap triple write (no leak changes yet)

Keep the existing kprobe/perf leaks (`tm`, `x`, `read_addrs`, perf page) and
merge stage0+stage1 into one UAF with the tile table above.

- env: `O3P_D3V3=1`
- reap check: `getuid()==0 && geteuid()==0`, then read
  `/sys/fs/selinux/enforce` (expect `0`); print `D3V3 uid=… euid=… enf=…`.
- then the normal `ksu_load()` path with `O3P_NO_CODEPATCH=1`.

Acceptance: one attempt, `D3V3 uid=0/0 enf=0`, `KSU_MODULE LIVE`,
`getenforce` Enforcing after KSU load, `ping`/`nc` OK.

### v3b — tracepoint-only leaks

Replace the `tm`/`x` kprobes and `read_addrs`:

- slide: B;
- timer addresses: `kmem_cache_alloc` records with
  `call_site == alloc_posix_timer+slide` and `bytes_req == 0x180`,
  filtered to the child pid — the record's `ptr` is `A_i`, so no
  `__set_task_comm` / `do_timer_create` probe is needed;
- `TASK`/`PTASK`: only needed for the dest slot and for reading the three
  rb_node fields; the dest is `PTASK+0x908` (parent task). A `task_struct`
  allocation record (`kmem_cache_alloc`, `name=task_struct`) can supply the
  parent task pointer, filtered by pid; fall back to the existing kprobe
  if it cannot be identified unambiguously;
- perf page: can stay for v3b; replacing it with the victim page itself
  (EA style, `cred = node+0x20`) is optional and tracked as v3c.

Acceptance: `ENFORCE`/`KSU`/`uid` results identical to v3a with
`/sys/kernel/tracing/kprobe_events` never opened (verified with
`strace`-free check: `grep -c '' kprobe_events` unchanged and payload log
has no `o3p/` probe lines).

### v3c — PFN-verified reclaim + app changes

- Switch the page source from `sendmsg` skb pages to `io_setup(1)` rings and
  use C's pfn matching to confirm the ring owns the node page before
  reaping; fail closed instead of retrying with a stale tree.
- App: drop the v1.8 "restore Permissive" guard to a mere warning (KSU now
  keeps Enforcing and the network stays healthy), remove the codepatch
  dependency, use KSU `su` for privileged actions instead of the shell-domain
  rootd (rootd keeps working when Permissive, but is limited under
  Enforcing).

## Risks / open questions

- three nodes must share one 8 KiB page; measured 3/3 so far, but the tile
  builder must refuse (`D3ADDR split`) otherwise;
- the PROF tile rewrites `q[2]` through the parent link — keep the fake
  page layout in sync (`repair_fake_cred()` already rewrites identity
  fields, not `q[2]`);
- writing `cred` first means the VIRT/SCHED erases run with a fake cred
  active; validated in D (kernel-side path only) but not yet with a real
  cred pointer — v3a is the first test;
- `enforce` read from the payload process is the user-visible oracle; if
  SELinux blocks it the payload falls back to `SEL_OK` from the kernel read
  (`read_addrs`) when available.

## Test matrix

| case | expected |
|---|---|
| clean boot, `O3P_D3V3=1 O3P_NO_CODEPATCH=1`, no KSU | `D3V3 uid=0/0 enf=0`, one attempt |
| same, KSU loads | `KSU_MODULE LIVE`, `getenforce=Enforcing`, `ping`/`nc` OK |
| cold boot app (v3 build) | Continue → KSU manager foreground, Permissive or Enforcing but network OK |
| repeat without reboot | documented limitation (allocator contamination, C) |

## Rollback

`O3P_D3V3` unset → v3.0 two-reap default (the shipped path).

## Outcome (2026-10-25)

v3a/v3b/v3c crashed right after `D3ADDR` in three consecutive cold boots; the
single-reap design was abandoned. v3.0 instead ships a **two-reap** variant on
`main` (`dc06899`, tag `v3.0`): A (enforcing-only zero) in stage 0 and the
proven `task_cred` swap in stage 1, with KernelSU loaded without the init patch.
Experiments B (`tp_slide`) and C (`tp_reclaim`) remain validated prototypes; see
`experiments/README.md`.

### Root-cause hypotheses (single-reap panic)

Evidence, three independent cold boots: the last durable `sdlog` line is always
`D3ADDR` (printed right after `d3_setup`), never `REAP`, and each boot reboots
within seconds. `/sys/fs/pstore` was empty on the next boot, so no PC, registers
or stack were captured — the mechanism below is **inference, not proof**.

What differs from experiment D (which passed): D wrote tile0 to a scratch slot
of the perf page, while v3a writes tile0 = `task->cred` **first**, so the VIRT
and SCHED `rb_erase` calls and the rest of the exit path run with a fake cred
active — exactly the untested combination called out under Risks.

- **H1 (most likely): mixed state during exit.** Tile0 swaps `cred` (uid 0, full
  caps, `security` pointing at the real blob), then tile1 clears `enforcing`;
  any security hook on the exit path (signal delivery from
  `flush_itimer_signals`, `security_task_kill`, an AVC notify taking
  `notif_lock`) then runs with the fake cred and a half-cleared SELinux state.
  D never exercised this combination.
- **H2: the unaligned write races `notif_lock`.** In this image the symbol is
  `avc_latest_notif_update.notif_lock` at `0xffffffc082959628`; `spinlock_t`
  resolves through `struct spinlock` -> `struct raw_spinlock` ->
  `arch_spinlock_t`/`struct qspinlock` to **4 bytes** (BTF: `spinlock_t` size
  0x4). The 8-byte write starts at `selinux_state - 7` = `0xffffffc082959629`,
  so it covers lock bytes **1..3** (`0x…9629..962b`) — the low byte carrying
  `locked`/`pending` is untouched — plus the four bytes `0x…962c..0x…962f`
  (tail of the same static object; no separate symbol or BTF type in this
  build) and finally the `enforcing` byte (`0x…9630`). A qspinlock's upper
  three bytes are the MCS tail (waiter/queue state), so a contended lock can
  have its queue corrupted without the locked bit being set; the lock reads 0
  (unlocked) in sampled builds, so this needs a race.
  `python3 tools/neighbor_lookup.py selinux_state --delta -7` reproduces the
  neighbouring-symbol claim.
- **H3: a wrong `rb_erase` branch near live data.** Tile1's erase writes next to
  `selinux_state`, whose neighbouring `status_lock`/`policy` are live kernel
  pointers; a Case-1/Case-2 branch taken differently than modelled could follow
  live data instead of our pattern. D's tile1 was also `selinux_state-7` and
  passed, so only ordering plus fake-cred activity differs.

How to prove it next time:

1. Capture a dump: keep `panic_on_oops=1`, make sure `ramoops`/pstore actually
   retains the console, and read `/sys/fs/pstore/console-ramoops*` after the
   reboot (this device's pstore was empty — fix that before retrying).
2. Bisect the tiles with single-reap runs: (a) `{cred, scratch, scratch}`,
   (b) `{scratch, selinux-7, scratch}`, (c) `{cred, selinux-7, scratch}`.
   Whichever pair first panics names the interaction.
3. Add kprobes on `posix_cpu_timers_exit` and `rb_erase` entry/return with
   `trace_printk` of node/target (or ftrace function graph), so the last kernel
   call is visible even without pstore.
4. Optionally arm only two timers (PROF + VIRT) to see whether the SCHED step is
   required for the crash.

None of this is on the v3.0 path: v3.0 ships two independent reaps (A, then the
cred swap), as tagged in `v3.0`.
