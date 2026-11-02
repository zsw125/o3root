# Testing the experimental O1 build (`v3.3-o1-test`)

> 中文版：[`TESTING_O1.zh-CN.md`](TESTING_O1.zh-CN.md)

This build adds a second kernel profile for Xiaomi **O1 (XRING) SoC** devices on
OS4.0.0.x. It is **static-analysis-only**: nothing here has been run on O1
hardware. Treat every test as potentially boot-breaking, and only test a device
you own and can recover.

## Who can test

| device codename | OTA examples | kernel (must match exactly) |
|---|---|---|
| `dijun` | OS4.0.0.19.XODCNXM | `6.18.21-android17-5-g1d099fcb35e0-abogki538445360-4k` |
| `jinghu` | OS4.0.0.16.XOXCNXM | same |
| `violin` | OS4.0.0.14.XOTCNXM | same |

All three OTA kernels are bit-identical (`sha256 0d9917ca…`). If your device
shows a different kernel, the gate will block it — do not try to bypass that.

## What this build changes

- The payload picks the `selinux_state` link address at runtime from
  `uname().release` (`SELINUX_PROFILE kernel=… base=…` in the log). `yingtian` /
  `lhasa` keep their existing constant; O1 gets `0xffffffc082958630`.
- `Main.checkFirmware()` accepts the three O1 codenames with that exact kernel
  and shows an **experimental** warning in the UI.
- Everything else is the v3.2 feature set, plus the hidden root console
  (long-press the version row; three confirmations) which is useful for
  verifying `uid=0` and exporting output.

## Requirements

1. Device unlocked for ADB and **Shizuku** (or ShizukuX) running in
   **shell mode**: `shizuku_plus_server` must be `uid=2000`, `u:r:shell:s0`.
   A root-mode Shizuku (`u:r:ksu:s0`) cannot exec the payload.
2. USB debugging or working `adb`; a PC you can use to reinstall/unbrick.
3. A full backup of anything you care about. The chain does **not** write
   partitions and is boot-only, but the exploit touches kernel memory; a panic
   or a boot loop is possible and needs a recovery path.

## Install and run

```bash
adb install -r release/test/o3root2-v3.3-o1-test.apk
# or, if MIUI refuses: allow "Install via USB" in Developer options first
```

1. Open **O3 Root**. The version line must read `v3.3-o1-test`.
2. Check the log: `固件门禁通过（O1 实验性：<codename> / 6.18.21-…-g1d099fcb35e0-…）`.
   If it says `固件门禁未通过`, stop and report the log — do not retry blindly.
3. Grant the Shizuku permission when asked and press **Continue to UID 0**.
4. Wait 20–120 s. Success markers: `★ UIDNOW uid=0`, `★ KSU_MODULE LIVE`,
   the KernelSU manager opens. Also look for `SELINUX_PROFILE kernel=…` and
   `SEL_WRITE … SEL_OK` near the start.
5. Verify root: long-press the version line → pass the three confirmations →
   run `id` (must print `uid=0`) and `getenforce`.
6. Tap **导出日志** (or **复制**) and keep the log file.

## What to report

Open a GitHub issue (or comment on the release) with:

- device codename, full `Build.FINGERPRINT`, `kernel` line, OS4 build number;
- app version (`v3.3-o1-test`), Shizuku version and mode (`shell` vs root);
- the exported log file (or the relevant `SELINUX_PROFILE` / `VERDICT` /
  `UIDNOW` / `KSU_*` lines);
- outcome: success, repeated `VERDICT NO_RECLAIM`, panic/reboot, or manager
  not opening; and whether `getenforce` ends in Enforcing with working network.

## Rollback

Reboot. The chain is boot-only; nothing is written to partitions. If the device
boot-loops, use your normal fastboot/EDL recovery path.

## Known risks (read before running)

- O1 kernels use an older `posix_cpu_timer_del` than yingtian; the UAF timing
  may differ, so more retries or a failure are possible.
- The built-in KernelSU LKMs are built for `6.18.32` and relinked/loaded on
  `6.18.21`; this works on yingtian, and every undefined symbol they need is
  present in the O1 kernel, but module loading on O1 is unverified.
- The experimental build compiles the hidden root console. Passing its three
  confirmations means you accept that root commands can damage the device.
