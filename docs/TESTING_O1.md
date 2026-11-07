# Testing the experimental O1 build (`v3.3.5-o1-test`)

> 中文版：[`TESTING_O1.zh-CN.md`](TESTING_O1.zh-CN.md)

This build carries the second kernel profile for Xiaomi **O1 (XRING) SoC** devices
on OS4.0.0.x, plus the v3.3.5 O1 runtime change: on O1 the app **no longer loads
a KernelSU LKM** and root comes from `@o3p_rootd` only. The O1 profile has been
**community-tested once** (see below); the maintainer has **not** independently
verified it, and only `jinghu` has been reported. Treat every test as
potentially boot-breaking, and only test a device you own and can recover.

## Verification status

| what | status |
|---|---|
| O1 end-to-end root | **community-tested once**: GitHub issue #2, reported by @Silas-02 on 2026-11-07 on a Xiaomi Pad 7 Ultra `jinghu` — HyperOS `OS4.0.0.16.XOXCNXM`, Android 17 / API 37, kernel `6.18.21-android17-5-g1d099fcb35e0-abogki538445360-4k`, **locked bootloader / vbmeta green**. Two clean boots reached `uid=0` with `@o3p_rootd` running in under 10 s (attempts 1–2). |
| maintainer reproduction | **not done** — the maintainer has not independently reproduced the O1 result. |
| `dijun` / `violin` | **not tested**. They ship the same analysed kernel (`sha256 0d9917ca…`) but no run has been reported. |
| relaxed-gate builds | not tested. The gate also accepts other OS4.0.0.x builds in the `6.18.21-android17…-4k` series; those reuse the O1 `selinux_state` address without a per-build comparison and are a calculated risk. |

Do not read this as support for every O1 device. A report from one `jinghu` unit
says the chain applies there; different O1 models, OEM builds or later
OS4.0.0.x builds may lay out the kernel differently.

## Who can test

| device codename | OTA examples | analysed kernel |
|---|---|---|
| `dijun` | OS4.0.0.19.XODCNXM | `6.18.21-android17-5-g1d099fcb35e0-abogki538445360-4k` |
| `jinghu` | OS4.0.0.16.XOXCNXM | same (community-tested once, see above) |
| `violin` | OS4.0.0.14.XOTCNXM | same |

All three OTA kernels are bit-identical (`sha256 0d9917ca…`). The gate also
accepts other OS4.0.0.x builds whose kernel stays in the
`6.18.21-android17…-4k` series: those log `固件门禁通过（O1 宽松模式：…）`
and reuse the O1 `selinux_state` address without a per-build comparison. Kernels
outside the series are still blocked.

## What this build changes on O1

- The app exports `O3P_NOKSU=1` together with `O3P_SELINUX_PROFILE=o1`, so the
  payload **skips the built-in KernelSU LKM** and root is provided by the
  payload's own `@o3p_rootd` daemon. This is deliberate, not a fallback (see
  "Why the LKM is skipped" below).
- UI: the module row shows `内核模块: O1 模式不加载 LKM（@o3p_rootd 提供 root）`
  and **选择 .ko** becomes **O1 不支持**; picking a custom `.ko` is rejected with
  `O1 模式不支持自定义内核模块`. The KernelSU-manager install prompt is
  skipped, and success is judged by `uid=0` + `@o3p_rootd` even without
  KernelSU, so the button becomes **再次运行**, not **打开 KernelSU**.
- The run log gains two O1 lines:
  - start: `O1 模式：跳过内置 KernelSU LKM（sys_call_table 受 EL2 保护），root 由 @o3p_rootd 提供`
  - success: `✔ 已获得 UID 0（O1 模式：@o3p_rootd 常驻；KernelSU 未加载）`
- Everything else is the v3.3 feature set: the `selinux_state` base is chosen at
  runtime from the app hint or `uname().release`
  (`SELINUX_PROFILE kernel=… base=0x… src=env|uname`), the gate accepts the
  three codenames, and the hidden root console is included.
- The **payload binary is unchanged** (`libo3p.so` 1,496,668 B, sha256
  `fef5e065…`); only app-side Java changed.

## Why the LKM is skipped

On XRING O1 the kernel's `sys_call_table` sits in the `Kernel code` physical
range `00200000-023bffff` (the analysed build maps it at PA `0x01560d60`), which
the EL2 XHEE hypervisor holds read-only (`kmod_protect`). The KernelSU LKM's
`stop_machine` page patch therefore cannot land. Its `sys_enter` tracepoint hook
still rewrites `pt_regs->syscallno` to a `ksu_dispatcher_nr` slot, but that slot
remains `__arm64_sys_ni_syscall` — so `execve`, `newfstatat` and friends for
pid1 / zygote / uid2000 start returning `-ENOSYS` after the second run until
reboot. The first `v3.3.4-o1-test` run hit exactly this. v3.3.5 therefore skips
the LKM on O1 and relies on `@o3p_rootd`, which needs no `sys_call_table` write.

## Requirements

1. Device unlocked for ADB and **Shizuku** (or ShizukuX) running in
   **shell mode**: `shizuku_plus_server` must be `uid=2000`, `u:r:shell:s0`.
   A root-mode Shizuku (`u:r:ksu:s0`) cannot exec the payload.
2. USB debugging or working `adb`; a PC you can use to reinstall/unbrick.
3. A full backup of anything you care about. The chain does **not** write
   partitions and is boot-only, but the exploit touches kernel memory; a panic
   or a boot loop is possible and needs a recovery path.
4. **Android 17 / Shizuku 13.5.4 known issue**: Shizuku 13.5.4 crashes there
   with `java.lang.AbstractMethodError: IProcessObserver.onProcessStarted(int,int,int,String,String)`.
   Testers need a patched Shizuku server; this project does not ship or fix
   Shizuku.

## Install and run

```bash
adb install -r release/test/o3root2-v3.3.5-o1-test.apk
# or, if MIUI refuses: allow "Install via USB" in Developer options first
```

Check the artifact first: 14,247,440 B, sha256
`405158cc9fbbd140da1ef38a83d8590e47a09b5480265554c54c3fa18a5ee6a8`
(versionCode 26 / versionName `3.3.5-o1-test`).

1. Open **O3 Root**. The version line must read `v3.3.5-o1-test`; the module row
   must read `内核模块: O1 模式不加载 LKM（@o3p_rootd 提供 root）` and **选择
   .ko** must say **O1 不支持**. No KernelSU-manager prompt should appear.
2. Press **Continue to UID 0**. Before anything runs, a two-step warning
   appears: a risk dialog, then a checkbox that enables **开始提权**. Nothing
   starts unless you accept both; cancel leaves the device untouched.
3. Check the log. The analysed build prints
   `固件门禁通过（O1 实验性：<codename> / 6.18.21-…-g1d099fcb35e0-…）`; another
   OS4.0.0.x build prints `固件门禁通过（O1 宽松模式：<codename> / <kernel>）`.
   If it says `固件门禁未通过`, stop and report the log — do not retry blindly.
4. Grant the Shizuku permission when asked; the payload starts after the
   confirmation above. Wait 20–120 s.
5. Success markers (see the exact expected sequence in the next section):
   `★ UIDNOW uid=0`, `ROOTD … FULLROOT armed`, then `CLIENT`/`SHELL`, and the
   success line `✔ 已获得 UID 0（O1 模式：@o3p_rootd 常驻；KernelSU 未加载）`.
   **`★ KSU_MODULE LIVE` and a KernelSU manager are absent on O1 and that is
   expected** — there is no KernelSU to load in this mode.
6. Verify root: long-press the version line → pass the three confirmations →
   run `id` (must print `uid=0`) and `getenforce`. On O1, `getenforce` ends
   **Permissive** by design: stage 0 clears `enforcing`, `policycap` stays
   untouched, and there is no KernelSU to turn enforcing back on. `su` is not
   provided on O1 because no KernelSU is loaded; use the console or
   `@o3p_rootd`.
7. Tap **导出日志** (or **复制**) and keep the raw log file.

## Expected log (O1)

In order, the O1 profile should print:

1. `固件门禁通过（O1 实验性：<codename> / <kernel>）` (or `O1 宽松模式` on an
   unanalysed OS4.0.0.x build)
2. `SELINUX_PROFILE kernel=… base=0x… src=env`
3. `SLIDE_CALC …`, `SELWRITE … SEL_OK` and `ENFORCE:[0]`
4. `CREDPTR … PTR_OK`
5. `★ UIDNOW uid=0`
6. `ROOTD … fork` / `ROOTD … sock ready` / `ROOTD … FULLROOT armed`
7. `CLIENT` / `SHELL`

Expected to be **absent**: `KSU_MODULE LIVE`, `KSU_INSMOD_OUT`, a KernelSU
manager in the foreground, and any `su`. SELinux ends Permissive (see above).

## What to report

Open a GitHub issue (or comment on the release) with:

- **the APK file name and its sha256** (`405158cc…` for
  `o3root2-v3.3.5-o1-test.apk`) — this pins the exact artifact;
- device codename, full `Build.FINGERPRINT`, the `uname -r` / kernel line, and
  the OS4 build number;
- app version (`v3.3.5-o1-test`), Shizuku version and mode (`shell` vs root),
  and whether the Shizuku 13.5.4 Android 17 crash above appeared;
- the **raw exported log file** (attach it — a summary is not enough), including
  the `固件门禁` / `SELINUX_PROFILE` / `UIDNOW` / `ROOTD` lines;
- outcome: success, repeated `VERDICT NO_RECLAIM`, panic/reboot, or a missing
  step from the expected sequence; number of attempts and time to root;
- whether a **second run in the same boot** still behaves normally (no dead
  `execve`/`newfstatat`), and whether `getenforce` ends Permissive with a
  working network.

Please do not report the absence of KernelSU or its manager on O1 as a defect:
that is the intended v3.3.5 behaviour. Report it only if the O1 start/success
lines are missing or root is not obtained.

## Rollback

Reboot. The chain is boot-only; nothing is written to partitions, and a reboot
also restores SELinux Enforcing. If the device boot-loops, use your normal
fastboot/EDL recovery path.

## Known risks (read before running)

- The maintainer has **not** independently verified O1 hardware. One community
  report on `jinghu` is the entire hardware evidence; `dijun` / `violin` have
  never run the chain.
- O1 kernels use an older `posix_cpu_timer_del` than yingtian; the UAF timing
  may differ, so more retries or a failure are possible.
- On a relaxed (unverified) OS4.0.0.x kernel the O1 `selinux_state` address is a
  guess: other statics may have moved, and a wrong address can panic the
  device. Prefer the analysed builds when you can.
- O1 ends in **Permissive** SELinux (no KernelSU re-enables Enforcing): MAC is
  off system-wide until reboot. This is not a daily-driver posture; reboot when
  done.
- The experimental build compiles the hidden root console. Passing its three
  confirmations means you accept that root commands can damage the device.
- No KernelSU on O1 means no manager, no `su`, no KSU allow-list. Root is
  `@o3p_rootd` only and disappears at reboot.
