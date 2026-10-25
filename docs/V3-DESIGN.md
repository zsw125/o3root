# v3 payload design (experimental)

Status: design draft on branch `exp/v3-tracepoint`. Nothing here is in a
release yet; every step is gated behind an environment flag so the shipped
payload keeps working unchanged.

## Goal

One UAF, one reclaimed slab page, one reap — and in that single reap do both
things v2.x needed two separate exploitations for:

1. install the fake `cred` (`task->cred = fake cred page`);
2. clear only `selinux_state.enforcing` (unaligned zero write), leaving
   `initialized` / `policycap` intact.

Side effects we want at the end:

- `getuid()==0`, full caps, SELinux Permissive;
- KernelSU LKM loads **without** the `mov w0,#0` init patch (`KSU_CODEPATCH`
  skipped), and when KernelSU sets `enforcing=1` again the network keeps
  working (clean `policycap`);
- no `kprobe_events`, no perf dependency where possible (tracked as v3b).

## Building blocks (already validated on this device)

| piece | where | evidence |
|---|---|---|
| B: slide + cred via static `kmem_cache_alloc` tracepoint | `experiments/tp_slide.c` | `SLIDE 0x1db3000000` matched payload `SLIDE_CALC`, `CREDSITE match=1` |
| A: unaligned `selinux_state-7` zero write | `O3P_SELZERO=1` | `SEL_OK state=0x0100000101010100`, KSU loaded, Enforcing + network OK |
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

`O3P_D3V3` unset → v2.1 behaviour, byte-identical tile path. The branch stays
off `main` until v3a/v3b acceptance passes on at least three cold boots.
