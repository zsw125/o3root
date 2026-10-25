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
