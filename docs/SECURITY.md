# Security notes and threat model

> Chinese translation: [`SECURITY.zh-CN.md`](SECURITY.zh-CN.md) — a translation of this file; if the two disagree, this English file is authoritative.

**Scope:** v3.3.5 (2026-11-08); legacy v1.7–v2.1 behaviour is called out explicitly.

## Read this first

This repository contains a **working local privilege-escalation exploit** for a
specific, fully-patched-at-release Android device and firmware. It is published
for research, education and for owners of the affected device who want to
understand/repair it.

- Only run it on devices **you own** and are willing to wipe/reset.
- Do not use it on shared, corporate or third-party devices.
- The exploit is **not persistent**: a reboot removes everything it did.
- Nothing here writes to partitions, changes `vbmeta`, or unlocks the
  bootloader. The device's verified boot state is not modified.

## Custom KernelSU modules (v3.1)

v3.1 lets the operator load a `.ko` of their choice. That file is **executed in
the kernel**, so treat it as code, not data:

- The app performs a basic compatibility check before the run (64-bit AArch64
  `ET_REL`, `.modinfo` present, `name=kernelsu`, same 6.18 kernel series) and
  the payload repeats it before `insmod`. Passing the check says "this looks
  like a module for this kernel", **not** "this module is safe or benign".
- Only load modules whose provenance you trust (your own build, or an official
  asset from the KernelSU / KernelSU-Next / SukiSU projects). A hostile module
  can do anything root can, and a buggy one can panic the device.
- When the custom module is rejected, unreadable or fails to load, the payload
  automatically falls back to the adaptive built-in LKM; the app also offers
  the fallback before the run.
- On a recognised O1 device the app requires a two-step risk confirmation
  before starting the payload; it is a guard against accidental runs, not a
  safety proof.
- Since v3.1.1 the selected module is persisted in the app-specific files
  directory (`custom-ko.ko` + `custom-ko.meta`，external storage under
  `Android/data/o3.st.root1/files`) and restored on the next
  launch, so a module you once selected is executed again — clear the row
  (long press) after trying an untrusted build.
- The test builds (v3.2.1-console and v3.3.5-o1-test) also ship a hidden root console (long press the
  version row): after three non-skippable confirmations it runs arbitrary
  commands through the rootd socket. This adds no privilege (the stable build
  can start the payload anyway), but it puts root command execution one gesture
  away; the hidden entry is not a security boundary, and the stable build
  contains no console code at all.
- v3.3.5 adds an experimental O1 (XRING) kernel profile for `dijun` / `jinghu` /
  `violin`, based on a comparison of the O1 kernel and the yingtian kernel
  (same kernel series 6.18.21, identical BTF/config, same `begin_new_exec`
  timer cleanup order, one different `selinux_state` address). It is
  **community-tested once** — GitHub issue #2, `jinghu` / OS4.0.0.16, two clean
  boots with `uid=0` + `@o3p_rootd` — but the maintainer has **not**
  independently reproduced it, `dijun`/`violin` are untested, and the O1
  `posix_cpu_timer_del` is the older implementation and may change UAF timing.
  On O1 the profile sets `O3P_NOKSU=1`: the KernelSU LKM's `sys_call_table`
  page patch cannot land under the EL2 hypervisor (the page is read-only), so
  the embedded LKMs are not loaded, no manager or `/system/bin/su` appears,
  root comes only from `@o3p_rootd`, and SELinux ends Permissive. A custom
  `.ko` is rejected on O1. The relaxed gate also accepts other OS4.0.0.x
  builds in the `6.18.21-android17…-4k` series without a per-build comparison
  and reuses the O1 `selinux_state` address, so a shifted static can turn a
  run into a kernel write to the wrong page. Treat every run as a potential
  panic/boot-loop and only test devices you own and can recover; see
  `docs/TESTING_O1.md`.


## Threat model

| attacker starting point | capability |
|---|---|
| any installed app | cannot run the chain directly (App domain is seccomp-filtered and has no `CAP_NET_RAW`; tracefs is denied) |
| Shizuku **shell** server (`uid=2000`, `u:r:shell:s0`) | can run the chain end-to-end (this repo's APK does exactly that) |
| ADB shell (USB debugging) | same as above |
| root already present | full kernel/userland control; this repo is unnecessary |

The intended flow on a clean device: install Shizuku, start it in shell mode
(wireless-debugging / ADB), install this app, press Continue. The app runs the
payload inside a Shizuku-spawned shell process, which becomes root and loads
KernelSU.

## What the exploit changes at runtime

The payload modifies kernel memory only:

1. `selinux_state` (stage 0): an 8-byte unaligned zero at `selinux_state-7`
   clears `enforcing` (and the adjacent `notif_lock` pending/tail bytes);
   `initialized` and `policycap[]` stay intact (the
   legacy `O3P_SELDIRTY=1` path still writes the old dirty value and dirties
   `policycap[]`).
2. `task_struct.cred` of the payload process (stage 1) → a fake cred page with
   uid 0, full capabilities and the original SELinux blob (still shell domain).
3. Loads a KernelSU LKM (relinked at runtime for the current KASLR slide) with
   **no init patch** by default; because `policycap` is clean, KernelSU's own
   `setenforce(1)` does not break networking. `O3P_CODEPATCH=1` restores the
   legacy patch (which then needs the old dirty-write caveats below). On O1
   (v3.3.5+) this step is skipped entirely (`O3P_NOKSU=1`): no LKM is loaded
   and root exists only through rootd.

Nothing is written to disk except:
- `/data/local/tmp/o3p_<millis>.bin` (the app's copy of the payload, mode 0755)
  and `/data/local/tmp/o3p_ksu.ko` (the runtime-rebased LKM, mode 0644; the
  embedded LKM ships unmodified and v3 patches neither `.init.text` nor its
  bytes — only its UNDEF symbols are rebased for the current slide),
- `/data/local/tmp/o3p_rootd.log` (mode 0600) and the proof file
  `/data/local/tmp/o3p_proof.txt` (written through `sh` redirection, so mode
  0666 before umask), plus `/data/local/tmp/o3p_stage.txt` (mode 0644),
- diagnostics on demand: `/data/local/tmp/o3p_insmod.out`,
  `/data/local/tmp/canary_t34` (mode 0666, stage-0 probe), and optionally
  `o3p_sd.log`, `o3p_post.sh` / `o3p_post.out`,
- optional app logs under the app's external files dir.

## Known side effects

### Legacy policycap corruption (`O3P_SELDIRTY=1` only) → network loss

The v3 default does not touch `policycap[]`. Only the legacy dirty write
(`O3P_SELDIRTY=1`) cannot preserve it, and then re-enabling SELinux enforcement
(any `setenforce 1`) makes the Wi-Fi transmit path stall: `wlan0` TX freezes,
RX continues, all traffic dies. Recovery:

- `setenforce 0` (from a context with `CAP_MAC_ADMIN`, e.g. KernelSU `su`), or
- reboot (clears the corruption; root/KSU are lost).

v1.8–v2.1 shipped a probe/warning and a one-click `su -c setenforce 0`; v3.0
removed them because the default no longer corrupts `policycap`.

### Global permissive SELinux (legacy paths, and O1 by design)

The v3 default ends in Enforcing with MAC on. Two cases leave the device
Permissive: the legacy `O3P_SELDIRTY=1` path (or a manual `setenforce 0`) on
yingtian/lhasa, and the O1 profile, whose stage 0 clears enforcing and which
loads no LKM to restore it. With MAC off:

- SELinux domain isolation is gone; a local app compromise is much easier to
  turn into root (tracefs/kprobe interfaces are 0777 and only SELinux stops
  them).
- Some system components behave differently (e.g. zygote/`powerkeeper`
  `selinux_android_setcontext` failures observed in logs).
- Detection: `getenforce` (on O1 there is no KernelSU manager; `getenforce`
  prints `Permissive` and nothing restores Enforcing until reboot).

This is not a normal daily-driver posture. Reboot when done.

### Kernel taint / ABI mismatch

The KernelSU LKM was built by upstream for `android17-6.18`
(`6.18.32-4k-g47cc22554442-dirty`) but is loaded on `6.18.21-…`. The runtime
relink bypasses symbol/CRC namespace checks; the module is tainted (`(O)` in
`/proc/modules`). Treat this as a research setup, not a production root. On O1
no LKM is loaded, so this taint does not apply there.

### Leftover files and labels

On normal runs, files created in `/data/local/tmp` get
`u:object_r:shell_data_file:s0` and are fine. Files created during the abandoned
"zero-write" experiment (`initialized=0`) stay `u:object_r:unlabeled:s0` and
cannot be read/deleted by shell while enforcing. With root in a non-enforcing
boot they can be repaired in bulk:

```sh
chcon -R u:object_r:shell_data_file:s0 /data/local/tmp
```

or deleted/recreated; the label is a persistent `security.selinux` xattr.

### Seccomp observation

Measured on the target (clean cold boot): `init`, `adbd`, `zygote64`, the
Shizuku server and rootd have **no seccomp filter**; `system_server`,
`systemui` and ordinary apps have 2/1. This repo does not change any process's
seccomp state — it is an Android-side design property, reported here only
because it affects exploitability.

## Recovery / cleanup checklist

1. `setenforce 0` if the network is broken (or reboot).
2. Remove test artifacts: `rm -f /data/local/tmp/o3p*` (root), and uninstall the
   APK and, if installed by the app, the KernelSU manager.
3. Reboot: SELinux returns to Enforcing, `selinux_state` is intact, no root.
4. If you granted KSU superuser to apps, `ksud`/manager allow-list lives in
   `/data/adb` and survives reboots — remove it with the manager or
   `rm -rf /data/adb/ksu /data/adb/modules` if you installed them.

## Reporting

Please do not file this as a vulnerability against Xiaomi's bounty program
without reading `docs/history/CVE-2026-98260_FEASIBILITY.md`; the underlying bugs
(`CVE-2026-98260`, and the earlier `CVE-2026-64560`/`CVE-2026-49881`) are
documented there with upstream fix references.
