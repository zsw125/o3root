# experiments — branch `exp/v3-tracepoint`

Prototypes for the v3 direction suggested by [EAEdward/xiaomi9pmroot](https://github.com/EAEdward/xiaomi9pmroot)
(MIT). See `docs/PRIOR-ART.md` for the comparison and the credit.

These are **research prototypes**, not part of the shipped payload. They do not
modify the device beyond enabling one ftrace event for the duration of the run.

## B — tracepoint-only KASLR slide (`tp_slide.c`)

Goal: recover the arm64 KASLR slide with **no kprobes, no perf, no root** — only
the static `kmem_cache_alloc` tracepoint and the per-CPU raw trace buffer.

Two independent anchors from our 6.18.21 vmlinux:

| anchor | static link address | allocation |
|---|---|---|
| `TIMER_LINK` | `0xffffffc0803a726c` (`alloc_posix_timer`) | `timer_create()` → `bytes_req = 0x180` |
| `CRED_LINK` | `0xffffffc08026d1b4` (`prepare_creds`) | `setresuid(same uid)` → `bytes_req = 0xb8` |

Because the KASLR slide is a multiple of 2 MiB, the recorded `call_site` equals
`LINK + slide`; two agreeing samples plus the independent cred anchor must
reproduce the same slide, otherwise the program prints `REFUSE`.

Mechanics:

1. Enable the event through the `rss_stat` trigger (world-writable). The
   `events/kmem/kmem_cache_alloc/enable` file itself is `0640 root:readtracefs`,
   so it is not writable from the shell — and under Enforcing even reading it
   returns `EACCES`; the trigger write and the raw read still work.
2. Pin to CPU0, `tracing_on=1`, create/delete a `CLOCK_THREAD_CPUTIME_ID` timer
   (or `setresuid` for the cred anchor), `tracing_on=0`.
3. Read `/sys/kernel/tracing/per_cpu/cpu0/trace_pipe_raw` (raw records, not the
   text file — the text form prints `%pS` symbols and hashed `%p` pointers).
   Record layout relative to `call_site`: `+0 site, +8 ptr, +24 bytes_req`.
4. Require `slide % 2 MiB == 0`, two equal samples, and `cred.site == CRED_LINK + slide`.

Build and run:

```bash
CC=/path/to/ndk/toolchains/llvm/prebuilt/<host>/bin/aarch64-linux-android35-clang
$CC -std=gnu11 -O2 -o tp_slide tp_slide.c
adb push tp_slide /data/local/tmp/tp_slide && adb shell chmod 755 /data/local/tmp/tp_slide
adb shell /data/local/tmp/tp_slide
```

Measured on yingtian (Xiaomi Pad 9 Pro Max, `6.18.21-android17-5-g805aa6adea19-…`):

| state | result |
|---|---|
| shell, Permissive | `SLIDE 0xf91200000`, `CRED_ANCHOR match=1` |
| shell, **Enforcing**, uid 2000, no root | `SLIDE 0x1db3000000`, `CRED_ANCHOR match=1` (boot `c774e834-…`) |

The Enforcing run is the one that matters: it uses only a world-writable
trigger, a readable raw pipe and two ordinary syscalls, which is exactly the
privilege the payload has inside the Shizuku shell before exploitation.

### Cross-check against the real exploit

On the same boot (`c774e834-592e-4750-97df-3c22ca751606`) the payload was run
directly (`work/o3p.bin`, which leaks `init_user_ns` for its own slide) and
printed:

```
SLIDE_CALC un=0xffffffde356d10d8 slide=0x1db3000000 align=1
```

i.e. the tracepoint-only measurement (`0x1db3000000`) agrees with the payload's
independent cred/`init_user_ns`-based measurement on the same boot.

## Next (not implemented yet)

- **A**: stage0 target `selinux_state - 7` with a zero write, so only the
  `enforcing` byte is cleared (`initialized`/`policycap` untouched). Goal:
  drop the KernelSU `mov w0,#0` init patch and keep the kernel SELinux state
  clean while KSU restores Enforcing — validate Wi-Fi + manager under Enforcing.
- **C**: PFN-verified page reclaim (`mm_page_free` + `mm_filemap_add_to_page_cache`
  → AIO ring index) instead of blind spray + retry.
- **D**: one UAF, three trees (PROF/VIRT/SCHED) → three independent `rb_erase`
  writes per reclaim (cred + enforcing-only zero + no-op).

## A — unaligned `selinux_state - 7` zero + KernelSU without the init patch

The payload now understands two experimental environment variables:

- `O3P_SELZERO=1` — stage0 targets `selinux_state - 7` and writes **0**, so the
  `enforcing` byte becomes 0 while `initialized` and `policycap[]` stay intact
  (our default stage0 writes a pointer *into* `selinux_state` and clobbers
  `policycap`, which is what later breaks Wi-Fi when SELinux returns to Enforcing).
- `O3P_NO_CODEPATCH=1` — skip the KernelSU `.init.text` `mov w0,#0` patch, so
  upstream KernelSU restores Enforcing through its own init hook.

Measured on a clean boot (`3976b14a-…`, Enforcing, no KernelSU loaded), tiann
LKM, official 375,328-byte build:

```
SELREAD got=1 zero=1
SELWRITE target=0xffffffde4d759629 want=0x0 val=0x0 SEL_OK state=0x0100000101010100
ENFORCE:[0]
...
KSU_LOADER kallsyms found=225 missing=0
KSU_CODEPATCH skip
KSU_INSMOD_OUT:[rc=0
KSU_MODULE LIVE
```

`state=0x0100000101010100` read back from `selinux_state`: byte0 = `0x00`
(enforcing cleared), byte1 = `0x01` (initialized intact), bytes 2..7 keep the
original policy capabilities. After KernelSU's init ran:

- `getenforce` → `Enforcing`;
- manager shows `工作中 [越狱模式]`, `SELinux 强制执行`;
- `ping 10.30.0.1` → 0% loss, `nc 223.5.5.5:443` → rc 0.

So without the `KSU_CODEPATCH` patch the kernel comes back to Enforcing with a
**clean** `selinux_state` and networking still works — the failure we chased in
v1.8 was policy-capability pollution, not Enforcing itself.

Caveats:

- `selinux_state - 7` also clears the last seven bytes of
  `avc_latest_notif_update.notif_lock` (unlocked in practice); this needs a
  per-build check before any shipped use.
- Rootd started *after* KernelSU restores Enforcing still works (socket + peercred),
  but some commands inside the shell domain are constrained; a v3 design would
  rely on KernelSU's `su` for post-root work instead of the shell-domain rootd.

## C — PFN-verified reclaim (`tp_reclaim.c`)

Goal: without kprobes or perf, tell **which AIO ring page owns a freed
`posix_timers` slab page**, so a real payload can stop guessing.

Method:

1. `kmem_cache_alloc` tracepoint + raw trace: `call_site == alloc_posix_timer +
   slide`, `bytes_req == 0x180`, `pid == self` -> k_itimer object addresses;
2. turn every address into page-frame numbers: the containing slab page
   (`addr & ~0xfff`) and the page holding `k_itimer.it` (`addr + 0xe0`), with
   order-1 slabs recording the even pfn;
3. enable `mm_page_free`, `mm_page_alloc` and
   `filemap:mm_filemap_add_to_page_cache`, then arm the timers, delete them and
   spray `io_setup(1)` rings on every CPU with pacing;
4. parse the per-CPU text traces: the N-th filemap line of CPU c is ring c/N.
   A watched pfn appearing there means that ring owns one of the slab pages.

Build and run:

```bash
NDK=/path/to/android-ndk-r28c/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android35-clang
$NDK -O2 -pthread -o tp_reclaim tp_reclaim.c
adb push tp_reclaim /data/local/tmp/ && adb shell chmod 755 /data/local/tmp/tp_reclaim
adb shell /data/local/tmp/tp_reclaim
```

Measured on yingtian / `6.18.21-android17-5-g805aa6adea19`, cold boot
`f545f2f7` (Enforcing, shell uid 2000, no KernelSU), first run after boot:

```
SLIDE 0x2c4e200000
TIMERS created=1584 addrs=1584
WATCH 152 node_watch=152
RINGS 12000
CPU0 rings=1200 seen=592 watch=0      <- trace buffer drops on busy CPUs
CPU1 rings=1200 seen=688 watch=33
CPU3 rings=1200 seen=624 watch=23
CPU8 rings=1200 seen=1200 watch=1
CPU9 rings=1200 seen=1200 watch=8
FILEMAP seen=9673 watch_hits=65 node_hits=65
MATCH cpu=8 ring=492 ctx=0x708446f000 pfn=0x334fa3 node_page=1 magic=0xa10a10a1
```

So 65 rings ended up owning one of the 152 watched node pages, and the ring
could be identified and validated by its io_event magic. An earlier
single-threaded run (3500 rings) showed the same effect with 26 hits.

**Limits measured:**

- repeating the experiment on the same boot yields `watch_hits=0`: every run
  destroys 12,000 AIO contexts at exit and frees ~48 MB of pages, so the next
  rings reuse those instead of the freshly freed slab pages. A real payload
  sprays once, so this is acceptable - but it means "run it twice to check"
  does not work.
- per-CPU trace buffers are small: busy CPUs lose filemap lines (`seen <
  rings`). The matcher must require `seen == rings` for a CPU before trusting
  it, and a real payload should spray fewer rings (hundreds) or raise
  `buffer_size_kb`.
